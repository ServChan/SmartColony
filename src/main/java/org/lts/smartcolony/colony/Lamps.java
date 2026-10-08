package org.lts.smartcolony.colony;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.CraftPlanner;
import org.lts.smartcolony.core.Ids;
import org.lts.smartcolony.core.LampDesign;
import org.lts.smartcolony.core.Pos;
import org.lts.smartcolony.core.Stock;
import org.lts.smartcolony.util.WorldUtil;

public final class Lamps {
    private static final int ACTIVE_MAX = 5;
    private static final long PLAN_PERIOD = 1200;
    private static final long IDLE_ABANDON = 6000;
    private static final int PLAN_BATCH = 6;
    private static final int UPGRADE_BATCH = 3;
    private static final int COLUMN_HEIGHT = 5;

    private final Colony colony;
    private long lastPlan = -100000;
    private final Set<Long> rejected = new HashSet<>();

    Lamps(Colony colony) {
        this.colony = colony;
    }

    private ColonyData data() {
        return colony.data();
    }

    private Paths paths() {
        return colony.construction().paths();
    }

    public void tick(ServerLevel level) {
        long now = level.getGameTime();
        if (!data().pendingTorches.isEmpty()) {
            data().pendingTorches.clear();
            colony.markDirty();
        }
        advance(level, now);
        if (now - lastPlan >= PLAN_PERIOD) {
            lastPlan = now;
            plan(level);
        }
        gauges();
    }

    private void gauges() {
        ColonyData d = data();
        d.stats.put("lamps_ground", d.torches.size());
        d.stats.put("lamps_stone", (int) d.lamps.stream().filter(l -> l.tier == LampDesign.STONE_POST && l.phase.equals("done")).count());
        d.stats.put("lamps_lantern", (int) d.lamps.stream().filter(l -> l.tier == LampDesign.LANTERN_POST && l.phase.equals("done")).count());
        d.stats.put("lamps_building", active());
    }

    private int active() {
        return (int) data().lamps.stream().filter(l -> !l.phase.equals("done")).count();
    }

    private boolean lanternReady(ServerLevel level) {
        Stock stock = colony.warehouse().stock(level);
        return stock.get(Ids.mc("lantern")) > 0 || CraftPlanner.plan(stock, Ids.mc("lantern"), 1).isPresent();
    }

    private String baseFor(ServerLevel level, int tier) {
        if (tier <= LampDesign.STONE_POST) {
            return Ids.mc("cobblestone");
        }
        Stock stock = colony.warehouse().stock(level);
        String bricks = Ids.mc("stone_bricks");
        return stock.get(bricks) > 0 || CraftPlanner.plan(stock, bricks, 1).isPresent() ? bricks : Ids.mc("cobblestone");
    }

    private List<LampDesign.Layer> layers(ColonyData.Lamp lamp) {
        return LampDesign.layers(lamp.tier, lamp.wood, lamp.base, lamp.armX, lamp.armZ);
    }

    private boolean feasible(ServerLevel level, List<LampDesign.Layer> layers) {
        return CraftPlanner.plan(colony.warehouse().stock(level), LampDesign.needs(layers)).isPresent();
    }

    private List<Pos> occupied(Pos except) {
        List<Pos> all = new ArrayList<>();
        for (Pos torch : data().torches) {
            if (!torch.equals(except)) {
                all.add(torch);
            }
        }
        for (ColonyData.Lamp lamp : data().lamps) {
            if (!lamp.pos.equals(except)) {
                all.add(lamp.pos);
            }
        }
        return all;
    }

    private static boolean bright(ServerLevel level, BlockPos feet) {
        return level.getBrightness(LightLayer.BLOCK, feet) >= 8;
    }

    private BlockPos site(ServerLevel level, int x, int z, Set<Long> roads, Set<Long> aprons) {
        if (!WorldUtil.loaded(level, new BlockPos(x, 0, z))) {
            return null;
        }
        long column = BlockPos.asLong(x, 0, z);
        if (roads.contains(column) || aprons.contains(column)) {
            return null;
        }
        if (org.lts.smartcolony.core.Decree.inAny(data().zones, x, z, 1)
                || org.lts.smartcolony.core.Clearance.inCorridor(x, z, colony.construction().doors())) {
            return null;
        }
        int y = paths().ground(level, x, z);
        BlockPos ground = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(ground);
        if (!state.getFluidState().isEmpty() || state.is(Blocks.DIRT_PATH) || state.is(Blocks.FARMLAND) || state.is(Blocks.MUD)
                || state.is(BlockTags.ICE) || state.is(BlockTags.LEAVES) || !state.isFaceSturdy(level, ground, Direction.UP)) {
            return null;
        }
        BlockPos feet = ground.above();
        for (int dy = 0; dy < COLUMN_HEIGHT; dy++) {
            BlockState cell = level.getBlockState(feet.above(dy));
            if (!WorldUtil.isAirLike(cell) || !cell.getFluidState().isEmpty()) {
                return null;
            }
        }
        if (paths().reserved(feet) || rejected.contains(feet.asLong()) || hasOrderAt(WorldUtil.pos(feet))) {
            return null;
        }
        Pos at = WorldUtil.pos(feet);
        for (Pos farm : data().farms) {
            if (farm.horizontalDistSq(at) <= 25 && Math.abs(farm.y() - at.y()) < 4) {
                return null;
            }
        }
        return feet.immutable();
    }

