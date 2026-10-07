package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ValuablesTest {
    private static String mc(String path) {
        return Ids.mc(path);
    }

    @Test
    void preciousItemsAreValuables() {
        for (String path : new String[]{"emerald", "emerald_block", "diamond", "gold_ingot", "gold_nugget", "gold_block",
                "raw_gold", "amethyst_shard", "enchanted_book", "golden_apple", "netherite_ingot", "totem_of_undying",
                "music_disc_cat", "wayfinder_armor_trim_smithing_template"}) {
            assertTrue(Valuables.is(mc(path)), path);
        }
    }

    @Test
    void everydayItemsAreNot() {
        for (String path : new String[]{"cobblestone", "iron_ingot", "oak_log", "wheat", "bread", "iron_sword", "book",
                "copper_ingot", "coal", "apple", "diamond_pickaxe"}) {
            assertFalse(Valuables.is(mc(path)), path);
        }
        assertFalse(Valuables.is(null));
    }

    @Test
    void worthCountsOnlyValuables() {
        assertEquals(0, Valuables.worth(mc("cobblestone"), 64));
        assertEquals(0, Valuables.worth(mc("emerald"), 0));
        assertEquals(3L * PriceTable.EMERALD, Valuables.worth(mc("emerald"), 3));
    }

    @Test
    void emeraldEquivalentSumsTheTreasury() {
        assertEquals(0, Valuables.emeralds(Map.of()));
        assertEquals(12, Valuables.emeralds(Map.of(mc("emerald"), 3, mc("emerald_block"), 1, mc("cobblestone"), 500)));
        assertEquals(2, Valuables.emeralds(Map.of(mc("diamond"), 1)));
    }

    @Test
    void valuablesStayClassifiedAsOrdinaryStorageUntilTheTreasuryExists() {
        assertEquals(Category.MINING, ItemClassifier.classify(mc("diamond")));
    }
}
