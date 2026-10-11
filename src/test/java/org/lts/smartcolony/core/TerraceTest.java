package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TerraceTest {
    private static int[][] slope(int width, int depth, int base, int rise) {
        return slope(width, depth, base, rise, 1);
    }

    private static int[][] slope(int width, int depth, int base, int rise, int run) {
        int[][] ground = new int[width][depth];
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                ground[x][z] = base + x * rise / run;
            }
        }
        return ground;
    }

    @Test
    void ringsCountOutwardFromTheFootprint() {
        assertEquals(0, Terrace.ring(3, 3, 3, 5, 5));
        assertEquals(0, Terrace.ring(7, 7, 3, 5, 5));
        assertEquals(1, Terrace.ring(2, 5, 3, 5, 5));
        assertEquals(3, Terrace.ring(0, 0, 3, 5, 5));
        assertEquals(2, Terrace.ring(9, 4, 3, 5, 5));
    }

    @Test
    void footprintIsCutFlatButNeverFilledByTheTerrace() {
        assertEquals(64, Terrace.target(70, 0, 64, false));
        assertEquals(60, Terrace.target(60, 0, 64, false));
    }

    @Test
    void hillsideStepsUpOneBlockPerRingAfterTheApron() {
        assertEquals(64, Terrace.target(70, 1, 64, false));
        assertEquals(65, Terrace.target(70, 2, 64, false));
        assertEquals(67, Terrace.target(70, 4, 64, false));
        assertEquals(66, Terrace.target(66, 4, 64, false));
    }

    @Test
    void cliffsTooTallToStepAreLeftStanding() {
        assertEquals(80, Terrace.target(80, 2, 64, false));
        assertEquals(65, Terrace.target(65 + Terrace.MAX_RING_CUT, 2, 64, false));
        assertEquals(64 + 20, Terrace.target(64 + 20, 1, 64, true));
    }

    @Test
    void holesNextToTheBuildingAreFilledInSteps() {
        assertEquals(64, Terrace.target(60, 1, 64, false));
        assertEquals(63, Terrace.target(60, 2, 64, false));
        assertEquals(62, Terrace.target(60, 3, 64, false));
        assertEquals(50, Terrace.target(50, Terrace.FILL_RINGS + 1, 64, false));
    }

    @Test
    void doorwayIsLevelledWithTheFloor() {
        assertEquals(64, Terrace.target(69, 2, 64, true));
        assertEquals(64, Terrace.target(62, 2, 64, true));
        assertEquals(55, Terrace.target(55, 2, 64, true));
        Terrace.Door door = new Terrace.Door(5, 2, 0, -1);
        assertTrue(Terrace.inDoorway(5, 2, door));
        assertTrue(Terrace.inDoorway(4, 1, door));
        assertTrue(Terrace.inDoorway(6, 0, door));
        assertTrue(!Terrace.inDoorway(5, 3, door));
        assertTrue(!Terrace.inDoorway(7, 1, door));
    }

    @Test
    void cutIntoAHillLeavesWalkableStepsUpToTheTerrain() {
        int margin = Terrace.MARGIN;
        int size = 5;
        int width = size + margin * 2;
        int[][] ground = slope(width, width, 60, 1, 2);
        boolean[][] fixed = new boolean[width][width];
        int base = ground[margin + size / 2][0];
        Terrace.Plan plan = Terrace.plan(ground, fixed, margin, size, size, base, null);
        for (int x = margin; x < margin + size; x++) {
            assertTrue(plan.at(x, margin + 2) <= base);
        }
        for (int x = margin + size / 2 + 1; x < width; x++) {
            int z = margin + 2;
            assertTrue(Math.abs(plan.at(x, z) - plan.at(x - 1, z)) <= 1, "step at " + x);
        }
        assertTrue(plan.cut() > 0);
        assertEquals(ground[width - 1][margin + 2], plan.at(width - 1, margin + 2));
    }

    @Test
    void fixedColumnsAreLeftAlone() {
        int[][] ground = slope(7, 7, 60, 2);
        boolean[][] fixed = new boolean[7][7];
        fixed[6][3] = true;
        Terrace.Plan plan = Terrace.plan(ground, fixed, 2, 3, 3, 64, null);
        assertEquals(ground[6][3], plan.at(6, 3));
    }

    @Test
    void cheapCutBeatsADistantFlatSiteButFillStaysExpensive() {
        double slope = Terrace.cost(0, 60, 4);
        double hole = Terrace.cost(60, 0, 0);
        assertTrue(slope < 25);
        assertTrue(hole > slope * 4);
    }
}
