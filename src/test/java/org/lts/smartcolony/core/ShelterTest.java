package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ShelterTest {
    @Test
    void rainSendsOutdoorWorkersInButNotMinersOrGuards() {
        assertTrue(Shelter.wants(Role.FARMER, false, false, false));
        assertTrue(Shelter.wants(Role.IDLE, false, false, false));
        assertTrue(Shelter.wants(Role.BUILDER, false, false, false));
        assertFalse(Shelter.wants(Role.MINER, false, false, false));
        assertFalse(Shelter.wants(Role.GUARD, false, false, false));
        assertFalse(Shelter.wants(Role.MEDIC, false, false, false));
        assertFalse(Shelter.wants(Role.FARMER, false, false, true));
    }

    @Test
    void thunderSendsEveryoneInExceptGuardsOnAlarm() {
        assertTrue(Shelter.wants(Role.FARMER, true, false, true));
        assertTrue(Shelter.wants(Role.GUARD, true, false, false));
        assertFalse(Shelter.wants(Role.GUARD, true, true, false));
    }

    @Test
    void workRatioSplitsVillagersByHash() {
        assertFalse(Shelter.keepsWorking(12345, 0.0));
        assertTrue(Shelter.keepsWorking(12345, 1.0));
        int keeping = 0;
        for (int i = 0; i < 1000; i++) {
            if (Shelter.keepsWorking(i * 6553 + 7, 0.3)) {
                keeping++;
            }
        }
        assertTrue(keeping > 200 && keeping < 400, "keeping " + keeping);
    }

    @Test
    void crowdedSpotsLoseToSlightlyFartherEmptyOnes() {
        List<Shelter.Spot> spots = List.of(
                new Shelter.Spot(2, 64, 0, 3),
                new Shelter.Spot(8, 64, 0, 0),
                new Shelter.Spot(40, 64, 0, 0));
        assertEquals(1, Shelter.pick(spots, 0, 64, 0, 24, 3.0, spot -> false));
        assertEquals(0, Shelter.pick(spots, 0, 64, 0, 24, 0.0, spot -> false));
        assertEquals(-1, Shelter.pick(spots, 100, 64, 0, 24, 3.0, spot -> false));
        assertEquals(0, Shelter.pick(spots, 0, 64, 0, 24, 3.0, spot -> spot.x() == 8));
    }
}
