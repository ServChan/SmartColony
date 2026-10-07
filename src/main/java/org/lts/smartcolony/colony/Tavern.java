package org.lts.smartcolony.colony;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.AABB;
import org.lts.smartcolony.SmartColonyConfig;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.core.TavernRules;
import org.lts.smartcolony.util.WorldUtil;
import org.lts.smartcolony.work.Colonist;
import org.lts.smartcolony.work.ControlledVillager;
import org.lts.smartcolony.work.Mover;

public final class Tavern {
    public static final String TAG = "smartcolony_traveller";
    private static final int ARRIVE_LIMIT = 7200;
    private static final int LEAVE_LIMIT = 2400;
    private static final int STAY_MIN = 3000;
    private static final int STAY_SPAN = 2400;
    private static final int EAT_TICKS = 50;
    private static final int[][] SEATS = {{4, 4}, {6, 4}, {5, 3}, {5, 5}, {4, 5}, {6, 5}, {4, 3}, {6, 3}, {2, 5}, {8, 5}};

    private enum Phase {
        ARRIVING,
        STAYING,
        LEAVING
    }

    private static final class Guest {
        final Mover mover = new Mover();
        final long since;
        Phase phase = Phase.ARRIVING;
        long until;

        Guest(long since) {
            this.since = since;
        }
    }

    private final Colony colony;
    private final Map<UUID, Guest> guests = new HashMap<>();
    private final Map<UUID, Integer> dined = new HashMap<>();
    private final Map<UUID, Long> dineStart = new HashMap<>();
    private final Map<UUID, Long> eating = new HashMap<>();
    private final Map<UUID, Long> fedUntil = new HashMap<>();
    private ColonyData.Building building;
    private boolean open;
    private long nextVisit;
    private long lastServed;

    Tavern(Colony colony) {
        this.colony = colony;
    }

    private ColonyData data() {
        return colony.data();
    }

    public boolean built() {
        return building != null;
    }

    public boolean open() {
        return open;
    }

    public int guestCount() {
        return guests.size();
    }

    public long lastServed() {
        return lastServed;
    }

    public ColonyData.Building building() {
        return building;
    }

    public int meals() {
        return data().stat("meals_served");
    }

    public int travellers() {
        return data().stat("travellers_seen");
    }

    public int settlers() {
        return data().stat("travellers_settled");
    }

    public boolean innkeeperPresent() {
        for (Colonist colonist : colony.colonists().values()) {
            if (colonist.role() == Role.INNKEEPER) {
                return true;
            }
        }
        return false;
    }

    public BlockPos counter() {
        return building == null ? colony.depot() : colony.construction().world(building, 8, 1, 7);
    }

    public BlockPos room() {
        return building == null ? colony.depot() : colony.construction().world(building, 5, 1, 4);
    }

    private BlockPos seat(UUID uuid) {
        int[] spot = SEATS[Math.floorMod(uuid.hashCode(), SEATS.length)];
        return colony.construction().world(building, spot[0], 1, spot[1]);
    }

    public BlockPos festivalSpot(ServerLevel level, UUID uuid) {
        if (building == null) {
            return null;
        }
        long bits = uuid.getLeastSignificantBits();
        int lx = 1 + (int) Math.floorMod(bits, 9L);
        int lz = -1 - (int) Math.floorMod(bits >>> 5, 5L);
        BlockPos spot = colony.construction().world(building, lx, 1, lz);
        BlockPos ground = WorldUtil.surface(level, spot.getX(), spot.getZ());
        if (Math.abs(ground.getY() - building.door.y()) > 2) {
            return WorldUtil.bp(building.door);
        }
        return ground;
    }

    public BlockPos plaza() {
        return building == null ? colony.center() : WorldUtil.bp(building.door);
    }

    public void tick(ServerLevel level) {
        long ticks = colony.ticks();
        long now = level.getGameTime();
        if (ticks % 20 == 5) {
            refresh(level, now);
        }
        if (!guests.isEmpty()) {
            tickGuests(level, now);
        }
        if (building != null && ticks % 100 == 41) {
            rescan(level, now);
            visit(level, now);
        }
    }

