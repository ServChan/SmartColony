package org.lts.smartcolony.core;

import java.util.List;
import java.util.Set;

public final class ItemClassifier {
    private static final List<String> ARSENAL_SUFFIXES = List.of(
            "_sword", "_pickaxe", "_axe", "_shovel", "_hoe", "_spear", "_helmet", "_chestplate", "_leggings", "_boots",
            "_horse_armor");
    private static final Set<String> ARSENAL = Set.of(
            "bow", "crossbow", "arrow", "spectral_arrow", "tipped_arrow", "shield", "fishing_rod", "shears", "bucket",
            "trident", "mace", "flint_and_steel", "lead", "turtle_helmet");
    private static final Set<String> FOOD = Set.of(
            "wheat", "wheat_seeds", "beetroot_seeds", "melon_seeds", "pumpkin_seeds", "carrot", "potato",
            "baked_potato", "poisonous_potato", "beetroot", "bread", "melon_slice", "melon", "pumpkin", "apple",
            "golden_apple", "cod", "salmon", "tropical_fish", "pufferfish", "beef", "porkchop", "mutton", "chicken",
            "rabbit", "sugar_cane", "sugar", "sweet_berries", "glow_berries", "cookie", "cake", "pumpkin_pie", "kelp",
            "dried_kelp", "mushroom_stew", "beetroot_soup", "rabbit_stew", "honey_bottle", "cocoa_beans",
            "brown_mushroom", "red_mushroom", "milk_bucket");
    private static final Set<String> ANIMAL = Set.of(
            "leather", "feather", "egg", "brown_egg", "blue_egg", "bone", "bone_meal", "string", "rabbit_hide",
            "rabbit_foot", "ink_sac", "glow_ink_sac", "slime_ball", "honeycomb", "turtle_scute", "armadillo_scute",
            "saddle");
    private static final Set<String> WOOD = Set.of(
            "stick", "chest", "barrel", "crafting_table", "ladder", "bowl", "bamboo", "bamboo_block", "stripped_bamboo_block");
    private static final List<String> WOOD_FAMILY_SUFFIXES = List.of(
            "_planks", "_stairs", "_slab", "_fence", "_fence_gate", "_door", "_trapdoor", "_pressure_plate", "_button",
            "_sign", "_hanging_sign", "_boat", "_chest_boat", "_leaves");

    private ItemClassifier() {
    }

    public static Category classify(String id) {
        String path = Ids.path(id);
        if (ARSENAL.contains(path)) {
            return Category.ARSENAL;
        }
        for (String suffix : ARSENAL_SUFFIXES) {
            if (path.endsWith(suffix)) {
                return Category.ARSENAL;
            }
        }
        if (FOOD.contains(path) || path.startsWith("cooked_")) {
            return Category.FOOD;
        }
        if (ANIMAL.contains(path) || Ids.isWool(id) || path.endsWith("_carpet") || path.endsWith("_bed")) {
            return Category.ANIMAL;
        }
        if (WOOD.contains(path) || Ids.isLog(id) || Ids.isSapling(id)) {
            return Category.WOOD;
        }
        if (Ids.woodOf(id) != null) {
            for (String suffix : WOOD_FAMILY_SUFFIXES) {
                if (path.endsWith(suffix)) {
                    return Category.WOOD;
                }
            }
        }
        return Category.MINING;
    }
}
