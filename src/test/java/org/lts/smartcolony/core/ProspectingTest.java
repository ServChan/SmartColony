package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProspectingTest {
    @Test
    void onlyUnworkedRichOreCounts() {
        assertTrue(Prospecting.rich(Expedition.ORE, 9, 0, 0));
        assertFalse(Prospecting.rich(Expedition.ORE, 3, 0, 0));
        assertFalse(Prospecting.rich(Expedition.ORE, 9, 1000, 0));
        assertFalse(Prospecting.rich(Expedition.ORE, 9, 0, Prospecting.MAX_DRY));
        assertFalse(Prospecting.rich(Expedition.VILLAGE, 9, 0, 0));
    }

    @Test
    void prospectorsNeedASecondMinerAndADeposit() {
        assertEquals(0, Prospecting.prospectors(1, 3));
        assertEquals(0, Prospecting.prospectors(4, 0));
        assertEquals(1, Prospecting.prospectors(2, 3));
        assertEquals(2, Prospecting.prospectors(5, 3));
        assertEquals(1, Prospecting.prospectors(6, 1));
    }

    @Test
    void twoMinersPerShaftUpToThree() {
        assertEquals(1, Prospecting.mines(0));
        assertEquals(1, Prospecting.mines(2));
        assertEquals(2, Prospecting.mines(3));
        assertEquals(2, Prospecting.mines(4));
        assertEquals(3, Prospecting.mines(6));
        assertEquals(3, Prospecting.mines(9));
    }

    @Test
    void assignmentSpreadsDiggersAndKeepsAtLeastOneDigging() {
        Map<String, Integer> plan = Prospecting.assign(List.of("d", "a", "c", "b", "e"), 2, 1);
        assertEquals(0, plan.get("a"));
        assertEquals(1, plan.get("b"));
        assertEquals(0, plan.get("c"));
        assertEquals(1, plan.get("d"));
        assertEquals(Prospecting.PROSPECTOR, plan.get("e"));
        Map<String, Integer> lone = Prospecting.assign(List.of("a"), 1, 1);
        assertEquals(0, lone.get("a"));
    }

    @Test
    void pickPrefersCloseRichFreeDeposits() {
        Prospecting.Deposit near = new Prospecting.Deposit(new Pos(10, 60, 0), 6, 60);
        Prospecting.Deposit rich = new Prospecting.Deposit(new Pos(80, 60, 0), 30, 90);
        Prospecting.Deposit far = new Prospecting.Deposit(new Pos(500, 60, 0), 40, 500);
        assertEquals(rich, Prospecting.pick(List.of(near, rich, far), List.of()));
        assertEquals(near, Prospecting.pick(List.of(near, rich, far), List.of(rich.pos())));
        assertNull(Prospecting.pick(List.of(far), List.of()));
    }

    @Test
    void mineAnchorIsANearbyDepositNotServedYet() {
        Prospecting.Deposit close = new Prospecting.Deposit(new Pos(70, 60, 0), 12, 70);
        Prospecting.Deposit remote = new Prospecting.Deposit(new Pos(150, 60, 0), 40, 150);
        assertEquals(close, Prospecting.anchor(List.of(close, remote), 32, List.of()));
        assertNull(Prospecting.anchor(List.of(close, remote), 32, List.of(new Pos(75, 60, 5))));
    }

    @Test
    void depositsBorrowAMinerFromSpareHands() {
        EnumMap<Role, Integer> desired = new EnumMap<>(Role.class);
        desired.put(Role.LUMBERJACK, 3);
        desired.put(Role.FARMER, 2);
        desired.put(Role.MINER, 1);
        EnumMap<Role, Integer> settled = Prospecting.settle(desired, 2, 8, true);
        assertEquals(2, settled.get(Role.MINER));
        assertEquals(2, settled.get(Role.LUMBERJACK));
        assertEquals(1, Prospecting.settle(desired, 0, 8, true).get(Role.MINER));
        assertEquals(1, Prospecting.settle(desired, 2, 4, true).get(Role.MINER));
        assertEquals(1, Prospecting.settle(desired, 2, 8, false).get(Role.MINER));
    }
}
