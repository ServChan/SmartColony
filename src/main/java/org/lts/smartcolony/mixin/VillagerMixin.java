package org.lts.smartcolony.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.npc.villager.Villager;
import org.lts.smartcolony.work.ControlledVillager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Villager.class)
public abstract class VillagerMixin implements ControlledVillager {
    @Unique
    private boolean smartcolony$controlled;

    @Override
    public boolean smartcolony$isControlled() {
        return smartcolony$controlled;
    }

    @Override
    public void smartcolony$setControlled(boolean controlled) {
        smartcolony$controlled = controlled;
    }

    @WrapOperation(method = "customServerAiStep",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/Brain;tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;)V"))
    private void smartcolony$skipBrain(Brain<?> brain, ServerLevel level, LivingEntity body, Operation<Void> original) {
        if (!smartcolony$controlled) {
            original.call(brain, level, body);
        }
    }
}
