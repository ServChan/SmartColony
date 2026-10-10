package org.lts.smartcolony.work;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.lts.smartcolony.SmartColonyConfig;
import org.lts.smartcolony.colony.Colony;
import org.lts.smartcolony.colony.ColonyManager;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.Expedition;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.core.Schedule;
import org.lts.smartcolony.util.WorldUtil;

public final class ScoutJob extends Job {
    private static final int MAX_TRIP_TICKS = 6000;
    private static final int MAX_MINED = 16;

    private BlockPos goal;
    private long tripStart;
    private final Deque<BlockPos> vein = new ArrayDeque<>();
    private int mined;
    private int found;
    private Villager recruit;
    private long recruitDay = -100;

    public ScoutJob(Colonist colonist) {
        super(colonist);
        depositThreshold = 10;
    }

    @Override
    public Role role() {
        return Role.SCOUT;
    }

    @Override
    protected Predicate<ItemStack> tools() {
        return Tools.PICKAXE;
    }

    @Override
    protected double speed() {
        return 0.7;
    }

    @Override
    protected boolean nightShift(Villager villager) {
        return false;
    }

    @Override
    public boolean wantsControl(ServerLevel level, Villager villager) {
        return recruit != null || !phase.equals("start") && !phase.equals("deposit") && colony().workTime(level)
                || super.wantsControl(level, villager);
    }

    @Override
    public void onRelease(ServerLevel level, Villager villager) {
        super.onRelease(level, villager);
        goal = null;
        vein.clear();
        recruit = null;
    }

    @Override
    public void tick(ServerLevel level, Villager villager) {
        if (wait > 0) {
            wait--;
            return;
        }
        switch (phase) {
            case "start" -> start(level, villager);
            case "out" -> out(level, villager);
            case "survey" -> survey(level, villager);
            case "mine" -> mine(level, villager);
            case "village" -> village(level, villager);
            case "home" -> home(level, villager);
            case "deposit" -> deposit(level, villager);
            default -> phase = "start";
        }
    }

    private void start(ServerLevel level, Villager villager) {
        if (hasCargo(villager)) {
            phase = "deposit";
            return;
        }
        if (!colony().workTime(level) || Schedule.hour(level.getDefaultClockTime()) > SmartColonyConfig.get().workEnd - 3000) {
            status = "resting before the next trip";
            wait = 200;
            return;
        }
        if (Inv.find(villager, Tools.PICKAXE).isEmpty() && !Tools.PICKAXE.test(villager.getMainHandItem())) {
            Equip equip = equip(level, villager, Tools.PICKAXE, Tools.PICKAXES);
            if (equip == Equip.BUSY) {
                return;
            }
        }
        if (SmartColonyConfig.get().recruitFromVillages && recruitTarget(level) != null
                && Schedule.day(level.getDefaultClockTime()) - recruitDay >= Expedition.RECRUIT_COOLDOWN_DAYS) {
            ColonyData.Discovery village = recruitTarget(level);
            goal = WorldUtil.bp(village.pos);
            tripStart = level.getGameTime();
            status = "going to invite a settler";
            phase = "village";
            return;
        }
        ColonyData data = colony().data();
        BlockPos center = colony().center();
        for (int tries = 0; tries < Expedition.DIRECTIONS * Expedition.MAX_RINGS; tries++) {
            Expedition.Target target = Expedition.target(data.scoutTrips++, data.radius);
            while (target != null) {
                BlockPos spot = new BlockPos(center.getX() + target.dx(), center.getY(), center.getZ() + target.dz());
                if (level.isPositionEntityTicking(spot)) {
                    goal = WorldUtil.surface(level, spot.getX(), spot.getZ());
                    tripStart = level.getGameTime();
                    found = 0;
                    colony().markDirty();
                    status = "setting out to " + goal.toShortString();
                    phase = "out";
                    return;
                }
                target = Expedition.shorter(target, data.radius);
            }
        }
        status = "the surroundings are explored";
        idle(level, 2400);
    }

    private boolean tooLong(ServerLevel level) {
        return level.getGameTime() - tripStart > MAX_TRIP_TICKS || !colony().workTime(level);
    }

    private void out(ServerLevel level, Villager villager) {
        if (goal == null || tooLong(level)) {
            phase = "home";
            return;
        }
        status = "scouting toward " + goal.toShortString();
        Mover.Result result = move(level, villager, goal, 4.0);
        if (result == Mover.Result.FAILED) {
            goal = villager.blockPosition();
            phase = "survey";
        } else if (result == Mover.Result.ARRIVED) {
            phase = "survey";
        }
    }

