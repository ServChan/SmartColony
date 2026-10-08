package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class DecreeTest {
    private static EnumMap<Role, Integer> base() {
        EnumMap<Role, Integer> roles = new EnumMap<>(Role.class);
        roles.put(Role.LUMBERJACK, 4);
        roles.put(Role.FARMER, 3);
        roles.put(Role.MINER, 3);
        roles.put(Role.FISHERMAN, 2);
        roles.put(Role.GUARD, 2);
        roles.put(Role.BUILDER, 2);
        return roles;
    }

    private static int total(java.util.Map<Role, Integer> roles) {
        return roles.values().stream().mapToInt(Integer::intValue).sum();
    }

    @Test
    void focusMovesWorkersWithoutChangingHeadcount() {
        EnumMap<Role, Integer> roles = base();
        EnumMap<Role, Integer> food = Decree.apply(roles, Decree.Focus.FOOD, 16);
        assertEquals(total(roles), total(food));
        assertTrue(food.get(Role.FARMER) + food.get(Role.FISHERMAN) > roles.get(Role.FARMER) + roles.get(Role.FISHERMAN));
        EnumMap<Role, Integer> defense = Decree.apply(roles, Decree.Focus.DEFENSE, 16);
        assertTrue(defense.get(Role.GUARD) > roles.get(Role.GUARD));
        assertTrue(defense.get(Role.LUMBERJACK) >= 1);
    }

    @Test
    void balancedAndTinyColoniesStayUntouched() {
        EnumMap<Role, Integer> roles = base();
        EnumMap<Role, Integer> balanced = Decree.apply(roles, Decree.Focus.BALANCED, 16);
        for (Role role : Role.values()) {
            assertEquals(roles.getOrDefault(role, 0), balanced.get(role), role.key());
        }
        assertEquals(roles.get(Role.GUARD), Decree.apply(roles, Decree.Focus.DEFENSE, 3).get(Role.GUARD));
    }

    @Test
    void zonesCoverCirclesAndBoxes() {
        List<Decree.Zone> zones = List.of(new Decree.Zone(0, 0, 5));
        assertTrue(Decree.inAny(zones, 3, 4, 0));
        assertFalse(Decree.inAny(zones, 4, 4, 0));
        assertTrue(Decree.boxInAny(zones, 5, -2, 12, 2, 0));
        assertFalse(Decree.boxInAny(zones, 7, 7, 12, 12, 1));
        assertEquals(Decree.Focus.FOOD, Decree.Focus.byKey("FOOD"));
        assertEquals(Decree.Focus.BALANCED, Decree.Focus.byKey("nonsense"));
        assertEquals(Decree.MAX_ZONE, Decree.clampRadius(500));
    }
}
