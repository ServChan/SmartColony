package org.lts.smartcolony.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.item.trading.MerchantOffer;
import org.lts.smartcolony.colony.Colony;
import org.lts.smartcolony.colony.ColonyManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractVillager.class)
public abstract class AbstractVillagerMixin {
    @Inject(method = "notifyTrade", at = @At("TAIL"))
    private void smartcolony$settleTrade(MerchantOffer offer, CallbackInfo ci) {
        if (!((Object) this instanceof WanderingTrader trader) || !(trader.level() instanceof ServerLevel level)) {
            return;
        }
        Colony colony = ColonyManager.colonyOfTrader(trader.getUUID());
        if (colony != null) {
            colony.trader().settle(level, trader, offer);
        }
    }
}
