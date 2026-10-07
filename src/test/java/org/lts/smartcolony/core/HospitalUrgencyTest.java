package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HospitalUrgencyTest {
    private static final int[] CONFIG = {8, 16, 30};

    @Test
    void firstStageIsPlannedForBigColonyWithoutIncident() {
        assertEquals(1, Hospital.next(0, false, 18, 6, false, CONFIG));
        assertFalse(Hospital.urgent(0, false, 18, 6, false, CONFIG));
    }

    @Test
    void woundedVillagersMakeTheFirstStageUrgent() {
        assertTrue(Hospital.urgent(0, false, 18, 6, true, CONFIG));
        assertTrue(Hospital.urgent(0, false, 5, 6, true, CONFIG));
    }

    @Test
    void urgencyNeedsAColonyThatCanBuild() {
        assertFalse(Hospital.urgent(0, false, 3, 6, true, CONFIG));
        assertFalse(Hospital.urgent(0, false, 18, 1, true, CONFIG));
        assertFalse(Hospital.urgent(0, true, 18, 6, true, CONFIG));
        assertFalse(Hospital.urgent(1, false, 18, 6, true, CONFIG));
    }
}
