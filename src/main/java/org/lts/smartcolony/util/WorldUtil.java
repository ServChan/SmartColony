package org.lts.smartcolony.util;

import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.lts.smartcolony.core.Pos;

public final class WorldUtil {
    private WorldUtil() {
    }

    public static BlockPos bp(Pos pos) {
        return new BlockPos(pos.x(), pos.y(), pos.z());
    }

    public static Pos pos(BlockPos pos) {
        return new Pos(pos.getX(), pos.getY(), pos.getZ());
    }

    public static boolean loaded(ServerLevel level, BlockPos pos) {
        return level.isLoaded(pos);
    }

    public static int surfaceY(ServerLevel level, int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    public static BlockPos surface(ServerLevel level, int x, int z) {
        return new BlockPos(x, surfaceY(level, x, z), z);
    }

    public static boolean passable(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty();
    }

    public static boolean solidFloor(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.isFaceSturdy(level, pos, Direction.UP);
    }

    public static boolean standable(ServerLevel level, BlockPos feet) {
        return loaded(level, feet) && passable(level, feet) && passable(level, feet.above()) && solidFloor(level, feet.below());
    }

    public static boolean isSoil(BlockState state) {
        return state.is(BlockTags.SUBSTRATE_OVERWORLD) || state.is(BlockTags.SAND);
    }

    public static boolean isReplaceablePlant(BlockState state) {
        return state.canBeReplaced() && state.getFluidState().isEmpty();
    }

    public static boolean isAirLike(BlockState state) {
        return state.isAir() || (state.canBeReplaced() && state.getFluidState().isEmpty());
    }

    public static void setBlock(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_ALL);
    }

    public static boolean naturalGround(BlockState state) {
        return state.is(BlockTags.SUBSTRATE_OVERWORLD) || state.is(BlockTags.SAND) || state.is(Blocks.GRAVEL)
                || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(Blocks.DIRT_PATH) || state.is(Blocks.SNOW_BLOCK)
                || state.is(Blocks.CLAY) || state.is(Blocks.POWDER_SNOW);
    }

    public static boolean clearable(BlockState state) {
        if (state.isAir()) {
            return true;
        }
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        if (state.hasProperty(LeavesBlock.PERSISTENT)) {
            return !state.getValue(LeavesBlock.PERSISTENT);
        }
        return state.canBeReplaced() || state.is(BlockTags.REPLACEABLE_BY_TREES) || state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.SAPLINGS) || state.is(Blocks.SNOW) || state.is(Blocks.BUSH) || state.is(Blocks.FIREFLY_BUSH)
                || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.BROWN_MUSHROOM) || state.is(Blocks.RED_MUSHROOM);
    }

    public static boolean fallenLog(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.is(BlockTags.LOGS) || !state.hasProperty(net.minecraft.world.level.block.RotatedPillarBlock.AXIS)
                || state.getValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS) == Direction.Axis.Y) {
            return false;
        }
        BlockState below = level.getBlockState(pos.below());
        return naturalGround(below) || below.is(BlockTags.LOGS);
    }

    public static boolean hugeMushroom(BlockState state) {
        return state.is(Blocks.RED_MUSHROOM_BLOCK) || state.is(Blocks.BROWN_MUSHROOM_BLOCK) || state.is(Blocks.MUSHROOM_STEM);
    }

    public static boolean overgrowth(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES) || hugeMushroom(state);
    }

    public static int soilY(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
        int floor = y - 48;
        while (y > floor) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (!overgrowth(state) && !clearable(state)) {
                break;
            }
            y--;
        }
        return y;
    }

    public static boolean removable(ServerLevel level, BlockPos pos) {
        return clearable(level.getBlockState(pos)) || fallenLog(level, pos);
    }
}
