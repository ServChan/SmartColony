package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class HazardTest {
    @Test
    void classificationPrefersTheWorstSource() {
        assertEquals(Hazard.Kind.LAVA, Hazard.classify(true, true, true, true));
        assertEquals(Hazard.Kind.FIRE, Hazard.classify(false, true, true, true));
        assertEquals(Hazard.Kind.CAMPFIRE, Hazard.classify(false, false, true, true));
        assertEquals(Hazard.Kind.MAGMA, Hazard.classify(false, false, false, true));
        assertEquals(Hazard.Kind.NONE, Hazard.classify(false, false, false, false));
        assertFalse(Hazard.Kind.MAGMA.burns());
        assertTrue(Hazard.Kind.CAMPFIRE.burns());
    }

    @Test
    void lavaNextToTheFeetThreatensUnlessTolerated() {
        Hazard.Kind[] cube = Hazard.emptyCube();
        cube[Hazard.index(1, 0, 0)] = Hazard.Kind.LAVA;
        assertTrue(Hazard.threatened(cube, false, false));
        assertFalse(Hazard.threatened(cube, false, true));
    }

    @Test
    void standingInFireOrOnMagmaAlwaysThreatens() {
        Hazard.Kind[] cube = Hazard.emptyCube();
        cube[Hazard.CENTER] = Hazard.Kind.FIRE;
        assertTrue(Hazard.threatened(cube, false, true));
        cube = Hazard.emptyCube();
        cube[Hazard.index(0, -1, 0)] = Hazard.Kind.MAGMA;
        assertTrue(Hazard.threatened(cube, false, true));
        assertTrue(Hazard.threatened(Hazard.emptyCube(), true, true));
        assertFalse(Hazard.threatened(Hazard.emptyCube(), false, false));
    }

    @Test
    void targetsBesideLavaOrFireAreRejected() {
        Hazard.Kind[] cube = Hazard.emptyCube();
        assertFalse(Hazard.unsafeTarget(cube, false));
        cube[Hazard.index(-1, 1, 1)] = Hazard.Kind.CAMPFIRE;
        assertTrue(Hazard.unsafeTarget(cube, false));
        assertFalse(Hazard.unsafeTarget(cube, true));
        cube = Hazard.emptyCube();
        cube[Hazard.index(0, -1, 1)] = Hazard.Kind.LAVA;
        assertTrue(Hazard.unsafeTarget(cube, false));
        cube = Hazard.emptyCube();
        cube[Hazard.index(1, -1, 1)] = Hazard.Kind.MAGMA;
        assertFalse(Hazard.unsafeTarget(cube, false));
        cube[Hazard.index(0, -1, 0)] = Hazard.Kind.MAGMA;
        assertTrue(Hazard.unsafeTarget(cube, true));
    }

    @Test
    void refugeKeepsDistanceFromEverySource() {
        List<int[]> sources = List.of(new int[] {0, 0, 0}, new int[] {5, 0, 0});
        assertFalse(Hazard.farFrom(2, 0, 0, sources, 3.0));
        assertTrue(Hazard.farFrom(0, 0, 8, sources, 3.0));
    }
}
