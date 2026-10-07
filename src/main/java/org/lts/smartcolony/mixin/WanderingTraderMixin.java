package org.lts.smartcolony.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import org.lts.smartcolony.colony.ColonyManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WanderingTrader.class)
public abstract class WanderingTraderMixin {
    @Inject(method = "updateTrades", at = @At("HEAD"), cancellable = true)
    private void smartcolony$noRandomTrades(ServerLevel level, CallbackInfo ci) {
        if (ColonyManager.colonyOfTrader(((WanderingTrader) (Object) this).getUUID()) != null) {
            ci.cancel();
        }
    }
}
