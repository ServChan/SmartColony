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
}
