package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QuarryFitTest {
    private static Set<Pos> forbidden(QuarryPlan plan, int completed) {
        Set<Pos> cells = new HashSet<>();
        for (int index = completed * plan.perLayer(); index < plan.size(); index++) {
            MinePlan.Cell cell = plan.cell(index);
            for (int h = 0; h < cell.height(); h++) {
                cells.add(cell.feet().above(h));
            }
            Pos stand = plan.standFor(index);
            cells.add(stand);
            cells.add(stand.above(1));
        }
        for (int layer = 0; layer < plan.layers(); layer++) {
            cells.add(plan.stepFeet(layer));
            cells.add(plan.stepFeet(layer).above(1));
        }
        for (int layer = 0; layer < plan.ladderLayers(); layer++) {
            cells.add(plan.ladderCell(layer));
        }
        cells.add(plan.entrance());
        cells.add(plan.entrance().above(1));
        return cells;
    }

    private static boolean replacesStub(Fit fit) {
        return fit.part() == Fit.Part.STAIR || fit.part() == Fit.Part.LANDING;
    }

    @Test
    void nothingBlocksCellsThatAreStillToBeDugOrWalked() {
        QuarryPlan plan = new QuarryPlan(new Pos(100, 70, 200), 7, 50);
        for (int completed = 0; completed <= plan.layers(); completed++) {
            Set<Pos> blocked = forbidden(plan, completed);
            for (Fit fit : QuarryFit.pit(plan, completed)) {
                assertFalse(blocked.contains(fit.pos()), fit + " blocks the way with " + completed + " layers done");
            }
        }
    }

    @Test
    void onlyStairsReplaceStubsAndTheLadderStaysFree() {
        QuarryPlan plan = new QuarryPlan(new Pos(0, 64, 0), 7, 50);
        Set<Pos> ladder = new HashSet<>();
        for (int layer = 0; layer < plan.ladderLayers(); layer++) {
            ladder.add(plan.ladderCell(layer));
        }
        Set<Pos> stubs = new HashSet<>();
        for (int layer = 0; layer < plan.layers(); layer++) {
            stubs.add(plan.step(layer));
        }
        for (Fit fit : QuarryFit.pit(plan, plan.layers())) {
            assertFalse(ladder.contains(fit.pos()), "ladder cell used: " + fit);
            assertEquals(replacesStub(fit), stubs.contains(fit.pos()), "only stairs may replace a stub: " + fit);
        }
    }

    @Test
    void stairsClimbToThePreviousStepAndLandingsRepeat() {
        QuarryPlan plan = new QuarryPlan(new Pos(0, 64, 0), 7, 30);
        List<Fit> fits = QuarryFit.pit(plan, 30);
        int stairs = 0;
        int landings = 0;
        for (Fit fit : fits) {
            if (!replacesStub(fit)) {
                continue;
            }
            int layer = plan.corner().y() - fit.pos().y();
            if (fit.part() == Fit.Part.LANDING) {
                landings++;
                assertTrue(QuarryFit.landing(layer));
                continue;
            }
            stairs++;
            Pos ahead = fit.pos().offset(fit.dir().dx(), 1, fit.dir().dz());
            assertEquals(plan.step(layer - 1), ahead, "stair at layer " + layer + " must climb to the previous step");
        }
        assertEquals(30, stairs + landings);
        assertEquals(8, landings);
    }

    @Test
    void fittingsGrowMonotonicallyWithoutDuplicates() {
        QuarryPlan plan = new QuarryPlan(new Pos(0, 64, 0), 7, 40);
        Set<Pos> before = new HashSet<>();
        for (int completed = 0; completed <= plan.layers(); completed++) {
            List<Fit> fits = QuarryFit.pit(plan, completed);
            Set<Pos> now = new HashSet<>();
            fits.forEach(fit -> now.add(fit.pos()));
            assertEquals(fits.size(), now.size(), "duplicate positions");
            assertTrue(now.containsAll(before), "a fitting vanished at " + completed);
            before = now;
        }
        assertTrue(QuarryFit.pit(plan, 0).isEmpty());
        assertTrue(QuarryFit.pit(plan, 1).stream().allMatch(QuarryFitTest::replacesStub));
    }

    @Test
    void railsAndPillarsStayInsideTheInnerFrame() {
        QuarryPlan plan = new QuarryPlan(new Pos(10, 70, 10), 7, 40);
        for (Fit fit : QuarryFit.pit(plan, 40)) {
            if (replacesStub(fit)) {
                continue;
            }
            int lx = fit.pos().x() - 10;
            int lz = fit.pos().z() - 10;
            assertTrue(lx >= 1 && lx <= 5 && lz >= 1 && lz <= 5, "outside the inner ring: " + fit);
        }
    }

    @Test
    void beamLevelsAreFourLayersApart() {
        QuarryPlan plan = new QuarryPlan(new Pos(0, 64, 0), 7, 40);
        Set<Integer> beamLayers = new HashSet<>();
        for (Fit fit : QuarryFit.pit(plan, 40)) {
            if (fit.part() == Fit.Part.LOG_X || fit.part() == Fit.Part.LOG_Z) {
                beamLayers.add(64 - fit.pos().y());
            }
        }
        for (int layer : beamLayers) {
            assertTrue(QuarryFit.beamLayer(layer), "beam off the grid at layer " + layer);
        }
        assertTrue(beamLayers.contains(3) && beamLayers.contains(7));
    }
}
