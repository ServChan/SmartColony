package org.lts.smartcolony.colony;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.Ids;
import org.lts.smartcolony.core.Pos;
import org.lts.smartcolony.util.WorldUtil;

public final class Improvements {
    public static final String WALL = "wall";
    public static final String DECOR = "decor";
    public static final String REPAIR = "repair";
    private static final int SECTORS = 36;
    private static final int WALL_HEIGHT = 3;
    private static final int GATE_SPACING = 28;
    private static final int TOWER_SPACING = 40;
    private static final int TOWER_HEIGHT = 5;
    private static final long WALL_AFTER = 24000L * 2;

    private final Colony colony;
    private long blockedUntil;
    private String waitingFor = "";
    private Set<Long> wallColumns;

    Improvements(Colony colony) {
        this.colony = colony;
    }

    private ColonyData data() {
        return colony.data();
    }

    public String waitingFor() {
        return waitingFor;
    }

    public boolean hasWork(ServerLevel level) {
        return !data().orders.isEmpty() && level.getGameTime() >= blockedUntil;
    }

    public boolean isWall(Pos pos) {
        if (data().wall == null) {
            return false;
        }
        if (wallColumns == null) {
            wallColumns = new HashSet<>();
            for (Pos column : data().wall.columns) {
                wallColumns.add(BlockPos.asLong(column.x(), 0, column.z()));
            }
        }
        return wallColumns.contains(BlockPos.asLong(pos.x(), 0, pos.z()));
    }

    public void tick(ServerLevel level) {
        if (!data().villageSurveyed) {
            data().villageSurveyed = true;
            surveyVillage(level);
            colony.markDirty();
        }
        ColonyData.Mine mine = data().mine;
        if (mine != null && mine.quarry() && !mine.decorated) {
            mine.decorated = true;
            fenceQuarry(level, mine);
            colony.markDirty();
        }
        if (data().wall == null && wallWanted(level)) {
            planWall(level);
        }
        if (level.getGameTime() % 600 < 100) {
            inspect(level);
        }
        if (level.getGameTime() - data().founded > 24000 && level.getGameTime() - lastDecor > 48000) {
            lastDecor = level.getGameTime();
            decorate(level);
        }
        ColonyData.Wall wall = data().wall;
        if (wall != null && !wall.announced && wall.total > 0 && wall.built >= wall.total) {
            wall.announced = true;
            colony.journal(level, "the wall around the village is finished");
            colony.notify(level, Component.translatable("smartcolony.chat.wall_done"));
            colony.markDirty();
        }
    }

    private long lastDecor = -1000000;

    private void decorate(ServerLevel level) {
        int added = 0;
        BlockPos bell = colony.center();
        String wood = colony.construction().preferredWood(level);
        for (Direction away : Direction.Plane.HORIZONTAL) {
            BlockPos seat = WorldUtil.surface(level, bell.getX() + away.getStepX() * 4, bell.getZ() + away.getStepZ() * 4);
            Direction along = away.getClockWise();
            for (BlockPos pos : new BlockPos[] {seat, seat.relative(along)}) {
                if (decorSpot(level, pos)) {
                    queue(pos, Ids.mc(wood + "_stairs") + "[facing=" + away.getSerializedName() + ",half=bottom]");
                    added++;
                }
            }
        }
        List<Pos> roads = data().roads;
        int trees = 0;
        for (int i = 3; i + 1 < roads.size() && trees < 12; i += 7) {
            Pos a = roads.get(i);
            Pos b = roads.get(i + 1);
            int dx = b.x() - a.x();
            int dz = b.z() - a.z();
            if (Math.abs(dx) + Math.abs(dz) != 1) {
                continue;
            }
            BlockPos spot = WorldUtil.surface(level, a.x() - dz * 2, a.z() + dx * 2);
            if (!decorSpot(level, spot) || !level.getBlockState(spot.below()).is(net.minecraft.tags.BlockTags.SUBSTRATE_OVERWORLD)
                    || !WorldUtil.isAirLike(level.getBlockState(spot.above())) || nearLog(level, spot)) {
                continue;
            }
            String sapling = saplingInStock(level, wood);
            if (sapling == null) {
                break;
            }
            queue(spot, sapling);
            trees++;
            added++;
        }
        if (added > 0) {
            colony.journal(level, "decorating the village: " + added + " benches and avenue trees");
            colony.markDirty();
        }
    }

