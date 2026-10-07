package org.lts.smartcolony.mixin;

import java.util.UUID;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ZombieVillager.class)
public interface ZombieVillagerInvoker {
    @Invoker("startConverting")
    void smartcolony$startConverting(UUID conversionStarter, int conversionTime);
}
