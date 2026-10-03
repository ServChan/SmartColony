package org.lts.smartcolony.work;

import java.util.function.Predicate;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import org.lts.smartcolony.util.ItemIds;

public final class Inv {
    private Inv() {
    }

    public static int count(Villager villager, Predicate<ItemStack> filter) {
        SimpleContainer inventory = villager.getInventory();
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && filter.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    public static int count(Villager villager, String id) {
        return count(villager, stack -> ItemIds.id(stack).equals(id));
    }

    public static ItemStack add(Villager villager, ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        return villager.getInventory().addItem(stack);
    }

    public static int remove(Villager villager, Predicate<ItemStack> filter, int amount) {
        SimpleContainer inventory = villager.getInventory();
        int removed = 0;
        for (int slot = 0; slot < inventory.getContainerSize() && removed < amount; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && filter.test(stack)) {
                int take = Math.min(amount - removed, stack.getCount());
                stack.shrink(take);
                removed += take;
            }
        }
        if (removed > 0) {
            inventory.setChanged();
        }
        return removed;
    }

    public static ItemStack takeFirst(Villager villager, Predicate<ItemStack> filter) {
        SimpleContainer inventory = villager.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && filter.test(stack)) {
                inventory.setItem(slot, ItemStack.EMPTY);
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    public static ItemStack find(Villager villager, Predicate<ItemStack> filter) {
        SimpleContainer inventory = villager.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && filter.test(stack)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    public static int used(Villager villager) {
        SimpleContainer inventory = villager.getInventory();
        int used = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (!inventory.getItem(slot).isEmpty()) {
                used++;
            }
        }
        return used;
    }

    public static int free(Villager villager) {
        return villager.getInventory().getContainerSize() - used(villager);
    }
}
