package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GuardDrillTest {
    @Test
    void barracksComeWithAFourthGuardOrAThreat() {
        assertFalse(GuardDrill.needed(3, 0.1, 20, false));
        assertTrue(GuardDrill.needed(4, 0.0, 20, false));
        assertTrue(GuardDrill.needed(2, 0.6, 12, false));
        assertFalse(GuardDrill.needed(1, 1.5, 12, false));
        assertFalse(GuardDrill.needed(6, 1.0, 20, true));
        assertFalse(GuardDrill.needed(6, 1.0, 7, false));
    }

    @Test
    void guardsSplitIntoNightAndDayShiftsOnlyFromThree() {
        assertFalse(GuardDrill.nightShift(0, 2));
        assertFalse(GuardDrill.nightShift(1, 2));
        assertTrue(GuardDrill.nightShift(0, 4));
        assertFalse(GuardDrill.nightShift(1, 4));
        assertTrue(GuardDrill.nightShift(2, 4));
        long nights = java.util.stream.IntStream.range(0, 5).filter(i -> GuardDrill.nightShift(i, 5)).count();
        assertEquals(3, nights);
    }

    @Test
    void onlyTheFirstGuardsGetCots() {
        assertTrue(GuardDrill.hasCot(0));
        assertTrue(GuardDrill.hasCot(GuardDrill.COTS - 1));
        assertFalse(GuardDrill.hasCot(GuardDrill.COTS));
        assertFalse(GuardDrill.hasCot(-1));
        assertEquals(GuardDrill.COTS, GuardDrill.COT_SPOTS.length);
    }

    @Test
    void trainingAlternatesWithPatrolBetweenGuards() {
        for (long window = 0; window < 6; window++) {
            long time = window * GuardDrill.WINDOW_TICKS + 5;
            assertTrue(GuardDrill.trainsNow(time, 0) != GuardDrill.trainsNow(time, 1));
        }
        assertTrue(GuardDrill.trainsNow(0, 0));
        assertFalse(GuardDrill.trainsNow(GuardDrill.WINDOW_TICKS, 0));
        assertTrue(GuardDrill.trainsNow(GuardDrill.WINDOW_TICKS * 2, 0));
    }

    @Test
    void everybodyStandsToUnderAlarmOrHighThreat() {
        assertTrue(GuardDrill.standDown(true, 0.0));
        assertTrue(GuardDrill.standDown(false, 0.5));
        assertFalse(GuardDrill.standDown(false, 0.49));
    }

    @Test
    void drillStopsGivingSkillAtLevelThree() {
        assertEquals(1, GuardDrill.trainingXp(0));
        assertEquals(1, GuardDrill.trainingXp(2));
        assertEquals(0, GuardDrill.trainingXp(3));
        assertEquals(0, GuardDrill.trainingXp(5));
    }

    @Test
    void skilledGuardsShootFasterStrikeSoonerAndAimBetter() {
        assertEquals(25, CombatMath.shotCooldown(0));
        assertEquals(15, CombatMath.shotCooldown(5));
        assertTrue(CombatMath.shotCooldown(14) >= 14);
        assertEquals(20, CombatMath.meleeCooldown(0));
        assertEquals(15, CombatMath.meleeCooldown(5));
        assertEquals(4.0F, CombatMath.shotSpread(0));
        assertTrue(CombatMath.shotSpread(5) < CombatMath.shotSpread(1));
        assertTrue(CombatMath.shotSpread(20) >= 1.0F);
    }

    @Test
    void barracksBlueprintKeepsItsSpotsAndLaneClear() {
        Blueprint barracks = Blueprints.get(GuardDrill.ID);
        assertEquals("barracks", barracks.kind());
        assertEquals(0, barracks.beds());
        assertEquals(0, barracks.doorZ());
        assertTrue(barracks.cells("oak").stream().noneMatch(c -> c.state().contains("_bed")));
        for (int[] spot : GuardDrill.DUMMIES) {
            assertEquals(Blueprint.AIR, barracks.at(spot[0], 1, spot[1]));
            assertEquals(Blueprint.AIR, barracks.at(spot[0], 2, spot[1]));
        }
        for (int[] head : GuardDrill.DUMMY_HEADS) {
            assertEquals('F', barracks.at(head[0], 1, head[1]));
            assertEquals('F', barracks.at(head[0], 2, head[1]));
            assertEquals('V', barracks.at(head[0], 3, head[1]));
        }
        int lane = GuardDrill.LANE_STAND[1];
        assertEquals(lane, GuardDrill.LANE_TARGET[1]);
        assertEquals(Blueprint.AIR, barracks.at(GuardDrill.LANE_STAND[0], 1, lane));
        assertEquals('V', barracks.at(GuardDrill.LANE_TARGET[0], 1, lane));
        for (int x = GuardDrill.LANE_TARGET[0] + 1; x < GuardDrill.LANE_STAND[0]; x++) {
            assertEquals(Blueprint.AIR, barracks.at(x, 1, lane), "lane x " + x);
            assertEquals(Blueprint.AIR, barracks.at(x, 2, lane), "lane x " + x);
        }
        for (int[] cot : GuardDrill.COT_SPOTS) {
            assertEquals(Blueprint.AIR, barracks.at(cot[0], 1, cot[1]));
            assertEquals('W', barracks.at(cot[0], 1, cot[1] + 1));
            assertEquals('P', barracks.at(cot[0], 0, cot[1]));
        }
    }
}
