package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GraveyardLogicTest {
    @Test
    void causesAreClassified() {
        assertEquals("lava", DeathCause.classify("lava", false, false));
        assertEquals("fire", DeathCause.classify("in_fire", false, false));
        assertEquals("void", DeathCause.classify("out_of_world", false, false));
        assertEquals("arrow", DeathCause.classify("mob", true, true));
        assertEquals("mob", DeathCause.classify("mob", true, false));
        assertEquals("explosion", DeathCause.classify("explosion.player", true, false));
        assertEquals("fall", DeathCause.classify("fall", false, false));
        assertEquals("drown", DeathCause.classify("drown", false, false));
        assertEquals("other", DeathCause.classify("somethingNew", false, false));
        assertEquals("other", DeathCause.classify(null, false, false));
    }

    @Test
    void itemsAreLostOnlyInLavaFireAndVoid() {
        assertTrue(DeathCause.itemsLost("lava"));
        assertTrue(DeathCause.itemsLost("void"));
        assertTrue(DeathCause.itemsLost("fire"));
        assertFalse(DeathCause.itemsLost("mob"));
        assertFalse(DeathCause.itemsLost("fall"));
    }

    @Test
    void graveNamesAreStableAndVaried() {
        assertEquals(GraveNames.of("abc"), GraveNames.of("abc"));
        Set<String> names = new HashSet<>();
        for (int i = 0; i < 60; i++) {
            String name = GraveNames.of("villager-" + i);
            assertTrue(name.length() >= 3 && name.length() <= 12);
            names.add(name);
        }
        assertTrue(names.size() > 15);
        assertNotEquals("", GraveNames.of(null));
    }

    @Test
    void slotsFitTheBlueprintAndLeaveSpaceAroundThem() {
        Blueprint blueprint = Blueprints.get(Cemetery.ID);
        assertEquals(Cemetery.WIDTH, blueprint.sizeX());
        assertEquals(Cemetery.DEPTH, blueprint.sizeZ());
        Set<Integer> seen = new HashSet<>();
        assertEquals(Cemetery.capacity(), Cemetery.slots().size());
        for (Cemetery.Slot slot : Cemetery.slots()) {
            assertTrue(seen.add(slot.x() * 100 + slot.z()));
            assertEquals(Blueprint.AIR, blueprint.at(slot.x(), 1, slot.z()));
            assertEquals(Blueprint.AIR, blueprint.at(slot.x(), 1, slot.z() + 1));
            assertEquals(Blueprint.AIR, blueprint.at(slot.x(), 1, slot.z() + 2));
            assertEquals(Blueprint.ANY, blueprint.at(slot.x(), 0, slot.z() + 1));
            assertEquals(Blueprint.ANY, blueprint.at(slot.x(), 0, slot.z() + 2));
            assertEquals(Blueprint.AIR, blueprint.at(slot.standX(), 1, slot.standZ()));
            assertEquals(Blueprint.ANY, blueprint.at(slot.standX(), 0, slot.standZ()));
            assertTrue(Math.abs(slot.standX() - slot.x()) == 1);
        }
    }

    @Test
    void cemeteryHasAnOpenGapAndNoGate() {
        Blueprint blueprint = Blueprints.get(Cemetery.ID);
        assertEquals(Blueprint.AIR, blueprint.at(blueprint.doorX(), 1, 0));
        assertTrue(blueprint.cells("oak").stream().noneMatch(cell -> cell.state().contains("fence_gate")));
        assertTrue(blueprint.materials("oak").containsKey("minecraft:oak_fence"));
    }

    @Test
    void cemeteryIsPlannedAfterTheFirstDeathOrWithManyAdults() {
        assertFalse(Cemetery.needed(5, 0, 0, 0, 0, 0));
        assertTrue(Cemetery.needed(5, 1, 0, 0, 0, 1));
        assertTrue(Cemetery.needed(Cemetery.ADULT_THRESHOLD, 0, 0, 0, 0, 0));
        assertFalse(Cemetery.needed(20, 3, 0, 1, 0, 3));
    }

    @Test
    void fullCemeteryCallsForAnotherOnlyWhenSomeoneIsWaiting() {
        assertTrue(Cemetery.needed(10, 14, 1, 0, 0, 2));
        assertFalse(Cemetery.needed(10, 12, 1, 0, 0, 0));
        assertFalse(Cemetery.needed(10, 5, 1, 0, 4, 1));
        assertFalse(Cemetery.needed(10, 14, 1, 1, 0, 2));
    }

    @Test
    void undertakerIsWantedForRemainsOrBurialWithRoom() {
        assertTrue(Cemetery.undertakerWanted(1, 0, 0));
        assertTrue(Cemetery.undertakerWanted(0, 2, 3));
        assertFalse(Cemetery.undertakerWanted(0, 2, 0));
        assertFalse(Cemetery.undertakerWanted(0, 0, 5));
    }

    @Test
    void undertakerTakesOnePlaceFromTheBiggestWorkforce() {
        EnumMap<Role, Integer> base = new EnumMap<>(Role.class);
        for (Role role : Role.values()) {
            base.put(role, 0);
        }
        base.put(Role.LUMBERJACK, 2);
        base.put(Role.FARMER, 4);
        base.put(Role.GUARD, 2);
        EnumMap<Role, Integer> with = RoleAllocator.withUndertaker(base, 1, 8);
        assertEquals(1, with.get(Role.UNDERTAKER));
        assertEquals(3, with.get(Role.FARMER));
        assertEquals(2, with.get(Role.LUMBERJACK));
        assertEquals(2, with.get(Role.GUARD));
        int total = with.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(8, total);
    }

    @Test
    void noUndertakerWhenNotWantedOrNoOneToSpare() {
        EnumMap<Role, Integer> base = new EnumMap<>(Role.class);
        for (Role role : Role.values()) {
            base.put(role, 0);
        }
        base.put(Role.GUARD, 1);
        base.put(Role.BUILDER, 1);
        assertEquals(0, RoleAllocator.withUndertaker(base, 0, 4).get(Role.UNDERTAKER));
        assertEquals(0, RoleAllocator.withUndertaker(base, 1, 4).get(Role.UNDERTAKER));
        base.put(Role.LUMBERJACK, 1);
        assertEquals(0, RoleAllocator.withUndertaker(base, 1, 1).get(Role.UNDERTAKER));
        assertEquals(1, RoleAllocator.withUndertaker(base, 1, 3).get(Role.UNDERTAKER));
    }

    @Test
    void undertakerRoleKeyRoundTrips() {
        assertEquals(Role.UNDERTAKER, Role.byKey("undertaker"));
    }

    @Test
    void deathRecordsSurviveSerialisation() {
        ColonyData data = new ColonyData();
        data.id = "c";
        ColonyData.Death death = new ColonyData.Death();
        death.id = "abc";
        death.name = "Alic";
        death.cause = "mob";
        death.remains = "pending";
        death.items = 7;
        death.pos = new Pos(1, 2, 3);
        data.deaths.add(death);
        data.graves.add(new Pos(4, 5, 6));
        ColonyData back = ColonyCodec.read(ColonyCodec.write(java.util.List.of(data))).getFirst();
        assertEquals(1, back.deaths.size());
        assertEquals("pending", back.deaths.getFirst().remains);
        assertEquals(new Pos(4, 5, 6), back.graves.getFirst());
        assertEquals(1, back.pendingRemains());
        assertEquals(1, back.unburied());
    }
}
