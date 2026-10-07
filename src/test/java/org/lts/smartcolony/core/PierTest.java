package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PierTest {
    @Test
    void depthLimitAndCost() {
        assertFalse(Pier.shallow(0));
        assertTrue(Pier.shallow(1));
        assertTrue(Pier.shallow(Pier.MAX_DEPTH));
        assertFalse(Pier.shallow(Pier.MAX_DEPTH + 1));
        assertTrue(Pier.cost(1) > 1.8);
        assertTrue(Pier.cost(4) > Pier.cost(1));
    }

    @Test
    void postsAtRunEndsAndEveryThirdCell() {
        boolean[] water = new boolean[12];
        for (int i = 2; i < 11; i++) {
            water[i] = true;
        }
        int[] marks = Pier.markers(water);
        for (int i = 0; i < 2; i++) {
            assertEquals(0, marks[i]);
        }
        assertEquals(0, marks[11]);
        assertTrue((marks[2] & Pier.POST) != 0);
        assertTrue((marks[10] & Pier.POST) != 0);
        int last = -1;
        for (int i = 2; i <= 10; i++) {
            if ((marks[i] & Pier.POST) != 0) {
                if (last >= 0) {
                    assertTrue(i - last <= Pier.POST_SPACING, "gap before " + i);
                }
                last = i;
            }
        }
    }

    @Test
    void lampsSitOnPostsAndAreSpacedApart() {
        boolean[] water = new boolean[40];
        java.util.Arrays.fill(water, true);
        int[] marks = Pier.markers(water);
        int lamps = 0;
        int previous = -100;
        for (int i = 0; i < marks.length; i++) {
            if ((marks[i] & Pier.LAMP) != 0) {
                assertTrue((marks[i] & Pier.POST) != 0);
                assertTrue(i - previous >= Pier.LAMP_SPACING);
                previous = i;
                lamps++;
            }
        }
        assertEquals(5, lamps);
    }

    @Test
    void separateRunsGetTheirOwnEndPosts() {
        boolean[] water = {true, true, false, false, true, true, true};
        int[] marks = Pier.markers(water);
        assertTrue((marks[0] & Pier.POST) != 0);
        assertTrue((marks[1] & Pier.POST) != 0);
        assertTrue((marks[4] & Pier.POST) != 0);
        assertTrue((marks[6] & Pier.POST) != 0);
        assertEquals(0, marks[2]);
    }

    @Test
    void railingsFollowTheWalkway() {
        assertArrayEquals(new boolean[] {false, true, false, true}, Pier.railing(true, false, true, false));
        assertArrayEquals(new boolean[] {true, false, false, true}, Pier.railing(false, true, true, false));
        assertArrayEquals(new boolean[] {false, true, false, true}, Pier.railing(true, false, false, false));
        assertArrayEquals(new boolean[] {false, false, false, true}, Pier.railing(true, true, true, false));
        assertArrayEquals(new boolean[4], Pier.railing(true, true, true, true));
        assertArrayEquals(new boolean[4], Pier.railing(false, false, false, false));
    }

    @Test
    void theOpenEndOfAPierStaysWithoutRail() {
        boolean[] rails = Pier.railing(true, false, false, false);
        assertFalse(rails[2]);
        assertTrue(rails[1] && rails[3]);
    }

    @Test
    void postsSupportCornersTipsAndTheLattice() {
        boolean[][] floor = new boolean[6][6];
        for (int x = 0; x < 6; x++) {
            for (int z = 0; z < 6; z++) {
                floor[x][z] = true;
            }
        }
        assertTrue(Pier.isPost(floor, 0, 0));
        assertTrue(Pier.isPost(floor, 5, 5));
        assertFalse(Pier.isPost(floor, 1, 1));
        assertTrue(Pier.isPost(floor, 1, 2));
        int posts = 0;
        for (int x = 0; x < 6; x++) {
            for (int z = 0; z < 6; z++) {
                if (Pier.isPost(floor, x, z)) {
                    posts++;
                }
            }
        }
        assertTrue(posts >= 12 && posts < 36);
    }

    @Test
    void aOneWideJettyGetsAPostEveryThirdCell() {
        boolean[][] floor = new boolean[3][12];
        for (int z = 0; z < 12; z++) {
            floor[1][z] = true;
        }
        int last = -1;
        for (int z = 0; z < 12; z++) {
            if (Pier.isPost(floor, 1, z)) {
                if (last >= 0) {
                    assertTrue(z - last <= 3);
                }
                last = z;
            }
        }
        assertTrue(Pier.isPost(floor, 1, 0));
        assertTrue(Pier.isPost(floor, 1, 11));
        assertFalse(Pier.isPost(floor, 0, 5));
    }

    @Test
    void waterKindsAndShare() {
        assertTrue(Pier.waterKind("house"));
        assertTrue(Pier.waterKind("dock"));
        assertTrue(Pier.waterKind("fisher"));
        assertFalse(Pier.waterKind("storage"));
        assertTrue(Pier.needsWater("dock"));
        assertFalse(Pier.needsWater("house"));
        assertTrue(Pier.enoughWater("house", 0, 50));
        assertFalse(Pier.enoughWater("dock", 5, 60));
        assertTrue(Pier.enoughWater("dock", 30, 60));
        assertTrue(Pier.platformPenalty(10) > Pier.platformPenalty(0));
        assertEquals(0.0, Pier.platformPenalty(0));
        assertTrue(Pier.waterVillage(900, 10000));
        assertFalse(Pier.waterVillage(100, 10000));
    }

    @Test
    void waterColumnsMayNotRiseAboveTheBase() {
        int[][] ground = {{64, 64, 62}, {64, 64, 62}};
        boolean[][] blocked = new boolean[2][3];
        boolean[][] water = {{false, false, true}, {false, false, true}};
        SiteEvaluator.Result result = SiteEvaluator.evaluate(ground, blocked, 3, 4, water);
        assertTrue(result.ok());
        assertEquals(64, result.baseY());
        int[][] flooded = {{60, 60, 62}, {60, 60, 62}};
        boolean[][] allWater = {{true, true, true}, {true, true, true}};
        SiteEvaluator.Result raised = SiteEvaluator.evaluate(flooded, blocked, 3, 4, allWater);
        assertTrue(raised.ok());
        assertEquals(62, raised.baseY());
        assertEquals(2, raised.maxFill());
    }

    @Test
    void landBelowTheWaterlineKeepsTheBaseOnTheWaterSurface() {
        int[][] ground = {{61, 61, 63}, {61, 61, 63}};
        boolean[][] blocked = new boolean[2][3];
        boolean[][] water = {{false, false, true}, {false, false, true}};
        SiteEvaluator.Result result = SiteEvaluator.evaluate(ground, blocked, 3, 4, water);
        assertEquals(63, result.baseY());
        assertTrue(result.ok());
        SiteEvaluator.Result strict = SiteEvaluator.evaluateAt(ground, blocked, 62, 3, 4, water);
        assertFalse(strict.ok());
    }

    @Test
    void stringIsKeptForARodInAWaterVillage() {
        assertTrue(StringSupply.bowAllowed(3, false));
        assertFalse(StringSupply.bowAllowed(4, true));
        assertTrue(StringSupply.bowAllowed(5, true));
        assertEquals(4, StringSupply.tradeCount(0, 0, true));
        assertEquals(1, StringSupply.tradeCount(3, 0, true));
        assertEquals(0, StringSupply.tradeCount(0, 1, true));
        assertEquals(0, StringSupply.tradeCount(0, 0, false));
    }

    @Test
    void pierStylesCarryTheirFlags() {
        String style = PathStyle.with(PathStyle.with(PathStyle.PIER, PathStyle.POST), PathStyle.LAMP);
        assertTrue(PathStyle.pier(style));
        assertTrue(PathStyle.post(style));
        assertTrue(PathStyle.lamp(style));
        assertFalse(PathStyle.pier(PathStyle.MAIN));
        assertFalse(PathStyle.post(PathStyle.PIER));
        assertEquals(PathStyle.PIER, PathStyle.base(style));
    }

    @Test
    void routerCrossesShallowWaterOnlyWhenTheDetourIsLonger() {
        RoadRouter.Grid grid = new RoadRouter.Grid() {
            @Override
            public double cost(int x, int z) {
                if (Math.abs(z) > 12 || x < -2 || x > 14) {
                    return -1;
                }
                boolean sea = x >= 4 && x <= 6 && z >= -12 && z < 4;
                return sea ? Pier.cost(2) : 1.0;
            }

            @Override
            public int height(int x, int z) {
                return 62;
            }

            @Override
            public boolean goal(int x, int z) {
                return x == 10 && z == 0;
            }
        };
        List<int[]> path = RoadRouter.route(grid, 0, 0, 5000);
        assertFalse(path.isEmpty());
        long wet = path.stream().filter(c -> c[0] >= 4 && c[0] <= 6 && c[1] < 4).count();
        assertTrue(wet == 0 || wet == 3, "wet cells " + wet);
        assertEquals(10, path.getLast()[0]);
    }

    @Test
    void docksAndHutsDeclareUsableFishingStands() {
        for (String id : List.of(Pier.DOCK_ID, Pier.FISHER_ID)) {
            Blueprint blueprint = Blueprints.get(id);
            for (int[] f : blueprint.fishing()) {
                assertTrue(blueprint.solidFloor(f[0], f[2]), id + " stand floor");
                assertEquals(Blueprint.AIR, blueprint.at(f[0], 1, f[2]), id + " stand headroom");
                assertEquals(Blueprint.AIR, blueprint.at(f[0], 2, f[2]), id + " stand headroom");
                assertFalse(blueprint.solidFloor(f[3], f[5]), id + " water cell is open");
            }
        }
        assertFalse(Blueprints.get(Pier.DOCK_ID).fishing().isEmpty());
        assertTrue(Pier.needsWater(Blueprints.get(Pier.DOCK_ID).kind()));
        assertTrue(Pier.needsWater(Blueprints.get(Pier.FISHER_ID).kind()));
    }
}
