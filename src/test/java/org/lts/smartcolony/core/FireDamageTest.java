package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FireDamageTest {
    @Test
    void onlyBuildingMaterialIsRebuiltAfterAFire() {
        assertTrue(FireDamage.rebuildable("minecraft:spruce_planks"));
        assertTrue(FireDamage.rebuildable("minecraft:oak_stairs"));
        assertTrue(FireDamage.rebuildable("minecraft:stripped_spruce_log"));
        assertTrue(FireDamage.rebuildable("minecraft:white_wool"));
        assertTrue(FireDamage.rebuildable("minecraft:bookshelf"));
        assertFalse(FireDamage.rebuildable("minecraft:spruce_leaves"));
        assertFalse(FireDamage.rebuildable("minecraft:short_grass"));
        assertFalse(FireDamage.rebuildable("minecraft:tnt"));
        assertFalse(FireDamage.rebuildable("minecraft:vine"));
    }

    @Test
    void repairsWaitUntilTheFireHasDiedDown() {
        assertFalse(FireDamage.settled(1000, 1000 - FireDamage.SETTLE_TICKS + 1));
        assertTrue(FireDamage.settled(1000, 1000 - FireDamage.SETTLE_TICKS));
    }
}
