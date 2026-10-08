package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ClearanceTest {
    @Test
    void outwardFollowsBlueprintRotation() {
        assertArrayEquals(new int[] {0, -1}, Clearance.outward(4, 2, 9, 9, 0));
        assertArrayEquals(new int[] {1, 0}, Clearance.outward(4, 2, 9, 9, 1));
        assertArrayEquals(new int[] {0, 1}, Clearance.outward(4, 2, 9, 9, 2));
        assertArrayEquals(new int[] {-1, 0}, Clearance.outward(4, 2, 9, 9, 3));
    }

    @Test
    void newBuildingMayNotSitInFrontOfADoor() {
        Clearance.Door door = new Clearance.Door(10, 0, 0, -1);
        Clearance.Box inFront = Clearance.Box.of(8, -10, 16, -3);
        Clearance.Box aside = Clearance.Box.of(20, -10, 28, -3);
        assertTrue(Clearance.blocksDoor(inFront, List.of(door)));
        assertFalse(Clearance.blocksDoor(aside, List.of(door)));
    }

    @Test
    void ownDoorMayNotFaceAWall() {
        Clearance.Box footprint = Clearance.Box.of(0, 0, 8, 8);
        Clearance.Door own = new Clearance.Door(4, 1, 0, -1);
        Clearance.Box neighbour = Clearance.Box.of(0, -6, 8, -2);
        Clearance.Box far = Clearance.Box.of(0, -20, 8, -12);
        assertTrue(Clearance.doorObstructed(own, footprint, List.of(neighbour)));
        assertFalse(Clearance.doorObstructed(own, footprint, List.of(far, footprint)));
        assertTrue(Clearance.inCorridor(4, -1, List.of(own)));
        assertFalse(Clearance.inCorridor(7, -1, List.of(own)));
    }
}
