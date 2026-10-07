package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MineFramesTest {
    @Test
    void framesNeverTouchTunnelCellsOrTheWalkingPath() {
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] direction : directions) {
            MinePlan plan = new MinePlan(new Pos(10, 70, 10), direction[0], direction[1], 12);
            Set<Pos> reserved = MineFrames.reserved(plan);
            List<Fit> frames = MineFrames.frames(plan, plan.size());
            assertFalse(frames.isEmpty());
            Set<Pos> seen = new HashSet<>();
            for (Fit fit : frames) {
                assertFalse(reserved.contains(fit.pos()), "frame in the tunnel: " + fit);
                assertTrue(seen.add(fit.pos()), "duplicate " + fit);
            }
        }
    }

    @Test
    void framesTrailTheDiggerByAFewCells() {
        MinePlan plan = new MinePlan(new Pos(0, 64, 0), 1, 0, 10);
        assertTrue(MineFrames.frames(plan, plan.depth() + MineFrames.LAG).isEmpty());
        int count = 0;
        for (int progress = 0; progress <= plan.size(); progress++) {
            int now = MineFrames.frames(plan, progress).size();
            assertTrue(now >= count);
            count = now;
        }
        Set<Pos> dug = new HashSet<>();
        int progress = plan.depth() + 40;
        for (int i = 0; i < progress; i++) {
            MinePlan.Cell cell = plan.cell(i);
            for (int h = 0; h < cell.height(); h++) {
                dug.add(cell.feet().above(h));
            }
        }
        for (Fit fit : MineFrames.frames(plan, progress)) {
            boolean nearDug = false;
            for (Pos cell : dug) {
                if (cell.distManhattan(fit.pos()) <= 3) {
                    nearDug = true;
                    break;
                }
            }
            assertTrue(nearDug, "frame far from dug cells " + fit);
        }
    }

    @Test
    void junctionRowsGetNoFrame() {
        MinePlan plan = new MinePlan(new Pos(0, 64, 0), 1, 0, 8);
        Pos bottom = plan.bottom();
        for (Fit fit : MineFrames.frames(plan, plan.size())) {
            assertFalse(fit.part() == Fit.Part.LOG_Y && fit.pos().z() == bottom.z() && fit.pos().y() == bottom.y(),
                    "post inside the main tunnel " + fit);
        }
    }
}
