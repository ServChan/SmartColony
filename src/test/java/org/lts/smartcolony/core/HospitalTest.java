package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HospitalTest {
    private static final int[] DEFAULT = Hospital.DEFAULT_ADULTS;

    @Test
    void stageIdsRoundTrip() {
        for (int stage = 1; stage <= Hospital.STAGES; stage++) {
            assertEquals(stage, Hospital.stageOf(Hospital.blueprint(stage)));
            assertTrue(Blueprints.IDS.contains(Hospital.blueprint(stage)));
            assertTrue(Staged.isStaged(Hospital.blueprint(stage)));
        }
        assertEquals(0, Hospital.stageOf("townhall_1"));
        assertEquals(0, Hospital.stageOf("infirmary_4"));
        assertEquals(0, Hospital.stageOf(null));
        assertTrue(Staged.sameFamily("infirmary_1", "infirmary_3"));
        assertFalse(Staged.sameFamily("infirmary_1", "townhall_1"));
        assertFalse(Staged.sameFamily("townhall_2", "infirmary_2"));
        assertTrue(Staged.sameFamily("townhall_2", "townhall_5"));
    }

    @Test
    void stagesFollowPopulationAndTheFirstIncident() {
        assertEquals(0, Hospital.next(0, false, 7, 5, false, DEFAULT));
        assertEquals(1, Hospital.next(0, false, 8, 5, false, DEFAULT));
        assertEquals(1, Hospital.next(0, false, 4, 5, true, DEFAULT));
        assertEquals(0, Hospital.next(0, false, 3, 5, true, DEFAULT));
        assertEquals(0, Hospital.next(0, true, 40, 5, true, DEFAULT));
        assertEquals(0, Hospital.next(0, false, 40, 1, true, DEFAULT));
        assertEquals(0, Hospital.next(1, false, 15, 5, true, DEFAULT));
        assertEquals(2, Hospital.next(1, false, 16, 5, false, DEFAULT));
        assertEquals(0, Hospital.next(2, false, 29, 5, true, DEFAULT));
        assertEquals(3, Hospital.next(2, false, 30, 5, false, DEFAULT));
        assertEquals(0, Hospital.next(3, false, 100, 5, true, DEFAULT));
    }

    @Test
    void thresholdsAreSanitised() {
        assertEquals(8, Hospital.thresholds(null)[0]);
        int[] sane = Hospital.thresholds(new int[] {20, 5, 0});
        assertEquals(20, sane[0]);
        assertEquals(20, sane[1]);
        assertEquals(20, sane[2]);
    }

    @Test
    void cotsGrowWithTheStage() {
        assertEquals(2, Hospital.cots(1));
        assertEquals(4, Hospital.cots(2));
        assertEquals(6, Hospital.cots(3));
        assertEquals(0, Hospital.cots(0));
    }

    @Test
    void blueprintsHoldTheAnnouncedCotsAndStation() {
        for (int stage = 1; stage <= Hospital.STAGES; stage++) {
            Blueprint blueprint = Blueprints.get(Hospital.blueprint(stage));
            Map<String, String> states = new java.util.HashMap<>();
            for (Blueprint.Cell cell : blueprint.cells("oak")) {
                states.put(cell.x() + "," + cell.y() + "," + cell.z(), cell.state());
            }
            int cots = 0;
            int mats = 0;
            int brewing = 0;
            for (Blueprint.Cell cell : blueprint.cells("oak")) {
                String path = Ids.path(Blueprint.blockId(cell.state()));
                if (path.endsWith("_carpet")) {
                    String below = states.get(cell.x() + "," + (cell.y() - 1) + "," + cell.z());
                    if (below != null && Ids.path(Blueprint.blockId(below)).endsWith("_slab")) {
                        cots++;
                    } else {
                        mats++;
                    }
                }
                if (path.equals("brewing_stand")) {
                    brewing++;
                }
                assertFalse(path.endsWith("_bed"), "cots must not be beds");
            }
            assertEquals(Hospital.cots(stage), cots, "stage " + stage);
            assertEquals(stage == 3 ? 2 : 0, mats, "stage " + stage);
            assertEquals(stage >= 2 ? 1 : 0, brewing, "stage " + stage);
            assertEquals(Hospital.KIND, blueprint.kind());
            assertEquals(0, blueprint.beds());
        }
    }

    @Test
    void stagesShareOneFootprintSoTheyCanUpgradeInPlace() {
        Blueprint first = Blueprints.get("infirmary_1");
        for (int stage = 2; stage <= Hospital.STAGES; stage++) {
            Blueprint next = Blueprints.get(Hospital.blueprint(stage));
            assertEquals(first.sizeX(), next.sizeX());
            assertEquals(first.sizeY(), next.sizeY());
            assertEquals(first.sizeZ(), next.sizeZ());
            assertEquals(first.doorX(), next.doorX());
            assertEquals(first.doorZ(), next.doorZ());
        }
    }

    @Test
    void medicsScaleWithAdultsOnceTheInfirmaryStands() {
        assertEquals(0, Hospital.medicsFor(40, false, 15));
        assertEquals(1, Hospital.medicsFor(3, true, 15));
        assertEquals(1, Hospital.medicsFor(14, true, 15));
        assertEquals(2, Hospital.medicsFor(30, true, 15));
        assertEquals(3, Hospital.medicsFor(45, true, 15));
        assertEquals(0, Hospital.medicsFor(0, true, 15));
    }

    @Test
    void reservingMedicsTakesFromTheLargestWorkforce() {
        EnumMap<Role, Integer> base = new EnumMap<>(Role.class);
        for (Role role : Role.values()) {
            base.put(role, 0);
        }
        base.put(Role.LUMBERJACK, 4);
        base.put(Role.FARMER, 2);
        base.put(Role.GUARD, 2);
        EnumMap<Role, Integer> result = RoleAllocator.reserveMedics(base, 2);
        assertEquals(2, result.get(Role.MEDIC));
        assertEquals(2, result.get(Role.GUARD));
        assertEquals(8, result.values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(base, RoleAllocator.reserveMedics(base, 0));
        assertEquals(0, base.get(Role.MEDIC));
    }

    @Test
    void reservingMedicsLeavesAtLeastOneWorker() {
        EnumMap<Role, Integer> base = new EnumMap<>(Role.class);
        for (Role role : Role.values()) {
            base.put(role, 0);
        }
        base.put(Role.LUMBERJACK, 1);
        EnumMap<Role, Integer> result = RoleAllocator.reserveMedics(base, 3);
        assertEquals(0, result.get(Role.MEDIC));
        assertEquals(1, result.get(Role.LUMBERJACK));
    }

    @Test
    void clericsPreferTheMedicRole() {
        assertEquals(Role.MEDIC, Role.affinity("cleric"));
        assertEquals(Role.MEDIC, Role.byKey("medic"));
    }

    @Test
    void triageSendsTheBadlyHurtImmediately() {
        assertEquals(Hospital.Triage.NONE, Hospital.triage(20, 20, false));
        assertEquals(Hospital.Triage.NONE, Hospital.triage(15, 20, false));
        assertEquals(Hospital.Triage.AFTER_SHIFT, Hospital.triage(11, 20, false));
        assertEquals(Hospital.Triage.NONE, Hospital.triage(11, 20, true));
        assertEquals(Hospital.Triage.NOW, Hospital.triage(5, 20, true));
        assertEquals(Hospital.Triage.NOW, Hospital.triage(5, 20, false));
        assertTrue(Hospital.critical(5, 20));
        assertFalse(Hospital.critical(7, 20));
        assertTrue(Hospital.discharged(18, 20));
        assertFalse(Hospital.discharged(17, 20));
    }

    @Test
    void careFollowsSeverityAndWhatIsInStorage() {
        assertEquals(Hospital.Care.NONE, Hospital.chooseCare(5, 20, 0, 0, 0));
        assertEquals(Hospital.Care.BANDAGE, Hospital.chooseCare(5, 20, 3, 3, 0));
        assertEquals(Hospital.Care.REMEDY, Hospital.chooseCare(10, 20, 3, 3, 0));
        assertEquals(Hospital.Care.REMEDY, Hospital.chooseCare(5, 20, 0, 3, 0));
        assertEquals(Hospital.Care.BANDAGE, Hospital.chooseCare(10, 20, 3, 0, 0));
        assertEquals(Hospital.Care.GOLDEN, Hospital.chooseCare(2, 20, 3, 3, 1));
        assertEquals(Hospital.Care.BANDAGE, Hospital.chooseCare(2, 20, 3, 3, 0));
    }

    @Test
    void bandagesHealFasterThanRemediesAndNothingHealsSlowly() {
        float max = 20.0F;
        assertTrue(Hospital.healAmount(Hospital.Care.BANDAGE, max) > Hospital.healAmount(Hospital.Care.REMEDY, max));
        assertEquals(max, Hospital.healAmount(Hospital.Care.GOLDEN, max));
        assertEquals(0.0F, Hospital.healAmount(Hospital.Care.NONE, max));
        assertTrue(Hospital.regenerationTicks(Hospital.Care.REMEDY) > Hospital.regenerationTicks(Hospital.Care.BANDAGE));
        assertEquals(0.0F, Hospital.restHeal(0));
        assertEquals(0.0F, Hospital.restHeal(180));
        assertEquals(1.0F, Hospital.restHeal(200));
        assertEquals("", Hospital.supplyItem(Hospital.Care.NONE));
        assertEquals(Hospital.BANDAGE, Hospital.supplyItem(Hospital.Care.BANDAGE));
    }

    @Test
    void fieldRescueCoversDownedAndDrowningVillagers() {
        assertTrue(Hospital.wantsFieldRescue(4, 20, 40, 300, 300, false));
        assertFalse(Hospital.wantsFieldRescue(4, 20, 5, 300, 300, false));
        assertFalse(Hospital.wantsFieldRescue(15, 20, 40, 300, 300, false));
        assertTrue(Hospital.wantsFieldRescue(20, 20, 40, 50, 300, true));
        assertFalse(Hospital.wantsFieldRescue(20, 20, 40, 50, 300, false));
    }

    @Test
    void zombiesAreSparedOnlyWhenACureIsPossibleInsideTheWall() {
        assertTrue(Hospital.cureReady(true, 1, false));
        assertTrue(Hospital.cureReady(true, 0, true));
        assertFalse(Hospital.cureReady(true, 0, false));
        assertFalse(Hospital.cureReady(false, 5, true));
        assertTrue(Hospital.sparesZombie(true, 30 * 30, 36));
        assertFalse(Hospital.sparesZombie(true, 40 * 40, 36));
        assertFalse(Hospital.sparesZombie(false, 0, 36));
    }

    @Test
    void suppliesCraftFromThePlainMaterialsTheColonyCanGather() {
        Stock stock = new Stock(Map.of(Ids.mc("sugar_cane"), 6, Ids.mc("poppy"), 2, Ids.mc("oak_planks"), 6,
                Ids.mc("apple"), 1, Ids.mc("gold_ingot"), 8));
        assertTrue(CraftPlanner.plan(stock, Hospital.BANDAGE, 6).isPresent());
        assertFalse(CraftPlanner.plan(stock, Hospital.BANDAGE, 7).isPresent());
        assertTrue(CraftPlanner.plan(stock, Hospital.REMEDY, 2).isPresent());
        assertFalse(CraftPlanner.plan(stock, Hospital.REMEDY, 3).isPresent());
        assertTrue(CraftPlanner.plan(stock, Hospital.GOLDEN, 1).isPresent());
        assertFalse(CraftPlanner.plan(new Stock(), Hospital.REMEDY, 1).isPresent());
        assertNotNull(Ids.SMALL_FLOWERS);
        assertTrue(Ids.isSmallFlower("minecraft:cornflower"));
        assertFalse(Ids.isSmallFlower("minecraft:sunflower"));
    }

    @Test
    void cotsAndWardFurnitureAreCraftable() {
        Stock stock = new Stock(Map.of(Ids.mc("white_wool"), 4, Ids.mc("iron_ingot"), 1, Ids.mc("cobblestone"), 3,
                Ids.mc("oak_planks"), 4));
        assertTrue(CraftPlanner.plan(stock, Ids.mc("white_carpet"), 6).isPresent());
        assertTrue(CraftPlanner.plan(stock, Ids.mc("brewing_stand"), 1).isPresent());
        assertFalse(CraftPlanner.plan(stock, Ids.mc("cauldron"), 1).isPresent());
        assertEquals(Ids.mc("cauldron"), Blueprint.material("minecraft:water_cauldron[level=3]").item());
    }

    @Test
    void supplyTargetsStayInBounds() {
        assertEquals(4, Hospital.bandageTarget(2));
        assertEquals(12, Hospital.bandageTarget(24));
        assertEquals(24, Hospital.bandageTarget(400));
        assertEquals(2, Hospital.remedyTarget(2));
        assertEquals(12, Hospital.remedyTarget(400));
    }
}
