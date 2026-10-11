package org.lts.smartcolony.colony;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.levelgen.Heightmap;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.Ids;
import org.lts.smartcolony.core.PathStyle;
import org.lts.smartcolony.core.Pier;
import org.lts.smartcolony.core.Pos;
import org.lts.smartcolony.core.RoadRouter;
import org.lts.smartcolony.core.Stock;
import org.lts.smartcolony.core.Tech;
import org.lts.smartcolony.util.WorldUtil;
import org.lts.smartcolony.work.Placer;

public final class Paths {
    private static final int MAX_NODES = 8000;
    private static final int MAX_PENDING = 250;
    private static final int PATH_VERSION = 1;
    private static final int CHUNKS_PER_TICK = 3;
    private static final int SCAN_PASSES = 3;
    private static final int MAX_DOOR_TARGETS = 80;
    private static final int MAX_MAIN_GATES = 3;
    private static final long STATIC_PERIOD = 1200;

    private final Colony colony;
    private long lastStatic = -100000;
    private int scanPasses;
    private boolean scanMissed;
    private long lastUpgrade = -100000;

    Paths(Colony colony) {
        this.colony = colony;
    }

    private ColonyData data() {
        return colony.data();
    }

    public BlockPos hub() {
        for (ColonyData.Building building : data().buildings) {
            if (org.lts.smartcolony.core.TownHall.stageOf(building.blueprint) > 0 && building.door != null) {
                return WorldUtil.bp(building.door).relative(outward(building), 2).below();
            }
        }
        return colony.groundCenter();
    }

    public void tick(ServerLevel level) {
        long now = level.getGameTime();
        if (data().pathVersion < PATH_VERSION) {
            data().pathVersion = PATH_VERSION;
            data().scanDone = false;
            data().scanIndex = 0;
            colony.markDirty();
        }
        scan(level);
        if (now - lastStatic >= STATIC_PERIOD) {
            lastStatic = now;
            collect(level);
        }
        planOne(level, now);
        if (now - lastUpgrade >= 2400) {
            lastUpgrade = now;
            upgradeMain(level);
        }
        gauges();
    }

    private void gauges() {
        ColonyData d = data();
        long main = d.roadStyle.values().stream().filter(s -> PathStyle.base(s).equals(PathStyle.MAIN)).count();
        d.stats.put("road_cells", d.roads.size());
        d.stats.put("main_street_cells", (int) main);
        d.stats.put("pier_cells", (int) d.roadStyle.values().stream().filter(PathStyle::pier).count());
        d.stats.put("pier_posts", (int) d.roadStyle.values().stream().filter(s -> PathStyle.pier(s) && PathStyle.post(s)).count());
        d.stats.put("path_targets_open", (int) d.targets.stream().filter(t -> t.state == 0).count());
    }

    public int ground(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        int floor = y - 6;
        boolean stepped = PathStyle.step(data().roadStyle.getOrDefault(styleKey(x, z), ""));
        while (y > floor) {
            BlockState state = level.getBlockState(new BlockPos(x, y, z));
            boolean slab = stepped && state.getBlock() instanceof SlabBlock;
            if (state.isAir() || state.is(Blocks.SNOW) || slab || WorldUtil.clearable(state) && state.getFluidState().isEmpty()) {
                y--;
                continue;
            }
            break;
        }
        return y;
    }

    private static String styleKey(int x, int z) {
        return x + "," + z;
    }

    public String styleAt(int x, int z) {
        return data().roadStyle.getOrDefault(styleKey(x, z), PathStyle.PLAIN);
    }

    private record Cell(int y, double cost, int kind) {
        static final Cell BLOCKED = new Cell(0, -1, 0);
    }

    private static final int NATURAL = 0;
    private static final int PATH = 1;
    private static final int OTHER = 2;
    private static final int PIER = 3;

    private final class Field implements RoadRouter.Grid {
        final ServerLevel level;
        final Map<Long, Integer> net = new HashMap<>();
        final Set<Long> gates = new HashSet<>();
        final Map<Long, Cell> cache = new HashMap<>();
        final BlockPos hub = hub();