    private boolean hasOrderAt(Pos pos) {
        for (ColonyData.Order order : data().orders) {
            if (order.pos.equals(pos)) {
                return true;
            }
        }
        return false;
    }

    private int[] arm(ServerLevel level, BlockPos feet, Set<Long> roads) {
        int[] best = {0, 0};
        int bestScore = -1;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos side = feet.relative(dir);
            if (!WorldUtil.isAirLike(level.getBlockState(side.above(2))) || !WorldUtil.isAirLike(level.getBlockState(side.above(3)))
                    || !level.getFluidState(side.above(2)).isEmpty() || paths().reserved(side)) {
                continue;
            }
            int score = roads.contains(BlockPos.asLong(side.getX(), 0, side.getZ())) ? 2 : 1;
            if (score > bestScore) {
                bestScore = score;
                best = new int[] {dir.getStepX(), dir.getStepZ()};
            }
        }
        return best;
    }

    private ColonyData.Lamp create(ServerLevel level, BlockPos feet, int tier, Set<Long> roads) {
        ColonyData.Lamp lamp = new ColonyData.Lamp();
        lamp.pos = WorldUtil.pos(feet);
        lamp.tier = tier;
        lamp.wood = colony.construction().preferredWood(level);
        lamp.base = baseFor(level, tier);
        if (tier >= LampDesign.LANTERN_POST) {
            int[] arm = arm(level, feet, roads);
            lamp.armX = arm[0];
            lamp.armZ = arm[1];
        }
        lamp.since = level.getGameTime();
        return lamp;
    }

    private void plan(ServerLevel level) {
        ColonyData d = data();
        int tier = LampDesign.tierFor(d.age, lanternReady(level));
        Set<Long> roads = paths().roadColumns();
        Set<Long> aprons = paths().apronColumns();
        int started = 0;
        if (active() < ACTIVE_MAX) {
            started += planFresh(level, tier, roads, aprons);
        }
        if (active() < ACTIVE_MAX && started == 0) {
            planUpgrade(level, tier, roads, aprons);
        }
        if (started > 0) {
            colony.markDirty();
        }
    }

    private List<int[]> candidates(ServerLevel level) {
        ColonyData d = data();
        BlockPos hub = colony.center();
        List<int[]> cells = new ArrayList<>();
        Set<Long> roads = paths().roadColumns();
        for (int i = 0; i < d.roads.size(); i += 2) {
            Pos road = d.roads.get(i);
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                int x = road.x() + dir.getStepX();
                int z = road.z() + dir.getStepZ();
                if (!roads.contains(BlockPos.asLong(x, 0, z))) {
                    cells.add(new int[] {x, z});
                }
            }
        }
        for (ColonyData.Building building : d.buildings) {
            Direction out = paths().outward(building);
            BlockPos front = WorldUtil.bp(building.door).relative(out);
            for (int sign = -1; sign <= 1; sign += 2) {
                BlockPos at = front.relative(out.getClockWise(), 2 * sign);
                cells.add(new int[] {at.getX(), at.getZ()});
            }
        }
        BlockPos depot = colony.depot();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            cells.add(new int[] {depot.getX() + dir.getStepX() * 3, depot.getZ() + dir.getStepZ() * 3});
        }
        int reach = (int) (d.radius * 0.85);
        for (int dx = -reach; dx <= reach; dx += LampDesign.SPACING) {
            for (int dz = -reach; dz <= reach; dz += LampDesign.SPACING) {
                if (dx * dx + dz * dz <= reach * reach) {
                    cells.add(new int[] {hub.getX() + dx, hub.getZ() + dz});
                }
            }
        }
        cells.sort(Comparator.comparingLong(c -> {
            long dx = c[0] - hub.getX();
            long dz = c[1] - hub.getZ();
            return dx * dx + dz * dz;
        }));
        return cells;
    }

    private int planFresh(ServerLevel level, int tier, Set<Long> roads, Set<Long> aprons) {
        int started = 0;
        List<Pos> taken = occupied(null);
        for (int[] cell : candidates(level)) {
            if (started >= PLAN_BATCH || active() >= ACTIVE_MAX) {
                break;
            }
            BlockPos feet = site(level, cell[0], cell[1], roads, aprons);
            if (feet == null || bright(level, feet) || !LampDesign.spaced(taken, WorldUtil.pos(feet), LampDesign.SPACING)) {
                continue;
            }
            ColonyData.Lamp lamp = create(level, feet, tier, roads);
            if (!feasible(level, layers(lamp))) {
                if (tier > LampDesign.STONE_POST) {
                    break;
                }
                continue;
            }
            data().lamps.add(lamp);
            taken.add(lamp.pos);
            started++;
        }
        return started;
    }

    private void planUpgrade(ServerLevel level, int tier, Set<Long> roads, Set<Long> aprons) {
        ColonyData d = data();
        BlockPos hub = colony.center();
        Set<Pos> replacing = new HashSet<>();
        for (ColonyData.Lamp lamp : d.lamps) {
            if (lamp.replaces != null && !lamp.phase.equals("done")) {
                replacing.add(lamp.replaces);
            }
        }
        List<Pos> legacy = new ArrayList<>(d.torches);
        legacy.sort(Comparator.comparingDouble(p -> p.horizontalDistSq(WorldUtil.pos(hub))));
        int started = 0;
        for (Pos old : legacy) {
            if (started >= UPGRADE_BATCH || active() >= ACTIVE_MAX) {
                return;
            }
            if (replacing.contains(old) || !WorldUtil.loaded(level, WorldUtil.bp(old))) {
                continue;
            }
            if (!legacyStanding(level, old)) {
                d.torches.remove(old);
                colony.markDirty();
                continue;
            }
            if (startUpgrade(level, old, 0, tier, roads, aprons)) {
                started++;
            }
        }
        if (tier < LampDesign.LANTERN_POST) {
            return;
        }
        for (ColonyData.Lamp lamp : new ArrayList<>(d.lamps)) {
            if (lamp.tier >= LampDesign.LANTERN_POST || !lamp.phase.equals("done") || replacing.contains(lamp.pos)
                    || !WorldUtil.loaded(level, WorldUtil.bp(lamp.pos))) {
                continue;
            }
            if (started >= UPGRADE_BATCH || active() >= ACTIVE_MAX) {
                return;
            }
            if (startUpgrade(level, lamp.pos, lamp.tier, tier, roads, aprons)) {
                started++;
            }
        }
    }

    private boolean legacyStanding(ServerLevel level, Pos old) {
        BlockPos at = WorldUtil.bp(old);
        return level.getBlockState(at).is(Blocks.TORCH) || level.getBlockState(at.above()).is(Blocks.TORCH)
                || Improvements.wallMaterial(level.getBlockState(at));
    }

    private boolean startUpgrade(ServerLevel level, Pos old, int oldTier, int tier, Set<Long> roads, Set<Long> aprons) {
        ColonyData d = data();
        List<Pos> others = occupied(old);
        BlockPos spot = null;
        BlockPos oldFeet = WorldUtil.bp(old);
        double bestScore = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                BlockPos feet = site(level, old.x() + dx, old.z() + dz, roads, aprons);
                if (feet == null || Math.abs(feet.getY() - oldFeet.getY()) > 1
                        || !LampDesign.spaced(others, WorldUtil.pos(feet), LampDesign.SPACING - 2)) {
                    continue;
                }
                boolean byRoad = false;
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    byRoad |= roads.contains(BlockPos.asLong(feet.getX() + dir.getStepX(), 0, feet.getZ() + dir.getStepZ()));
                }
                double score = Math.hypot(dx, dz) + (byRoad ? 0 : 3);
                if (score < bestScore) {
                    bestScore = score;
                    spot = feet;
                }
            }
        }
        ColonyData.Lamp lamp;
        if (spot != null) {
            lamp = create(level, spot, tier, roads);
        } else {
            lamp = create(level, oldFeet, tier, roads);
            lamp.inPlace = true;
            lamp.phase = "clear";
        }
        lamp.replaces = old;
        lamp.replacesTier = oldTier;
        if (!feasible(level, layers(lamp))) {
            return false;
        }
        d.lamps.add(lamp);
        colony.markDirty();
        return true;
    }

    private void advance(ServerLevel level, long now) {
        ColonyData d = data();
        for (ColonyData.Lamp lamp : new ArrayList<>(d.lamps)) {
            if (lamp.phase.equals("done") || !WorldUtil.loaded(level, WorldUtil.bp(lamp.pos))) {
                continue;
            }
            switch (lamp.phase) {
                case "clear" -> {
                    if (retire(level, lamp)) {
                        forget(lamp);
                        lamp.phase = "build";
                        lamp.since = now;
                        colony.markDirty();
                    }
                }
                case "build" -> build(level, lamp, now);
                case "retire" -> {
                    if (retire(level, lamp)) {
                        forget(lamp);
                        finish(level, lamp);
                    }
                }
                default -> lamp.phase = "build";
            }
        }
    }

    private void forget(ColonyData.Lamp lamp) {
        if (lamp.replaces == null) {
            return;
        }
        if (lamp.replacesTier == 0) {
            data().torches.remove(lamp.replaces);
        } else {
            data().lamps.removeIf(other -> other != lamp && other.pos.equals(lamp.replaces) && other.tier == lamp.replacesTier);
        }
    }

    private void build(ServerLevel level, ColonyData.Lamp lamp, long now) {
        BlockPos base = WorldUtil.bp(lamp.pos);
        List<LampDesign.Layer> layers = layers(lamp);
        for (int i = 0; i < layers.size(); i++) {
            LampDesign.Layer layer = layers.get(i);
            BlockPos at = base.offset(layer.dx(), layer.dy(), layer.dz());
            BlockState target = parse(layer.state());
            if (target == null) {
                abandon(lamp, base);
                return;
            }
            BlockState current = level.getBlockState(at);
            if (current.is(target.getBlock())) {
                continue;
            }
            if (!WorldUtil.isAirLike(current) || !current.getFluidState().isEmpty()) {
                abandon(lamp, base);
                return;
            }
            if (i == 0 && now - lamp.since > IDLE_ABANDON && !feasible(level, layers)) {
                abandon(lamp, base);
                return;
            }
            if (!hasOrderAt(WorldUtil.pos(at))) {
                colony.improvements().queueFirst(at, layer.state(), Improvements.DECOR);
            }
            return;
        }
        if (lamp.replaces != null && !lamp.inPlace) {
            lamp.phase = "retire";
        } else {
            finish(level, lamp);
        }
        colony.markDirty();
    }

    private void abandon(ColonyData.Lamp lamp, BlockPos base) {
        rejected.add(base.asLong());
        data().lamps.remove(lamp);
        colony.markDirty();
    }

    private void finish(ServerLevel level, ColonyData.Lamp lamp) {
        ColonyData d = data();
        lamp.phase = "done";
        if (lamp.replaces != null) {
            d.bump("lamp_upgrades", 1);
        } else {
            d.bump("lamp_posts", 1);
        }
        if (lamp.tier >= LampDesign.LANTERN_POST && d.stat("lantern_posts") == 0) {
            colony.notify(level, Component.translatable("smartcolony.chat.lantern_posts"));
        } else if (lamp.tier == LampDesign.STONE_POST && d.stat("lamp_posts") + d.stat("lamp_upgrades") == 1) {
            colony.notify(level, Component.translatable("smartcolony.chat.lamp_posts"));
        }
        if (lamp.tier >= LampDesign.LANTERN_POST) {
            d.bump("lantern_posts", 1);
        }
        colony.journal(level, (lamp.tier >= LampDesign.LANTERN_POST ? "lantern" : "stone") + " lamp post finished at " + lamp.pos
                + (lamp.replaces != null ? ", replaced the old light" : ""));
        colony.markDirty();
    }

    private boolean retire(ServerLevel level, ColonyData.Lamp lamp) {
        BlockPos old = WorldUtil.bp(lamp.replaces);
        List<BlockPos> cells = new ArrayList<>();
        if (lamp.replacesTier == 0) {
            cells.add(old);
            cells.add(old.above());
        } else {
            for (LampDesign.Layer layer : LampDesign.layers(lamp.replacesTier, lamp.wood, lamp.base, 0, 0)) {
                cells.add(old.offset(layer.dx(), layer.dy(), layer.dz()));
            }
        }
        cells.sort(Comparator.comparingInt((BlockPos p) -> p.getY()).reversed());
        for (BlockPos cell : cells) {
            BlockState state = level.getBlockState(cell);
            if (state.isAir() || !Improvements.wallMaterial(state)) {
                continue;
            }
            if (!hasOrderAt(WorldUtil.pos(cell))) {
                colony.improvements().queueFirst(cell, "minecraft:air", Improvements.SALVAGE);
            }
            return false;
        }
        return true;
    }

    private static BlockState parse(String state) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, state, false).blockState();
        } catch (Exception e) {
            return null;
        }
    }

    public JsonObject debug() {
        ColonyData d = data();
        JsonObject json = new JsonObject();
        json.addProperty("ground", d.torches.size());
        json.addProperty("stone", d.lamps.stream().filter(l -> l.tier == LampDesign.STONE_POST && l.phase.equals("done")).count());
        json.addProperty("lantern", d.lamps.stream().filter(l -> l.tier == LampDesign.LANTERN_POST && l.phase.equals("done")).count());
        json.addProperty("building", active());
        return json;
    }
}
