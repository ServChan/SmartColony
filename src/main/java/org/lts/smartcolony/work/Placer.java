package org.lts.smartcolony.work;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.lts.smartcolony.util.WorldUtil;

public final class Placer {
    private Placer() {
    }

    public static void place(ServerLevel level, Villager villager, BlockPos pos, BlockState state) {
        BlockState shaped = Block.updateFromNeighbourShapes(state, level, pos);
        level.setBlock(pos, shaped, Block.UPDATE_ALL);
        SoundType sound = shaped.getSoundType();
        level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
        if (villager != null) {
            villager.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            villager.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        }
    }

    public static boolean occupied(ServerLevel level, BlockPos pos) {
        return !level.getEntitiesOfClass(LivingEntity.class, new AABB(pos), LivingEntity::isAlive).isEmpty();
    }

    public static boolean placeTorch(ServerLevel level, Villager villager, BlockPos feet) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos at = feet.above();
            BlockPos wall = at.relative(direction);
            if (WorldUtil.isAirLike(level.getBlockState(at)) && level.getBlockState(wall).isFaceSturdy(level, wall, direction.getOpposite())) {
                BlockState torch = Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, direction.getOpposite());
                if (torch.canSurvive(level, at)) {
                    place(level, villager, at, torch);
                    return true;
                }
            }
        }
        BlockState floor = Blocks.TORCH.defaultBlockState();
        if (WorldUtil.isAirLike(level.getBlockState(feet)) && floor.canSurvive(level, feet)) {
            place(level, villager, feet, floor);
            return true;
        }
        return false;
    }
}