        Field(ServerLevel level) {
            this.level = level;
            for (Pos p : data().roads) {
                net.put(BlockPos.asLong(p.x(), 0, p.z()), p.y());
            }
            for (Pos p : data().pendingRoad) {
                net.putIfAbsent(BlockPos.asLong(p.x(), 0, p.z()), p.y());
            }
            ColonyData.Wall wall = data().wall;
            if (wall != null) {
                for (Pos gate : wall.gates) {
                    gates.add(BlockPos.asLong(gate.x(), 0, gate.z()));
                }
            }
        }

        boolean free(BlockPos pos) {
            BlockState state = level.getBlockState(pos);
            if (state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.CACTUS) || state.is(BlockTags.FIRE) || state.is(Blocks.COBWEB)) {
                return false;
            }
            return state.is(Blocks.SNOW) || WorldUtil.passable(level, pos);
        }

        Cell cell(int x, int z) {
            return cache.computeIfAbsent(BlockPos.asLong(x, 0, z), key -> evaluate(x, z, key));
        }

        private Cell evaluate(int x, int z, long key) {
            if (!WorldUtil.loaded(level, new BlockPos(x, 0, z))) {
                return Cell.BLOCKED;
            }
            Integer known = net.get(key);
            if (known != null) {
                return new Cell(known, 0.35, PATH);
            }
            int y = ground(level, x, z);
            if (gates.contains(key)) {
                return new Cell(y, 1.0, NATURAL);
            }
            BlockPos ground = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(ground);
            if (!state.getFluidState().isEmpty() || !level.getFluidState(ground.above()).isEmpty()) {
                return pierCell(ground);
            }
            if (state.is(Blocks.FARMLAND) || state.is(Blocks.MUD) || state.is(BlockTags.ICE) || state.is(BlockTags.LEAVES)
                    || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)) {
                return Cell.BLOCKED;
            }
            if (!free(ground.above()) || !free(ground.above(2)) || reserved(ground.above())) {
                return Cell.BLOCKED;
            }
            if (state.is(Blocks.DIRT_PATH)) {
                return new Cell(y, 0.5, PATH);
            }
            if (state.is(Blocks.SNOW_BLOCK)) {
                return new Cell(y, 1.2, NATURAL);
            }
            if (pathable(state)) {
                return new Cell(y, 1.0, NATURAL);
            }
            if (state.is(BlockTags.SAND) || state.is(Blocks.GRAVEL)) {
                return new Cell(y, 1.8, NATURAL);
            }
            if (state.isFaceSturdy(level, ground, Direction.UP)) {
                return new Cell(y, state.is(BlockTags.BASE_STONE_OVERWORLD) ? 2.5 : 0.9, OTHER);
            }
            return Cell.BLOCKED;
        }

        private Cell pierCell(BlockPos top) {
            if (!level.getFluidState(top).isSourceOfType(Fluids.WATER) || !level.getFluidState(top.above()).isEmpty()) {
                return Cell.BLOCKED;
            }
            int depth = 0;
            BlockPos cursor = top;
            while (depth <= Pier.MAX_DEPTH + 1 && !level.getFluidState(cursor).isEmpty()) {
                cursor = cursor.below();
                depth++;
            }
            if (!Pier.shallow(depth) || !WorldUtil.solidFloor(level, cursor)) {
                return Cell.BLOCKED;
            }
            if (!free(top.above()) || !free(top.above(2)) || reserved(top.above())) {
                return Cell.BLOCKED;
            }
            return new Cell(top.getY(), Pier.cost(depth), PIER);
        }

        @Override
        public double cost(int x, int z) {
            return cell(x, z).cost();
        }

        @Override
        public int height(int x, int z) {
            return cell(x, z).y();
        }

        @Override
        public boolean goal(int x, int z) {
            if (net.containsKey(BlockPos.asLong(x, 0, z))) {
                return true;
            }
            int dx = x - hub.getX();
            int dz = z - hub.getZ();
            return dx * dx + dz * dz <= 4;
        }
    }

    public boolean reserved(BlockPos feet) {
        Pos p = WorldUtil.pos(feet);
        for (ColonyData.Building building : data().buildings) {
            if (building.contains(p, 0)) {
                return true;
            }
        }
        for (ColonyData.Project project : data().projects) {
            if (project.building.contains(p, 1)) {
                return true;
            }
        }
        ColonyData.Pen pen = data().pen;
        if (pen != null && p.x() >= pen.min.x() - 1 && p.x() <= pen.max.x() + 1 && p.z() >= pen.min.z() - 1 && p.z() <= pen.max.z() + 1) {
            return true;
        }
        for (ColonyData.Mine mine : colony.allMines()) {
            if (mine.quarry() && p.x() >= mine.corner.x() - 2 && p.z() >= mine.corner.z() - 2
                    && p.x() <= mine.corner.x() + mine.width + 1 && p.z() <= mine.corner.z() + mine.width + 1) {
                return true;
            }
            Pos shed = mine.shed;
            if (shed != null && p.x() >= shed.x() - 1 && p.x() <= shed.x() + Improvements.SHED_LENGTH
                    && p.z() >= shed.z() - 1 && p.z() <= shed.z() + Improvements.SHED_WIDTH) {
                return true;
            }
        }
        return colony.improvements().isWall(p) || colony.warehouse().contains(feet);
    }

    private static boolean pathable(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.PODZOL)
                || state.is(Blocks.MYCELIUM) || state.is(Blocks.ROOTED_DIRT);
    }

    private BlockPos approach(Field field, ColonyData.Target target) {
        int radius = switch (target.kind) {
            case "farm" -> 6;
            case "mine" -> 5;
            default -> 2;
        };
        int tx = target.pos.x();
        int tz = target.pos.z();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                Cell cell = field.cell(tx + dx, tz + dz);
                if (cell.cost() < 0) {
                    continue;
                }
                double toHub = Math.hypot(tx + dx - field.hub.getX(), tz + dz - field.hub.getZ());
                double score = Math.hypot(dx, dz) * 10 + toHub * 0.01;
                if (score < bestScore) {
                    bestScore = score;
                    best = new BlockPos(tx + dx, cell.y(), tz + dz);
                }
            }
        }
        return best;
    }

    private void planOne(ServerLevel level, long now) {
        ColonyData d = data();
        if (d.pendingRoad.size() > MAX_PENDING || d.targets.isEmpty()) {
            return;
        }
        BlockPos hub = hub();
        ColonyData.Target pick = null;
        for (ColonyData.Target target : d.targets) {
            if (target.state != 0 || target.retryAt > now || !WorldUtil.loaded(level, WorldUtil.bp(target.pos))) {
                continue;
            }
            if (pick == null || rank(target, hub) < rank(pick, hub)) {
                pick = target;
            }
        }
        if (pick == null) {
            return;
        }
        Field field = new Field(level);
        BlockPos from = approach(field, pick);
        List<int[]> path = from == null ? List.of() : RoadRouter.route(field, from.getX(), from.getZ(), MAX_NODES);
        if (path.stream().filter(c -> field.cell(c[0], c[1]).kind() == PIER).count() > Pier.MAX_ROUTE_CELLS) {
            path = List.of();
        }
        if (path.isEmpty()) {
            pick.tries++;
            pick.retryAt = now + 3000L * pick.tries;
            if (pick.tries >= 4) {
                pick.state = 2;
                colony.journal(level, "no way found for a path to the " + pick.kind + " at " + pick.pos);
            }
            colony.markDirty();
            return;
        }
        int added = commit(field, path, pick.main);
        pick.state = 1;
        d.bump("paths_planned", 1);
        if (added > 0) {
            colony.journal(level, (pick.main ? "main street" : "path") + " planned to the " + pick.kind + " at " + pick.pos + ": " + added + " blocks");
        }
        colony.markDirty();
    }

    private static double rank(ColonyData.Target target, BlockPos hub) {
        double dx = target.pos.x() - hub.getX();
        double dz = target.pos.z() - hub.getZ();
        return (target.main ? 0 : 100000) + Math.sqrt(dx * dx + dz * dz);
    }

    private int commit(Field field, List<int[]> path, boolean main) {
        Set<Long> steps = new HashSet<>();
        for (int[] cell : RoadRouter.stepCells(path, field)) {
            steps.add(BlockPos.asLong(cell[0], 0, cell[1]));
        }
        boolean[] water = new boolean[path.size()];
        for (int i = 0; i < water.length; i++) {
            water[i] = field.cell(path.get(i)[0], path.get(i)[1]).kind() == PIER;
        }
        int[] marks = Pier.markers(water);
        int added = 0;
        for (int i = 0; i < path.size(); i++) {
            int[] cell = path.get(i);
            String style = main ? PathStyle.MAIN : PathStyle.PLAIN;
            if (water[i]) {
                style = PathStyle.PIER;
                if ((marks[i] & Pier.POST) != 0) {
                    style = PathStyle.with(style, PathStyle.POST);
                }
                if ((marks[i] & Pier.LAMP) != 0) {
                    style = PathStyle.with(style, PathStyle.LAMP);
                }
            }
            added += addCell(field, cell[0], cell[1], style, steps.contains(BlockPos.asLong(cell[0], 0, cell[1])));
        }
        if (main) {
            for (int[] cell : RoadRouter.widen(path, field)) {
                if (field.cell(cell[0], cell[1]).kind() != PIER) {
                    added += addCell(field, cell[0], cell[1], PathStyle.SIDE, false);
                }
            }
        }
        return added;
    }

    private int addCell(Field field, int x, int z, String style, boolean step) {
        ColonyData d = data();
        long key = BlockPos.asLong(x, 0, z);
        String name = styleKey(x, z);
        String existing = d.roadStyle.get(name);
        Cell cell = field.cell(x, z);
        if (field.net.containsKey(key)) {
            if (existing == null && !style.equals(PathStyle.PLAIN)) {
                d.roadStyle.put(name, style);
            } else if (existing != null && PathStyle.base(existing).equals(PathStyle.PLAIN) && !style.equals(PathStyle.PLAIN)) {
                d.roadStyle.put(name, style + (PathStyle.step(existing) ? PathStyle.STEP : ""));
            }
            return 0;
        }
        Pos pos = new Pos(x, cell.y(), z);
        String full = step ? PathStyle.with(style, PathStyle.STEP) : style;
        if (!full.equals(PathStyle.PLAIN)) {
            d.roadStyle.put(name, full);
        }
        boolean built = cell.kind() != NATURAL && cell.kind() != PIER && !step;
        if (built) {
            if (!d.roads.contains(pos)) {
                d.roads.add(pos);
            }
        } else {
            d.pendingRoad.add(pos);
        }
        field.net.put(key, cell.y());
        return built ? 0 : 1;
    }

    public boolean build(ServerLevel level, Villager villager, BlockPos ground) {
        String style = styleAt(ground.getX(), ground.getZ());
        BlockPos above = ground.above();
        BlockState up = level.getBlockState(above);
        boolean ownSlab = PathStyle.step(style) && up.getBlock() instanceof SlabBlock;
        if (!up.isAir() && !ownSlab) {
            if (WorldUtil.isAirLike(up) && up.getFluidState().isEmpty()) {
                level.destroyBlock(above, false, villager);
            } else {
                return true;
            }
        }
        if (PathStyle.pier(style)) {
            if (!buildPier(level, villager, ground, style)) {
                return false;
            }
            if (PathStyle.step(style) && !ownSlab && WorldUtil.isAirLike(level.getBlockState(above))) {
                BlockState slab = slab(level);
                if (slab != null) {
                    Placer.place(level, villager, above, slab);
                    colony.data().bump("street_steps", 1);
                }
            }
            return true;
        }
        BlockState state = level.getBlockState(ground);
        Stock stock = colony.warehouse().stock(level);
        String material = PathStyle.material(style, Tech.ironAge(data().age), ground.getX(), ground.getZ(),
                stock.get(Ids.mc("stone_bricks")) >= 8, stock.get(Ids.mc("gravel")) >= 24);
        boolean soil = pathable(state) || state.is(Blocks.SNOW_BLOCK);
        boolean loose = state.is(BlockTags.SAND) || state.is(Blocks.GRAVEL);
        boolean laid = state.is(Blocks.DIRT_PATH);
        if (material.equals(PathStyle.DIRT_PATH)) {
            if (soil && !laid) {
                level.setBlock(ground, Blocks.DIRT_PATH.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
                level.playSound(null, ground, SoundEvents.SHOVEL_FLATTEN.value(), SoundSource.BLOCKS, 1.0F, 1.0F);
                villager.getLookControl().setLookAt(ground.getX() + 0.5, ground.getY() + 1.0, ground.getZ() + 0.5);
            } else if (loose && takeOrCraft(level, Ids.mc("cobblestone"))) {
                Placer.place(level, villager, ground, Blocks.COBBLESTONE.defaultBlockState());
            }
        } else if (soil || laid || loose) {
            String id = Ids.mc(material);
            BlockState wanted = parse(id);
            if (wanted != null && state.is(wanted.getBlock())) {
                id = null;
            } else if (!takeOrCraft(level, id)) {
                if (soil && !laid) {
                    level.setBlock(ground, Blocks.DIRT_PATH.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
                }
                id = null;
            }
            if (id != null) {
                BlockState paved = parse(id);
                if (paved != null) {
                    Placer.place(level, villager, ground, paved);
                    colony.data().bump("street_paved", 1);
                }
            }
        }
        if (PathStyle.step(style) && !ownSlab && WorldUtil.isAirLike(level.getBlockState(above))) {
            BlockState slab = slab(level);
            if (slab != null) {
                Placer.place(level, villager, above, slab);
                colony.data().bump("street_steps", 1);
            }
        }
        return true;
    }

    private boolean openWater(ServerLevel level, BlockPos side) {
        if (!WorldUtil.loaded(level, side) || !level.getFluidState(side).isSourceOfType(Fluids.WATER)) {
            return false;
        }
        if (PathStyle.pier(styleAt(side.getX(), side.getZ())) || reserved(side.above())) {
            return false;
        }
        return true;
    }

    private String takeKind(ServerLevel level, String suffix, boolean craftable) {
        String preferred = colony.construction().preferredWood(level);
        List<String> woods = new ArrayList<>();
        woods.add(preferred);
        for (String wood : Construction.OVERWORLD_WOODS) {
            if (!wood.equals(preferred)) {
                woods.add(wood);
            }
        }
        for (String wood : woods) {
            String id = Ids.mc(wood + suffix);
            if (colony.warehouse().take(level, id, 1) == 1) {
                return id;
            }
        }
        if (craftable) {
            String id = Ids.mc(preferred + suffix);
            if (colony.craft(level, id, 1) && colony.warehouse().take(level, id, 1) == 1) {
                return id;
            }
        }
        colony.request(Ids.mc(preferred + suffix));
        return null;
    }

    private boolean buildPier(ServerLevel level, Villager villager, BlockPos deck, String style) {
        BlockState here = level.getBlockState(deck);
        if (here.getFluidState().isEmpty() && !here.isAir() && !here.is(BlockTags.PLANKS)) {
            return true;
        }
        if (!here.is(BlockTags.PLANKS)) {
            String planks = takeKind(level, "_planks", true);
            if (planks == null) {
                return false;
            }
            Placer.place(level, villager, deck, parse(planks));
            colony.data().bump("pier_blocks", 1);
        }
        if (PathStyle.post(style)) {
            BlockPos post = deck.below();
            int guard = 0;
            while (guard++ <= Pier.MAX_DEPTH + 1 && !level.getFluidState(post).isEmpty()) {
                String log = takeKind(level, "_log", false);
                if (log == null) {
                    return false;
                }
                Placer.place(level, villager, post, parse(log));
                colony.data().bump("pier_blocks", 1);
                post = post.below();
            }
        }
        Direction[] sides = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
        boolean[] open = new boolean[4];
        for (int i = 0; i < 4; i++) {
            open[i] = openWater(level, deck.relative(sides[i]));
        }
        boolean[] rails = Pier.railing(!open[0], !open[1], !open[2], !open[3]);
        BlockPos lamp = null;
        for (int i = 0; i < 4; i++) {
            if (!rails[i]) {
                continue;
            }
            BlockPos rail = deck.relative(sides[i]).above();
            if (!level.getBlockState(rail).is(BlockTags.WOODEN_FENCES)) {
                if (!WorldUtil.isAirLike(level.getBlockState(rail))) {
                    continue;
                }
                String fence = takeKind(level, "_fence", true);
                if (fence == null) {
                    return false;
                }
                Placer.place(level, villager, rail, parse(fence));
                colony.data().bump("pier_blocks", 1);
            }
            if (lamp == null) {
                lamp = rail;
            }
        }
        if (lamp != null && PathStyle.lamp(style) && WorldUtil.isAirLike(level.getBlockState(lamp.above()))) {
            String light = Tech.ironAge(data().age) && takeOrCraft(level, Ids.mc("lantern")) ? Ids.mc("lantern")
                    : takeOrCraft(level, Ids.mc("torch")) ? Ids.mc("torch") : null;
            if (light != null) {
                BlockState lit = parse(light);
                if (lit != null && lit.canSurvive(level, lamp.above())) {
                    Placer.place(level, villager, lamp.above(), lit);
                    colony.data().bump("pier_lamps", 1);
                } else {
                    colony.warehouse().give(level, light, 1);
                }
            }
        }
        return true;
    }

    private boolean takeOrCraft(ServerLevel level, String id) {
        if (colony.warehouse().take(level, id, 1) == 1) {
            return true;
        }
        return colony.craft(level, id, 1) && colony.warehouse().take(level, id, 1) == 1;
    }

    private BlockState slab(ServerLevel level) {
        List<String> options = new ArrayList<>();
        options.add(Ids.mc("cobblestone_slab"));
        options.add(Ids.mc(colony.construction().preferredWood(level) + "_slab"));
        for (String id : options) {
            if (takeOrCraft(level, id)) {
                BlockState state = parse(id + "[type=bottom]");
                if (state != null) {
                    return state;
                }
            }
        }
        return null;
    }

    private static BlockState parse(String state) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, state, false).blockState();
        } catch (Exception e) {
            return null;
        }
    }

    private void upgradeMain(ServerLevel level) {
        ColonyData d = data();
        if (!Tech.ironAge(d.age) || d.pendingRoad.size() > 40 || colony.warehouse().stock(level).get(Ids.mc("cobblestone")) < 40) {
            return;
        }
        Map<String, Pos> byKey = new HashMap<>();
        for (Pos road : d.roads) {
            byKey.put(styleKey(road.x(), road.z()), road);
        }
        int queued = 0;
        for (Map.Entry<String, String> entry : new ArrayList<>(d.roadStyle.entrySet())) {
            if (queued >= 8) {
                break;
            }
            String style = entry.getValue();
            Pos road = byKey.get(entry.getKey());
            if (!PathStyle.base(style).equals(PathStyle.MAIN) || PathStyle.paved(style) || road == null
                    || !WorldUtil.loaded(level, WorldUtil.bp(road))) {
                continue;
            }
            d.roadStyle.put(entry.getKey(), PathStyle.with(style, PathStyle.STONE));
            d.pendingRoad.add(road);
            queued++;
        }
        if (queued > 0) {
            colony.journal(level, "paving " + queued + " main street blocks with stone");
            colony.markDirty();
        }
    }

    public Direction outward(ColonyData.Building building) {
        Pos door = building.door;
        int midX = (building.min.x() + building.max.x()) / 2;
        int midZ = (building.min.z() + building.max.z()) / 2;
        int ox = door.x() - midX;
        int oz = door.z() - midZ;
        return Math.abs(ox) >= Math.abs(oz) ? (ox >= 0 ? Direction.EAST : Direction.WEST) : (oz >= 0 ? Direction.SOUTH : Direction.NORTH);
    }

    public void onBuilt(ServerLevel level, ColonyData.Building building) {
        Direction out = outward(building);
        BlockPos door = WorldUtil.bp(building.door).relative(out);
        addTarget(new Pos(door.getX(), door.getY(), door.getZ()), "door", out.getStepX(), out.getStepZ(), false);
        colony.markDirty();
    }

    public void addDoorTarget(ServerLevel level, BlockPos outside) {
        addTarget(WorldUtil.pos(outside), "door", 0, 0, false);
    }

    private boolean addTarget(Pos pos, String kind, int dx, int dz, boolean main) {
        for (ColonyData.Target target : data().targets) {
            if (Math.abs(target.pos.x() - pos.x()) <= 2 && Math.abs(target.pos.z() - pos.z()) <= 2) {
                if (main && !target.main) {
                    target.main = true;
                    target.state = 0;
                    target.tries = 0;
                    target.retryAt = 0;
                }
                return false;
            }
        }
        ColonyData.Target target = new ColonyData.Target();
        target.pos = pos;
        target.kind = kind;
        target.dx = dx;
        target.dz = dz;
        target.main = main;
        data().targets.add(target);
        return true;
    }

    private void collect(ServerLevel level) {
        ColonyData d = data();
        BlockPos hub = hub();
        addTarget(WorldUtil.pos(colony.depot()), "depot", 0, 0, false);
        for (ColonyData.Building building : d.buildings) {
            if (building.door != null && !building.kind.equals("pen")) {
                Direction out = outward(building);
                BlockPos door = WorldUtil.bp(building.door).relative(out);
                addTarget(new Pos(door.getX(), door.getY(), door.getZ()), "door", out.getStepX(), out.getStepZ(), false);
            }
        }
        for (ColonyData.Mine mine : colony.allMines()) {
            addTarget(mine.entrance, "mine", 0, 0, false);
        }
        if (d.pen != null) {
            addTarget(d.pen.outside, "pen", 0, 0, false);
        }
        int farms = 0;
        List<Pos> nearFarms = new ArrayList<>(d.farms);
        nearFarms.sort(Comparator.comparingDouble(f -> f.horizontalDistSq(WorldUtil.pos(hub))));
        for (Pos farm : nearFarms) {
            if (farms++ >= 12) {
                break;
            }
            addTarget(farm, "farm", 0, 0, false);
        }
        collectGates(level);
        colony.markDirty();
    }

    private void collectGates(ServerLevel level) {
        ColonyData.Wall wall = data().wall;
        if (wall == null || wall.gates.isEmpty()) {
            return;
        }
        BlockPos hub = hub();
        List<Pos> gates = new ArrayList<>(wall.gates);
        gates.sort(Comparator.comparingDouble(g -> g.horizontalDistSq(WorldUtil.pos(hub))));
        List<Pos> mains = new ArrayList<>();
        for (Pos gate : gates) {
            if (mains.size() >= MAX_MAIN_GATES) {
                break;
            }
            boolean apart = true;
            double angle = Math.atan2(gate.z() - hub.getZ(), gate.x() - hub.getX());
            for (Pos other : mains) {
                double diff = Math.abs(Math.atan2(other.z() - hub.getZ(), other.x() - hub.getX()) - angle);
                diff = Math.min(diff, Math.PI * 2 - diff);
                if (diff < Math.PI / 3) {
                    apart = false;
                    break;
                }
            }
            if (apart) {
                mains.add(gate);
            }
        }
        for (Pos gate : gates) {
            int index = wall.columns.indexOf(gate);
            Pos before = wall.columns.get(Math.max(0, index - 1));
            Pos after = wall.columns.get(Math.min(wall.columns.size() - 1, index + 1));
            boolean passageAlongZ = Math.abs(after.x() - before.x()) >= Math.abs(after.z() - before.z());
            int ax = passageAlongZ ? 0 : 1;
            int az = passageAlongZ ? 1 : 0;
            Pos plus = new Pos(gate.x() + ax * 2, gate.y(), gate.z() + az * 2);
            Pos minus = new Pos(gate.x() - ax * 2, gate.y(), gate.z() - az * 2);
            boolean plusInside = plus.horizontalDistSq(WorldUtil.pos(hub)) <= minus.horizontalDistSq(WorldUtil.pos(hub));
            Pos inner = plusInside ? plus : minus;
            Pos outer = plusInside ? minus : plus;
            addTarget(inner, "gate", 0, 0, mains.contains(gate));
            addTarget(outer, "gate_out", 0, 0, false);
        }
    }

    private void scan(ServerLevel level) {
        ColonyData d = data();
        int radius = Math.min(80, d.radius + 8);
        if (d.scanDone && d.scanRadius >= radius) {
            return;
        }
        if (d.scanDone) {
            d.scanDone = false;
            d.scanIndex = 0;
            scanPasses = 0;
        }
        BlockPos c = colony.center();
        int minCx = (c.getX() - radius) >> 4;
        int minCz = (c.getZ() - radius) >> 4;
        int side = ((c.getX() + radius) >> 4) - minCx + 1;
        int sideZ = ((c.getZ() + radius) >> 4) - minCz + 1;
        int total = side * sideZ;
        for (int step = 0; step < CHUNKS_PER_TICK && d.scanIndex < total; step++) {
            int cx = minCx + d.scanIndex % side;
            int cz = minCz + d.scanIndex / side;
            d.scanIndex++;
            if (!WorldUtil.loaded(level, new BlockPos(cx << 4, 0, cz << 4))) {
                scanMissed = true;
                continue;
            }
            scanChunk(level, cx, cz, c.getY());
        }
        if (d.scanIndex >= total) {
            scanPasses++;
            if (scanMissed && scanPasses < SCAN_PASSES) {
                d.scanIndex = 0;
                scanMissed = false;
            } else {
                d.scanDone = true;
                d.scanRadius = radius;
                scanMissed = false;
                colony.journal(level, "scanned the village doors: " + doorTargets() + " doors to connect");
            }
        }
        colony.markDirty();
    }

    private long doorTargets() {
        return data().targets.stream().filter(t -> t.kind.equals("door")).count();
    }

    private void scanChunk(ServerLevel level, int cx, int cz, int centerY) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = cx << 4; x < (cx << 4) + 16; x++) {
            for (int z = cz << 4; z < (cz << 4) + 16; z++) {
                for (int y = centerY - 12; y <= centerY + 12; y++) {
                    cursor.set(x, y, z);
                    BlockState state = level.getBlockState(cursor);
                    if (!(state.getBlock() instanceof DoorBlock) || state.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
                        continue;
                    }
                    if (doorTargets() >= MAX_DOOR_TARGETS) {
                        return;
                    }
                    registerDoor(level, cursor.immutable(), state.getValue(DoorBlock.FACING));
                }
            }
        }
    }

    private void registerDoor(ServerLevel level, BlockPos door, Direction facing) {
        Field field = new Field(level);
        for (Direction side : new Direction[] {facing, facing.getOpposite()}) {
            BlockPos column = door.relative(side);
            if (!WorldUtil.loaded(level, column)) {
                continue;
            }
            int gy = ground(level, column.getX(), column.getZ());
            BlockPos feet = new BlockPos(column.getX(), gy + 1, column.getZ());
            if (Math.abs(feet.getY() - door.getY()) > 1 || !field.free(feet) || !field.free(feet.above())) {
                continue;
            }
            if (level.canSeeSky(feet) || level.canSeeSky(feet.relative(side))) {
                addTarget(WorldUtil.pos(feet), "door", side.getStepX(), side.getStepZ(), false);
                return;
            }
        }
    }

    public Set<Long> apronColumns() {
        Set<Long> cells = new HashSet<>();
        for (ColonyData.Target target : data().targets) {
            if (!target.kind.equals("door")) {
                continue;
            }
            for (int k = 0; k <= 1; k++) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        cells.add(BlockPos.asLong(target.pos.x() + target.dx * k + dx, 0, target.pos.z() + target.dz * k + dz));
                    }
                }
            }
        }
        for (ColonyData.Building building : data().buildings) {
            Direction out = outward(building);
            for (int k = 0; k <= 2; k++) {
                for (int j = -1; j <= 1; j++) {
                    BlockPos cell = WorldUtil.bp(building.door).relative(out, k).relative(out.getClockWise(), j);
                    cells.add(BlockPos.asLong(cell.getX(), 0, cell.getZ()));
                }
            }
        }
        return cells;
    }

    public Set<Long> roadColumns() {
        Set<Long> cells = new HashSet<>();
        for (Pos p : data().roads) {
            cells.add(BlockPos.asLong(p.x(), 0, p.z()));
        }
        for (Pos p : data().pendingRoad) {
            cells.add(BlockPos.asLong(p.x(), 0, p.z()));
        }
        return cells;
    }

    public JsonObject debug() {
        ColonyData d = data();
        JsonObject json = new JsonObject();
        json.addProperty("roads", d.roads.size());
        json.addProperty("pendingRoad", d.pendingRoad.size());
        json.addProperty("mainStreet", d.roadStyle.values().stream().filter(s -> PathStyle.base(s).equals(PathStyle.MAIN)).count());
        json.addProperty("steps", d.roadStyle.values().stream().filter(PathStyle::step).count());
        json.addProperty("targets", d.targets.size());
        json.addProperty("targetsOpen", d.targets.stream().filter(t -> t.state == 0).count());
        json.addProperty("targetsRouted", d.targets.stream().filter(t -> t.state == 1).count());
        json.addProperty("targetsFailed", d.targets.stream().filter(t -> t.state == 2).count());
        json.addProperty("doorScan", d.scanDone ? "done" : d.scanIndex + " chunks");
        return json;
    }
}