    private void refresh(ServerLevel level, long now) {
        ColonyData.Building found = null;
        for (ColonyData.Building candidate : data().buildings) {
            if (TavernRules.KIND.equals(candidate.kind)) {
                found = candidate;
                break;
            }
        }
        building = found;
        open = found != null && innkeeperPresent() && colony.foodPoints(level) >= 40;
        if (found == null && !guests.isEmpty()) {
            releaseAll(level);
        }
        if (found != null && nextVisit == 0) {
            nextVisit = now + 6000;
        }
    }

    private int day(ServerLevel level) {
        return (int) (level.getDefaultClockTime() / TavernRules.DAY);
    }

    public boolean dinnerOpen(ServerLevel level, UUID uuid) {
        return open && TavernRules.serviceHours(level.getDefaultClockTime(), SmartColonyConfig.get().workEnd)
                && dined.getOrDefault(uuid, -1) != day(level);
    }

    public boolean serviceHours(ServerLevel level) {
        return open && TavernRules.serviceHours(level.getDefaultClockTime(), SmartColonyConfig.get().workEnd);
    }

    public boolean dine(ServerLevel level, Villager villager, Mover mover, UUID uuid) {
        long now = level.getGameTime();
        int today = day(level);
        if (building == null) {
            finishDinner(uuid, today);
            return false;
        }
        long started = dineStart.computeIfAbsent(uuid, key -> now);
        if (now - started > TavernRules.DINNER_SPAN) {
            finishDinner(uuid, today);
            return false;
        }
        Long meal = eating.get(uuid);
        if (meal != null) {
            villager.getNavigation().stop();
            if (now >= meal) {
                eating.remove(uuid);
                serve(level, villager, uuid, today);
                return false;
            }
            villager.getLookControl().setLookAt(counter().getX() + 0.5, counter().getY() + 1.0, counter().getZ() + 0.5);
            return true;
        }
        Mover.Result result = mover.tick(level, villager, seat(uuid), 1.8, 0.6, false);
        if (result == Mover.Result.ARRIVED) {
            eating.put(uuid, now + EAT_TICKS);
        }
        return true;
    }

    private void finishDinner(UUID uuid, int today) {
        dined.put(uuid, today);
        dineStart.remove(uuid);
        eating.remove(uuid);
    }

