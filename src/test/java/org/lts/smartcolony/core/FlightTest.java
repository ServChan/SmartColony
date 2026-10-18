package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class FlightTest {
    private static Flight.Spot spot(int x, int z) {
        return new Flight.Spot(x, 64, z, x * 31L + z, 4, false, 0);
    }

    @Test
    void dangerDependsOnKindOfMob() {
        assertTrue(Flight.dangerous(4, true, false, false, false));
        assertFalse(Flight.dangerous(8, true, false, false, false));
        assertTrue(Flight.dangerous(8, true, true, false, false));
        assertFalse(Flight.dangerous(3, false, true, false, false));
        assertTrue(Flight.dangerous(14, true, true, true, false));
        assertFalse(Flight.dangerous(14, true, false, true, false));
        assertTrue(Flight.dangerous(6, true, false, false, true));
    }

    @Test
    void refugeBehindTheZombieLosesToOneAwayFromIt() {
        List<Flight.Spot> spots = List.of(spot(10, 0), spot(-14, 0));
        List<Flight.Threat> threats = List.of(new Flight.Threat(5, 64, 0));
        assertEquals(1, Flight.pick(spots, 0, 64, 0, threats, 64, s -> false));
        assertEquals(0, Flight.pick(spots, 0, 64, 0, List.of(), 64, s -> false));
    }

    @Test
    void roomWithAMonsterAtTheDoorIsSkipped() {
        List<Flight.Spot> spots = List.of(spot(10, 0));
        assertEquals(-1, Flight.pick(spots, 0, 64, 20, List.of(new Flight.Threat(12, 64, 1)), 64, s -> false));
    }

    @Test
    void crowdedRoomsAndHomeShiftTheChoice() {
        Flight.Spot full = new Flight.Spot(5, 64, 0, 1, 2, false, 4);
        Flight.Spot free = new Flight.Spot(-8, 64, 0, 2, 2, false, 0);
        assertEquals(1, Flight.pick(List.of(full, free), 0, 64, 0, List.of(), 64, s -> false));
        Flight.Spot home = new Flight.Spot(-9, 64, 0, 3, 2, true, 0);
        Flight.Spot near = new Flight.Spot(5, 64, 0, 4, 2, false, 0);
        assertEquals(0, Flight.pick(List.of(home, near), 0, 64, 0, List.of(), 64, s -> false));
    }

    @Test
    void fleeingRunsAwayFromThreatsWithAPullHome() {
        double[] away = Flight.away(0, 0, List.of(new Flight.Threat(3, 64, 0)), 0, 20);
        assertTrue(away[0] < -0.9);
        assertTrue(away[1] > 0);
        double[] home = Flight.away(0, 0, List.of(), 0, 20);
        assertEquals(1.0, home[1], 1e-9);
    }

    @Test
    void pathPassingNextToAMonsterIsExposed() {
        List<int[]> nodes = List.of(new int[] {0, 64, 0}, new int[] {1, 64, 0}, new int[] {2, 64, 0});
        assertTrue(Flight.exposed(nodes, List.of(new Flight.Threat(2.5, 64, 1.5))));
        assertFalse(Flight.exposed(nodes, List.of(new Flight.Threat(2.5, 64, 6))));
        assertFalse(Flight.exposed(nodes, List.of(new Flight.Threat(2.5, 70, 0.5))));
    }
}
