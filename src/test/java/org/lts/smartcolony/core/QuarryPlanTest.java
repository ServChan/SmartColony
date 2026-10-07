package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QuarryPlanTest {
    private static String key(Pos pos) {
        return pos.x() + "," + pos.y() + "," + pos.z();
    }

    private static boolean adjacent(Pos a, Pos b) {
        return Math.abs(a.x() - b.x()) + Math.abs(a.z() - b.z()) == 1;
    }

    @Test
    void everyLayerDigsAllButTheStepAndStandsOnDugGround() {
        QuarryPlan plan = new QuarryPlan(new Pos(100, 70, 200), 7, 40);
        assertEquals(40 * 48, plan.size());
        Set<String> dug = new HashSet<>();
        for (int index = 0; index < plan.size(); index++) {
            int layer = plan.layerOf(index);
            MinePlan.Cell cell = plan.cell(index);
            assertEquals(plan.layerY(layer), cell.feet().y());
            assertTrue(plan.inside(cell.feet()));
            assertFalse(cell.feet().equals(plan.step(layer)), "step must stay at layer " + layer);
            assertTrue(dug.add(key(cell.feet())), "cell dug twice " + cell.feet());
            Pos stand = plan.standFor(index);
            if (stand.equals(plan.stepFeet(layer))) {
                assertTrue(adjacent(stand, cell.feet()));
            } else {
                assertEquals(cell.feet().y(), stand.y());
                assertTrue(adjacent(stand, cell.feet()));
                assertTrue(dug.contains(key(stand)), "stand not dug yet " + stand);
                if (layer > 0) {
                    Pos head = stand.offset(0, 1, 0);
                    assertFalse(head.equals(plan.step(layer - 1)), "no head room at " + stand);
                }
            }
        }
    }

    @Test
    void stepsSpiralDownOneBlockAtATime() {
        QuarryPlan plan = new QuarryPlan(new Pos(0, 64, 0), 7, 30);
        for (int layer = 1; layer < 30; layer++) {
            Pos upper = plan.step(layer - 1);
            Pos lower = plan.step(layer);
            assertEquals(upper.y() - 1, lower.y());
            assertTrue(adjacent(upper, lower));
        }
        Pos entrance = plan.entrance();
        assertTrue(adjacent(entrance, plan.step(0)));
        assertEquals(plan.stepFeet(0).y(), entrance.y());
        List<Pos> route = plan.routeTo(plan.size() - 1);
        assertEquals(entrance, route.getFirst());
        assertEquals(plan.standFor(plan.size() - 1), route.getLast());
    }

    @Test
    void ladderColumnNeverCollidesWithStepsOrWorkingCells() {
        int[][] shapes = {{7, 6}, {7, 23}, {7, 24}, {7, 40}, {7, 50}, {5, 30}, {4, 20}};
        for (int[] shape : shapes) {
            QuarryPlan plan = new QuarryPlan(new Pos(100, 70, 200), shape[0], shape[1]);
            Set<String> steps = new HashSet<>();
            for (int layer = 0; layer < plan.layers(); layer++) {
                steps.add(key(plan.step(layer)));
            }
            List<Pos> all = plan.ladderCells(plan.layers());
            assertEquals(plan.ladderLayers(), all.size());
            Pos exit = plan.ladderExit();
            for (int layer = 0; layer < plan.ladderLayers(); layer++) {
                Pos cell = plan.ladderCell(layer);
                assertEquals(plan.layerY(layer), cell.y());
                assertEquals(100, cell.x(), "ladder must hug the west wall");
                assertTrue(plan.inside(cell));
                assertFalse(steps.contains(key(cell)), "ladder on a step block at layer " + layer);
                assertEquals(cell.z(), plan.ladderWall(layer).z());
                assertEquals(99, plan.ladderWall(layer).x());
                assertFalse(plan.inside(plan.ladderWall(layer)));
                assertEquals(exit.z(), cell.z());
                assertEquals(99, exit.x());
                assertFalse(exit.equals(plan.entrance()));
                assertTrue(adjacent(exit, plan.entrance()));
                assertTrue(layer == 0 || plan.ladderCell(layer - 1).y() == cell.y() + 1);
            }
            for (int index = 0; index < plan.size(); index++) {
                int layer = plan.layerOf(index);
                Set<String> ladders = new HashSet<>();
                for (Pos cell : plan.ladderCells(layer)) {
                    ladders.add(key(cell));
                }
                Pos stand = plan.standFor(index);
                assertFalse(ladders.contains(key(stand)), "stand on ladder " + stand);
                assertFalse(ladders.contains(key(stand.offset(0, 1, 0))), "head in ladder " + stand);
                assertFalse(ladders.contains(key(plan.cell(index).feet())), "digging a ladder cell");
                assertFalse(ladders.contains(key(plan.stepFeet(layer))));
                assertFalse(ladders.contains(key(plan.stepFeet(layer).offset(0, 1, 0))));
                if (layer > 0) {
                    assertFalse(ladders.contains(key(plan.stepFeet(layer - 1))));
                    assertFalse(ladders.contains(key(plan.stepFeet(layer - 1).offset(0, 1, 0))));
                }
            }
            for (int index = 0; index < plan.size(); index += 7) {
                for (Pos waypoint : plan.routeTo(index)) {
                    for (Pos cell : plan.ladderCells(plan.layerOf(index))) {
                        assertFalse(waypoint.equals(cell) || waypoint.offset(0, 1, 0).equals(cell), "route through ladder " + waypoint);
                    }
                }
            }
        }
    }

    @Test
    void ladderIsOnlyOfferedBelowFinishedLayers() {
        QuarryPlan plan = new QuarryPlan(new Pos(0, 64, 0), 7, 40);
        assertTrue(plan.ladderCells(0).isEmpty());
        assertTrue(plan.ladderCells(1).isEmpty());
        assertEquals(1, plan.ladderCells(2).size());
        assertEquals(plan.ladderCell(0), plan.ladderCells(2).getFirst());
        assertEquals(plan.ladderLayers(), plan.ladderCells(40).size());
    }
}
