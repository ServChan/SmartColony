package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RoleDamperTest {
    private static EnumMap<Role, Integer> roles(Object... pairs) {
        EnumMap<Role, Integer> map = new EnumMap<>(Role.class);
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((Role) pairs[i], (Integer) pairs[i + 1]);
        }
        return map;
    }

    @Test
    void firstPlanIsTakenAsIs() {
        RoleDamper damper = new RoleDamper();
        EnumMap<Role, Integer> settled = damper.settle(roles(Role.FARMER, 3, Role.SMITH, 1));
        assertEquals(3, settled.get(Role.FARMER));
        assertEquals(1, settled.get(Role.SMITH));
        assertEquals(0, settled.get(Role.GUARD));
    }

    @Test
    void aSingleFlickerChangesNothing() {
        RoleDamper damper = new RoleDamper();
        damper.settle(roles(Role.FARMER, 4, Role.SMITH, 0));
        assertEquals(0, damper.settle(roles(Role.FARMER, 2, Role.SMITH, 2)).get(Role.SMITH));
        EnumMap<Role, Integer> back = damper.settle(roles(Role.FARMER, 4, Role.SMITH, 0));
        assertEquals(0, back.get(Role.SMITH));
        assertEquals(4, back.get(Role.FARMER));
        assertEquals(0, damper.settle(roles(Role.FARMER, 2, Role.SMITH, 2)).get(Role.SMITH));
    }

    @Test
    void lastingNeedIsRaisedSoonerThanItIsLowered() {
        RoleDamper damper = new RoleDamper();
        damper.settle(roles(Role.FARMER, 4, Role.SMITH, 0));
        EnumMap<Role, Integer> settled = null;
        for (int plan = 0; plan < RoleDamper.RAISE_AFTER; plan++) {
            settled = damper.settle(roles(Role.FARMER, 2, Role.SMITH, 2));
        }
        assertEquals(2, settled.get(Role.SMITH));
        assertEquals(4, settled.get(Role.FARMER));
        for (int plan = RoleDamper.RAISE_AFTER; plan < RoleDamper.LOWER_AFTER; plan++) {
            settled = damper.settle(roles(Role.FARMER, 2, Role.SMITH, 2));
        }
        assertEquals(2, settled.get(Role.FARMER));
    }

    @Test
    void reversingDirectionRestartsTheCount() {
        RoleDamper damper = new RoleDamper();
        damper.settle(roles(Role.GUARD, 4));
        damper.settle(roles(Role.GUARD, 6));
        damper.settle(roles(Role.GUARD, 2));
        assertEquals(4, damper.settle(roles(Role.GUARD, 6)).get(Role.GUARD));
        assertEquals(6, damper.settle(roles(Role.GUARD, 6)).get(Role.GUARD));
    }

    @Test
    void makingMoreAlwaysCraftsEvenWhenTheStockAlreadyHoldsEnough() {
        String bread = Ids.mc("bread");
        String wheat = Ids.mc("wheat");
        Stock stock = new Stock(Map.of(bread, 3, wheat, 9));
        assertTrue(CraftPlanner.plan(stock, bread, 3).orElseThrow().trivial());
        CraftPlanner.Plan more = CraftPlanner.planMore(stock, bread, 3).orElseThrow();
        assertFalse(more.trivial());
        assertEquals(6, more.after().get(bread));
        assertEquals(0, more.after().get(wheat));
        assertFalse(CraftPlanner.planMore(stock, bread, 4).isPresent());
    }
}
