package org.lts.smartcolony.core;

import java.util.List;

public final class FireDamage {
    public static final long SETTLE_TICKS = 600;
    public static final int MAX_PENDING = 512;

    private static final List<String> BUILT_SUFFIXES = List.of("_planks", "_log", "_wood", "_stem", "_hyphae", "_stairs", "_slab",
            "_fence", "_fence_gate", "_wool", "_carpet", "_trapdoor", "_door", "_sign", "_hanging_sign");
    private static final List<String> BUILT_BLOCKS = List.of("bookshelf", "chiseled_bookshelf", "lectern", "hay_block", "composter",
            "beehive", "bee_nest", "target", "dried_kelp_block", "bamboo_block", "bamboo_mosaic", "loom", "cartography_table",
            "fletching_table", "smithing_table", "crafting_table", "barrel", "chest", "trapped_chest", "note_block", "jukebox");

    private FireDamage() {
    }

    public static boolean rebuildable(String id) {
        String path = Ids.path(id);
        if (path.contains("leaves") || path.contains("sapling")) {
            return false;
        }
        if (BUILT_BLOCKS.contains(path)) {
            return true;
        }
        for (String suffix : BUILT_SUFFIXES) {
            if (path.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    public static boolean settled(long now, long lastBurn) {
        return now - lastBurn >= SETTLE_TICKS;
    }
}