    private void survey(ServerLevel level, Villager villager) {
        BlockPos here = villager.blockPosition();
        status = "surveying the area";
        int r = Expedition.SURVEY_RADIUS;
        BlockPos bestOre = null;
        int bestValue = 0;
        Set<Long> seen = new HashSet<>();
        for (BlockPos pos : BlockPos.betweenClosed(here.offset(-r, -10, -r), here.offset(r, 6, r))) {
            if (!WorldUtil.loaded(level, pos) || seen.contains(pos.asLong())) {
                continue;
            }
            String path = path(level.getBlockState(pos));
            int value = Expedition.oreValue(path);
            if (value == 0 || !exposed(level, pos)) {
                continue;
            }
            List<BlockPos> cluster = cluster(level, pos.immutable(), path, seen);
            if (Expedition.worthVein(value, cluster.size()) && value * cluster.size() > bestValue) {
                bestValue = value * cluster.size();
                bestOre = pos.immutable();
            }
        }
        if (bestOre != null) {
            record(level, Expedition.ORE, path(level.getBlockState(bestOre)), bestOre, bestValue);
            vein.clear();
            vein.addAll(cluster(level, bestOre, path(level.getBlockState(bestOre)), new HashSet<>()));
            mined = 0;
            phase = "mine";
        } else {
            phase = "home";
        }
        for (BlockPos bell : level.getPoiManager().getInRange(type -> type.is(PoiTypes.MEETING), here, 48, PoiManager.Occupancy.ANY)
                .map(record -> record.getPos()).toList()) {
            if (ColonyManager.colonyAt(level, bell) == null && ColonyManager.colonyNear(level, bell, 48) == null) {
                record(level, Expedition.VILLAGE, "village", bell, 1);
            }
        }
        level.getPoiManager().getInRange(type -> type.is(PoiTypes.BEE_NEST), here, 32, PoiManager.Occupancy.ANY)
                .map(record -> record.getPos())
                .findFirst()
                .ifPresent(nest -> record(level, Expedition.NEST, "bee_nest", nest, 1));
        gainXp(level, villager, 2);
    }

    private static String path(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    }

