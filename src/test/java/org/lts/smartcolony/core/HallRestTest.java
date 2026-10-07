package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class HallRestTest {
    private static final String ROOF = "[\"       \",\"CCCCCCC\",\"CCCCCCC\",\"CCCCCCC\",\"CCCCCCC\",\"CCCCCCC\",\"CCCCCCC\"]";
    private static final String HALL = "{\"id\":\"t\",\"kind\":\"townhall\",\"beds\":0,\"door\":[3,6],"
            + "\"palette\":{\"C\":\"minecraft:oak_planks\",\"X\":\"minecraft:oak_log\",\"S\":\"minecraft:oak_slab[type=bottom]\"},"
            + "\"layers\":["
            + "[\"       \",\"CCCCCCC\",\"CCCCCCC\",\"CCCCCCC\",\"CCCCCCC\",\"CCCCCCC\",\"CCCCCCC\"],"
            + "[\"       \",\"X.....X\",\"X.....X\",\"X..S..X\",\"X.....X\",\"X.....X\",\"XXX.XXX\"],"
            + "[\"       \",\"X.....X\",\"X.....X\",\"X.....X\",\"X.....X\",\"X.....X\",\"XXX.XXX\"],"
            + ROOF + "]}";
    private static final String OPEN = "{\"id\":\"t\",\"kind\":\"townhall\",\"beds\":0,\"door\":[3,6],"
            + "\"palette\":{\"C\":\"minecraft:oak_planks\",\"X\":\"minecraft:oak_log\"},"
            + "\"layers\":["
            + "[\"       \",\"CCCCCCC\",\"CCCCCCC\",\"CCCCCCC\",\"CCCCCCC\",\"CCCCCCC\",\"CCCCCCC\"],"
            + "[\"       \",\"X.....X\",\"X.....X\",\"X.....X\",\"X.....X\",\"X.....X\",\"XXX.XXX\"],"
            + "[\"       \",\"X.....X\",\"X.....X\",\"X.....X\",\"X.....X\",\"X.....X\",\"XXX.XXX\"],"
            + "[\"       \",\"       \",\"       \",\"       \",\"       \",\"       \",\"       \"]]}";

    @Test
    void cellsAreInteriorAirWithFloorAndHeadroom() {
        Blueprint blueprint = Blueprint.parse(HALL);
        List<int[]> cells = HallRest.cells(blueprint);
        assertFalse(cells.isEmpty());
        for (int[] cell : cells) {
            assertEquals(Blueprint.AIR, blueprint.at(cell[0], cell[1], cell[2]));
            assertEquals(Blueprint.AIR, blueprint.at(cell[0], cell[1] + 1, cell[2]));
            assertTrue(blueprint.solidFloor(cell[0], cell[2]));
            assertTrue(Math.abs(cell[0] - 3) > 1 || Math.abs(cell[2] - 6) > 1);
        }
    }

    @Test
    void furnitureAndDoorwayAreSkipped() {
        List<int[]> cells = HallRest.cells(Blueprint.parse(HALL));
        assertTrue(cells.stream().noneMatch(c -> c[0] == 3 && c[2] == 3 && c[1] == 1));
        assertTrue(cells.stream().noneMatch(c -> c[2] >= 5 && c[0] >= 2 && c[0] <= 4));
    }

    @Test
    void cellsWithoutARoofAreSkipped() {
        assertTrue(HallRest.cells(Blueprint.parse(OPEN)).isEmpty());
    }

    @Test
    void groundFloorComesFirst() {
        List<int[]> cells = HallRest.cells(Blueprint.parse(HALL));
        assertEquals(1, cells.getFirst()[1]);
    }

    @Test
    void everyShippedHallBlueprintKeepsAllInvariants() {
        for (int stage = 1; stage <= TownHall.STAGES; stage++) {
            Blueprint blueprint = Blueprints.get(TownHall.blueprint(stage));
            for (int[] cell : HallRest.cells(blueprint)) {
                assertEquals(Blueprint.AIR, blueprint.at(cell[0], cell[1], cell[2]));
                assertEquals(Blueprint.AIR, blueprint.at(cell[0], cell[1] + 1, cell[2]));
                assertTrue(cell[1] >= 1);
            }
        }
    }

    @Test
    void slotsSpreadVillagersAndStayStable() {
        List<String> ids = List.of("c", "a", "b", "d");
        assertEquals(0, HallRest.slot(ids, "a", 3));
        assertEquals(1, HallRest.slot(ids, "b", 3));
        assertEquals(2, HallRest.slot(ids, "c", 3));
        assertEquals(0, HallRest.slot(ids, "d", 3));
        assertEquals(-1, HallRest.slot(ids, "a", 0));
        assertTrue(HallRest.slot(ids, "stranger", 3) >= 0);
    }

    @Test
    void onlyBedlessNonGuardsRestAtNightInAFinishedHall() {
        assertTrue(HallRest.restsHere(true, true, false, false, false));
        assertFalse(HallRest.restsHere(false, true, false, false, false));
        assertFalse(HallRest.restsHere(true, false, false, false, false));
        assertFalse(HallRest.restsHere(true, true, true, false, false));
        assertFalse(HallRest.restsHere(true, true, false, true, false));
        assertFalse(HallRest.restsHere(true, true, false, false, true));
    }

    @Test
    void floorMaterialsAreJudgedByStability() {
        assertTrue(HallRest.stableFloor("minecraft:oak_planks"));
        assertTrue(HallRest.stableFloor("minecraft:stone_bricks"));
        assertFalse(HallRest.stableFloor("minecraft:oak_slab[type=bottom]"));
        assertFalse(HallRest.stableFloor("minecraft:white_carpet"));
        assertFalse(HallRest.stableFloor("minecraft:oak_stairs[facing=north]"));
    }
}
