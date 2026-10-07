package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import org.junit.jupiter.api.Test;

class TavernRulesTest {
    private static EnumMap<Role, Integer> roles(int lumberjack, int farmer, int miner) {
        EnumMap<Role, Integer> map = new EnumMap<>(Role.class);
        for (Role role : Role.values()) {
            map.put(role, 0);
        }
        map.put(Role.LUMBERJACK, lumberjack);
        map.put(Role.FARMER, farmer);
        map.put(Role.MINER, miner);
        return map;
    }

    @Test
    void tavernIsPlannedOnceFoodIsStable() {
        assertTrue(TavernRules.needed(10, 0, true));
        assertFalse(TavernRules.needed(9, 0, true));
        assertFalse(TavernRules.needed(12, 1, true));
        assertFalse(TavernRules.needed(12, 0, false));
        assertTrue(TavernRules.foodStable(60 + 10 * 30, 10));
        assertFalse(TavernRules.foodStable(300, 10));
    }

    @Test
    void innkeeperTakesOneWorkerFromTheBiggestRole() {
        EnumMap<Role, Integer> base = roles(4, 3, 2);
        EnumMap<Role, Integer> result = TavernRules.withInnkeeper(base, true, 12);
        assertEquals(1, result.get(Role.INNKEEPER));
        assertEquals(3, result.get(Role.LUMBERJACK));
        assertEquals(3, result.get(Role.FARMER));
        int before = base.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(before, result.values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void noInnkeeperWithoutTavernOrWorkersToSpare() {
        assertEquals(0, TavernRules.withInnkeeper(roles(4, 3, 2), false, 12).get(Role.INNKEEPER));
        assertEquals(0, TavernRules.withInnkeeper(roles(1, 1, 1), true, 12).get(Role.INNKEEPER));
        assertEquals(0, TavernRules.withInnkeeper(roles(4, 3, 2), true, 5).get(Role.INNKEEPER));
    }

    @Test
    void serviceHoursFollowTheEndOfTheShift() {
        assertTrue(TavernRules.serviceHours(10500, 10500));
        assertTrue(TavernRules.serviceHours(12000, 10500));
        assertFalse(TavernRules.serviceHours(13000, 10500));
        assertFalse(TavernRules.serviceHours(5000, 10500));
        assertTrue(TavernRules.serviceHours(24000L * 5 + 11000, 10500));
        assertTrue(TavernRules.serviceHours(1000, 23000));
    }

    @Test
    void mealPrefersCookedFoodAndNeedsStock() {
        Stock stock = new Stock();
        assertNull(TavernRules.meal(stock));
        stock.add(Ids.mc("carrot"), 5);
        assertEquals(Ids.mc("carrot"), TavernRules.meal(stock));
        stock.add(Ids.mc("bread"), 1);
        assertEquals(Ids.mc("bread"), TavernRules.meal(stock));
        stock.add(Ids.mc("cooked_beef"), 1);
        assertEquals(Ids.mc("cooked_beef"), TavernRules.meal(stock));
    }

    @Test
    void travellersNeedBedsFoodAndAGoodRecord() {
        assertTrue(TavernRules.welcoming(1, 60 + 10 * 30, 10, 0));
        assertFalse(TavernRules.welcoming(0, 1000, 10, 0));
        assertFalse(TavernRules.welcoming(2, 100, 10, 0));
        assertFalse(TavernRules.welcoming(2, 1000, 10, 2));
        assertTrue(TavernRules.welcoming(2, 1000, 10, 1));
        assertFalse(TavernRules.welcoming(2, 1000, 0, 0));
    }

    @Test
    void arrivalsDependOnTheInnkeeperAndTheRoll() {
        assertEquals(0, TavernRules.arrivals(false, true, 5, true, 0.0));
        assertEquals(1, TavernRules.arrivals(true, false, 5, true, 0.5));
        assertEquals(0, TavernRules.arrivals(true, false, 5, true, 0.7));
        assertEquals(0, TavernRules.arrivals(true, false, 5, false, 0.4));
        assertEquals(1, TavernRules.arrivals(true, false, 5, false, 0.2));
        assertEquals(2, TavernRules.arrivals(true, true, 2, true, 0.1));
        assertEquals(1, TavernRules.arrivals(true, true, 1, true, 0.1));
    }

    @Test
    void travellersStayOnlyWhileTheColonyStaysWelcoming() {
        assertTrue(TavernRules.stays(true, 0.5));
        assertFalse(TavernRules.stays(true, 0.9));
        assertFalse(TavernRules.stays(false, 0.0));
    }

    @Test
    void flourishingNeedsDoubleFoodAndNoDeaths() {
        assertTrue(TavernRules.flourishing(60 + 10 * 60, 10, 0));
        assertFalse(TavernRules.flourishing(60 + 10 * 60, 10, 1));
        assertFalse(TavernRules.flourishing(60 + 10 * 30, 10, 0));
    }

    @Test
    void deathWindowRotatesEveryThreeDays() {
        assertArrayEquals(new int[] {5, 7, 7}, TavernRules.rotateDeathWindow(5, 9, 5, 7, 7));
        assertArrayEquals(new int[] {5, 7, 2}, TavernRules.rotateDeathWindow(6, 9, 5, 7, 2));
        assertArrayEquals(new int[] {8, 9, 7}, TavernRules.rotateDeathWindow(8, 9, 5, 7, 2));
        assertEquals(2, TavernRules.recentDeaths(9, 7));
        assertEquals(0, TavernRules.recentDeaths(3, 7));
    }

    @Test
    void roleAllocatorCapsInnkeeperAtOne() {
        EnumMap<Role, Integer> base = roles(4, 3, 2);
        EnumMap<Role, Integer> targets = new EnumMap<>(Role.class);
        targets.put(Role.INNKEEPER, 4);
        assertEquals(1, RoleAllocator.withTargets(base, targets, 12).get(Role.INNKEEPER));
    }

    @Test
    void tavernBlueprintHasNoBedsAndALegalDoor() {
        Blueprint tavern = Blueprints.get(TavernRules.ID);
        assertEquals(TavernRules.KIND, tavern.kind());
        assertEquals(0, tavern.beds());
        assertTrue(tavern.materials("oak").keySet().stream().noneMatch(id -> id.endsWith("_bed")));
        assertEquals('D', tavern.at(tavern.doorX(), 1, tavern.doorZ()));
        assertEquals('.', tavern.at(8, 1, 7));
        assertEquals('.', tavern.at(5, 1, 4));
    }
}
