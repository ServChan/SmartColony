package org.lts.smartcolony.mixin;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(AbstractHorse.class)
public interface AbstractHorseAccessor {
    @Accessor("inventory")
    SimpleContainer smartcolony$inventory();

    @Invoker("createInventory")
    void smartcolony$createInventory();
}