    private static boolean exposed(ServerLevel level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockState next = level.getBlockState(pos.relative(direction));
            if (next.isAir() || next.canBeReplaced() && next.getFluidState().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static List<BlockPos> cluster(ServerLevel level, BlockPos start, String path, Set<Long> seen) {
        List<BlockPos> result = new java.util.ArrayList<>();
        Deque<BlockPos> open = new ArrayDeque<>();
        open.add(start);
        seen.add(start.asLong());
        while (!open.isEmpty() && result.size() < Expedition.VEIN_LIMIT) {
            BlockPos pos = open.poll();
            result.add(pos);
            for (Direction direction : Direction.values()) {
                BlockPos next = pos.relative(direction);
                if (seen.add(next.asLong()) && WorldUtil.loaded(level, next) && path(level.getBlockState(next)).equals(path)) {
                    open.add(next);
                }
            }
        }
        return result;
    }

    private void record(ServerLevel level, String kind, String what, BlockPos pos, int value) {
        List<ColonyData.Discovery> list = colony().data().discoveries;
        for (ColonyData.Discovery discovery : list) {
            if (discovery.kind.equals(kind) && discovery.pos.horizontalDistSq(WorldUtil.pos(pos)) < 24 * 24) {
                discovery.visited = level.getGameTime();
                return;
            }
        }
        ColonyData.Discovery discovery = new ColonyData.Discovery();
        discovery.kind = kind;
        discovery.what = what;
        discovery.pos = WorldUtil.pos(pos);
        discovery.value = value;
        discovery.time = level.getGameTime();
        discovery.visited = level.getGameTime();
        list.add(discovery);
        while (list.size() > Expedition.MAX_DISCOVERIES) {
            list.removeFirst();
        }
        found++;
        colony().data().bump("discoveries", 1);
        colony().journal(level, "scout found " + kind + " (" + what + ") at " + pos.toShortString());
        if (Expedition.VILLAGE.equals(kind) || Expedition.ORE.equals(kind) && value >= 6) {
            colony().notify(level, Component.translatable(Expedition.VILLAGE.equals(kind) ? "smartcolony.chat.scout_village"
                    : "smartcolony.chat.scout_ore", pos.getX(), pos.getZ()));
        }
        colony().markDirty();
    }

    private void mine(ServerLevel level, Villager villager) {
        if (tooLong(level) || mined >= MAX_MINED || cargoFull(villager)) {
            phase = "home";
            return;
        }
        while (!vein.isEmpty() && Expedition.oreValue(path(level.getBlockState(vein.peek()))) == 0) {
            vein.poll();
        }
        if (vein.isEmpty()) {
            phase = "home";
            return;
        }
        Equip equip = equip(level, villager, Tools.PICKAXE, List.of());
        if (equip == Equip.MISSING && !Tools.PICKAXE.test(villager.getMainHandItem())) {
            phase = "home";
            return;
        }
        BlockPos ore = vein.peek();
        status = "mining an ore vein";
        if (villager.getEyePosition().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(ore)) > 4.2) {
            BlockPos stand = standNear(level, ore);
            if (stand == null || move(level, villager, stand, 1.2) == Mover.Result.FAILED) {
                vein.poll();
            }
            return;
        }
        Digger.Result result = colonist.digger().tick(level, villager, ore, true);
        if (result == Digger.Result.DONE) {
            stash(level, villager, colonist.digger().takeDrops());
            vein.poll();
            mined++;
            gainXp(level, villager, 2);
            colony().data().bump("scout_ores", 1);
        } else if (result == Digger.Result.FAILED) {
            vein.poll();
        }
    }

    static BlockPos standNear(ServerLevel level, BlockPos ore) {
        for (int dy = 0; dy >= -2; dy--) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos feet = ore.relative(direction).above(dy);
                if (WorldUtil.standable(level, feet)) {
                    return feet;
                }
            }
        }
        return WorldUtil.standable(level, ore.above()) ? ore.above() : null;
    }

    private ColonyData.Discovery recruitTarget(ServerLevel level) {
        if (colony().migration().freeBeds(level) <= 0) {
            return null;
        }
        return colony().data().discoveries.stream()
                .filter(d -> Expedition.VILLAGE.equals(d.kind) && level.getGameTime() - d.visited < Expedition.VILLAGE_FORGET
                        && level.isPositionEntityTicking(WorldUtil.bp(d.pos)))
                .min(Comparator.comparingDouble(d -> d.pos.horizontalDistSq(WorldUtil.pos(colony().center()))))
                .orElse(null);
    }

    private void village(ServerLevel level, Villager villager) {
        if (goal == null || tooLong(level)) {
            phase = "home";
            return;
        }
        if (Mover.distance(villager, goal) > 6.0) {
            status = "walking to a village at " + goal.toShortString();
            if (move(level, villager, goal, 4.0) == Mover.Result.FAILED) {
                phase = "home";
            }
            return;
        }
        recruitDay = Schedule.day(level.getDefaultClockTime());
        Villager guest = level.getEntitiesOfClass(Villager.class, new AABB(goal).inflate(32, 8, 32),
                        v -> v.isAlive() && !v.isBaby() && !v.isTrading() && !v.isSleeping() && ColonyManager.colonyOf(v) == null
                                && !v.entityTags().contains(org.lts.smartcolony.colony.Tavern.TAG))
                .stream()
                .min(Comparator.comparingInt((Villager v) -> unemployed(v) ? 0 : 1).thenComparingDouble(v -> v.distanceToSqr(villager)))
                .orElse(null);
        if (guest == null) {
            colony().journal(level, "scout found nobody willing to move at " + goal.toShortString());
            phase = "home";
            return;
        }
        Colony colony = colony();
        colony.join(level, guest);
        Colonist settler = colony.colonists().get(guest.getUUID());
        if (settler != null) {
            settler.followLeader(villager.getUUID(), level.getGameTime() + MAX_TRIP_TICKS);
        }
        recruit = guest;
        colony.journal(level, "scout invited a villager from the village at " + goal.toShortString());
        colony.notify(level, Component.translatable("smartcolony.chat.scout_recruit"));
        gainXp(level, villager, 5);
        phase = "home";
    }

    private static boolean unemployed(Villager villager) {
        String profession = villager.getVillagerData().profession().unwrapKey().map(k -> k.identifier().getPath()).orElse("none");
        return profession.equals("none") || profession.equals("nitwit");
    }

    private void home(ServerLevel level, Villager villager) {
        status = recruit != null ? "bringing a settler home" : "returning from the trip";
        if (recruit != null && (!recruit.isAlive() || !colony().colonists().containsKey(recruit.getUUID()))) {
            recruit = null;
        }
        Mover.Result result = goDepot(level, villager);
        if (recruit != null && recruit.distanceTo(villager) > 16) {
            villager.getNavigation().stop();
            villager.getLookControl().setLookAt(recruit);
            return;
        }
        if (result == Mover.Result.ARRIVED || result == Mover.Result.FAILED) {
            if (found > 0) {
                colony().journal(level, "scout returned with " + found + " new findings");
            }
            recruit = null;
            phase = hasCargo(villager) ? "deposit" : "start";
            wait = 200;
        }
    }

    private void deposit(ServerLevel level, Villager villager) {
        if (!atDepot(level, villager)) {
            if (goDepot(level, villager) == Mover.Result.FAILED) {
                wait = 100;
            }
            return;
        }
        depositAll(level, villager);
        phase = "start";
    }
}
