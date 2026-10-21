package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void woodItemsAreParsedWithLongestSpeciesFirst() {
        assertEquals("dark_oak", MaterialSwaps.woodItem("minecraft:dark_oak_stairs").wood());
        assertEquals("pale_oak", MaterialSwaps.woodItem("minecraft:pale_oak_planks").wood());
        assertEquals("stripped_", MaterialSwaps.woodItem("minecraft:stripped_oak_log").prefix());
        assertEquals("_fence_gate", MaterialSwaps.woodItem("minecraft:birch_fence_gate").kind());
        assertTrue(MaterialSwaps.woodItem("minecraft:cobblestone") == null);
        assertTrue(MaterialSwaps.keepsShape("minecraft:oak_log", "minecraft:birch_log"));
        assertTrue(!MaterialSwaps.keepsShape("minecraft:oak_log", "minecraft:birch_planks"));
    }

    @Test
    void enoughOfTheChosenWoodNeedsNoMixing() {
        Map<String, Integer> stock = Map.of("minecraft:oak_log", 200);
        assertTrue(MaterialSwaps.mixWood(Map.of("minecraft:oak_planks", 40, "minecraft:oak_stairs", 30), "oak",
                id -> stock.getOrDefault(id, 0)).isEmpty());
    }

    @Test
    void houseSplitsItsWoodBetweenTwoHalfStocks() {
        Map<String, Integer> required = new TreeMap<>(Blueprints.get("house_family").materials("oak"));
        required.keySet().removeIf(id -> MaterialSwaps.woodItem(id) == null);
        double logs = 0;
        for (Map.Entry<String, Integer> entry : required.entrySet()) {
            logs += entry.getValue() * MaterialSwaps.woodItem(entry.getKey()).logs();
        }
        int half = (int) Math.ceil(logs * 0.6);
        Map<String, Integer> stock = Map.of("minecraft:oak_log", half, "minecraft:birch_log", half);
        Map<String, String> swaps = MaterialSwaps.mixWood(required, "oak", id -> stock.getOrDefault(id, 0));
        assertTrue(!swaps.isEmpty(), "needs birch for part of the house");
        Map<String, Double> used = new java.util.HashMap<>();
        for (Map.Entry<String, Integer> entry : required.entrySet()) {
            String id = swaps.getOrDefault(entry.getKey(), entry.getKey());
            MaterialSwaps.WoodItem item = MaterialSwaps.woodItem(id);
            used.merge(item.wood(), entry.getValue() * item.logs(), Double::sum);
        }
        assertTrue(used.getOrDefault("oak", 0.0) <= half, "oak " + used);
        assertTrue(used.getOrDefault("birch", 0.0) <= half, "birch " + used);
        for (Map.Entry<String, String> swap : swaps.entrySet()) {
            assertEquals(MaterialSwaps.woodItem(swap.getKey()).kind(), MaterialSwaps.woodItem(swap.getValue()).kind());
        }
    }

    @Test
    void desertStoneStandsInForCobblestone() {
        Map<String, String> swaps = propose(Map.of("minecraft:cobblestone", 120), Set.of("minecraft:sandstone"), false);
        assertEquals("minecraft:sandstone", swaps.get("minecraft:cobblestone"));
        assertTrue(propose(Map.of("minecraft:cobblestone", 120), Set.of(), false).isEmpty());
    }

    @Test
    void anotherSpeciesOrBedColourCountsAsTheSamePiece() {
        assertTrue(MaterialSwaps.sameKin("minecraft:acacia_stairs", "minecraft:oak_stairs"));
        assertTrue(MaterialSwaps.sameKin("minecraft:red_bed", "minecraft:white_bed"));
        assertFalse(MaterialSwaps.sameKin("minecraft:acacia_slab", "minecraft:oak_stairs"));
        assertFalse(MaterialSwaps.sameKin("minecraft:stripped_oak_log", "minecraft:oak_log"));
        assertFalse(MaterialSwaps.sameKin("minecraft:cobblestone_stairs", "minecraft:oak_stairs"));
    }
}