    private void fenceQuarry(ServerLevel level, ColonyData.Mine mine) {
        String wood = colony.construction().preferredWood(level);
        String fence = Ids.mc(wood + "_fence");
        int cx = mine.corner.x();
        int cz = mine.corner.z();
        int w = mine.width;
        Pos gate = new Pos(mine.entrance.x(), 0, mine.entrance.z());
        int added = 0;
        for (int x = cx - 1; x <= cx + w; x++) {
            for (int z = cz - 1; z <= cz + w; z++) {
                boolean ring = x == cx - 1 || x == cx + w || z == cz - 1 || z == cz + w;
                if (!ring) {
                    continue;
                }
                int y = groundY(level, x, z) + 1;
                BlockPos pos = new BlockPos(x, y, z);
                if (!WorldUtil.loaded(level, pos) || !WorldUtil.isAirLike(level.getBlockState(pos)) || !level.getFluidState(pos.below()).isEmpty()) {
                    continue;
                }
                boolean corner = (x == cx - 1 || x == cx + w) && (z == cz - 1 || z == cz + w);
                if (x == gate.x() && z == gate.z()) {
                    queue(pos, Ids.mc(wood + "_fence_gate") + "[facing=east]");
                } else {
                    queue(pos, fence);
                    if (corner) {
                        queue(pos.above(), fence);
                        queue(pos.above(2), "minecraft:torch");
                    }
                }
                added++;
            }
        }
        colony.journal(level, "fencing the quarry: " + added + " posts, a gate and corner lamps");
        tidyQuarry(level, mine);
    }

