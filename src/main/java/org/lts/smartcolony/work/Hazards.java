package org.lts.smartcolony.work;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathType;
import org.lts.smartcolony.SmartColonyConfig;
import org.lts.smartcolony.colony.Colony;
import org.lts.smartcolony.core.Hazard;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.core.Shelter;
import org.lts.smartcolony.util.WorldUtil;

public final class Hazards {
    private static final int SCAN_INTERVAL = 5;
    private static final int ESCAPE_LIMIT = 120;
    private static final int ESCAPE_TELEPORT = 60;
    private static final int ESCAPE_HOLD = 80;
    private static final int ADJACENT_COOLDOWN = 100;
    private static final int SOURCE_RADIUS = 5;
    private static final int REFUGE_RADIUS = 8;
    private static final int WATER_RADIUS = 6;
    private static final int SHELTER_RADIUS = 24;
    private static final int SHELTER_CHECK = 20;
    private static final int DRY_GRACE = 100;
    private static final Map<Colony, ShelterSpots> SPOTS = new WeakHashMap<>();

    private final Colonist colonist;
    private long lastScan;
    private long cooldownUntil;
    private BlockPos escapeGoal;
    private long escapeStart;
    private boolean sheltering;
    private BlockPos shelterSpot;
    private long dryAt;
    private long lastShelterCheck;
    private long noShelterUntil;
    private long lastClaim;
    private final Set<Long> badSpots = new HashSet<>();

    Hazards(Colonist colonist) {
        this.colonist = colonist;
    }

    public static Hazard.Kind kind(BlockState state) {
        if (state.isAir()) {
            return Hazard.Kind.NONE;
        }
        return Hazard.classify(state.getFluidState().is(FluidTags.LAVA) || state.is(Blocks.LAVA_CAULDRON),
                state.is(BlockTags.FIRE), CampfireBlock.isLitCampfire(state), state.is(Blocks.MAGMA_BLOCK));
    }

