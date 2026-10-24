package org.lts.smartcolony.mixin;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.lts.smartcolony.colony.Burns;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FireBlock.class)
public abstract class FireBlockMixin {
    @Inject(method = "checkBurnOut", at = @At("HEAD"))
    private void smartcolony$beforeBurn(Level level, BlockPos pos, int chance, RandomSource random, int age, CallbackInfo ci,
                                        @Share("burning") LocalRef<BlockState> burning) {
        burning.set(level.getBlockState(pos));
    }

    @Inject(method = "checkBurnOut", at = @At("RETURN"))
    private void smartcolony$afterBurn(Level level, BlockPos pos, int chance, RandomSource random, int age, CallbackInfo ci,
                                       @Share("burning") LocalRef<BlockState> burning) {
        BlockState before = burning.get();
        if (before != null && level instanceof ServerLevel server && level.getBlockState(pos) != before) {
            Burns.record(server, pos, before);
        }
    }
}
