package org.lts.smartcolony.util;

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
        return state.is(BlockTags.DIRT) || state.is(BlockTags.SAND);
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
}
