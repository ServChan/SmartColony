package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntBinaryOperator;
import org.junit.jupiter.api.Test;

class QuarryHeadTest {
    private static final Pos CORNER = new Pos(100, 70, 200);
    private static final Pos SHED_NORTH = new Pos(CORNER.x() - 6, CORNER.y(), CORNER.z() - 4);
    private static final Pos SHED_SOUTH = new Pos(CORNER.x() - 6, CORNER.y(), CORNER.z() + 2);

    private static QuarryPlan plan() {
        return new QuarryPlan(CORNER, 7, 30);
    }

    private static void check(IntBinaryOperator ground, Pos shed) {
        QuarryPlan plan = plan();
        List<Fit> head = QuarryHead.head(plan, ground, shed);
        Set<Pos> unique = new HashSet<>();
        for (Fit fit : head) {
            assertTrue(unique.add(fit.pos()), "duplicate " + fit);
            boolean overPit = fit.pos().x() >= CORNER.x() && fit.pos().x() < CORNER.x() + 7
                    && fit.pos().z() >= CORNER.z() && fit.pos().z() < CORNER.z() + 7;
            if (overPit) {
                assertTrue(fit.pos().y() >= CORNER.y() + 3, "inside the layer 0 clearance: " + fit);
            }
        }
        for (Pos open : new Pos[]{plan.entrance(), plan.ladderExit()}) {
            assertFalse(unique.contains(open), "entrance blocked at " + open);
            assertFalse(unique.contains(open.above(1)), "entrance head room blocked at " + open);
        }
        for (Fit fit : head) {
            if (fit.pos().x() == CORNER.x() - 1 && fit.pos().z() >= CORNER.z() && fit.pos().z() <= CORNER.z() + 1) {
                assertTrue(fit.pos().y() >= plan.entrance().y() + 3, "lintel too low: " + fit);
            }
        }
    }

    @Test
    void flatGroundKeepsEntranceAndClearanceFree() {
        check((x, z) -> CORNER.y(), SHED_NORTH);
        check((x, z) -> CORNER.y(), SHED_SOUTH);
        check((x, z) -> CORNER.y(), null);
    }

    @Test
    void unevenGroundStillKeepsTheWayOpen() {
        check((x, z) -> x == CORNER.x() - 1 && z >= CORNER.z() && z <= CORNER.z() + 1 ? CORNER.y() : CORNER.y() + Math.floorMod(x * 3 + z, 3) - 1, SHED_NORTH);
        check((x, z) -> x == CORNER.x() - 1 && z >= CORNER.z() && z <= CORNER.z() + 1 ? CORNER.y() : CORNER.y() - 2, null);
        check((x, z) -> x == CORNER.x() - 1 && z >= CORNER.z() && z <= CORNER.z() + 1 ? CORNER.y() : CORNER.y() + 2, SHED_SOUTH);
    }

    @Test
    void headHasAFrameAGateAFenceAndAShed() {
        List<Fit> head = QuarryHead.head(plan(), (x, z) -> CORNER.y(), SHED_NORTH);
        Set<Fit.Part> parts = new HashSet<>();
        head.forEach(fit -> parts.add(fit.part()));
        for (Fit.Part part : List.of(Fit.Part.LOG_X, Fit.Part.LOG_Y, Fit.Part.LOG_Z, Fit.Part.FENCE, Fit.Part.CURB, Fit.Part.SLAB,
                Fit.Part.BARREL, Fit.Part.ROPE, Fit.Part.HANG_LAMP, Fit.Part.STAIR_TOP, Fit.Part.PLANK, Fit.Part.LAMP)) {
            assertTrue(parts.contains(part), "missing " + part);
        }
        long rim = head.stream().filter(fit -> fit.part() == Fit.Part.FENCE).count();
        assertTrue(rim >= 14, "rim fence too short: " + rim);
        assertEquals(1, head.stream().filter(fit -> fit.part() == Fit.Part.HANG_LAMP && fit.pos().x() >= CORNER.x()).count());
    }

    @Test
    void shedStaysOffTheEntranceRows() {
        for (Fit fit : QuarryHead.head(plan(), (x, z) -> CORNER.y(), SHED_NORTH)) {
            if (fit.pos().x() < CORNER.x() - 2) {
                assertTrue(fit.pos().z() <= CORNER.z() - 2, "shed crosses the path: " + fit);
            }
        }
    }
}
