package org.lts.smartcolony.work;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.lts.smartcolony.util.WorldUtil;

public final class TreeScan {
    private static final int MAX_LOGS = 220;
    private static final int MAX_LEAVES = 420;

    private TreeScan() {
    }

    public static boolean isLog(BlockState state) {
        return state.is(BlockTags.LOGS);
    }

    public static boolean isNaturalLeaf(BlockState state) {
        return state.getBlock() instanceof LeavesBlock && state.hasProperty(LeavesBlock.PERSISTENT)
                && !state.getValue(LeavesBlock.PERSISTENT);
    }

    private static boolean isArtificial(BlockState state) {
        return state.is(BlockTags.PLANKS) || state.is(BlockTags.STAIRS) || state.is(BlockTags.SLABS)
                || state.is(BlockTags.FENCES) || state.is(BlockTags.DOORS) || state.is(BlockTags.WALLS)
                || state.is(BlockTags.BEDS) || state.is(BlockTags.WOOL) || state.is(BlockTags.IMPERMEABLE)
                || (state.getBlock() instanceof LeavesBlock && !isNaturalLeaf(state));
    }

    private static boolean dwelling(BlockState state) {
        return state.is(BlockTags.PLANKS) || state.is(BlockTags.DOORS) || state.is(BlockTags.BEDS) || state.is(BlockTags.WOOL)
                || state.is(BlockTags.IMPERMEABLE) || (state.getBlock() instanceof LeavesBlock && !isNaturalLeaf(state));
    }

    public static List<BlockPos> obstacleTree(ServerLevel level, BlockPos base) {
        if (!WorldUtil.loaded(level, base) || !isLog(level.getBlockState(base))) {
            return List.of();
        }
        List<BlockPos> logs = connectedLogs(level, base);
        int leaves = 0;
        for (BlockPos log : logs) {
            for (Direction direction : Direction.values()) {
                BlockPos side = log.relative(direction);
                if (!WorldUtil.loaded(level, side)) {
                    return List.of();
                }
                BlockState state = level.getBlockState(side);
                if (dwelling(state)) {
                    return List.of();
                }
                if (isNaturalLeaf(state)) {
                    leaves++;
                }
            }
        }
        return leaves >= 3 ? logs : List.of();
    }

    public static BlockPos baseOf(ServerLevel level, BlockPos log) {
        BlockPos pos = log;
        int guard = 0;
        while (guard++ < 40 && WorldUtil.loaded(level, pos.below()) && isLog(level.getBlockState(pos.below()))) {
            pos = pos.below();
        }
        return pos;
    }

    public static List<BlockPos> naturalTree(ServerLevel level, BlockPos base) {
        if (!WorldUtil.loaded(level, base)) {
            return List.of();
        }
        BlockState baseState = level.getBlockState(base);
        if (!isLog(baseState)) {
            return List.of();
        }
        if (baseState.hasProperty(RotatedPillarBlock.AXIS) && baseState.getValue(RotatedPillarBlock.AXIS) != Direction.Axis.Y) {
            return List.of();
        }
        if (!level.getBlockState(base.below()).is(BlockTags.SUBSTRATE_OVERWORLD)) {
            return List.of();
        }
        List<BlockPos> logs = connectedLogs(level, base);
        if (logs.isEmpty()) {
            return List.of();
        }
        int leaves = 0;
        for (BlockPos log : logs) {
            for (Direction direction : Direction.values()) {
                BlockPos side = log.relative(direction);
                if (!WorldUtil.loaded(level, side)) {
                    return List.of();
                }
                BlockState state = level.getBlockState(side);
                if (isArtificial(state)) {
                    return List.of();
                }
                if (isNaturalLeaf(state)) {
                    leaves++;
                }
            }
        }
        return leaves >= 3 ? logs : List.of();
    }

    public static List<BlockPos> connectedLogs(ServerLevel level, BlockPos base) {
        List<BlockPos> result = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(base);
        seen.add(base);
        while (!queue.isEmpty() && result.size() < MAX_LOGS) {
            BlockPos pos = queue.poll();
            result.add(pos);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = 0; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos next = pos.offset(dx, dy, dz);
                        if (Math.abs(next.getX() - base.getX()) > 7 || Math.abs(next.getZ() - base.getZ()) > 7) {
                            continue;
                        }
                        if (seen.add(next) && WorldUtil.loaded(level, next) && isLog(level.getBlockState(next))) {
                            queue.add(next);
                        }
                    }
                }
            }
        }
        result.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingDouble(p -> p.distSqr(base)));
        return result;
    }

    public static List<BlockPos> canopy(ServerLevel level, List<BlockPos> logs) {
        List<BlockPos> result = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>(logs);
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        ArrayDeque<Integer> depth = new ArrayDeque<>();
        for (BlockPos log : logs) {
            queue.add(log);
            depth.add(0);
        }
        while (!queue.isEmpty() && result.size() < MAX_LEAVES) {
            BlockPos pos = queue.poll();
            int d = depth.poll();
            if (d >= 5) {
                continue;
            }
            for (Direction direction : Direction.values()) {
                BlockPos next = pos.relative(direction);
                if (seen.add(next) && WorldUtil.loaded(level, next) && isNaturalLeaf(level.getBlockState(next))) {
                    result.add(next);
                    queue.add(next);
                    depth.add(d + 1);
                }
            }
        }
        result.sort(Comparator.comparingInt(BlockPos::getY));
        return result;
    }
}
