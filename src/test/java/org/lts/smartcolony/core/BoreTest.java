package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class BoreTest {
    private static void assertWalkable(Pos start, List<Bore.Step> steps) {
        Pos previous = start;
        for (Bore.Step step : steps) {
            Pos feet = step.feet();
            if (feet.equals(start)) {
                continue;
            }
            int horizontal = Math.abs(feet.x() - previous.x()) + Math.abs(feet.z() - previous.z());
            assertEquals(1, horizontal, "step must move to a side neighbour: " + previous + " -> " + feet);
            assertTrue(Math.abs(feet.y() - previous.y()) <= 1, "step height: " + previous + " -> " + feet);
            previous = feet;
        }
    }

    @Test
    void alreadyInReachNeedsNoTunnel() {
        List<Bore.Step> steps = Bore.route(new Pos(0, 64, 0), new Pos(1, 65, 0), 10);
        assertTrue(steps.isEmpty());
        assertTrue(Bore.reached(new Pos(0, 64, 0), new Pos(1, 63, 1)));
        assertTrue(!Bore.reached(new Pos(0, 64, 0), new Pos(0, 61, 0)));
    }

    @Test
    void sideOreIsReachedByAStaircaseDown() {
        Pos start = new Pos(0, 70, 0);
        Pos ore = new Pos(8, 64, 0);
        List<Bore.Step> steps = Bore.route(start, ore, Bore.MAX_STEPS);
        assertWalkable(start, steps);
        assertTrue(Bore.reached(steps.getLast().feet(), ore));
        assertTrue(steps.stream().allMatch(step -> step.height() == Bore.HEIGHT));
    }

    @Test
    void oreRightBelowIsReachedByASpiral() {
        Pos start = new Pos(0, 70, 0);
        Pos ore = new Pos(0, 60, 0);
        List<Bore.Step> steps = Bore.route(start, ore, Bore.MAX_STEPS);
        assertWalkable(start, steps);
        assertTrue(Bore.reached(steps.getLast().feet(), ore));
        for (Bore.Step step : steps) {
            assertTrue(step.feet().x() != ore.x() || step.feet().z() != ore.z(), "never stands in the ore column");
        }
    }

    @Test
    void climbingStepsGetExtraHeadroom() {
        Pos start = new Pos(0, 60, 0);
        Pos ore = new Pos(6, 66, 0);
        List<Bore.Step> steps = Bore.route(start, ore, Bore.MAX_STEPS);
        assertWalkable(start, steps);
        assertEquals(start, steps.getFirst().feet());
        assertEquals(Bore.HEIGHT + 1, steps.getFirst().height());
        assertTrue(Bore.reached(steps.getLast().feet(), ore));
    }

    @Test
    void tooFarIsRefused() {
        assertNull(Bore.route(new Pos(0, 64, 0), new Pos(40, 30, 0), Bore.MAX_STEPS));
    }
}
