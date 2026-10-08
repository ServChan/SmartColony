package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExpansionLogicTest {
    @Test
    void houseTiersPairUpWithTheirBase() {
        assertEquals("house_small_2", HouseTier.upgradeOf("house_small"));
        assertEquals("house_family", HouseTier.baseOf("house_family_2"));
        assertTrue(HouseTier.isUpgrade("house_garden_2"));
        assertNull(HouseTier.upgradeOf("house_small_2"));
        for (String id : List.of("house_small_2", "house_family_2", "house_garden_2")) {
            Blueprint upgrade = Blueprints.get(id);
            Blueprint base = Blueprints.get(HouseTier.baseOf(id));
            assertEquals(base.sizeX(), upgrade.sizeX(), id);
            assertEquals(base.sizeZ(), upgrade.sizeZ(), id);
            assertEquals(base.doorX(), upgrade.doorX(), id);
            assertTrue(upgrade.beds() > base.beds(), id);
        }
    }

    @Test
    void housesUpgradeWhenLandOrStoneAllows() {
        assertFalse(HouseTier.wanted(6, 3, false, true, 0, true));
        assertTrue(HouseTier.wanted(12, 1, false, true, 0, true));
        assertTrue(HouseTier.wanted(12, 3, false, true, 0, false));
        assertFalse(HouseTier.wanted(12, 3, true, true, 999, true));
        assertTrue(HouseTier.wanted(12, 1, false, false, 999, false));
    }

    @Test
    void caravansCarrySurplusToShortages() {
        Stock rich = new Stock(Map.of(Ids.mc("wheat"), 400, Ids.mc("cobblestone"), 50));
        Stock poor = new Stock(Map.of(Ids.mc("wheat"), 2, Ids.mc("cobblestone"), 900));
        List<CaravanPlan.Load> loads = CaravanPlan.loads(rich, poor);
        assertEquals(1, loads.size());
        assertEquals(Ids.mc("wheat"), loads.getFirst().item());
        assertEquals(62, loads.getFirst().amount());
        assertTrue(CaravanPlan.loads(poor, rich).stream().anyMatch(load -> load.item().equals(Ids.mc("cobblestone"))));
        assertEquals(CaravanPlan.MIN_TRIP, CaravanPlan.travelTicks(10));
    }

    @Test
    void expeditionsFanOutAndShrinkInward() {
        Expedition.Target first = Expedition.target(0, 32);
        Expedition.Target second = Expedition.target(1, 32);
        assertTrue(first.direction() != second.direction());
        Expedition.Target shorter = Expedition.shorter(first, 32);
        assertNotNull(shorter);
        assertTrue(Math.hypot(shorter.dx(), shorter.dz()) < Math.hypot(first.dx(), first.dz()));
        assertTrue(Expedition.isOre("deepslate_iron_ore"));
        assertFalse(Expedition.isOre("stone"));
        assertTrue(Expedition.worthVein(3, 2));
        assertFalse(Expedition.worthVein(1, 3));
    }

    @Test
    void brewingTargetsHealingFirst() {
        Stock stock = new Stock(Map.of(Brewing.WART, 3, Ids.mc("glistering_melon_slice"), 2));
        assertEquals("healing", Brewing.wanted(stock, 0, 4, true));
        assertNull(Brewing.wanted(stock, 0, 4, false));
        assertNull(Brewing.wanted(stock, 5, 4, true));
        assertTrue(Brewing.healing("strong_healing"));
        assertFalse(Brewing.healing("swiftness"));
    }

    @Test
    void newBlueprintsParseAndCarryTheirWorkstations() {
        assertTrue(Blueprints.get("masonry").materials("oak").containsKey(Ids.mc("stonecutter")));
        assertTrue(Blueprints.get("apiary").materials("oak").containsKey(Ids.mc("beehive")));
        assertTrue(Blueprints.get("apothecary").materials("oak").containsKey(Ids.mc("brewing_stand")));
        assertTrue(Blueprints.get("library").materials("oak").containsKey(Ids.mc("lectern")));
        assertTrue(Blueprints.get("stable").materials("oak").containsKey(Ids.mc("oak_fence")));
        for (String id : Blueprints.IDS) {
            assertNotNull(Blueprints.get(id), id);
        }
    }
}
