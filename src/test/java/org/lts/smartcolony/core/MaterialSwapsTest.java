package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class MaterialSwapsTest {
    private static Map<String, String> propose(Map<String, Integer> required, Set<String> have, boolean ironAge) {
        return MaterialSwaps.propose(required, Map.of(), ironAge, (item, count) -> have.contains(item));
    }

    @Test
    void missingCarpetBecomesPressurePlate() {
        Map<String, String> swaps = propose(Map.of("minecraft:white_carpet", 6), Set.of(), false);
        assertEquals(MaterialSwaps.WOOD_PLATE, swaps.get("minecraft:white_carpet"));
        assertEquals("minecraft:birch_pressure_plate", MaterialSwaps.resolve(swaps.get("minecraft:white_carpet"), "birch"));
    }

    @Test
    void availableCarpetIsKept() {
        assertTrue(propose(Map.of("minecraft:white_carpet", 6), Set.of("minecraft:white_carpet"), false).isEmpty());
    }

    @Test
    void missingWoolBecomesPlanks() {
        assertEquals(MaterialSwaps.WOOD_PLANKS, propose(Map.of("minecraft:white_wool", 4), Set.of(), false).get("minecraft:white_wool"));
    }

    @Test
    void lanternFallsBackToTorchOnlyBeforeIronAge() {
        assertEquals(MaterialSwaps.TORCH, propose(Map.of("minecraft:lantern", 2), Set.of(), false).get("minecraft:lantern"));
        assertTrue(propose(Map.of("minecraft:lantern", 2), Set.of(), true).isEmpty());
    }

    @Test
    void bedSwapsToAnotherCraftableColour() {
        Map<String, String> swaps = propose(Map.of("minecraft:red_bed", 1), Set.of("minecraft:blue_bed"), false);
        assertEquals("minecraft:blue_bed", swaps.get("minecraft:red_bed"));
    }

    @Test
    void existingSwapsAreNotProposedAgain() {
        Map<String, Integer> required = new TreeMap<>(Map.of("minecraft:white_carpet", 6, "minecraft:glass_pane", 4));
        Map<String, String> swaps = MaterialSwaps.propose(required, Map.of("minecraft:white_carpet", MaterialSwaps.WOOD_PLATE), false,
                (item, count) -> false);
        assertEquals(1, swaps.size());
        assertEquals(MaterialSwaps.WOOD_FENCE, swaps.get("minecraft:glass_pane"));
    }

    @Test
    void infirmaryBlueprintBecomesBuildableWithoutWool() {
        Map<String, Integer> materials = new TreeMap<>(Blueprints.get("infirmary_1").materials("oak"));
        Map<String, String> swaps = propose(materials, Set.of(), false);
        for (String key : materials.keySet()) {
            if (Ids.path(key).endsWith("_carpet") || Ids.path(key).endsWith("_wool")) {
                assertTrue(swaps.containsKey(key), key);
            }
        }
    }
}