    public static Hazard.Kind[] cube(ServerLevel level, BlockPos center) {
        Hazard.Kind[] cube = Hazard.emptyCube();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    pos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (level.isLoaded(pos)) {
                        cube[Hazard.index(dx, dy, dz)] = kind(level.getBlockState(pos));
                    }
                }
            }
        }
        return cube;
    }

    public static boolean unsafeGoal(ServerLevel level, BlockPos goal, boolean tolerateAdjacent) {
        return Hazard.unsafeTarget(cube(level, goal), tolerateAdjacent);
    }

    public static boolean lavaTouches(ServerLevel level, BlockPos pos) {
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
            BlockPos side = pos.relative(direction);
            if (level.isLoaded(side) && level.getFluidState(side).is(FluidTags.LAVA)) {
                return true;
            }
        }
        return false;
    }

    public static void equip(Villager villager) {
        if (villager.getPathfindingMalus(PathType.FIRE) != -1.0F) {
            villager.setPathfindingMalus(PathType.FIRE, -1.0F);
            villager.setPathfindingMalus(PathType.FIRE_IN_NEIGHBOR, 64.0F);
            villager.setPathfindingMalus(PathType.LAVA, -1.0F);
        }
    }

    private static ShelterSpots spots(Colony colony) {
        return SPOTS.computeIfAbsent(colony, key -> new ShelterSpots());
    }

    boolean tick(ServerLevel level, Villager villager, long now) {
        if (!villager.isAlive() || villager.isBaby()) {
            return false;
        }
        equip(villager);
        Role role = colonist.role();
        colonist.mover().screen(role == Role.GUARD || role == Role.MEDIC ? Mover.SCREEN_OFF
                : role == Role.MINER ? Mover.SCREEN_TOLERANT : Mover.SCREEN_STRICT);
        if (escape(level, villager, now, role)) {
            return true;
        }
        return shelter(level, villager, now, role);
    }

    private boolean escape(ServerLevel level, Villager villager, long now, Role role) {
        if (escapeGoal == null) {
            boolean ablaze = villager.isOnFire();
            if (!ablaze && now - lastScan < SCAN_INTERVAL) {
                return false;
            }
            lastScan = now;
            boolean tolerate = role == Role.MINER || now < cooldownUntil;
            if (!Hazard.threatened(cube(level, villager.blockPosition()), ablaze, tolerate)) {
                return false;
            }
            BlockPos refuge = refuge(level, villager, ablaze);
            if (refuge == null) {
                return false;
            }
            escapeGoal = refuge;
            escapeStart = now;
            endShelter(villager);
            colonist.hold(level, villager, now + ESCAPE_HOLD, "hazard");
            colonist.seize(level, villager);
            colonist.colony().data().bump("hazard_escapes", 1);
        }
        Mover.keepAfloat(villager);
        colonist.mover().screen(Mover.SCREEN_OFF);
        Mover.Result result = colonist.mover().tick(level, villager, escapeGoal, 1.2, 0.9, false);
        long elapsed = now - escapeStart;
        boolean safe = !Hazard.threatened(cube(level, villager.blockPosition()), villager.isOnFire(), true);
        if (result == Mover.Result.FAILED) {
            villager.getMoveControl().setWantedPosition(escapeGoal.getX() + 0.5, escapeGoal.getY(), escapeGoal.getZ() + 0.5, 1.0);
        }
        if (elapsed > ESCAPE_TELEPORT && !safe) {
            Mover.rescue(level, villager, escapeGoal);
            safe = true;
        }
        if (result == Mover.Result.ARRIVED || elapsed > ESCAPE_LIMIT || safe && Mover.distance(villager, escapeGoal) <= 2.0) {
            escapeGoal = null;
            cooldownUntil = now + ADJACENT_COOLDOWN;
            colonist.hold(level, villager, now + ESCAPE_HOLD / 2, "hazard");
            return false;
        }
        return true;
    }

    private BlockPos refuge(ServerLevel level, Villager villager, boolean ablaze) {
        BlockPos origin = villager.blockPosition();
        List<int[]> sources = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = -SOURCE_RADIUS; dx <= SOURCE_RADIUS; dx++) {
            for (int dz = -SOURCE_RADIUS; dz <= SOURCE_RADIUS; dz++) {
                for (int dy = -2; dy <= 3; dy++) {
                    pos.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (level.isLoaded(pos) && kind(level.getBlockState(pos)) != Hazard.Kind.NONE) {
                        sources.add(new int[] {pos.getX(), pos.getY(), pos.getZ()});
                    }
                }
            }
        }
        if (ablaze) {
            BlockPos water = nearest(level, origin, WATER_RADIUS, 2, candidate -> level.getFluidState(candidate).is(FluidTags.WATER)
                    && WorldUtil.passable(level, candidate.above()), sources, 2.0);
            if (water != null) {
                return water;
            }
        }
        return nearest(level, origin, REFUGE_RADIUS, 3, candidate -> WorldUtil.standable(level, candidate)
                && kind(level.getBlockState(candidate.below())) == Hazard.Kind.NONE, sources, 3.0);
    }

    private static BlockPos nearest(ServerLevel level, BlockPos origin, int radius, int vertical,
            java.util.function.Predicate<BlockPos> fits, List<int[]> sources, double distance) {
        for (int r = 1; r <= radius; r++) {
            BlockPos best = null;
            double bestDistance = Double.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) {
                        continue;
                    }
                    for (int dy = -vertical; dy <= vertical; dy++) {
                        BlockPos candidate = origin.offset(dx, dy, dz);
                        double d = candidate.distSqr(origin);
                        if (d >= bestDistance || !level.isLoaded(candidate)
                                || !Hazard.farFrom(candidate.getX(), candidate.getY(), candidate.getZ(), sources, distance)
                                || !fits.test(candidate)) {
                            continue;
                        }
                        bestDistance = d;
                        best = candidate;
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    private boolean shelter(ServerLevel level, Villager villager, long now, Role role) {
        SmartColonyConfig config = SmartColonyConfig.get();
        Colony colony = colonist.colony();
        boolean raining = config.shelterInRain && level.isRaining();
        if (sheltering) {
            if (villager.isSleeping() || villager.isTrading() || !villager.isAlive()) {
                endShelter(villager);
                return false;
            }
            if (!raining) {
                if (dryAt == 0) {
                    dryAt = now;
                }
                if (now - dryAt > DRY_GRACE) {
                    endShelter(villager);
                    colonist.hold(level, villager, now, "rain stopped");
                    return false;
                }
            } else {
                dryAt = 0;
            }
            return holdShelter(level, villager, now);
        }
        if (!raining || now < noShelterUntil || now - lastShelterCheck < SHELTER_CHECK) {
            return false;
        }
        lastShelterCheck = now;
        if (villager.isSleeping() || villager.isTrading() || colony.festival(level) || colony.tavern().dinnerOpen(level, colonist.uuid())) {
            return false;
        }
        if (!colony.workTime(level) && !Colonist.bedless(villager)) {
            return false;
        }
        boolean keeps = Shelter.keepsWorking(colonist.uuid().hashCode(), config.rainWorkRatio);
        if (!Shelter.wants(role, level.isThundering(), colony.defense().alarmed(level), keeps)) {
            return false;
        }
        if (!level.isRainingAt(villager.blockPosition())) {
            return false;
        }
        BlockPos spot = chooseSpot(level, villager, now);
        if (spot == null) {
            noShelterUntil = now + 200;
            return false;
        }
        sheltering = true;
        shelterSpot = spot;
        dryAt = 0;
        lastClaim = now;
        spots(colony).claim(colonist.uuid(), spot, now);
        colonist.hold(level, villager, now + 40, "rain");
        colonist.seize(level, villager);
        colony.data().bump("rain_shelters", 1);
        return holdShelter(level, villager, now);
    }

    private BlockPos chooseSpot(ServerLevel level, Villager villager, long now) {
        BlockPos at = villager.blockPosition();
        List<Shelter.Spot> candidates = spots(colonist.colony()).candidates(level, colonist.colony(), now);
        int index = Shelter.pick(candidates, at.getX(), at.getY(), at.getZ(), SHELTER_RADIUS, 3.0,
                spot -> badSpots.contains(BlockPos.asLong(spot.x(), spot.y(), spot.z()))
                        || !ShelterSpots.covered(level, new BlockPos(spot.x(), spot.y(), spot.z())));
        if (index >= 0) {
            Shelter.Spot spot = candidates.get(index);
            return new BlockPos(spot.x(), spot.y(), spot.z());
        }
        return ShelterSpots.nearbyCovered(level, at, 8, pos -> badSpots.contains(pos.asLong()));
    }

    private boolean holdShelter(ServerLevel level, Villager villager, long now) {
        Mover.keepAfloat(villager);
        if (now - lastClaim >= 100) {
            lastClaim = now;
            spots(colonist.colony()).claim(colonist.uuid(), shelterSpot, now);
        }
        Mover.Result result = colonist.mover().tick(level, villager, shelterSpot, 1.2, 0.7, false);
        if (result == Mover.Result.FAILED) {
            badSpots.add(shelterSpot.asLong());
            BlockPos next = badSpots.size() > 3 ? null : chooseSpot(level, villager, now);
            if (next == null) {
                endShelter(villager);
                noShelterUntil = now + 400;
                colonist.hold(level, villager, now, "no shelter");
                return false;
            }
            shelterSpot = next;
            spots(colonist.colony()).claim(colonist.uuid(), next, now);
            return true;
        }
        if (result == Mover.Result.ARRIVED) {
            villager.getNavigation().stop();
        }
        return true;
    }

    private void endShelter(Villager villager) {
        if (sheltering) {
            spots(colonist.colony()).release(colonist.uuid());
        }
        sheltering = false;
        shelterSpot = null;
        dryAt = 0;
        badSpots.clear();
    }
}
