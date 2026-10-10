package org.lts.smartcolony.colony;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.lts.smartcolony.core.Blueprint;
import org.lts.smartcolony.core.Blueprints;
import org.lts.smartcolony.core.Clearance;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.Pier;
import org.lts.smartcolony.core.Pos;
import org.lts.smartcolony.core.Terrace;
import org.lts.smartcolony.util.WorldUtil;
import org.lts.smartcolony.work.TreeScan;

final class Earthworks {
    private static final int OBSTACLE_MARGIN = 2;
    private static final int OVERGROWTH_LIMIT = 700;
    private static final List<String> FILL_ITEMS = List.of("dirt", "coarse_dirt", "cobblestone", "cobbled_deepslate", "andesite",
            "diorite", "granite", "tuff", "stone");

    record Work(List<BlockPos> clear, List<BlockPos> fill, int cut, int obstacles) {
    }

    private Earthworks() {
    }

    static Work plan(ServerLevel level, Colony colony, ColonyData.Project project) {
        ColonyData.Building building = project.building;
        Blueprint blueprint = Blueprints.get(building.blueprint);
        int sx = blueprint.rotatedSizeX(building.rotation);
        int sz = blueprint.rotatedSizeZ(building.rotation);
        int ox = building.origin.x();
        int oz = building.origin.z();
        int base = building.origin.y();
        boolean wet = Pier.waterKind(blueprint.kind()) || !project.stilts.isEmpty() || project.upgrade;
        int margin = wet ? OBSTACLE_MARGIN : Terrace.MARGIN;
        int width = sx + margin * 2;
        int depth = sz + margin * 2;
        int wx = ox - margin;
        int wz = oz - margin;
        int[][] ground = new int[width][depth];
        boolean[][] fixed = new boolean[width][depth];
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                int ax = wx + x;
                int az = wz + z;
                if (!WorldUtil.loaded(level, new BlockPos(ax, 0, az))) {
                    ground[x][z] = base;
                    fixed[x][z] = true;
                    continue;
                }
                ground[x][z] = WorldUtil.soilY(level, ax, az);
                int ring = Terrace.ring(x, z, margin, sx, sz);
                fixed[x][z] = wet || ring > 0 && fixedColumn(level, colony, project, new BlockPos(ax, ground[x][z], az));
            }
        }
        Terrace.Door door = null;
        if (building.door != null && !wet && !"pen".equals(building.kind)) {
            int[] out = Clearance.outward(blueprint.doorX(), blueprint.doorZ(), blueprint.sizeX(), blueprint.sizeZ(), building.rotation);
            door = new Terrace.Door(building.door.x() - wx, building.door.z() - wz, out[0], out[1]);
        }
        Terrace.Plan plan = Terrace.plan(ground, fixed, margin, sx, sz, base, door);
        Set<BlockPos> clear = new LinkedHashSet<>();
        List<BlockPos> fill = new ArrayList<>();
        int cut = 0;
        Set<Long> zone = new HashSet<>();
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                int ax = wx + x;
                int az = wz + z;
                int ring = Terrace.ring(x, z, margin, sx, sz);
                int target = plan.at(x, z);
                if (ring <= OBSTACLE_MARGIN || target != ground[x][z] || Terrace.inDoorway(x, z, door)) {
                    zone.add(BlockPos.asLong(ax, 0, az));
                }
                for (int y = ground[x][z]; y > target; y--) {
                    BlockPos pos = new BlockPos(ax, y, az);
                    if (!earth(level.getBlockState(pos))) {
                        break;
                    }
                    clear.add(pos);
                    cut++;
                }
                if (ring > 0) {
                    for (int y = ground[x][z] + 1; y <= target; y++) {
                        fill.add(new BlockPos(ax, y, az));
                    }
                }
            }
        }
        int top = base + blueprint.sizeY() + 10;
        Set<BlockPos> overgrowth = new HashSet<>();
        Set<BlockPos> visited = new HashSet<>();
        for (long column : zone) {
            int ax = BlockPos.getX(column);
            int az = BlockPos.getZ(column);
            int from = Math.min(base, WorldUtil.soilY(level, ax, az)) + 1;
            for (int y = from; y <= top; y++) {
                BlockPos pos = new BlockPos(ax, y, az);
                if (overgrowth.contains(pos) || visited.contains(pos)) {
                    continue;
                }
                BlockState state = level.getBlockState(pos);
                if (state.is(BlockTags.LOGS) || WorldUtil.hugeMushroom(state)) {
                    flood(level, colony, project, pos, overgrowth, visited);
                } else if (TreeScan.isNaturalLeaf(state) && y <= base + blueprint.sizeY() + 2) {
                    overgrowth.add(pos);
                }
            }
        }
        List<BlockPos> all = new ArrayList<>(clear);
        for (BlockPos pos : overgrowth) {
            if (!clear.contains(pos)) {
                all.add(pos);
            }
        }
        all.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).reversed().thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getZ));
        fill.sort(Comparator.comparingInt(BlockPos::getY));
        return new Work(all, fill, cut, overgrowth.size());
    }

    private static boolean fixedColumn(ServerLevel level, Colony colony, ColonyData.Project project, BlockPos top) {
        if (colony.isProtected(top.above(), true, project) || colony.sites().onRoad(top) || colony.improvements().isWall(WorldUtil.pos(top))) {
            return true;
        }
        if (colony.inMine(top) || colony.inMine(top.above())) {
            return true;
        }
        BlockState state = level.getBlockState(top);
        if (!state.getFluidState().isEmpty() || !level.getFluidState(top.above()).isEmpty() || level.getBlockEntity(top) != null) {
            return true;
        }
        return !earth(state) || state.is(Blocks.FARMLAND) || state.is(Blocks.DIRT_PATH);
    }

    static boolean earth(BlockState state) {
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        return WorldUtil.naturalGround(state) || WorldUtil.clearable(state) || WorldUtil.overgrowth(state) && !leafPersistent(state);
    }

    private static boolean leafPersistent(BlockState state) {
        return state.is(BlockTags.LEAVES) && !TreeScan.isNaturalLeaf(state);
    }

    private static void flood(ServerLevel level, Colony colony, ColonyData.Project project, BlockPos start, Set<BlockPos> out,
                              Set<BlockPos> visited) {
        boolean mushroom = WorldUtil.hugeMushroom(level.getBlockState(start));
        Set<BlockPos> found = new HashSet<>();
        int leaves = 0;
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty() && found.size() < OVERGROWTH_LIMIT) {
            BlockPos pos = queue.poll();
            if (foreign(colony, project, pos)) {
                continue;
            }
            found.add(pos);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos next = pos.offset(dx, dy, dz);
                        if (!seen.add(next) || Math.abs(next.getX() - start.getX()) > 12 || Math.abs(next.getZ() - start.getZ()) > 12
                                || !WorldUtil.loaded(level, next)) {
                            continue;
                        }
                        BlockState state = level.getBlockState(next);
                        boolean same = mushroom ? WorldUtil.hugeMushroom(state) : state.is(BlockTags.LOGS);
                        if (same) {
                            queue.add(next);
                        } else if (!mushroom && TreeScan.isNaturalLeaf(state) && Math.abs(dx) + Math.abs(dy) + Math.abs(dz) == 1) {
                            found.add(next);
                            leaves++;
                        }
                    }
                }
            }
        }
        visited.addAll(found);
        visited.add(start);
        if (mushroom || leaves >= 3) {
            out.addAll(found);
        }
    }

    private static boolean foreign(Colony colony, ColonyData.Project project, BlockPos pos) {
        Pos p = WorldUtil.pos(pos);
        for (ColonyData.Building building : colony.data().buildings) {
            if (building.contains(p, 0)) {
                return true;
            }
        }
        for (ColonyData.Project other : colony.data().projects) {
            if (other != project && other.building.contains(p, 0)) {
                return true;
            }
        }
        return false;
    }

    static BlockState takeFill(ServerLevel level, Colony colony) {
        for (String id : FILL_ITEMS) {
            String item = "minecraft:" + id;
            if (colony.warehouse().take(level, item, 1) == 1) {
                return Construction.parse(item, 0);
            }
        }
        return null;
    }
}
