package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KindergartenTest {
    @Test
    void neededByChildrenAndAdults() {
        assertFalse(Kindergarten.needed(1, 20, false));
        assertFalse(Kindergarten.needed(2, 9, false));
        assertTrue(Kindergarten.needed(2, 10, false));
        assertTrue(Kindergarten.needed(3, 4, false));
        assertFalse(Kindergarten.needed(5, 12, true));
    }

    @Test
    void insideIsInclusive() {
        assertTrue(Kindergarten.inside(0, 0, 0, 0, 10, 15));
        assertTrue(Kindergarten.inside(10, 15, 0, 0, 10, 15));
        assertFalse(Kindergarten.inside(11, 5, 0, 0, 10, 15));
        assertFalse(Kindergarten.inside(5, -1, 0, 0, 10, 15));
    }

    @Test
    void unsafeNearLooksAroundTheRim() {
        assertTrue(Kindergarten.unsafeNear(0, 0, 4, (x, z) -> x >= 3 && x <= 20 && z >= 0 && z <= 20));
        assertFalse(Kindergarten.unsafeNear(-5, 0, 4, (x, z) -> x >= 3 && x <= 20 && z >= 0 && z <= 20));
    }

    @Test
    void kindergartenBlueprintHasOpenGateOutsideTheFence() {
        Blueprint blueprint = Blueprints.get(Kindergarten.ID);
        assertEquals("kindergarten", blueprint.kind());
        assertEquals(0, blueprint.beds());
        assertEquals(0, blueprint.doorZ());
    }
}
