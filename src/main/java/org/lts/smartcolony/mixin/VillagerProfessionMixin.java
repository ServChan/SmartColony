package org.lts.smartcolony.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import org.lts.smartcolony.colony.ColonyManager;
import org.lts.smartcolony.work.ProfessionAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Villager.class)
public abstract class VillagerProfessionMixin implements ProfessionAccess {
    @Unique
    private boolean smartcolony$professionOpen;

    @Override
    public void smartcolony$setProfessionOpen(boolean open) {
        smartcolony$professionOpen = open;
    }

    @Override
    @Invoker("updateTrades")
    public abstract void smartcolony$updateTrades(ServerLevel level);

    @Inject(method = "setVillagerData", at = @At("HEAD"), cancellable = true)
    private void smartcolony$keepColonyProfession(VillagerData data, CallbackInfo ci) {
        Villager self = (Villager) (Object) this;
        if (smartcolony$professionOpen || self.getVillagerData().profession().equals(data.profession())) {
            return;
        }
        if (ColonyManager.colonyOf(self) != null) {
            ci.cancel();
        }
    }

    @Inject(method = "shouldIncreaseLevel", at = @At("HEAD"), cancellable = true)
    private void smartcolony$levelFromSkill(CallbackInfoReturnable<Boolean> cir) {
        if (ColonyManager.colonyOf((Villager) (Object) this) != null) {
            cir.setReturnValue(false);
        }
    }
}