    private void tidyQuarry(ServerLevel level, ColonyData.Mine mine) {
        org.lts.smartcolony.core.QuarryPlan quarry = (org.lts.smartcolony.core.QuarryPlan) mine.plan();
        int dug = Math.min(quarry.layers(), mine.progress / quarry.perLayer());
        int cx = quarry.corner().x();
        int cz = quarry.corner().z();
        int w = quarry.width();
        int removed = 0;
        for (int y = quarry.layerY(dug) - 1; y <= quarry.corner().y() + 3; y++) {
            for (int x = cx; x < cx + w; x++) {
                for (int z = cz; z < cz + w; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH)) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
                        removed++;
                    }
                }
            }
        }
        int mid = w / 2;
        int[][] spots = {{cx + mid, cz, 0, -1}, {cx + mid, cz + w - 1, 0, 1}, {cx, cz + mid, -1, 0}, {cx + w - 1, cz + mid, 1, 0}};
        int placed = 0;
        for (int layer = 4; layer < dug; layer += 4) {
            int y = quarry.layerY(layer) + 1;
            for (int[] spot : spots) {
                BlockPos at = new BlockPos(spot[0], y, spot[1]);
                BlockPos wall = at.offset(spot[2], 0, spot[3]);
                Direction facing = Direction.getApproximateNearest(-spot[2], 0, -spot[3]);
                BlockState torch = Blocks.WALL_TORCH.defaultBlockState().setValue(net.minecraft.world.level.block.WallTorchBlock.FACING, facing);
                if (!WorldUtil.isAirLike(level.getBlockState(at)) || !level.getBlockState(wall).isFaceSturdy(level, wall, facing)
                        || !torch.canSurvive(level, at)) {
                    continue;
                }
                if (removed > placed || colony.warehouse().take(level, Ids.mc("torch"), 1) == 1) {
                    level.setBlock(at, torch, net.minecraft.world.level.block.Block.UPDATE_ALL);
                    placed++;
                }
            }
        }
        if (removed > placed) {
            colony.warehouse().give(level, Ids.mc("torch"), removed - placed);
        }
        colony.journal(level, "tidied the quarry lights: " + removed + " stray torches replaced by " + placed + " wall lamps");
    }

    private boolean decorSpot(ServerLevel level, BlockPos pos) {
        if (!WorldUtil.loaded(level, pos) || !WorldUtil.isAirLike(level.getBlockState(pos)) || !level.getFluidState(pos).isEmpty()) {
            return false;
        }
        if (!WorldUtil.solidFloor(level, pos.below()) || colony.sites().onRoad(pos.below()) || level.getBlockState(pos.below()).is(Blocks.DIRT_PATH)) {
            return false;
        }
        return !colony.isProtected(pos) && data().orders.stream().noneMatch(order -> order.pos.equals(WorldUtil.pos(pos)));
    }

    private static boolean nearLog(ServerLevel level, BlockPos pos) {
        for (BlockPos check : BlockPos.betweenClosed(pos.offset(-3, 0, -3), pos.offset(3, 3, 3))) {
            BlockState state = level.getBlockState(check);
            if (state.is(net.minecraft.tags.BlockTags.LOGS) || state.is(net.minecraft.tags.BlockTags.SAPLINGS)) {
                return true;
            }
        }
        return false;
    }

    private String saplingInStock(ServerLevel level, String wood) {
        String preferred = Ids.mc(wood + "_sapling");
        if (colony.stockOf(level, id -> id.equals(preferred)) > 0) {
            return preferred;
        }
        for (String id : colony.warehouse().stock(level).sorted().keySet()) {
            if (id.endsWith("_sapling") && !id.contains("dark_oak")) {
                return id;
            }
        }
        return null;
    }

    private void queue(BlockPos pos, String state) {
        data().orders.add(new ColonyData.Order(WorldUtil.pos(pos), state, DECOR));
    }

    private int inspectBuilding;
    private int inspectWall;

    private void inspect(ServerLevel level) {
        long repairs = data().orders.stream().filter(order -> REPAIR.equals(order.tag)).count();
        if (repairs >= 64) {
            return;
        }
        List<ColonyData.Order> found = new ArrayList<>();
        List<ColonyData.Building> buildings = data().buildings;
        if (!buildings.isEmpty()) {
            ColonyData.Building building = buildings.get(Math.floorMod(inspectBuilding++, buildings.size()));
            for (org.lts.smartcolony.core.Blueprint.Cell cell : colony.construction().cells(building, building.swaps)) {
                BlockPos pos = colony.construction().world(building, cell.x(), cell.y(), cell.z());
                if (!WorldUtil.loaded(level, pos) || !missing(level, pos)) {
                    continue;
                }
                BlockState expected = Construction.parse(cell.state(), building.rotation);
                if (expected.isAir() || expected.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF)
                        || expected.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.BED_PART)) {
                    continue;
                }
                found.add(new ColonyData.Order(WorldUtil.pos(pos), BlockStateParser.serialize(expected), REPAIR));
            }
        }
        ColonyData.Wall wall = data().wall;
        boolean wallPending = data().orders.stream().anyMatch(order -> WALL.equals(order.tag));
        if (wall != null && !wallPending && !wall.columns.isEmpty()) {
            for (int i = 0; i < 40; i++) {
                Pos column = wall.columns.get(Math.floorMod(inspectWall++, wall.columns.size()));
                boolean gate = wall.gates.contains(column);
                for (int h = 1; h <= (gate ? 1 : WALL_HEIGHT); h++) {
                    BlockPos pos = new BlockPos(column.x(), column.y() + h, column.z());
                    if (!WorldUtil.loaded(level, pos) || !missing(level, pos)) {
                        continue;
                    }
                    String state;
                    if (gate) {
                        state = Ids.mc((wall.material.equals("stone") ? "oak" : wall.material) + "_fence_gate");
                    } else if (wall.material.equals("stone")) {
                        state = h == WALL_HEIGHT ? "minecraft:cobblestone_wall" : "minecraft:cobblestone";
                    } else {
                        state = Ids.mc(wall.material + "_log");
                    }
                    found.add(new ColonyData.Order(WorldUtil.pos(pos), state, REPAIR));
                }
            }
        }
        if (found.isEmpty()) {
            return;
        }
        for (ColonyData.Order order : found) {
            if (data().orders.stream().noneMatch(existing -> existing.pos.equals(order.pos))) {
                data().orders.addFirst(order);
            }
        }
        blockedUntil = 0;
        colony.journal(level, "found " + found.size() + " damaged blocks to repair");
        colony.data().bump("repairs_planned", found.size());
        colony.markDirty();
    }

    private static boolean missing(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.isAir() || state.is(net.minecraft.tags.BlockTags.FIRE) || (!state.getFluidState().isEmpty() && state.getFluidState().isSource() == false && state.canBeReplaced());
    }

    private boolean wallWanted(ServerLevel level) {
        if (level.getGameTime() - data().founded < WALL_AFTER && colony.defense().threatLevel() < 0.8) {
            return false;
        }
        return colony.adultCount(level) >= 5;
    }

    private void surveyVillage(ServerLevel level) {
        BlockPos center = colony.center();
        int radius = data().radius + 8;
        int doors = 0;
        int lights = 0;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -10, -radius), center.offset(radius, 10, radius))) {
            if (doors >= 16) {
                break;
            }
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof DoorBlock) || state.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
                continue;
            }
            Direction facing = state.getValue(DoorBlock.FACING);
            BlockPos outside = null;
            for (Direction side : new Direction[] {facing, facing.getOpposite()}) {
                BlockPos candidate = pos.relative(side);
                if (level.canSeeSky(candidate) && WorldUtil.standable(level, candidate)) {
                    outside = candidate.immutable();
                    break;
                }
            }
            if (outside == null) {
                continue;
            }
            doors++;
            colony.construction().planRoad(level, outside);
            BlockPos lamp = outside.relative(facing.getClockWise());
            if (WorldUtil.isAirLike(level.getBlockState(lamp)) && WorldUtil.standable(level, lamp)
                    && !data().pendingTorches.contains(WorldUtil.pos(lamp))) {
                data().pendingTorches.add(WorldUtil.pos(lamp));
                lights++;
            }
        }
        colony.journal(level, "surveyed the village: paths to " + doors + " doors, " + lights + " door lamps");
    }

    private void planWall(ServerLevel level) {
        BlockPos center = colony.center();
        List<BlockPos> points = new ArrayList<>();
        int reach = data().radius + 16;
        level.getPoiManager().getInSquare(type -> true, center, reach, PoiManager.Occupancy.ANY)
                .forEach(record -> points.add(record.getPos()));
        for (ColonyData.Building building : data().buildings) {
            points.add(WorldUtil.bp(building.min));
            points.add(WorldUtil.bp(building.max));
        }
        for (Pos farm : data().farms) {
            points.add(WorldUtil.bp(farm));
        }
        points.add(colony.depot());
        if (data().pen != null) {
            points.add(WorldUtil.bp(data().pen.min));
            points.add(WorldUtil.bp(data().pen.max));
        }
        double[] radii = new double[SECTORS];
        for (int k = 0; k < SECTORS; k++) {
            radii[k] = 14;
        }
        for (BlockPos point : points) {
            double dx = point.getX() - center.getX();
            double dz = point.getZ() - center.getZ();
            double distance = Math.sqrt(dx * dx + dz * dz);
            if (distance > reach) {
                continue;
            }
            double angle = Math.atan2(dz, dx);
            int sector = Math.floorMod((int) Math.round(angle / (2 * Math.PI) * SECTORS), SECTORS);
            for (int spread = -2; spread <= 2; spread++) {
                int k = Math.floorMod(sector + spread, SECTORS);
                radii[k] = Math.max(radii[k], distance + 5 - Math.abs(spread));
            }
        }
        long seed = data().id.hashCode();
        for (int pass = 0; pass < 2; pass++) {
            double[] smooth = new double[SECTORS];
            for (int k = 0; k < SECTORS; k++) {
                smooth[k] = (radii[Math.floorMod(k - 1, SECTORS)] + radii[k] * 2 + radii[(k + 1) % SECTORS]) / 4.0;
            }
            radii = smooth;
        }
        for (int k = 0; k < SECTORS; k++) {
            long hash = (seed * 31 + k) * 0x9E3779B97F4A7C15L;
            double noise = ((hash >>> 40) % 5) - 2;
            radii[k] = Math.max(14, Math.min(data().radius + 26, Math.max(radii[k], radii[k] + noise)));
        }
        List<int[]> ring = new ArrayList<>();
        for (int k = 0; k < SECTORS; k++) {
            double a0 = 2 * Math.PI * k / SECTORS;
            double a1 = 2 * Math.PI * (k + 1) / SECTORS;
            int x0 = center.getX() + (int) Math.round(Math.cos(a0) * radii[k]);
            int z0 = center.getZ() + (int) Math.round(Math.sin(a0) * radii[k]);
            int x1 = center.getX() + (int) Math.round(Math.cos(a1) * radii[(k + 1) % SECTORS]);
            int z1 = center.getZ() + (int) Math.round(Math.sin(a1) * radii[(k + 1) % SECTORS]);
            line(ring, x0, z0, x1, z1);
        }
        boolean stone = data().mine != null || colony.stockOf(level, id -> id.equals(Ids.mc("cobblestone"))) >= 64;
        String wood = colony.construction().preferredWood(level);
        ColonyData.Wall wall = new ColonyData.Wall();
        wall.material = stone ? "stone" : wood;
        wall.planned = level.getGameTime();
        int sinceGate = GATE_SPACING / 2;
        int sinceTower = TOWER_SPACING / 2;
        List<ColonyData.Order> towerOrders = new ArrayList<>();
        List<ColonyData.Order> wallOrders = new ArrayList<>();
        Set<Long> towerCells = new HashSet<>();
        String pillar = stone ? "minecraft:cobblestone" : Ids.mc(wood + "_log");
        String cap = stone ? "minecraft:cobblestone_wall" : Ids.mc(wood + "_fence");
        List<Pos> mainGates = new ArrayList<>();
        for (int[] cell : ring) {
            BlockPos column = new BlockPos(cell[0], 0, cell[1]);
            if (!WorldUtil.loaded(level, column)) {
                continue;
            }
            int groundY = groundY(level, cell[0], cell[1]);
            BlockPos ground = new BlockPos(cell[0], groundY, cell[1]);
            if (!level.getFluidState(ground).isEmpty() || !level.getFluidState(ground.above()).isEmpty()) {
                continue;
            }
            if (colony.isProtected(ground.above(), false) || colony.warehouse().contains(ground.above())) {
                continue;
            }
            Pos columnPos = new Pos(cell[0], groundY, cell[1]);
            wall.columns.add(columnPos);
            BlockState groundState = level.getBlockState(ground);
            boolean road = colony.sites().onRoad(ground);
            boolean path = groundState.is(Blocks.DIRT_PATH) || road;
            sinceGate++;
            sinceTower++;
            if (path || sinceGate >= GATE_SPACING) {
                sinceGate = 0;
                Direction across = Math.abs(cell[0] - center.getX()) > Math.abs(cell[1] - center.getZ()) ? Direction.EAST : Direction.NORTH;
                String gate = Ids.mc(wood + "_fence_gate") + "[facing=" + across.getSerializedName() + "]";
                wallOrders.add(new ColonyData.Order(new Pos(cell[0], groundY + 1, cell[1]), gate, WALL));
                wall.gates.add(columnPos);
                if (road) {
                    mainGates.add(columnPos);
                }
                continue;
            }
            if (sinceTower >= TOWER_SPACING && sinceGate > 3) {
                sinceTower = 0;
                tower(level, columnPos, pillar, cap, towerOrders, towerCells);
            }
            for (int h = 1; h <= WALL_HEIGHT; h++) {
                String state;
                if (stone) {
                    state = h == WALL_HEIGHT ? "minecraft:cobblestone_wall" : "minecraft:cobblestone";
                } else {
                    state = Ids.mc(wood + "_log");
                }
                wallOrders.add(new ColonyData.Order(new Pos(cell[0], groundY + h, cell[1]), state, WALL));
            }
        }
        for (Pos gate : mainGates) {
            int index = wall.columns.indexOf(gate);
            for (int side : new int[] {-1, 1}) {
                int neighbour = index + side;
                if (neighbour < 0 || neighbour >= wall.columns.size() || wall.gates.contains(wall.columns.get(neighbour))) {
                    continue;
                }
                Pos post = wall.columns.get(neighbour);
                wallOrders.add(new ColonyData.Order(new Pos(post.x(), post.y() + WALL_HEIGHT + 1, post.z()), pillar, WALL));
                wallOrders.add(new ColonyData.Order(new Pos(post.x(), post.y() + WALL_HEIGHT + 2, post.z()), "minecraft:lantern", WALL));
            }
        }
        wallOrders.removeIf(order -> towerCells.contains(BlockPos.asLong(order.pos.x(), 0, order.pos.z())));
        data().orders.addAll(towerOrders);
        data().orders.addAll(wallOrders);
        wall.total = towerOrders.size() + wallOrders.size();
        data().wall = wall;
        wallColumns = null;
        colony.journal(level, "planned a " + wall.material + " wall: " + wall.columns.size() + " columns, " + wall.total + " blocks");
        colony.notify(level, Component.translatable("smartcolony.chat.wall_planned", wall.columns.size()));
        colony.markDirty();
    }

    private void tower(ServerLevel level, Pos base, String pillar, String cap, List<ColonyData.Order> orders, Set<Long> cells) {
        int top = base.y() + TOWER_HEIGHT;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int x = base.x() + dx;
                int z = base.z() + dz;
                int ground = groundY(level, x, z);
                BlockPos foot = new BlockPos(x, ground, z);
                if (top - ground > TOWER_HEIGHT + 3 || !level.getFluidState(foot).isEmpty()
                        || colony.isProtected(foot.above(), false) || colony.warehouse().contains(foot.above())) {
                    continue;
                }
                cells.add(BlockPos.asLong(x, 0, z));
                for (int y = ground + 1; y <= top; y++) {
                    orders.add(new ColonyData.Order(new Pos(x, y, z), pillar, WALL));
                }
                if (dx != 0 || dz != 0) {
                    orders.add(new ColonyData.Order(new Pos(x, top + 1, z), cap, WALL));
                } else {
                    orders.add(new ColonyData.Order(new Pos(x, top + 1, z), "minecraft:torch", WALL));
                }
            }
        }
    }

    private static void line(List<int[]> out, int x0, int z0, int x1, int z1) {
        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;
        int err = dx - dz;
        int x = x0;
        int z = z0;
        while (true) {
            if (out.isEmpty() || out.getLast()[0] != x || out.getLast()[1] != z) {
                if (!out.isEmpty()) {
                    int[] last = out.getLast();
                    if (last[0] != x && last[1] != z) {
                        out.add(new int[] {x, last[1]});
                    }
                }
                out.add(new int[] {x, z});
            }
            if (x == x1 && z == z1) {
                break;
            }
            int e2 = 2 * err;
            if (e2 > -dz) {
                err -= dz;
                x += sx;
            }
            if (e2 < dx) {
                err += dx;
                z += sz;
            }
        }
    }

    private static int groundY(ServerLevel level, int x, int z) {
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        int floor = y - 8;
        while (y > floor && WorldUtil.removable(level, new BlockPos(x, y, z))) {
            y--;
        }
        return y;
    }

    public Construction.Task next(ServerLevel level) {
        if (level.getGameTime() < blockedUntil) {
            return null;
        }
        List<ColonyData.Order> orders = data().orders;
        int checks = Math.min(orders.size(), 48);
        for (int i = 0; i < checks && !orders.isEmpty(); i++) {
            ColonyData.Order order = orders.getFirst();
            BlockPos pos = WorldUtil.bp(order.pos);
            if (!WorldUtil.loaded(level, pos)) {
                orders.add(orders.removeFirst());
                continue;
            }
            BlockState target = parse(order.state);
            BlockState current = level.getBlockState(pos);
            if (target == null || current.is(target.getBlock())
                    || (!WorldUtil.isAirLike(current) && !WorldUtil.removable(level, pos))) {
                orders.removeFirst();
                done(order);
                continue;
            }
            String item = BuiltInRegistries.ITEM.getKey(target.getBlock().asItem()).toString();
            if (colony.stockOf(level, id -> id.equals(item)) == 0 && !colony.craft(level, item, 1)) {
                colony.request(item);
                waitingFor = item;
                orders.add(orders.removeFirst());
                continue;
            }
            waitingFor = "";
            BlockPos center = colony.center();
            int sx = Integer.signum(center.getX() - pos.getX());
            int sz = Integer.signum(center.getZ() - pos.getZ());
            BlockPos stand = WorldUtil.surface(level, pos.getX() + sx * 2, pos.getZ() + sz * 2);
            return new Construction.Task(Construction.Kind.ORDER, pos, target, null, null, stand);
        }
        if (!orders.isEmpty()) {
            blockedUntil = level.getGameTime() + 1200;
        }
        return null;
    }

    public boolean take(ServerLevel level, BlockState state) {
        String item = BuiltInRegistries.ITEM.getKey(state.getBlock().asItem()).toString();
        return colony.warehouse().take(level, item, 1) == 1;
    }

    public void completed(Construction.Task task) {
        Pos pos = WorldUtil.pos(task.pos());
        List<ColonyData.Order> orders = data().orders;
        for (int i = 0; i < orders.size(); i++) {
            if (orders.get(i).pos.equals(pos)) {
                done(orders.remove(i));
                break;
            }
        }
        colony.markDirty();
    }

    private void done(ColonyData.Order order) {
        if (WALL.equals(order.tag) && data().wall != null) {
            data().wall.built++;
        }
    }

    private static BlockState parse(String state) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, state, false).blockState();
        } catch (Exception e) {
            return null;
        }
    }
}
