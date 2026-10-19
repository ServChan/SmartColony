package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.IntBinaryOperator;
import org.junit.jupiter.api.Test;

class WallPlannerTest {
    private static final int SECTORS = 36;

    private static WallPlanner.Terrain terrain(IntBinaryOperator height, IntBinaryOperator penalty) {
        return new WallPlanner.Terrain() {
            @Override
            public boolean loaded(int x, int z) {
                return true;
            }

            @Override
            public int height(int x, int z) {
                return height.applyAsInt(x, z);
            }

            @Override
            public double penalty(int x, int z) {
                return penalty.applyAsInt(x, z);
            }
        };
    }

    private static double[] flat(double radius) {
        double[] radii = new double[SECTORS];
        java.util.Arrays.fill(radii, radius);
        return radii;
    }

    private static boolean inField(int x, int z) {
        return x >= 20 && x <= 34 && z >= -8 && z <= 8;
    }

    @Test
    void openGroundKeepsTheBaseRing() {
        double[] radii = WallPlanner.optimize(0, 0, flat(30), terrain((x, z) -> 64, (x, z) -> 0), 10, 3);
        for (double radius : radii) {
            assertEquals(30, radius, 1.5);
        }
    }

    @Test
    void ringMovesOutsideAFieldTheBaseRingCrosses() {
        WallPlanner.Terrain field = terrain((x, z) -> 64, (x, z) -> inField(x, z) ? 40 : 0);
        double[] radii = WallPlanner.optimize(0, 0, flat(30), field, 10, 3);
        for (int k = 0; k < SECTORS; k++) {
            int x = WallPlanner.vx(0, k, SECTORS, radii[k]);
            int z = WallPlanner.vz(0, k, SECTORS, radii[k]);
            assertTrue(!inField(x, z), "vertex " + k + " still in field at " + x + "," + z);
        }
        assertTrue(radii[0] > 34);
    }

    @Test
    void ringStaysSmoothAfterDetour() {
        WallPlanner.Terrain field = terrain((x, z) -> 64, (x, z) -> inField(x, z) ? 40 : 0);
        double[] radii = WallPlanner.optimize(0, 0, flat(30), field, 10, 3);
        for (int k = 0; k < SECTORS; k++) {
            assertTrue(Math.abs(radii[k] - radii[(k + 1) % SECTORS]) <= 10);
        }
    }

    @Test
    void ringPrefersLevelGroundWithinTheWindow() {
        WallPlanner.Terrain level = terrain((x, z) -> 64, (x, z) -> 0);
        WallPlanner.Terrain ridge = terrain((x, z) -> {
            int r = (int) Math.round(Math.sqrt((double) x * x + (double) z * z));
            return r >= 27 && r <= 32 && Math.floorMod(x + z, 4) < 2 ? 70 : 64;
        }, (x, z) -> 0);
        double[] flatRadii = WallPlanner.optimize(0, 0, flat(30), level, 10, 3);
        double[] ridgeRadii = WallPlanner.optimize(0, 0, flat(30), ridge, 10, 3);
        double flatMean = java.util.Arrays.stream(flatRadii).average().orElse(0);
        double ridgeMean = java.util.Arrays.stream(ridgeRadii).average().orElse(0);
        assertTrue(ridgeMean > flatMean + 1);
    }

    @Test
    void unloadedBaseVertexIsLeftAlone() {
        WallPlanner.Terrain unloaded = new WallPlanner.Terrain() {
            @Override
            public boolean loaded(int x, int z) {
                return false;
            }

            @Override
            public int height(int x, int z) {
                return 0;
            }

            @Override
            public double penalty(int x, int z) {
                return 0;
            }
        };
        double[] radii = WallPlanner.optimize(0, 0, flat(30), unloaded, 10, 3);
        for (double radius : radii) {
            assertEquals(30, radius, 1e-9);
        }
    }

    @Test
    void waterPenaltyGrowsThenJumpsPastTheLimit() {
        assertEquals(0, WallPlanner.waterPenalty(0));
        assertTrue(WallPlanner.waterPenalty(2) > WallPlanner.waterPenalty(1));
        assertEquals(WallPlanner.DEEP_WATER, WallPlanner.waterPenalty(WallPlanner.MAX_WATER_DEPTH + 1));
        assertTrue(WallPlanner.waterPenalty(WallPlanner.MAX_WATER_DEPTH) < WallPlanner.OBSTACLE);
    }

    @Test
    void steepStepRaisesTheLowSideSoTheTopLineIsContinuous() {
        int[] ground = {70, 70, 70, 64, 64, 64, 64};
        int[] wanted = {3, 3, 3, 3, 3, 3, 3};
        boolean[] fixed = new boolean[7];
        boolean[] linked = {true, true, true, true, true, true, false};
        int[] heights = WallPlanner.heights(ground, wanted, fixed, linked, 2, 8);
        for (int i = 0; i < 6; i++) {
            int a = ground[i] + heights[i];
            int b = ground[i + 1] + heights[i + 1];
            assertTrue(Math.abs(a - b) <= 2, "step at " + i);
        }
        assertEquals(3, heights[0]);
        assertTrue(heights[3] > 3);
        assertEquals(3, heights[6]);
    }

    @Test
    void raisedLowSideNeverExceedsTheCap() {
        int[] ground = {90, 60, 60, 60};
        int[] wanted = {3, 3, 3, 3};
        int[] heights = WallPlanner.heights(ground, wanted, new boolean[4], new boolean[] {true, true, true, false}, 2, 8);
        for (int height : heights) {
            assertTrue(height <= 8);
        }
    }

    @Test
    void fixedColumnsKeepTheirHeight() {
        int[] ground = {70, 64, 64};
        int[] wanted = {3, 3, 3};
        boolean[] fixed = {false, true, false};
        int[] heights = WallPlanner.heights(ground, wanted, fixed, new boolean[] {true, true, false}, 2, 8);
        assertEquals(3, heights[1]);
    }

    @Test
    void levelGroundIsNotRaised() {
        int[] ground = {64, 65, 65, 66};
        int[] wanted = {3, 3, 3, 3};
        int[] heights = WallPlanner.heights(ground, wanted, new boolean[4], new boolean[] {true, true, true, true}, 2, 8);
        assertEquals(3, heights[0]);
        assertEquals(3, heights[1]);
        assertEquals(3, heights[3]);
    }

    @Test
    void enclosesPointsInsideTheRingOnly() {
        int n = SECTORS;
        int[] xs = new int[n];
        int[] zs = new int[n];
        for (int k = 0; k < n; k++) {
            xs[k] = WallPlanner.vx(100, k, n, 30);
            zs[k] = WallPlanner.vz(-50, k, n, 30);
        }
        assertTrue(WallPlanner.encloses(xs, zs, 100, -50));
        assertTrue(WallPlanner.encloses(xs, zs, 125, -50));
        assertTrue(!WallPlanner.encloses(xs, zs, 135, -50));
        assertTrue(!WallPlanner.encloses(xs, zs, 100, -85));
        assertTrue(!WallPlanner.encloses(new int[] {0, 1}, new int[] {0, 1}, 0, 0));
    }
}
