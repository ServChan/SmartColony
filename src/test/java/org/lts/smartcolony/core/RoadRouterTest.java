package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

class RoadRouterTest {
    private static final class TestMap implements RoadRouter.Grid {
        final BiFunction<Integer, Integer, Integer> height;
        final Set<String> blocked = new HashSet<>();
        final int gx;
        final int gz;

        TestMap(BiFunction<Integer, Integer, Integer> height, int gx, int gz) {
            this.height = height;
            this.gx = gx;
            this.gz = gz;
        }

        @Override
        public double cost(int x, int z) {
            return Math.abs(x) > 30 || Math.abs(z) > 30 || blocked.contains(x + "," + z) ? -1 : 1.0;
        }

        @Override
        public int height(int x, int z) {
            return height.apply(x, z);
        }

        @Override
        public boolean goal(int x, int z) {
            return x == gx && z == gz;
        }
    }

    @Test
    void routesAroundABuilding() {
        TestMap map = new TestMap((x, z) -> 64, 10, 0);
        for (int z = -3; z <= 3; z++) {
            map.blocked.add("5," + z);
        }
        List<int[]> path = RoadRouter.route(map, 0, 0, 5000);
        assertFalse(path.isEmpty());
        assertEquals(10, path.getLast()[0]);
        for (int[] cell : path) {
            assertFalse(map.blocked.contains(cell[0] + "," + cell[1]));
        }
    }

    @Test
    void neverClimbsMoreThanOneBlockAndReportsTheLowerCellOfAStep() {
        TestMap map = new TestMap((x, z) -> x < 5 ? 64 : 65, 9, 0);
        List<int[]> path = RoadRouter.route(map, 0, 0, 5000);
        assertFalse(path.isEmpty());
        for (int i = 0; i + 1 < path.size(); i++) {
            assertTrue(Math.abs(map.height(path.get(i)[0], path.get(i)[1]) - map.height(path.get(i + 1)[0], path.get(i + 1)[1])) <= 1);
        }
        List<int[]> steps = RoadRouter.stepCells(path, map);
        assertEquals(1, steps.size());
        assertEquals(4, steps.getFirst()[0]);
    }

    @Test
    void refusesACliff() {
        TestMap map = new TestMap((x, z) -> x < 5 ? 64 : 67, 9, 0);
        assertTrue(RoadRouter.route(map, 0, 0, 5000).isEmpty());
    }

    @Test
    void respectsTheNodeBudget() {
        TestMap map = new TestMap((x, z) -> 64, 30, 30);
        assertTrue(RoadRouter.route(map, -30, -30, 50).isEmpty());
        assertFalse(RoadRouter.route(map, -30, -30, 20000).isEmpty());
    }

    @Test
    void widensAStraightStreetToThreeBlocks() {
        TestMap map = new TestMap((x, z) -> 64, 8, 0);
        List<int[]> path = RoadRouter.route(map, 0, 0, 5000);
        List<int[]> sides = RoadRouter.widen(path, map);
        assertEquals(path.size() * 2, sides.size());
        for (int[] side : sides) {
            assertEquals(1, Math.abs(side[1]));
        }
    }

    @Test
    void doesNotWidenOntoDifferentHeightOrBlockedCells() {
        TestMap map = new TestMap((x, z) -> z == 1 ? 65 : 64, 8, 0);
        map.blocked.add("3,-1");
        List<int[]> path = RoadRouter.route(map, 0, 0, 5000);
        List<int[]> sides = RoadRouter.widen(path, map);
        assertEquals(path.size() - 1, sides.size());
    }
}