    private void serve(ServerLevel level, Villager villager, UUID uuid, int today) {
        finishDinner(uuid, today);
        String meal = TavernRules.meal(colony.warehouse().stock(level));
        if (meal == null || colony.warehouse().take(level, meal, 1) < 1) {
            return;
        }
        fedUntil.put(uuid, level.getDefaultClockTime() + TavernRules.FED_TICKS);
        villager.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 0));
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, villager.getX(), villager.getY() + 1.8, villager.getZ(), 5, 0.3, 0.3, 0.3, 0.0);
        level.playSound(null, villager.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 0.5F, 1.0F);
        lastServed = level.getGameTime();
        data().bump("meals_served", 1);
        colony.markDirty();
    }

    public float workBonus(ServerLevel level, UUID uuid) {
        Long until = fedUntil.get(uuid);
        if (until == null) {
            return 0.0F;
        }
        if (level.getDefaultClockTime() >= until) {
            fedUntil.remove(uuid);
            return 0.0F;
        }
        return TavernRules.FED_BONUS;
    }

    private int recentDeaths(ServerLevel level) {
        ColonyData data = data();
        int deaths = data.stat("deaths");
        if (!data.stats.containsKey("death_mark_day")) {
            data.stats.put("death_mark_day", day(level));
            data.stats.put("death_mark", deaths);
            data.stats.put("death_prev", deaths);
            colony.markDirty();
        }
        int[] window = TavernRules.rotateDeathWindow(day(level), deaths, data.stat("death_mark_day"), data.stat("death_mark"),
                data.stat("death_prev"));
        if (window[0] != data.stat("death_mark_day")) {
            data.stats.put("death_mark_day", window[0]);
            data.stats.put("death_mark", window[1]);
            data.stats.put("death_prev", window[2]);
            colony.markDirty();
        }
        return TavernRules.recentDeaths(deaths, data.stat("death_prev"));
    }

    private void visit(ServerLevel level, long now) {
        if (now < nextVisit || guests.size() >= TavernRules.MAX_GUESTS || !colony.workTime(level)) {
            return;
        }
        nextVisit = now + 12000 + level.getRandom().nextInt(12000);
        int members = colony.colonists().size();
        int free = colony.migration().freeBeds(level) - guests.size();
        int food = colony.foodPoints(level);
        int deaths = recentDeaths(level);
        boolean welcoming = TavernRules.welcoming(free, food, members, deaths);
        int count = TavernRules.arrivals(welcoming, TavernRules.flourishing(food, members, deaths), free, open,
                level.getRandom().nextDouble());
        count = Math.min(count, Math.min(free, TavernRules.MAX_GUESTS - guests.size()));
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            if (spawn(level, now)) {
                spawned++;
            }
        }
        if (spawned > 0) {
            data().bump("travellers_seen", spawned);
            colony.journal(level, spawned + " travellers are coming to the tavern");
            colony.notify(level, Component.translatable("smartcolony.chat.traveller_arrived", spawned));
        }
    }

    private boolean spawn(ServerLevel level, long now) {
        for (int attempt = 0; attempt < 6; attempt++) {
            double angle = level.getRandom().nextDouble() * Math.PI * 2.0;
            double radius = data().radius + 12 + level.getRandom().nextInt(10);
            int x = colony.center().getX() + (int) Math.round(Math.cos(angle) * radius);
            int z = colony.center().getZ() + (int) Math.round(Math.sin(angle) * radius);
            if (!level.isLoaded(new BlockPos(x, colony.center().getY(), z))) {
                continue;
            }
            BlockPos at = WorldUtil.surface(level, x, z);
            if (!WorldUtil.standable(level, at) || !level.getFluidState(at.below()).isEmpty()) {
                continue;
            }
            Villager villager = EntityTypes.VILLAGER.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (villager == null) {
                return false;
            }
            villager.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, level.getRandom().nextFloat() * 360.0F, 0.0F);
            villager.finalizeSpawn(level, level.getCurrentDifficultyAt(at), EntitySpawnReason.MOB_SUMMONED, null);
            villager.setPersistenceRequired();
            villager.addTag(TAG);
            ((ControlledVillager) villager).smartcolony$setControlled(true);
            level.addFreshEntity(villager);
            guests.put(villager.getUUID(), new Guest(now));
            return true;
        }
        return false;
    }

    private void rescan(ServerLevel level, long now) {
        if (colony.ticks() % 1200 != 41) {
            return;
        }
        int radius = data().radius + 40;
        AABB box = new AABB(colony.center()).inflate(radius, 48, radius);
        for (Villager villager : level.getEntitiesOfClass(Villager.class, box,
                v -> v.isAlive() && v.entityTags().contains(TAG) && !guests.containsKey(v.getUUID()))) {
            ((ControlledVillager) villager).smartcolony$setControlled(true);
            guests.put(villager.getUUID(), new Guest(now));
        }
    }

    private void tickGuests(ServerLevel level, long now) {
        Iterator<Map.Entry<UUID, Guest>> it = guests.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Guest> entry = it.next();
            Guest guest = entry.getValue();
            if (!(level.getEntity(entry.getKey()) instanceof Villager villager) || !villager.isAlive()) {
                it.remove();
                continue;
            }
            if (building == null) {
                dismiss(level, villager, guest);
                it.remove();
                continue;
            }
            Mover.keepAfloat(villager);
            switch (guest.phase) {
                case ARRIVING -> arriving(level, villager, guest, now);
                case STAYING -> {
                    if (staying(level, villager, guest, now)) {
                        it.remove();
                    }
                }
                case LEAVING -> {
                    if (leaving(level, villager, guest, now)) {
                        it.remove();
                    }
                }
            }
        }
    }

    private void arriving(ServerLevel level, Villager villager, Guest guest, long now) {
        if (now - guest.since > ARRIVE_LIMIT) {
            startLeaving(level, guest, now);
            return;
        }
        Mover.Result result = guest.mover.tick(level, villager, room(), 2.0, 0.55, true);
        if (result == Mover.Result.ARRIVED) {
            guest.phase = Phase.STAYING;
            guest.until = now + STAY_MIN + level.getRandom().nextInt(STAY_SPAN);
            guest.mover.reset();
            welcome(level, villager);
        }
    }

    private void welcome(ServerLevel level, Villager villager) {
        String meal = TavernRules.meal(colony.warehouse().stock(level));
        if (meal != null && open && colony.warehouse().take(level, meal, 1) == 1) {
            data().bump("meals_served", 1);
            lastServed = level.getGameTime();
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, villager.getX(), villager.getY() + 1.8, villager.getZ(), 5, 0.3, 0.3, 0.3, 0.0);
        }
        level.playSound(null, villager.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 0.6F, 1.0F);
    }

    private boolean staying(ServerLevel level, Villager villager, Guest guest, long now) {
        BlockPos counter = counter();
        villager.getNavigation().stop();
        villager.getLookControl().setLookAt(counter.getX() + 0.5, counter.getY() + 1.0, counter.getZ() + 0.5);
        if (now < guest.until) {
            return false;
        }
        int members = colony.colonists().size();
        int free = colony.migration().freeBeds(level);
        int food = colony.foodPoints(level);
        boolean welcoming = TavernRules.welcoming(free, food, members, recentDeaths(level));
        if (TavernRules.stays(welcoming, level.getRandom().nextDouble())) {
            settle(level, villager);
            return true;
        }
        startLeaving(level, guest, now);
        return false;
    }

    private void settle(ServerLevel level, Villager villager) {
        villager.removeTag(TAG);
        villager.getNavigation().stop();
        ((ControlledVillager) villager).smartcolony$setControlled(false);
        if (ColonyManager.colonyOf(villager) == null) {
            colony.join(level, villager);
        }
        data().bump("travellers_settled", 1);
        level.sendParticles(ParticleTypes.HEART, villager.getX(), villager.getY() + 2.0, villager.getZ(), 6, 0.4, 0.4, 0.4, 0.0);
        colony.journal(level, "a traveller decided to stay in the colony");
        colony.notify(level, Component.translatable("smartcolony.chat.traveller_settled"));
    }

    private void startLeaving(ServerLevel level, Guest guest, long now) {
        guest.phase = Phase.LEAVING;
        guest.until = now + LEAVE_LIMIT;
        guest.mover.reset();
        data().bump("travellers_left", 1);
        colony.journal(level, "a traveller moved on");
    }

    private boolean leaving(ServerLevel level, Villager villager, Guest guest, long now) {
        double dx = villager.getX() - colony.center().getX();
        double dz = villager.getZ() - colony.center().getZ();
        double length = Math.max(1.0, Math.sqrt(dx * dx + dz * dz));
        double reach = data().radius + 36;
        if (length >= reach || now >= guest.until) {
            villager.discard();
            return true;
        }
        BlockPos away = WorldUtil.surface(level, colony.center().getX() + (int) Math.round(dx / length * (reach + 6)),
                colony.center().getZ() + (int) Math.round(dz / length * (reach + 6)));
        guest.mover.tick(level, villager, away, 3.0, 0.55, false);
        return false;
    }

    private void dismiss(ServerLevel level, Villager villager, Guest guest) {
        guest.mover.closeAll(level, villager);
        villager.removeTag(TAG);
        villager.getNavigation().stop();
        ((ControlledVillager) villager).smartcolony$setControlled(false);
        villager.discard();
    }

    public void releaseAll(ServerLevel level) {
        for (Map.Entry<UUID, Guest> entry : List.copyOf(guests.entrySet())) {
            if (level.getEntity(entry.getKey()) instanceof Villager villager) {
                dismiss(level, villager, entry.getValue());
            }
        }
        guests.clear();
    }
}
