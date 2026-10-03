package org.lts.smartcolony.util;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class ItemIds {
    private ItemIds() {
    }

    public static String id(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    public static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    public static Item item(String id) {
        Identifier key = Identifier.tryParse(id);
        if (key == null) {
            return Items.AIR;
        }
        return BuiltInRegistries.ITEM.getValue(key);
    }

    public static ItemStack stack(String id, int count) {
        Item item = item(id);
        if (item == Items.AIR || count <= 0) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(item, count);
    }

    public static boolean is(ItemStack stack, String id) {
        return !stack.isEmpty() && id(stack).equals(id);
    }
}
