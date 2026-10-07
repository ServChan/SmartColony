package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class PorterPlanTest {
    private static EnumMap<Role, Integer> roles(int lumberjack, int farmer, int miner, int fisherman, int herder) {
        EnumMap<Role, Integer> map = new EnumMap<>(Role.class);
        for (Role role : Role.values()) {
            map.put(role, 0);
        }
        map.put(Role.LUMBERJACK, lumberjack);
        map.put(Role.FARMER, farmer);
        map.put(Role.MINER, miner);
        map.put(Role.FISHERMAN, fisherman);
        map.put(Role.HERDER, herder);
        map.put(Role.GUARD, 2);
        map.put(Role.BUILDER, 1);
        return map;
    }

    @Test
    void shortWalksNeverNeedAnOutpost() {
        assertFalse(PorterPlan.longWalk(8, 30, 9));
        assertFalse(PorterPlan.longWalk(20, 0, 0));
        assertTrue(PorterPlan.longWalk(30, 0, 0));
    }

    @Test
    void steepHillsCountAsLongerWalks() {
        assertFalse(PorterPlan.longWalk(18, 2, 0));
        assertTrue(PorterPlan.longWalk(18, 6, 0));
        assertTrue(PorterPlan.longWalk(18, -6, 0));
    }

    @Test
    void repeatedPathTroubleLowersTheThreshold() {
        assertFalse(PorterPlan.longWalk(16, 0, 2));
        assertTrue(PorterPlan.longWalk(16, 0, PorterPlan.TROUBLE_LIMIT));
        assertFalse(PorterPlan.longWalk(12, 0, PorterPlan.TROUBLE_LIMIT));
    }

    @Test
    void forestNeedsEnoughFarSamples() {
        assertFalse(PorterPlan.forestFar(3, 80));
        assertFalse(PorterPlan.forestFar(20, 30));
        assertTrue(PorterPlan.forestFar(PorterPlan.FOREST_SAMPLES, 55));
    }

    @Test
    void forestAnchorSitsAtTheNearEdgeOnTheLineToTheTrees() {
        Pos depot = new Pos(100, 64, 100);
        Pos anchor = PorterPlan.forestAnchor(depot, 6 * 60.0, 6 * 80.0, 6);
        assertEquals(100 + 54, anchor.x());
        assertEquals(100 + 72, anchor.z());
        assertEquals(depot, PorterPlan.forestAnchor(depot, 0, 0, 0));
        Pos close = PorterPlan.forestAnchor(depot, 10.0, 0, 1);
        assertEquals(100 + 18, close.x());
    }

    @Test
    void workersChooseTheNearerOutpostOnlyWhenItSavesRealWalking() {
        List<PorterPlan.Candidate> candidates = List.of(
                new PorterPlan.Candidate(10, 64, 0, 20, true),
                new PorterPlan.Candidate(3, 64, 0, 20, true));
        assertEquals(1, PorterPlan.pickDrop(0, 64, 0, 40, 64, 0, candidates));
        assertEquals(-1, PorterPlan.pickDrop(30, 64, 0, 40, 64, 0, List.of(new PorterPlan.Candidate(26, 64, 0, 20, true))));
    }

    @Test
    void fullOrBlockedOutpostsAreSkipped() {
        List<PorterPlan.Candidate> candidates = List.of(
                new PorterPlan.Candidate(2, 64, 0, 1, true),
                new PorterPlan.Candidate(3, 64, 0, 20, false),
                new PorterPlan.Candidate(6, 64, 0, 10, true));
        assertEquals(2, PorterPlan.pickDrop(0, 64, 0, 60, 64, 0, candidates));
        assertEquals(-1, PorterPlan.pickDrop(0, 64, 0, 60, 64, 0, candidates.subList(0, 2)));
        assertEquals(-1, PorterPlan.pickDrop(0, 64, 0, 60, 64, 0, List.of()));
    }

    @Test
    void heightMakesTheDepotFartherThanItLooks() {
        List<PorterPlan.Candidate> candidates = List.of(new PorterPlan.Candidate(14, 64, 0, 20, true));
        assertEquals(-1, PorterPlan.pickDrop(0, 64, 0, 20, 64, 0, candidates));
        assertEquals(0, PorterPlan.pickDrop(0, 64, 0, 20, 40, 0, candidates));
    }

    @Test
    void savedWalkIsTheRoundTripDifference() {
        assertEquals(80, PorterPlan.savedBlocks(50, 10));
        assertEquals(0, PorterPlan.savedBlocks(10, 50));
    }

    @Test
    void haulingWaitsForALoadOrPatience() {
        assertFalse(PorterPlan.worthHauling(0, 100000));
        assertFalse(PorterPlan.worthHauling(5, 100));
        assertTrue(PorterPlan.worthHauling(5, PorterPlan.HAUL_PATIENCE));
        assertTrue(PorterPlan.worthHauling(PorterPlan.HAUL_ITEMS, 0));
    }

    @Test
    void porterDemandFollowsOutpostsAndFarSitesAndIsCapped() {
        assertEquals(0, PorterPlan.portersWanted(0, 0, 30));
        assertEquals(0, PorterPlan.portersWanted(2, 4, 3));
        assertEquals(1, PorterPlan.portersWanted(1, 0, 6));
        assertEquals(2, PorterPlan.portersWanted(1, 2, 20));
        assertEquals(1, PorterPlan.portersWanted(1, 1, 8));
        assertEquals(2, PorterPlan.portersWanted(4, 4, 12));
        assertEquals(1, PorterPlan.portersWanted(0, 1, 6));
    }

    @Test
    void outpostsGoIdleWhenNothingIsInThemAndNothingWasUsedRecently() {
        assertTrue(PorterPlan.busy(3, 100000));
        assertTrue(PorterPlan.busy(0, 100));
        assertFalse(PorterPlan.busy(0, PorterPlan.RECENT_USE));
    }

    @Test
    void settlingTakesPortersFromTheBiggestSpareRoleAndKeepsOneOfEach() {
        EnumMap<Role, Integer> settled = PorterPlan.settle(roles(4, 3, 1, 0, 0), 2);
        assertEquals(2, settled.get(Role.PORTER));
        assertEquals(3, settled.get(Role.LUMBERJACK));
        assertEquals(2, settled.get(Role.FARMER));
        assertEquals(1, settled.get(Role.MINER));
        assertEquals(2, settled.get(Role.GUARD));
        int before = 4 + 3 + 1 + 2 + 1;
        int after = settled.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(before, after);
    }

    @Test
    void settlingStopsWhenNobodyCanSpareAWorker() {
        EnumMap<Role, Integer> settled = PorterPlan.settle(roles(1, 1, 1, 1, 1), 3);
        assertEquals(0, settled.get(Role.PORTER));
        assertEquals(1, settled.get(Role.LUMBERJACK));
    }

    @Test
    void settlingReturnsUnwantedPortersToTheLumberjacks() {
        EnumMap<Role, Integer> base = roles(1, 2, 1, 0, 0);
        base.put(Role.PORTER, 3);
        EnumMap<Role, Integer> settled = PorterPlan.settle(base, 1);
        assertEquals(1, settled.get(Role.PORTER));
        assertEquals(3, settled.get(Role.LUMBERJACK));
        EnumMap<Role, Integer> none = PorterPlan.settle(base, 0);
        assertEquals(0, none.get(Role.PORTER));
        assertEquals(4, none.get(Role.LUMBERJACK));
    }

    @Test
    void quarryOutpostNeverOverlapsThePitOrTheShed() {
        List<int[]> forbidden = PorterPlan.quarryForbidden(100, 100, 12, new Pos(92, 64, 98), 4, 3);
        assertTrue(PorterPlan.overlaps(96, 96, 100, 100, forbidden));
        assertTrue(PorterPlan.overlaps(105, 105, 109, 109, forbidden));
        assertTrue(PorterPlan.overlaps(90, 96, 94, 100, forbidden));
        assertFalse(PorterPlan.overlaps(120, 100, 124, 104, forbidden));
        assertFalse(PorterPlan.overlaps(100, 80, 104, 84, forbidden));
    }

    @Test
    void tunnelOutpostStaysOffTheEntranceAndTheShaft() {
        List<int[]> forbidden = PorterPlan.tunnelForbidden(new Pos(50, 70, 50), 1, 0, 20);
        assertTrue(PorterPlan.overlaps(48, 48, 52, 52, forbidden));
        assertTrue(PorterPlan.overlaps(60, 49, 64, 53, forbidden));
        assertFalse(PorterPlan.overlaps(60, 60, 64, 64, forbidden));
        assertFalse(PorterPlan.overlaps(30, 40, 34, 44, forbidden));
    }

    @Test
    void outpostBlueprintHasBarrelsAnOpenFrontAndFitsTheFootprint() {
        Blueprint blueprint = Blueprints.get(PorterPlan.OUTPOST_ID);
        assertEquals(PorterPlan.OUTPOST_KIND, blueprint.kind());
        assertEquals(5, blueprint.sizeX());
        assertEquals(5, blueprint.sizeZ());
        int barrels = 0;
        for (Blueprint.Cell cell : blueprint.cells("oak")) {
            if (Blueprint.blockId(cell.state()).equals("minecraft:barrel")) {
                barrels++;
            }
        }
        assertEquals(blueprint.containers().size(), barrels);
        assertTrue(barrels >= 2 && barrels <= 4);
        for (int[] container : blueprint.containers()) {
            assertTrue(Blueprint.blockId(blueprint.cells("oak").stream()
                    .filter(c -> c.x() == container[0] && c.y() == container[1] && c.z() == container[2])
                    .findFirst().orElseThrow().state()).equals("minecraft:barrel"));
        }
    }
}
