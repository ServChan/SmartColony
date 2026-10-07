package org.lts.smartcolony.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RecipeBook {
    private static final Map<String, List<Recipe>> BY_OUTPUT = new HashMap<>();
    private static final Map<String, Integer> FUEL = new LinkedHashMap<>();
    public static final int SMELT_COST = 10;

    static {
        for (String wood : Ids.WOOD_TYPES) {
            String planks = Ids.mc(wood + "_planks");
            boolean nether = wood.equals("crimson") || wood.equals("warped");
            String log = nether ? wood + "_stem" : wood + "_log";
            String bark = nether ? wood + "_hyphae" : wood + "_wood";
            for (String source : List.of(log, bark, "stripped_" + log, "stripped_" + bark)) {
                craft(planks, 4, Ids.mc(source), 1);
            }
            craft(Ids.mc(wood + "_door"), 3, planks, 6);
            craft(Ids.mc(wood + "_trapdoor"), 2, planks, 6);
            craft(Ids.mc(wood + "_fence"), 3, planks, 4, Ids.mc("stick"), 2);
            craft(Ids.mc(wood + "_fence_gate"), 1, Ids.mc("stick"), 4, planks, 2);
            craft(Ids.mc(wood + "_stairs"), 4, planks, 6);
            craft(Ids.mc(wood + "_slab"), 6, planks, 3);
            craft(Ids.mc(wood + "_sign"), 3, planks, 6, Ids.mc("stick"), 1);
        }
        craft(Ids.mc("bamboo_planks"), 2, Ids.mc("bamboo_block"), 1);
        for (String color : Ids.COLORS) {
            craft(Ids.mc(color + "_bed"), 1, Ids.mc(color + "_wool"), 3, Recipe.GROUP_PLANKS, 3);
        }
        craft(Ids.mc("white_wool"), 1, Ids.mc("string"), 4);
        craft(Ids.mc("stick"), 4, Recipe.GROUP_PLANKS, 2);
        craft(Ids.mc("crafting_table"), 1, Recipe.GROUP_PLANKS, 4);
        craft(Ids.mc("chest"), 1, Recipe.GROUP_PLANKS, 8);
        craft(Ids.mc("barrel"), 1, Recipe.GROUP_PLANKS, 6, Recipe.GROUP_WOODEN_SLAB, 2);
        craft(Ids.mc("torch"), 4, Recipe.GROUP_COAL, 1, Ids.mc("stick"), 1);
        craft(Ids.mc("ladder"), 3, Ids.mc("stick"), 7);
        craft(Ids.mc("furnace"), 1, Recipe.GROUP_STONE, 8);
        craft(Ids.mc("cobblestone_stairs"), 4, Ids.mc("cobblestone"), 6);
        craft(Ids.mc("cobblestone_slab"), 6, Ids.mc("cobblestone"), 3);
        craft(Ids.mc("cobblestone_wall"), 6, Ids.mc("cobblestone"), 6);
        craft(Ids.mc("stone_bricks"), 4, Ids.mc("stone"), 4);
        craft(Ids.mc("stone_brick_stairs"), 4, Ids.mc("stone_bricks"), 6);
        craft(Ids.mc("stone_brick_slab"), 6, Ids.mc("stone_bricks"), 3);
        craft(Ids.mc("glass_pane"), 16, Ids.mc("glass"), 6);
        craft(Ids.mc("lantern"), 1, Ids.mc("torch"), 1, Ids.mc("iron_nugget"), 8);
        craft(Ids.mc("iron_nugget"), 9, Ids.mc("iron_ingot"), 1);
        craft(Ids.mc("hay_block"), 1, Ids.mc("wheat"), 9);
        craft(Ids.mc("iron_block"), 1, Ids.mc("iron_ingot"), 9);
        craft(Ids.mc("anvil"), 1, Ids.mc("iron_block"), 3, Ids.mc("iron_ingot"), 4);
        craft(Ids.mc("smithing_table"), 1, Ids.mc("iron_ingot"), 2, Recipe.GROUP_PLANKS, 4);
        craft(Ids.mc("fletching_table"), 1, Ids.mc("flint"), 2, Recipe.GROUP_PLANKS, 4);
        craft(Ids.mc("smoker"), 1, Ids.mc("furnace"), 1, Recipe.GROUP_LOGS, 4);
        craft(Ids.mc("composter"), 1, Recipe.GROUP_WOODEN_SLAB, 7);
        craft(Ids.mc("iron_ingot"), 9, Ids.mc("iron_block"), 1);
        craft(Ids.mc("bread"), 1, Ids.mc("wheat"), 3);
        craft(Ids.mc("paper"), 3, Ids.mc("sugar_cane"), 3);
        craft(Ids.mc("bowl"), 4, Recipe.GROUP_PLANKS, 3);
        craft(Ids.mc("suspicious_stew"), 1, Ids.mc("bowl"), 1, Recipe.GROUP_SMALL_FLOWER, 1);
        craft(Ids.mc("white_carpet"), 3, Ids.mc("white_wool"), 2);
        craft(Ids.mc("cauldron"), 1, Ids.mc("iron_ingot"), 7);
        craft(Ids.mc("brewing_stand"), 1, Ids.mc("iron_ingot"), 1, Ids.mc("cobblestone"), 3, Ids.mc("stick"), 1);
        craft(Ids.mc("golden_apple"), 1, Ids.mc("apple"), 1, Ids.mc("gold_ingot"), 8);
        craft(Ids.mc("bow"), 1, Ids.mc("stick"), 3, Ids.mc("string"), 3);
        craft(Ids.mc("arrow"), 4, Ids.mc("flint"), 1, Ids.mc("stick"), 1, Ids.mc("feather"), 1);
        craft(Ids.mc("shield"), 1, Recipe.GROUP_PLANKS, 6, Ids.mc("iron_ingot"), 1);
        craft(Ids.mc("fishing_rod"), 1, Ids.mc("stick"), 3, Ids.mc("string"), 2);
        craft(Ids.mc("shears"), 1, Ids.mc("iron_ingot"), 2);
        craft(Ids.mc("bucket"), 1, Ids.mc("iron_ingot"), 3);
        craft(Ids.mc("lead"), 2, Ids.mc("string"), 4, Ids.mc("slime_ball"), 1);
        tools("wooden", Recipe.GROUP_PLANKS);
        tools("stone", Recipe.GROUP_STONE);
        tools("iron", Ids.mc("iron_ingot"));
        armor("iron", Ids.mc("iron_ingot"));
        armor("leather", Ids.mc("leather"));
        smeltAt(Tech.FORGE, Ids.mc("iron_ingot"), Ids.mc("raw_iron"));
        smeltAt(Tech.FORGE, Ids.mc("copper_ingot"), Ids.mc("raw_copper"));
        smeltAt(Tech.FORGE, Ids.mc("gold_ingot"), Ids.mc("raw_gold"));
        smelt(Ids.mc("glass"), Ids.mc("sand"));
        smelt(Ids.mc("glass"), Ids.mc("red_sand"));
        smelt(Ids.mc("stone"), Ids.mc("cobblestone"));
        smelt(Ids.mc("deepslate"), Ids.mc("cobbled_deepslate"));
        smelt(Ids.mc("smooth_stone"), Ids.mc("stone"));
        smelt(Ids.mc("charcoal"), Recipe.GROUP_LOGS);
        smelt(Ids.mc("brick"), Ids.mc("clay_ball"));
        smelt(Ids.mc("dried_kelp"), Ids.mc("kelp"));
        smelt(Ids.mc("baked_potato"), Ids.mc("potato"));
        for (String meat : List.of("cod", "salmon", "beef", "porkchop", "mutton", "chicken", "rabbit")) {
            smelt(Ids.mc("cooked_" + meat), Ids.mc(meat));
        }
        FUEL.put(Ids.mc("coal_block"), 800);
        FUEL.put(Ids.mc("coal"), 80);
        FUEL.put(Ids.mc("charcoal"), 80);
    }

    private RecipeBook() {
    }

    private static void tools(String tier, String material) {
        String stick = Ids.mc("stick");
        craft(Ids.mc(tier + "_pickaxe"), 1, material, 3, stick, 2);
        craft(Ids.mc(tier + "_axe"), 1, material, 3, stick, 2);
        craft(Ids.mc(tier + "_shovel"), 1, material, 1, stick, 2);
        craft(Ids.mc(tier + "_hoe"), 1, material, 2, stick, 2);
        craft(Ids.mc(tier + "_sword"), 1, material, 2, stick, 1);
    }

    private static void armor(String tier, String material) {
        craft(Ids.mc(tier + "_helmet"), 1, material, 5);
        craft(Ids.mc(tier + "_chestplate"), 1, material, 8);
        craft(Ids.mc(tier + "_leggings"), 1, material, 7);
        craft(Ids.mc(tier + "_boots"), 1, material, 4);
    }

    private static void craft(String output, int count, Object... pairs) {
        Map<String, Integer> inputs = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            inputs.merge((String) pairs[i], (Integer) pairs[i + 1], Integer::sum);
        }
        BY_OUTPUT.computeIfAbsent(output, key -> new ArrayList<>()).add(new Recipe(output, count, inputs, false));
    }

    private static void smeltAt(String requires, String output, String input) {
        BY_OUTPUT.computeIfAbsent(output, key -> new ArrayList<>()).add(new Recipe(output, 1, Map.of(input, 1), true, requires));
    }

    private static void smelt(String output, String input) {
        BY_OUTPUT.computeIfAbsent(output, key -> new ArrayList<>()).add(new Recipe(output, 1, Map.of(input, 1), true));
    }

    public static List<Recipe> recipesFor(String output) {
        return BY_OUTPUT.getOrDefault(output, List.of());
    }

    public static boolean craftable(String output) {
        return BY_OUTPUT.containsKey(output);
    }

    public static int fuelValue(String id) {
        Integer fixed = FUEL.get(id);
        if (fixed != null) {
            return fixed;
        }
        if (Ids.isPlanks(id) || Ids.isLog(id)) {
            return 15;
        }
        if (id.equals(Ids.mc("stick"))) {
            return 5;
        }
        return 0;
    }

    public static List<String> groupCraftCandidates(String group) {
        List<String> result = new ArrayList<>();
        if (group.equals(Recipe.GROUP_PLANKS)) {
            for (String wood : Ids.WOOD_TYPES) {
                result.add(Ids.mc(wood + "_planks"));
            }
            result.add(Ids.mc("bamboo_planks"));
        } else if (group.equals(Recipe.GROUP_WOODEN_SLAB)) {
            for (String wood : Ids.WOOD_TYPES) {
                result.add(Ids.mc(wood + "_slab"));
            }
        } else if (group.equals(Recipe.GROUP_COAL)) {
            result.add(Ids.mc("charcoal"));
        }
        return result;
    }
}
