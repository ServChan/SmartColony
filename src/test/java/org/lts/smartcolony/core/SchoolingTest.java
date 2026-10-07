package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SchoolingTest {
    private static int level(int xp) {
        return Math.min(5, (int) Math.floor(Math.sqrt(xp / 12.0)));
    }

    @Test
    void schoolFollowsTheKindergartenOrComesWithAGrownColony() {
        assertFalse(Schooling.needed(5, 10, false, false));
        assertTrue(Schooling.needed(3, 10, true, false));
        assertFalse(Schooling.needed(2, 10, true, false));
        assertTrue(Schooling.needed(0, 18, false, false));
        assertFalse(Schooling.needed(6, 30, true, true));
    }

    @Test
    void onlyTheOlderHalfOfChildhoodGoesToSchool() {
        assertFalse(Schooling.schoolAge(-24000));
        assertFalse(Schooling.schoolAge(-12001));
        assertTrue(Schooling.schoolAge(-12000));
        assertTrue(Schooling.schoolAge(-1));
        assertFalse(Schooling.schoolAge(0));
    }

    @Test
    void subjectIsTheMostNeededProfessionAndPupilsSpreadOut() {
        EnumMap<Role, Integer> desired = new EnumMap<>(Role.class);
        desired.put(Role.FARMER, 4);
        desired.put(Role.GUARD, 3);
        desired.put(Role.MINER, 1);
        EnumMap<Role, Integer> current = new EnumMap<>(Role.class);
        current.put(Role.FARMER, 3);
        current.put(Role.GUARD, 1);
        EnumMap<Role, Integer> enrolled = new EnumMap<>(Role.class);
        assertEquals(Role.GUARD, Schooling.pickSubject(desired, current, enrolled));
        enrolled.put(Role.GUARD, 2);
        Role next = Schooling.pickSubject(desired, current, enrolled);
        assertTrue(next == Role.MINER || next == Role.FARMER, next.name());
        assertFalse(Schooling.SUBJECTS.contains(Role.TEACHER));
        assertFalse(Schooling.SUBJECTS.contains(Role.IDLE));
    }

    @Test
    void subjectStaysWhileTheColonyStillWantsIt() {
        Map<Role, Integer> desired = Map.of(Role.FARMER, 2);
        assertTrue(Schooling.keepSubject(Role.FARMER, desired));
        assertFalse(Schooling.keepSubject(Role.GUARD, desired));
        assertFalse(Schooling.keepSubject(null, desired));
        assertFalse(Schooling.keepSubject(Role.TEACHER, Map.of(Role.TEACHER, 1)));
    }

    @Test
    void teacherSpeedsLessonsUp() {
        assertEquals(1, Schooling.points(false, -1));
        assertEquals(2, Schooling.points(true, 0));
        assertEquals(4, Schooling.points(true, 5));
        assertTrue(Schooling.points(true, 3) > Schooling.points(false, 0));
    }

    @Test
    void graduatesStartWithALevelButNeverMoreThanThree() {
        assertEquals(0, Schooling.graduateXp(0));
        assertEquals(25, Schooling.graduateXp(100));
        assertEquals(Schooling.MAX_XP, Schooling.graduateXp(100000));
        assertEquals(3, level(Schooling.MAX_XP));
        assertEquals(0, level(Schooling.graduateXp(40)));
        assertEquals(2, level(Schooling.graduateXp(200)));
    }

    @Test
    void graduateRoleNeedsEnoughStudyAndTakesTheMostStudied() {
        Map<String, Integer> study = new LinkedHashMap<>();
        assertNull(Schooling.graduateRole(study));
        study.put("farmer", 30);
        assertNull(Schooling.graduateRole(study));
        study.put("guard", 90);
        study.put("miner", 50);
        assertEquals(Role.GUARD, Schooling.graduateRole(study));
        assertEquals(170, Schooling.totalPoints(study));
        study.put("teacher", 500);
        assertEquals(Role.GUARD, Schooling.graduateRole(study));
    }

    @Test
    void librariansAreTeachers() {
        assertEquals(Role.TEACHER, Role.affinity("librarian"));
        assertEquals(Role.MEDIC, Role.affinity("cleric"));
        assertEquals(Role.BUILDER, Role.affinity("cartographer"));
        assertEquals(Role.TEACHER, Role.byKey("teacher"));
    }

    @Test
    void oneTeacherIsPlannedOnlyWhenChildrenAreAround() {
        Set<Role> gear = Set.of(Role.MINER, Role.FISHERMAN, Role.GUARD);
        RoleAllocator.Needs without = new RoleAllocator.Needs(20, 300, 300, 10, 500, 0, 0, true, gear, 0, false, false, false);
        RoleAllocator.Needs with = new RoleAllocator.Needs(20, 300, 300, 10, 500, 0, 0, true, gear, 0, false, false, false, true);
        assertEquals(0, RoleAllocator.desired(without).get(Role.TEACHER));
        assertEquals(1, RoleAllocator.desired(with).get(Role.TEACHER));
        int total = RoleAllocator.desired(with).values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(20, total);
        RoleAllocator.Needs tiny = new RoleAllocator.Needs(4, 300, 300, 10, 500, 0, 0, true, gear, 0, false, false, false, true);
        assertEquals(0, RoleAllocator.desired(tiny).get(Role.TEACHER));
    }

    @Test
    void studyFieldsHaveDefaultsForOldSaves() {
        ColonyData data = new ColonyData();
        data.members.put("a", new ColonyData.Member());
        ColonyData restored = ColonyCodec.read(ColonyCodec.write(List.of(data))).getFirst();
        assertTrue(restored.members.get("a").study.isEmpty());
        assertNull(restored.members.get("a").graduateRole());
        String old = "{\"schemaVersion\":1,\"colonies\":[{\"id\":\"x\",\"members\":{\"b\":{\"role\":\"guard\",\"xp\":{\"guard\":5}}}}]}";
        ColonyData.Member legacy = ColonyCodec.read(old).getFirst().members.get("b");
        assertTrue(legacy.study.isEmpty());
        assertEquals("", legacy.subject);
        assertNull(legacy.graduateRole());
        legacy.graduate = "farmer";
        assertEquals(Role.FARMER, legacy.graduateRole());
    }

    @Test
    void schoolBlueprintKeepsItsSpotsFree() {
        Blueprint school = Blueprints.get(Schooling.ID);
        assertEquals("school", school.kind());
        assertEquals(0, school.beds());
        assertEquals(0, school.doorZ());
        for (int[] spot : new int[][] {{Schooling.CLASS_X, Schooling.CLASS_Z}, {Schooling.TEACHER_X, Schooling.TEACHER_Z}}) {
            assertEquals(Blueprint.AIR, school.at(spot[0], 1, spot[1]));
            assertEquals(Blueprint.AIR, school.at(spot[0], 2, spot[1]));
            assertEquals('P', school.at(spot[0], 0, spot[1]));
        }
        assertTrue(school.cells("oak").stream().anyMatch(c -> c.state().endsWith("_slab[type=bottom]")));
        assertTrue(school.materials("oak").getOrDefault("minecraft:cobblestone", 0) > 0);
    }
}
