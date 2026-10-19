package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class HighwayPlanTest {
    private record Field(int[][] heights, boolean[][] blocked) implements HighwayPlan.Grid {
        static Field flat(int size) {
            return new Field(new int[size][size], new boolean[size][size]);
        }

        boolean inside(int x, int z) {
            return x >= 0 && z >= 0 && x < heights.length && z < heights.length;
        }

        @Override
        public double cost(int x, int z) {
            return !inside(x, z) || blocked[x][z] ? -1 : 1.0;
        }

        @Override
        public int height(int x, int z) {
            return inside(x, z) ? heights[x][z] : 0;
        }
    }

    @Test
    void straightRoadOnFlatGround() {
        Field field = Field.flat(80);
        List<int[]> path = HighwayPlan.route(field, 2, 40, 70, 40, 20, 5000);
        assertFalse(path.isEmpty());
        assertEquals(2, path.getFirst()[0]);
        assertTrue(Math.abs(path.getLast()[0] - 70) <= HighwayPlan.GOAL_REACH);
        assertTrue(path.size() <= 72);
    }

    @Test
    void roadGoesAroundALake() {
        Field field = Field.flat(80);
        for (int x = 30; x < 40; x++) {
            for (int z = 30; z < 50; z++) {
                field.blocked()[x][z] = true;
            }
        }
        List<int[]> path = HighwayPlan.route(field, 2, 40, 70, 40, 20, 8000);
        assertFalse(path.isEmpty());
        for (int[] cell : path) {
            assertFalse(field.blocked()[cell[0]][cell[1]]);
        }
    }

    @Test
    void corridorLimitsDetours() {
        Field field = Field.flat(80);
        for (int z = 0; z < 80; z++) {
            if (z < 10 || z > 70) {
                continue;
            }
            field.blocked()[40][z] = true;
        }
        assertTrue(HighwayPlan.route(field, 2, 40, 70, 40, 10, 8000).isEmpty());
        assertFalse(HighwayPlan.route(field, 2, 40, 70, 40, 35, 20000).isEmpty());
    }

    @Test
    void stepsAreAtMostOneBlock() {
        Field field = Field.flat(60);
        for (int x = 20; x < 60; x++) {
            for (int z = 0; z < 60; z++) {
                field.heights()[x][z] = z < 25 ? 3 : Math.max(0, x - 20) / 4;
            }
        }
        List<int[]> path = HighwayPlan.route(field, 2, 30, 55, 30, 30, 20000);
        assertFalse(path.isEmpty());
        for (int i = 1; i < path.size(); i++) {
            assertTrue(Math.abs(field.height(path.get(i)[0], path.get(i)[1]) - field.height(path.get(i - 1)[0], path.get(i - 1)[1])) <= 1);
        }
    }

    @Test
    void orthogonalRoadHasNoDiagonalGaps() {
        List<int[]> diagonal = List.of(new int[] {0, 0}, new int[] {1, 1}, new int[] {2, 2});
        List<int[]> road = HighwayPlan.orthogonal(diagonal);
        assertEquals(5, road.size());
        for (int i = 1; i < road.size(); i++) {
            int dx = Math.abs(road.get(i)[0] - road.get(i - 1)[0]);
            int dz = Math.abs(road.get(i)[1] - road.get(i - 1)[1]);
            assertEquals(1, dx + dz);
        }
    }

    @Test
    void campsOnlyOnLongRoadsAndEvenlySpaced() {
        assertTrue(HighwayPlan.camps(150).isEmpty());
        assertEquals(List.of(125), HighwayPlan.camps(250));
        List<Integer> marks = HighwayPlan.camps(640);
        assertEquals(4, marks.size());
        for (int i = 1; i < marks.size(); i++) {
            assertEquals(128, marks.get(i) - marks.get(i - 1));
        }
    }

    @Test
    void waypointsEndAtTheGoalAndSidestepIsPerpendicular() {
        List<int[]> points = HighwayPlan.waypoints(0, 0, 100, 0, 48);
        assertEquals(3, points.size());
        assertEquals(100, points.getLast()[0]);
        int[] moved = HighwayPlan.sidestep(0, 0, 100, 0, points.getFirst(), 14);
        assertEquals(points.getFirst()[0], moved[0]);
        assertEquals(14, Math.abs(moved[1]));
        int[] edge = HighwayPlan.edge(0, 0, 100, 0, 30);
        assertEquals(30, edge[0]);
    }

    @Test
    void roadsFollowFriendlyTreatiesOnly() {
        assertTrue(HighwayPlan.wanted(Diplomacy.Treaty.TRADE, 0, 300));
        assertTrue(HighwayPlan.wanted(Diplomacy.Treaty.NONE, 30, 300));
        assertFalse(HighwayPlan.wanted(Diplomacy.Treaty.NONE, 10, 300));
        assertFalse(HighwayPlan.wanted(Diplomacy.Treaty.WAR, 90, 300));
        assertFalse(HighwayPlan.wanted(Diplomacy.Treaty.ALLIANCE, 90, 900));
    }
}
