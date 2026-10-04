package org.lts.smartcolony.colony;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.AABB;
import org.lts.smartcolony.work.ControlledVillager;
import org.lts.smartcolony.work.Mover;

public final class Migration {
    private static final int SEARCH_RADIUS = 256;
    private static final long CHECK_INTERVAL = 1200;
    private static final long WALK_LIMIT = 9600;

    private final Colony colony;
    private final Map<UUID, Walker> walkers = new HashMap<>();
    private long nextCheck;
    private long nextWave;

    private static final class Walker {
        final Mover mover = new Mover();
        final long started;
        int failures;

        Walker(long started) {
            this.started = started;
        }
    }

    Migration(Colony colony) {
        this.colony = colony;
    }

    public int walking() {
        return walkers.size();
    }

    public void tick(ServerLevel level) {
        long now = level.getGameTime();
        tickWalkers(level, now);
        if (now < nextCheck) {
            return;
        }
        nextCheck = now + CHECK_INTERVAL;
        int members = colony.colonists().size();
        if (members > colony.data().peakMembers) {
            colony.data().peakMembers = members;
            colony.markDirty();
        }
        if (now < nextWave || !walkers.isEmpty() || !colony.workTime(level)) {
            return;
        }
        int freeBeds = (int) homes(level) - members;
        if (freeBeds <= 0 || !depleted(members) || !wealthy(level)) {
            return;
        }
        List<Villager> candidates = candidates(level);
        if (candidates.isEmpty()) {
            return;
        }
        int count = Math.min(Math.min(2, freeBeds), candidates.size());
        for (Villager villager : candidates.subList(0, count)) {
            start(level, villager, now);
        }
        nextWave = now + 24000;
        colony.journal(level, count + " villagers from a nearby village are moving in");
        colony.notify(level, Component.translatable("smartcolony.chat.migrants", count));
    }

    private boolean depleted(int members) {
        int peak = colony.data().peakMembers;
        return members < 4 || members <= peak * 0.6;
    }

    private boolean wealthy(ServerLevel level) {
        int total = 0;
        for (int count : colony.warehouse().stock(level).sorted().values()) {
            total += count;
        }
        return total >= 96 && colony.foodPoints(level) >= 30;
    }

    private long homes(ServerLevel level) {
        return level.getPoiManager().getCountInRange(type -> type.is(PoiTypes.HOME), colony.center(),
                colony.data().radius + 8, PoiManager.Occupancy.ANY);
    }

    private List<Villager> candidates(ServerLevel level) {
        BlockPos center = colony.center();
        double ownSqr = Math.pow(colony.data().radius + 40, 2);
        List<Villager> list = new ArrayList<>(level.getEntitiesOfClass(Villager.class,
                new AABB(center).inflate(SEARCH_RADIUS, 64, SEARCH_RADIUS),
                villager -> villager.isAlive() && !villager.isBaby() && !villager.isTrading() && !villager.isSleeping()
                        && ColonyManager.colonyOf(villager) == null && !walkers.containsKey(villager.getUUID())
                        && !((ControlledVillager) villager).smartcolony$isControlled()
                        && villager.blockPosition().distSqr(center) > ownSqr
                        && ColonyManager.colonyNear(level, villager.blockPosition(), 96) == null));
        list.sort(Comparator.comparingDouble(villager -> villager.blockPosition().distSqr(center)));
        return list;
    }

    private void start(ServerLevel level, Villager villager, long now) {
        villager.getBrain().stopAll(level, villager);
        villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        villager.getBrain().eraseMemory(MemoryModuleType.PATH);
        villager.getNavigation().stop();
        villager.releasePoi(MemoryModuleType.HOME);
        villager.releasePoi(MemoryModuleType.MEETING_POINT);
        ((ControlledVillager) villager).smartcolony$setControlled(true);
        walkers.put(villager.getUUID(), new Walker(now));
    }

    private void tickWalkers(ServerLevel level, long now) {
        Iterator<Map.Entry<UUID, Walker>> it = walkers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Walker> entry = it.next();
            Walker walker = entry.getValue();
            if (!(level.getEntity(entry.getKey()) instanceof Villager villager) || !villager.isAlive()) {
                it.remove();
                continue;
            }
            double distance = Math.sqrt(villager.blockPosition().distSqr(colony.center()));
            if (distance <= colony.data().radius) {
                arrive(level, villager);
                it.remove();
                continue;
            }
            if (now - walker.started > WALK_LIMIT || walker.failures > 40) {
                release(level, villager, walker);
                it.remove();
                continue;
            }
            Mover.Result result = walker.mover.tick(level, villager, colony.depot(), 4.0, 0.6, true);
            if (result == Mover.Result.FAILED) {
                walker.failures++;
            } else if (result == Mover.Result.ARRIVED) {
                arrive(level, villager);
                it.remove();
            }
        }
    }

    private void arrive(ServerLevel level, Villager villager) {
        villager.releasePoi(MemoryModuleType.JOB_SITE);
        villager.releasePoi(MemoryModuleType.POTENTIAL_JOB_SITE);
        ((ControlledVillager) villager).smartcolony$setControlled(false);
        villager.getNavigation().stop();
        if (ColonyManager.colonyOf(villager) == null) {
            colony.join(level, villager);
        }
        colony.data().bump("migrants_arrived", 1);
    }

    private void release(ServerLevel level, Villager villager, Walker walker) {
        walker.mover.closeAll(level, villager);
        ((ControlledVillager) villager).smartcolony$setControlled(false);
        villager.getNavigation().stop();
        colony.data().bump("migrants_lost", 1);
    }

    public void releaseAll(ServerLevel level) {
        for (Map.Entry<UUID, Walker> entry : walkers.entrySet()) {
            if (level.getEntity(entry.getKey()) instanceof Villager villager) {
                release(level, villager, entry.getValue());
            }
        }
        walkers.clear();
    }
}
