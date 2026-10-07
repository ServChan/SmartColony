package org.lts.smartcolony.core;

import java.util.Map;
import java.util.Set;

public final class Valuables {
    private static final Set<String> EXACT = Set.of(
            "emerald", "emerald_block", "diamond", "diamond_block", "gold_ingot", "gold_nugget", "gold_block", "raw_gold",
            "raw_gold_block", "amethyst_shard", "amethyst_block", "enchanted_book", "golden_apple", "enchanted_golden_apple",
            "netherite_ingot", "netherite_scrap", "netherite_block", "ancient_debris", "nether_star", "totem_of_undying",
            "heart_of_the_sea", "echo_shard", "recovery_compass", "elytra", "beacon", "conduit", "dragon_egg", "dragon_head",
            "wither_skeleton_skull", "trial_key", "ominous_trial_key", "heavy_core", "nautilus_shell", "enchanted_golden_carrot");
    private static final long EMERALD_UNIT = PriceTable.EMERALD;

    private Valuables() {
    }

    public static boolean is(String id) {
        if (id == null) {
            return false;
        }
        String path = Ids.path(id);
        return EXACT.contains(path) || path.startsWith("music_disc_") || path.endsWith("_smithing_template")
                || path.endsWith("_pottery_sherd") || path.endsWith("_banner_pattern");
    }

    public static long worth(String id, int count) {
        if (!is(id) || count <= 0) {
            return 0;
        }
        return (long) PriceTable.base(id) * count;
    }

    public static int emeralds(Map<String, Integer> items) {
        long total = 0;
        for (Map.Entry<String, Integer> entry : items.entrySet()) {
            total += worth(entry.getKey(), entry.getValue());
        }
        return (int) Math.min(Integer.MAX_VALUE, total / EMERALD_UNIT);
    }
}
