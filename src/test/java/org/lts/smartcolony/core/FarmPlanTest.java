package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class FarmPlanTest {
    @Test
    void baseGrowsWithAdultsAndAlwaysHasOneField() {
        assertEquals(1, FarmPlan.base(0));
        assertEquals(1, FarmPlan.base(4));
        assertEquals(2, FarmPlan.base(5));
        assertEquals(4, FarmPlan.base(18));
        assertEquals(5, FarmPlan.base(22));
    }

    @Test
    void lowFoodAddsFieldsAndHardCapHolds() {
        int adults = 18;
        int comfortable = FarmPlan.COMFORT_BASE + adults * FarmPlan.COMFORT_PER_ADULT;
        assertEquals(4, FarmPlan.budget(adults, comfortable));
        assertEquals(5, FarmPlan.budget(adults, adults * FarmPlan.LOW_FOOD_PER_HEAD - 1));
        assertEquals(6, FarmPlan.budget(adults, 0));
        assertEquals(FarmPlan.HARD_CAP, FarmPlan.budget(200, 0));
        assertEquals(FarmPlan.HARD_CAP, FarmPlan.ceiling(200));
    }

    @Test
    void noNewAnchorsWhileFoodIsComfortable() {
        int adults = 10;
        int comfortable = FarmPlan.COMFORT_BASE + adults * FarmPlan.COMFORT_PER_ADULT;
        assertFalse(FarmPlan.mayAddAnchor(1, adults, comfortable));
        assertTrue(FarmPlan.mayAddAnchor(1, adults, comfortable - 1));
    }

    @Test
    void noNewAnchorsOnceBudgetIsReached() {
        int adults = 18;
        assertTrue(FarmPlan.mayAddAnchor(3, adults, 100));
        assertFalse(FarmPlan.mayAddAnchor(6, adults, 0));
        assertFalse(FarmPlan.mayAddAnchor(24, adults, 0));
    }

    @Test
    void newAnchorsClusterNextToExistingOnes() {
        List<Pos> anchors = List.of(new Pos(0, 64, 0), new Pos(10, 64, 0));
        assertTrue(FarmPlan.clusterOk(10, 10, anchors));
        assertTrue(FarmPlan.clusterOk(0, -12, anchors));
        assertFalse(FarmPlan.clusterOk(3, 3, anchors));
        assertFalse(FarmPlan.clusterOk(60, 60, anchors));
        assertFalse(FarmPlan.clusterOk(10, 0, anchors));
        assertTrue(FarmPlan.clusterOk(60, 60, List.of()));
    }

    @Test
    void withinCeilingNothingGoesFallow() {
        List<Pos> anchors = List.of(new Pos(0, 64, 0), new Pos(10, 64, 0), new Pos(90, 64, 0));
        boolean[] fallow = FarmPlan.fallow(anchors, 0, 0, 3);
        assertFalse(fallow[0] || fallow[1] || fallow[2]);
    }

    @Test
    void distantSurplusAnchorsGoFallowButCloseOnesStay() {
        List<Pos> anchors = List.of(new Pos(0, 64, 0), new Pos(10, 64, 0), new Pos(20, 64, 0), new Pos(90, 64, 40),
                new Pos(30, 64, 0), new Pos(-80, 64, -70));
        boolean[] fallow = FarmPlan.fallow(anchors, 0, 0, 3);
        assertFalse(fallow[0]);
        assertFalse(fallow[1]);
        assertFalse(fallow[2]);
        assertTrue(fallow[3]);
        assertFalse(fallow[4]);
        assertTrue(fallow[5]);
    }

    @Test
    void keptAnchorsFollowTheClusterAroundTheCentre() {
        List<Pos> anchors = List.of(new Pos(100, 64, 100), new Pos(5, 64, 0), new Pos(14, 64, 0), new Pos(100, 64, -100));
        boolean[] fallow = FarmPlan.fallow(anchors, 0, 0, 2);
        assertFalse(fallow[1]);
        assertFalse(fallow[2]);
        assertTrue(fallow[0]);
        assertTrue(fallow[3]);
    }
}
