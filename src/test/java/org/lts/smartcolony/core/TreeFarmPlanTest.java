package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TreeFarmPlanTest {
    private static final Blueprint FARM = Blueprints.get(TreeFarmPlan.ID);

    private static List<int[]> torches() {
        List<int[]> torches = new ArrayList<>();
        for (Blueprint.Cell cell : FARM.cells("oak")) {
            if (Blueprint.blockId(cell.state()).equals("minecraft:torch")) {
                torches.add(new int[]{cell.x(), cell.z()});
            }
        }
        return torches;
    }

    @Test
    void blueprintIsRegisteredAsTreeFarm() {
        assertEquals(TreeFarmPlan.KIND, FARM.kind());
        assertTrue(Blueprints.IDS.contains(TreeFarmPlan.ID));
        assertEquals(15, FARM.sizeX());
        assertEquals(15, FARM.sizeZ());
    }

    @Test
    void plotsKeepSpacingAndMarginFromTheBorder() {
        List<int[]> plots = TreeFarmPlan.plots(FARM);
        assertEquals(9, plots.size());
        for (int[] plot : plots) {
            assertTrue(plot[0] >= 3 && plot[1] >= 3 && plot[0] <= FARM.sizeX() - 4 && plot[1] <= FARM.sizeZ() - 4);
            assertEquals('p', FARM.at(plot[0], 0, plot[1]));
            assertEquals('.', FARM.at(plot[0], 1, plot[1]));
        }
        for (int i = 0; i < plots.size(); i++) {
            for (int j = i + 1; j < plots.size(); j++) {
                int spacing = Math.max(Math.abs(plots.get(i)[0] - plots.get(j)[0]), Math.abs(plots.get(i)[1] - plots.get(j)[1]));
                assertTrue(spacing >= 4, "plots too close");
            }
        }
    }

    @Test
    void everyPlotIsLitEnoughForSaplings() {
        List<int[]> torches = torches();
        assertFalse(torches.isEmpty());
        for (int[] plot : TreeFarmPlan.plots(FARM)) {
            int nearest = Integer.MAX_VALUE;
            for (int[] torch : torches) {
                nearest = Math.min(nearest, Math.abs(plot[0] - torch[0]) + Math.abs(plot[1] - torch[1]));
            }
            assertTrue(14 - nearest >= 9, "plot " + plot[0] + "," + plot[1] + " gets light " + (14 - nearest));
        }
    }

    @Test
    void borderHasAnOpenGapAndNoGate() {
        assertEquals('.', FARM.at(FARM.doorX(), 1, FARM.doorZ()));
        assertEquals('.', FARM.at(FARM.doorX(), 2, FARM.doorZ()));
        for (Blueprint.Cell cell : FARM.cells("oak")) {
            assertFalse(cell.state().contains("gate"));
        }
        int walls = 0;
        for (int x = 0; x < FARM.sizeX(); x++) {
            if (FARM.at(x, 1, 0) == 'W') {
                walls++;
            }
        }
        assertEquals(FARM.sizeX() - 1, walls);
    }

    @Test
    void floorLayerIsSoilAndPath() {
        for (Blueprint.Cell cell : FARM.cells("oak")) {
            if (cell.y() == 0) {
                String id = Blueprint.blockId(cell.state());
                assertTrue(id.equals("minecraft:dirt") || id.equals("minecraft:dirt_path"), id);
            }
        }
        assertTrue(FARM.materials("oak").getOrDefault("minecraft:dirt", 0) > 100);
    }

    @Test
    void minersKeepDirtOnlyWhileStockAndPackAreLow() {
        assertTrue(TreeFarmPlan.keepDirt(0, 0));
        assertTrue(TreeFarmPlan.keepDirt(63, 95));
        assertFalse(TreeFarmPlan.keepDirt(64, 0));
        assertFalse(TreeFarmPlan.keepDirt(0, 96));
    }

    @Test
    void farmsAreWantedByColonySize() {
        assertEquals(0, TreeFarmPlan.wanted(5, false));
        assertEquals(1, TreeFarmPlan.wanted(6, false));
        assertEquals(1, TreeFarmPlan.wanted(4, true));
        assertEquals(0, TreeFarmPlan.wanted(3, true));
        assertEquals(1, TreeFarmPlan.wanted(24, false));
        assertEquals(2, TreeFarmPlan.wanted(25, false));
        assertFalse(TreeFarmPlan.needed(10, 0, false, false));
        assertFalse(TreeFarmPlan.needed(5, 0, false, true));
        assertTrue(TreeFarmPlan.needed(8, 0, false, true));
        assertTrue(TreeFarmPlan.needed(10, 0, true, false));
        assertFalse(TreeFarmPlan.needed(10, 1, true, false));
        assertTrue(TreeFarmPlan.needed(30, 1, true, false));
        assertFalse(TreeFarmPlan.needed(30, 2, true, true));
        assertTrue(TreeFarmPlan.forestThin(2));
        assertFalse(TreeFarmPlan.forestThin(20));
    }
}
