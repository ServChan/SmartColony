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
    private static final int SECTORS = 36;
    private static final int WALL_HEIGHT = 3;
    private static final int GATE_SPACING = 28;
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
        if (data().wall == null && wallWanted(level)) {
            planWall(level);
        }
        ColonyData.Wall wall = data().wall;
        if (wall != null && !wall.announced && wall.total > 0 && wall.built >= wall.total) {
            wall.announced = true;
            colony.journal(level, "the wall around the village is finished");
            colony.notify(level, Component.translatable("smartcolony.chat.wall_done"));
            colony.markDirty();
        }
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
            boolean path = groundState.is(Blocks.DIRT_PATH) || colony.sites().onRoad(ground);
            sinceGate++;
            if (path || sinceGate >= GATE_SPACING) {
                sinceGate = 0;
                Direction across = Math.abs(cell[0] - center.getX()) > Math.abs(cell[1] - center.getZ()) ? Direction.EAST : Direction.NORTH;
                String gate = Ids.mc(wood + "_fence_gate") + "[facing=" + across.getSerializedName() + "]";
                data().orders.add(new ColonyData.Order(new Pos(cell[0], groundY + 1, cell[1]), gate, WALL));
                wall.total++;
                continue;
            }
            for (int h = 1; h <= WALL_HEIGHT; h++) {
                String state;
                if (stone) {
                    state = h == WALL_HEIGHT ? "minecraft:cobblestone_wall" : "minecraft:cobblestone";
                } else {
                    state = Ids.mc(wood + "_log");
                }
                data().orders.add(new ColonyData.Order(new Pos(cell[0], groundY + h, cell[1]), state, WALL));
                wall.total++;
            }
        }
        data().wall = wall;
        wallColumns = null;
        colony.journal(level, "planned a " + wall.material + " wall: " + wall.columns.size() + " columns, " + wall.total + " blocks");
        colony.notify(level, Component.translatable("smartcolony.chat.wall_planned", wall.columns.size()));
        colony.markDirty();
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
                blockedUntil = level.getGameTime() + 1200;
                return null;
            }
            waitingFor = "";
            BlockPos center = colony.center();
            int sx = Integer.signum(center.getX() - pos.getX());
            int sz = Integer.signum(center.getZ() - pos.getZ());
            BlockPos stand = WorldUtil.surface(level, pos.getX() + sx * 2, pos.getZ() + sz * 2);
            return new Construction.Task(Construction.Kind.ORDER, pos, target, null, null, stand);
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
