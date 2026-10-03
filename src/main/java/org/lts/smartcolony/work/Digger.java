package org.lts.smartcolony.work;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class Digger {
    public enum Result {
        DIGGING,
        DONE,
        FAILED
    }

    private BlockPos target;
    private float progress;
    private int ticks;
    private int lastStage = -1;
    private final List<ItemStack> drops = new ArrayList<>();

    public List<ItemStack> takeDrops() {
        List<ItemStack> copy = new ArrayList<>(drops);
        drops.clear();
        return copy;
    }

    public void abort(ServerLevel level, Villager villager) {
        if (target != null) {
            level.destroyBlockProgress(villager.getId(), target, -1);
        }
        target = null;
        progress = 0;
        ticks = 0;
        lastStage = -1;
    }

    public static boolean canHarvest(ItemStack tool, BlockState state) {
        return !state.requiresCorrectToolForDrops() || (!tool.isEmpty() && tool.isCorrectToolForDrops(state));
    }

    public static float speed(ItemStack tool, BlockState state, ServerLevel level, BlockPos pos) {
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0) {
            return 0;
        }
        if (hardness == 0) {
            return 1;
        }
        float toolSpeed = tool.isEmpty() ? 1.0F : Math.max(1.0F, tool.getDestroySpeed(state));
        float divisor = canHarvest(tool, state) ? 30.0F : 100.0F;
        return toolSpeed / hardness / divisor;
    }

    public Result tick(ServerLevel level, Villager villager, BlockPos pos, boolean collectDrops) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || !state.getFluidState().isEmpty() && state.getCollisionShape(level, pos).isEmpty() && state.canBeReplaced()) {
            if (target != null && target.equals(pos)) {
                abort(level, villager);
            }
            return Result.DONE;
        }
        if (target == null || !target.equals(pos)) {
            abort(level, villager);
            target = pos.immutable();
        }
        ItemStack tool = villager.getMainHandItem();
        float perTick = speed(tool, state, level, pos);
        if (perTick <= 0) {
            abort(level, villager);
            return Result.FAILED;
        }
        villager.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        ticks++;
        progress += perTick;
        if (ticks % 5 == 1) {
            villager.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            SoundType sound = state.getSoundType();
            level.playSound(null, pos, sound.getHitSound(), SoundSource.NEUTRAL, (sound.getVolume() + 1.0F) / 8.0F, sound.getPitch() * 0.5F);
        }
        if (progress < 1.0F) {
            int stage = (int) (progress * 10.0F);
            if (stage != lastStage) {
                level.destroyBlockProgress(villager.getId(), pos, stage);
                lastStage = stage;
            }
            return Result.DIGGING;
        }
        breakNow(level, villager, pos, collectDrops);
        abort(level, villager);
        return Result.DONE;
    }

    public void breakNow(ServerLevel level, Villager villager, BlockPos pos, boolean collectDrops) {
        BlockState state = level.getBlockState(pos);
        ItemStack tool = villager.getMainHandItem();
        float hardness = state.getDestroySpeed(level, pos);
        if (collectDrops && canHarvest(tool, state)) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            drops.addAll(Block.getDrops(state, level, pos, blockEntity, villager, tool));
        }
        level.destroyBlock(pos, false, villager);
        if (!tool.isEmpty() && tool.isDamageableItem() && hardness != 0.0F) {
            tool.hurtAndBreak(1, villager, EquipmentSlot.MAINHAND);
        }
    }
}
