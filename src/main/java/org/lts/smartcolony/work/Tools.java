package org.lts.smartcolony.work;

import java.util.List;
import java.util.function.Predicate;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lts.smartcolony.core.Ids;
import org.lts.smartcolony.util.ItemIds;

public final class Tools {
    public static final Predicate<ItemStack> PICKAXE = stack -> stack.is(ItemTags.PICKAXES);
    public static final Predicate<ItemStack> AXE = stack -> stack.is(ItemTags.AXES);
    public static final Predicate<ItemStack> HOE = stack -> stack.is(ItemTags.HOES);
    public static final Predicate<ItemStack> SHOVEL = stack -> stack.is(ItemTags.SHOVELS);
    public static final Predicate<ItemStack> SWORD = stack -> stack.is(ItemTags.SWORDS);
    public static final Predicate<ItemStack> BOW = stack -> stack.is(Items.BOW);
    public static final Predicate<ItemStack> ROD = stack -> stack.is(Items.FISHING_ROD);
    public static final Predicate<ItemStack> SHEARS = stack -> stack.is(Items.SHEARS);
    public static final Predicate<ItemStack> ARROW = stack -> stack.is(ItemTags.ARROWS);
    public static final Predicate<ItemStack> TORCH = stack -> stack.is(Items.TORCH);
    public static final Predicate<ItemStack> ANY_TOOL = PICKAXE.or(AXE).or(HOE).or(SHOVEL).or(SWORD).or(BOW).or(ROD).or(SHEARS);

    public static final List<String> PICKAXES = List.of(Ids.mc("iron_pickaxe"), Ids.mc("stone_pickaxe"), Ids.mc("wooden_pickaxe"));
    public static final List<String> AXES = List.of(Ids.mc("iron_axe"), Ids.mc("stone_axe"), Ids.mc("wooden_axe"));
    public static final List<String> HOES = List.of(Ids.mc("stone_hoe"), Ids.mc("wooden_hoe"), Ids.mc("iron_hoe"));
    public static final List<String> SHOVELS = List.of(Ids.mc("stone_shovel"), Ids.mc("wooden_shovel"), Ids.mc("iron_shovel"));
    public static final List<String> SWORDS = List.of(Ids.mc("iron_sword"), Ids.mc("stone_sword"), Ids.mc("wooden_sword"));

    private Tools() {
    }

    public static int tier(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        String path = Ids.path(ItemIds.id(stack));
        if (path.startsWith("netherite_")) {
            return 6;
        }
        if (path.startsWith("diamond_")) {
            return 5;
        }
        if (path.startsWith("iron_")) {
            return 4;
        }
        if (path.startsWith("copper_")) {
            return 3;
        }
        if (path.startsWith("stone_") || path.startsWith("golden_")) {
            return 2;
        }
        return 1;
    }

    public static double attackDamage(ItemStack stack) {
        String path = stack.isEmpty() ? "" : Ids.path(ItemIds.id(stack));
        if (path.endsWith("_sword")) {
            return switch (tier(stack)) {
                case 6 -> 8;
                case 5 -> 7;
                case 4 -> 6;
                case 3, 2 -> 5;
                default -> 4;
            };
        }
        if (path.endsWith("_axe")) {
            return 4 + tier(stack);
        }
        return 1;
    }
}
