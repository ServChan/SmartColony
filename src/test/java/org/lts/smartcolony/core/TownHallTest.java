package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TownHallTest {
    private static final int[] DEFAULT = TownHall.DEFAULT_ADULTS;

    @Test
    void stageIdsRoundTrip() {
        for (int stage = 1; stage <= TownHall.STAGES; stage++) {
            assertEquals(stage, TownHall.stageOf(TownHall.blueprint(stage)));
            assertTrue(Blueprints.IDS.contains(TownHall.blueprint(stage)));
        }
        assertEquals(0, TownHall.stageOf("house_small"));
        assertEquals(0, TownHall.stageOf("townhall_6"));
        assertEquals(0, TownHall.stageOf("townhall_x"));
        assertEquals(0, TownHall.stageOf(null));
    }

    @Test
    void firstStageNeedsStorageAndPopulationButNotBeds() {
        assertEquals(0, TownHall.next(0, false, 3, true, false, DEFAULT));
        assertEquals(0, TownHall.next(0, false, 8, false, false, DEFAULT));
        assertEquals(1, TownHall.next(0, false, 4, true, false, DEFAULT));
        assertEquals(1, TownHall.next(0, false, 12, true, false, DEFAULT));
    }

    @Test
    void firstStageJumpsTheQueueOnly() {
        assertTrue(TownHall.prioritised(0, false));
        assertFalse(TownHall.prioritised(0, true));
        assertFalse(TownHall.prioritised(1, false));
    }

    @Test
    void stagesFollowOneAnotherByPopulation() {
        assertEquals(0, TownHall.next(1, false, 9, true, false, DEFAULT));
        assertEquals(2, TownHall.next(1, false, 10, true, false, DEFAULT));
        assertEquals(0, TownHall.next(2, false, 17, true, false, DEFAULT));
        assertEquals(3, TownHall.next(2, false, 18, true, false, DEFAULT));
        assertEquals(0, TownHall.next(5, false, 100, true, true, DEFAULT));
    }

    @Test
    void nothingStartsWhileAStageIsInProgress() {
        assertEquals(0, TownHall.next(1, true, 50, true, true, DEFAULT));
    }

    @Test
    void fourthStageNeedsTheIronAge() {
        assertEquals(0, TownHall.next(3, false, 28, true, false, DEFAULT));
        assertEquals(4, TownHall.next(3, false, 28, true, true, DEFAULT));
        assertEquals(0, TownHall.next(4, false, 39, true, true, DEFAULT));
        assertEquals(5, TownHall.next(4, false, 40, true, true, DEFAULT));
    }

    @Test
    void configuredThresholdsAreSanitised() {
        assertEquals(TownHall.DEFAULT_ADULTS[4], TownHall.thresholds(null)[4]);
        int[] fixed = TownHall.thresholds(new int[]{6, 3, 0, 900});
        assertEquals(6, fixed[0]);
        assertEquals(6, fixed[1]);
        assertEquals(6, fixed[2]);
        assertEquals(500, fixed[3]);
        assertEquals(500, fixed[4]);
        assertEquals(6, TownHall.requiredAdults(1, new int[]{6}));
        assertEquals(Integer.MAX_VALUE, TownHall.requiredAdults(6, null));
    }

    @Test
    void benefitsGrowWithTheStage() {
        assertEquals(0, TownHall.projectBonus(0));
        assertEquals(0, TownHall.projectBonus(2));
        assertEquals(1, TownHall.projectBonus(3));
        assertEquals(1, TownHall.projectBonus(4));
        assertEquals(2, TownHall.projectBonus(5));
    }

    @Test
    void highestFinishedStageIsTheColonyStage() {
        ColonyData data = new ColonyData();
        assertEquals(0, TownHall.stage(data));
        ColonyData.Building house = new ColonyData.Building();
        house.blueprint = "house_small";
        data.buildings.add(house);
        ColonyData.Building hall = new ColonyData.Building();
        hall.blueprint = "townhall_3";
        data.buildings.add(hall);
        assertEquals(3, TownHall.stage(data));
        assertFalse(TownHall.inProgress(data));
        ColonyData.Project project = new ColonyData.Project();
        project.building.blueprint = "townhall_4";
        data.projects.add(project);
        assertTrue(TownHall.inProgress(data));
    }

    @Test
    void oldSavesLoadWithoutUpgradeFlag() {
        String json = "{\"schemaVersion\":1,\"colonies\":[{\"id\":\"a\",\"projects\":[{\"phase\":\"clear\"}]}]}";
        ColonyData colony = ColonyCodec.read(json).getFirst();
        assertEquals(1, colony.projects.size());
        assertFalse(colony.projects.getFirst().upgrade);
    }

    @Test
    void everyStageIsAWellFormedBlueprintOnTheSameFootprint() {
        Blueprint first = Blueprints.get("townhall_1");
        for (int stage = 1; stage <= TownHall.STAGES; stage++) {
            Blueprint blueprint = Blueprints.get(TownHall.blueprint(stage));
            assertEquals(TownHall.KIND, blueprint.kind());
            assertEquals(first.sizeX(), blueprint.sizeX());
            assertEquals(first.sizeY(), blueprint.sizeY());
            assertEquals(first.sizeZ(), blueprint.sizeZ());
            assertEquals(first.doorX(), blueprint.doorX());
            assertEquals(first.doorZ(), blueprint.doorZ());
            assertDoor(blueprint);
            assertTrue(blueprint.containers().isEmpty());
        }
    }

    private static Blueprint.Cell cellAt(Blueprint blueprint, int x, int y, int z) {
        for (Blueprint.Cell cell : blueprint.cells("oak")) {
            if (cell.x() == x && cell.y() == y && cell.z() == z) {
                return cell;
            }
        }
        return null;
    }

    private static void assertDoor(Blueprint blueprint) {
        Blueprint.Cell lower = cellAt(blueprint, blueprint.doorX(), 1, blueprint.doorZ());
        Blueprint.Cell upper = cellAt(blueprint, blueprint.doorX(), 2, blueprint.doorZ());
        assertTrue(lower != null && lower.state().contains("_door") && lower.state().contains("half=lower"), blueprint.id());
        assertTrue(upper != null && upper.state().contains("_door") && upper.state().contains("half=upper"), blueprint.id());
        for (int z = 0; z < blueprint.doorZ(); z++) {
            for (int y = 1; y <= 2; y++) {
                Blueprint.Cell cell = cellAt(blueprint, blueprint.doorX(), y, z);
                assertTrue(cell == null || cell.state().contains("torch") || cell.state().contains("lantern"),
                        blueprint.id() + " blocked approach at z " + z);
            }
        }
    }

    @Test
    void floorAreaNeverShrinksBetweenStages() {
        Blueprint previous = null;
        for (int stage = 1; stage <= TownHall.STAGES; stage++) {
            Blueprint blueprint = Blueprints.get(TownHall.blueprint(stage));
            if (previous != null) {
                for (int x = 0; x < blueprint.sizeX(); x++) {
                    for (int z = 0; z < blueprint.sizeZ(); z++) {
                        if (previous.at(x, 0, z) != Blueprint.ANY) {
                            assertTrue(blueprint.at(x, 0, z) != Blueprint.ANY, "floor lost at " + x + "," + z + " stage " + stage);
                        }
                    }
                }
            }
            previous = blueprint;
        }
    }

    @Test
    void everyStageIsCraftableFromRawColonyMaterials() {
        Map<String, Integer> raw = Map.ofEntries(
                Map.entry("minecraft:oak_log", 2000), Map.entry("minecraft:cobblestone", 2000),
                Map.entry("minecraft:sand", 500), Map.entry("minecraft:string", 200),
                Map.entry("minecraft:coal", 400), Map.entry("minecraft:iron_ingot", 40));
        for (int stage = 1; stage <= TownHall.STAGES; stage++) {
            Blueprint blueprint = Blueprints.get(TownHall.blueprint(stage));
            Stock stock = new Stock(raw);
            assertTrue(CraftPlanner.plan(stock, blueprint.materials("oak")).isPresent(), blueprint.id());
        }
    }

    @Test
    void ironLanternsAppearOnlyFromTheIronStage() {
        for (int stage = 1; stage <= TownHall.STAGES; stage++) {
            boolean lanterns = Blueprints.get(TownHall.blueprint(stage)).materials("oak").containsKey("minecraft:lantern");
            assertEquals(stage >= TownHall.IRON_STAGE, lanterns, "stage " + stage);
        }
    }

    @Test
    void stageCostGrowsMonotonically() {
        int previous = 0;
        for (int stage = 1; stage <= TownHall.STAGES; stage++) {
            int total = Blueprints.get(TownHall.blueprint(stage)).materials("oak").values().stream().mapToInt(Integer::intValue).sum();
            assertTrue(total > previous, "stage " + stage);
            previous = total;
        }
    }
}
