package org.lts.smartcolony.core;

import java.util.HashMap;
import java.util.Map;

public final class PriceTable {
    public static final int EMERALD = 1000;
    private static final Map<String, Integer> BASE = new HashMap<>();

    static {
        put(EMERALD, "emerald");
        put(9000, "emerald_block");
        put(2000, "diamond");
        put(250, "iron_ingot");
        put(200, "raw_iron");
        put(150, "copper_ingot");
        put(100, "raw_copper");
        put(300, "gold_ingot");
        put(250, "raw_gold");
        put(30, "iron_nugget");
        put(2250, "iron_block");
        put(66, "coal", "charcoal");
        put(50, "cobblestone", "cobbled_deepslate", "stone", "deepslate", "blackstone");
        put(40, "redstone");
        put(100, "lapis_lazuli", "flint", "clay_ball", "quartz");
        put(20, "sand", "red_sand", "gravel");
        put(5, "dirt", "coarse_dirt");
        put(250, "glass");
        put(20, "glass_pane");
        put(30, "torch");
        put(60, "stick");
        put(15, "brick");
        put(50, "wheat");
        put(10, "wheat_seeds", "beetroot_seeds");
        put(45, "carrot");
        put(38, "potato");
        put(66, "beetroot");
        put(160, "bread");
        put(60, "baked_potato");
        put(100, "apple");
        put(70, "cod", "salmon", "chicken", "rabbit");
        put(100, "beef", "porkchop", "mutton", "cooked_cod", "cooked_salmon", "cooked_chicken", "cooked_rabbit");
        put(150, "cooked_beef", "cooked_porkchop", "cooked_mutton");
        put(160, "leather");
        put(42, "feather");
        put(50, "egg", "bone", "bone_meal");
        put(70, "string");
        put(55, "white_wool");
        put(20, "oak_sapling", "spruce_sapling", "birch_sapling", "jungle_sapling", "acacia_sapling", "dark_oak_sapling",
                "cherry_sapling", "pale_oak_sapling", "poplar_sapling", "mangrove_propagule");
        put(62, "arrow");
        put(500, "bow", "shears");
        put(600, "shield");
        put(300, "fishing_rod");
        put(750, "bucket");
        put(150, "wooden_pickaxe", "wooden_axe", "wooden_shovel", "wooden_hoe", "wooden_sword");
        put(250, "stone_pickaxe", "stone_axe", "stone_shovel", "stone_hoe", "stone_sword");
        put(1000, "iron_pickaxe", "iron_axe", "iron_sword");
        put(500, "iron_shovel", "iron_hoe");
        put(1500, "iron_helmet", "iron_boots");
        put(2500, "iron_chestplate", "iron_leggings");
        put(800, "leather_helmet", "leather_boots");
        put(1300, "leather_chestplate", "leather_leggings");
        put(400, "chest", "crafting_table");
        put(450, "furnace");
    }

    private PriceTable() {
    }

    private static void put(int value, String... paths) {
        for (String path : paths) {
            BASE.put(Ids.mc(path), value);
        }
    }

    public static int base(String id) {
        Integer fixed = BASE.get(id);
        if (fixed != null) {
            return fixed;
        }
        String path = Ids.path(id);
        if (Ids.isLog(id)) {
            return 240;
        }
        if (Ids.isPlanks(id)) {
            return 60;
        }
        if (Ids.isWool(id)) {
            return 55;
        }
        if (Ids.isSapling(id)) {
            return 20;
        }
        if (path.endsWith("_bed")) {
            return 400;
        }
        if (path.endsWith("_door") || path.endsWith("_fence") || path.endsWith("_fence_gate") || path.endsWith("_trapdoor")) {
            return 120;
        }
        if (path.endsWith("_stairs") || path.endsWith("_slab")) {
            return 40;
        }
        if (path.endsWith("_ore")) {
            return 150;
        }
        return 100;
    }

    public static int value(String id, int stockLevel, int target) {
        int base = base(id);
        if (target <= 0) {
            return base;
        }
        double ratio = (double) target / Math.max(1, stockLevel);
        double factor = Math.max(0.5, Math.min(3.0, ratio));
        return (int) Math.round(base * factor);
    }

    public static long total(Map<String, Integer> items) {
        long sum = 0;
        for (Map.Entry<String, Integer> entry : items.entrySet()) {
            sum += (long) base(entry.getKey()) * entry.getValue();
        }
        return sum;
    }
}
