package org.lts.smartcolony.colony;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.npc.villager.Villager;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.Ids;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.util.WorldUtil;
import org.lts.smartcolony.work.Colonist;

public final class FireWatch {
    private static final int MAX_FIGHTERS = 3;
    private static final int SPREAD_RADIUS = 3;
    private static final long ASSIGN_TIMEOUT = 600;
    private static final int MAX_FIRES = 64;

    private final Colony colony;
    private final Set<Long> fires = new LinkedHashSet<>();
    private final Map<UUID, Long> brigade = new HashMap<>();
    private final Map<UUID, Long> assignedAt = new HashMap<>();
    private int cursor;
    private long lastAlert = -100000;
    private boolean burning;

    public FireWatch(Colony colony) {
        this.colony = colony;
    }

    public boolean burning() {
        return burning;
    }

    public int fires() {
        return fires.size();
    }

    public void tick(ServerLevel level, long ticks) {
        if (ticks % 10 == 4) {
            scanNextBuilding(level);
        }
        if (ticks % 20 == 9 && !fires.isEmpty()) {
            spread(level);
            dispatch(level);
        }
        if (fires.isEmpty() && burning) {
            burning = false;
            brigade.clear();
            assignedAt.clear();
            colony.journal(level, "the fire is out");
            colony.data().bump("fires_put_out", 1);
        }
    }

    private void scanNextBuilding(ServerLevel level) {
        List<ColonyData.Building> buildings = colony.data().buildings;
        if (buildings.isEmpty()) {
            return;
        }
        cursor = Math.floorMod(cursor + 1, buildings.size() + 1);
        if (cursor == buildings.size()) {
            BlockPos depot = colony.depot();
            scanBox(level, depot.offset(-6, -2, -6), depot.offset(6, 4, 6));
            return;
        }
        ColonyData.Building building = buildings.get(cursor);
        scanBox(level, new BlockPos(building.min.x() - 1, building.origin.y(), building.min.z() - 1),
                new BlockPos(building.max.x() + 1, building.max.y() + 2, building.max.z() + 1));
    }

    private void scanBox(ServerLevel level, BlockPos from, BlockPos to) {
        if (!WorldUtil.loaded(level, from) || !WorldUtil.loaded(level, to)) {
            return;
        }
        for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
            if (level.getBlockState(pos).is(BlockTags.FIRE)) {
                note(level, pos.immutable());
            }
        }
    }

    private void note(ServerLevel level, BlockPos pos) {
        if (fires.size() >= MAX_FIRES || !colony.contains(pos)) {
            return;
        }
        if (fires.add(pos.asLong()) && !burning) {
            burning = true;
            long now = level.getGameTime();
            colony.journal(level, "fire at " + pos.toShortString() + ": the brigade is called");
            colony.data().bump("fires", 1);
            if (now - lastAlert > 2400) {
                lastAlert = now;
                colony.notify(level, Component.translatable("smartcolony.chat.fire"));
            }
        }
    }

    private void spread(ServerLevel level) {
        List<BlockPos> known = new ArrayList<>();
        for (long key : fires) {
            known.add(BlockPos.of(key));
        }
        Iterator<Long> it = fires.iterator();
        while (it.hasNext()) {
            BlockPos pos = BlockPos.of(it.next());
            if (WorldUtil.loaded(level, pos) && !level.getBlockState(pos).is(BlockTags.FIRE)) {
                it.remove();
            }
        }
        for (BlockPos pos : known) {
            if (fires.size() >= MAX_FIRES) {
                break;
            }
            if (!WorldUtil.loaded(level, pos)) {
                continue;
            }
            for (BlockPos near : BlockPos.betweenClosed(pos.offset(-SPREAD_RADIUS, -1, -SPREAD_RADIUS), pos.offset(SPREAD_RADIUS, 2, SPREAD_RADIUS))) {
                if (level.getBlockState(near).is(BlockTags.FIRE)) {
                    note(level, near.immutable());
                }
            }
        }
    }

    private void dispatch(ServerLevel level) {
        long now = level.getGameTime();
        brigade.entrySet().removeIf(entry -> !fires.contains(entry.getValue())
                || now - assignedAt.getOrDefault(entry.getKey(), now) > ASSIGN_TIMEOUT);
        assignedAt.keySet().retainAll(brigade.keySet());
        if (fires.isEmpty()) {
            return;
        }
        boolean fighting = !colony.defense().enemies().isEmpty();
        int wanted = Math.min(MAX_FIGHTERS, fires.size() + 1);
        while (brigade.size() < wanted) {
            Villager best = null;
            long bestFire = 0;
            double bestDistance = 48 * 48;
            for (Map.Entry<UUID, Colonist> entry : colony.colonists().entrySet()) {
                if (brigade.containsKey(entry.getKey())) {
                    continue;
                }
                if (!(level.getEntity(entry.getKey()) instanceof Villager villager) || !villager.isAlive() || villager.isBaby()
                        || villager.isSleeping() || villager.isOnFire() || !available(entry.getValue(), villager, fighting)) {
                    continue;
                }
                for (long key : fires) {
                    double distance = villager.blockPosition().distSqr(BlockPos.of(key));
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = villager;
                        bestFire = key;
                    }
                }
            }
            if (best == null) {
                return;
            }
            brigade.put(best.getUUID(), bestFire);
            assignedAt.put(best.getUUID(), now);
        }
    }

    private static boolean available(Colonist colonist, Villager villager, boolean fighting) {
        Role role = colonist.role();
        if (role == Role.GUARD && fighting || role == Role.MEDIC) {
            return false;
        }
        return colonist.job() == null || org.lts.smartcolony.work.Jobs.canInterrupt(colonist, villager);
    }

    public BlockPos assignment(UUID uuid) {
        Long key = brigade.get(uuid);
        return key == null ? null : BlockPos.of(key);
    }

    public void release(UUID uuid) {
        brigade.remove(uuid);
        assignedAt.remove(uuid);
    }

    public int extinguish(ServerLevel level, BlockPos center, boolean bucket) {
        int radius = bucket ? 2 : 0;
        int put = 0;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -1, -radius), center.offset(radius, 1, radius))) {
            if (level.getBlockState(pos).is(BlockTags.FIRE)) {
                level.removeBlock(pos, false);
                fires.remove(pos.asLong());
                put++;
            }
        }
        if (level.getBlockState(center).is(BlockTags.FIRE)) {
            level.removeBlock(center, false);
            put++;
        }
        fires.remove(center.asLong());
        if (put > 0) {
            level.playSound(null, center, net.minecraft.sounds.SoundEvents.FIRE_EXTINGUISH, net.minecraft.sounds.SoundSource.BLOCKS, 0.7F, 1.0F);
            colony.data().bump("fire_blocks_extinguished", put);
        }
        return put;
    }

    public boolean bucketAvailable(ServerLevel level) {
        return colony.warehouse().stock(level).get(Ids.mc("water_bucket")) > 0;
    }

    public BlockPos nextFire(BlockPos near) {
        BlockPos best = null;
        double bestDistance = 12 * 12;
        for (long key : fires) {
            BlockPos pos = BlockPos.of(key);
            double distance = pos.distSqr(near);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos;
            }
        }
        return best;
    }

    public void retarget(UUID uuid, BlockPos fire) {
        brigade.put(uuid, fire.asLong());
    }
}
