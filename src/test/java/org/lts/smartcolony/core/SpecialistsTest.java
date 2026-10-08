package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import org.junit.jupiter.api.Test;

class SpecialistsTest {
    @Test
    void specialistsComeFromTheBiggestCrewsAndKeepBasics() {
        EnumMap<Role, Integer> roles = new EnumMap<>(Role.class);
        roles.put(Role.LUMBERJACK, 1);
        roles.put(Role.FARMER, 1);
        roles.put(Role.MINER, 5);
        roles.put(Role.FISHERMAN, 3);
        EnumMap<Role, Integer> result = Specialists.settle(roles, new Specialists.Wants(1, 1, 1, 1, 1), 15);
        assertEquals(1, result.get(Role.MASON));
        assertEquals(1, result.get(Role.SCOUT));
        assertEquals(1, result.get(Role.LUMBERJACK));
        assertEquals(1, result.get(Role.FARMER));
        int before = roles.values().stream().mapToInt(Integer::intValue).sum();
        int after = result.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(before, after);
    }

    @Test
    void idleVillagersAreUsedFirstAndBudgetCapsSpecialists() {
        EnumMap<Role, Integer> roles = new EnumMap<>(Role.class);
        roles.put(Role.IDLE, 2);
        roles.put(Role.LUMBERJACK, 2);
        roles.put(Role.FARMER, 2);
        EnumMap<Role, Integer> result = Specialists.settle(roles, new Specialists.Wants(2, 1, 1, 1, 2), 6);
        assertEquals(0, result.get(Role.IDLE));
        assertTrue(result.get(Role.MASON) + result.get(Role.SCOUT) + result.get(Role.BEEKEEPER) + result.get(Role.ALCHEMIST)
                + result.get(Role.SCRIBE) <= 2);
    }

    @Test
    void wantsDependOnBuildingsAndSize() {
        assertEquals(0, Specialists.masons(false, 30));
        assertEquals(2, Specialists.masons(true, 30));
        assertEquals(1, Specialists.beekeepers(false, true, 12));
        assertEquals(0, Specialists.scribes(true, 5));
        assertEquals(2, Specialists.scouts(25, true));
    }
}
