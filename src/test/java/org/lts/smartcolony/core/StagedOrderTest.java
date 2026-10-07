package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class StagedOrderTest {
    @Test
    void familyIdMapsToNextStage() {
        Staged.Order none = Staged.order("infirmary", 0, false, false);
        assertTrue(none.ok());
        assertEquals("infirmary_1", none.blueprint());
        assertEquals("infirmary_3", Staged.order("infirmary", 2, false, false).blueprint());
        assertEquals("townhall_2", Staged.order("townhall", 1, false, false).blueprint());
    }

    @Test
    void firstStageIdWorksOnlyWhenNothingStandsYet() {
        assertTrue(Staged.order("infirmary_1", 0, false, false).ok());
        assertEquals(Staged.Failure.WRONG_STAGE, Staged.order("infirmary_1", 1, false, false).failure());
        assertEquals(Staged.Failure.WRONG_STAGE, Staged.order("infirmary_3", 0, false, false).failure());
        assertTrue(Staged.order("infirmary_2", 1, false, false).ok());
    }

    @Test
    void busyAndCompleteAreRejected() {
        assertEquals(Staged.Failure.BUSY, Staged.order("infirmary", 0, true, false).failure());
        assertEquals(Staged.Failure.COMPLETE, Staged.order("infirmary", Hospital.STAGES, false, false).failure());
        assertEquals(Staged.Failure.COMPLETE, Staged.order("townhall", TownHall.STAGES, false, true).failure());
    }

    @Test
    void townHallIronStagesNeedIronAge() {
        assertEquals(Staged.Failure.IRON_AGE, Staged.order("townhall", TownHall.IRON_STAGE - 1, false, false).failure());
        assertTrue(Staged.order("townhall", TownHall.IRON_STAGE - 1, false, true).ok());
        assertTrue(Staged.order("townhall", 1, false, false).ok());
    }

    @Test
    void plainBlueprintsAreNotStaged() {
        assertEquals(Staged.Failure.NOT_STAGED, Staged.order("house_small", 0, false, false).failure());
        assertNull(Staged.familyOf("house_small"));
        assertEquals("townhall", Staged.familyOf("townhall_3"));
        assertEquals("infirmary", Staged.familyOf("infirmary_2"));
    }

    @Test
    void suggestionsListFamiliesInsteadOfStages() {
        List<String> suggestions = Staged.suggestions(Blueprints.IDS);
        assertTrue(suggestions.contains("infirmary"));
        assertTrue(suggestions.contains("townhall"));
        assertTrue(suggestions.contains("house_small"));
        assertFalse(suggestions.contains("infirmary_1"));
        assertFalse(suggestions.contains("townhall_5"));
    }

    private static void assertNull(Object value) {
        org.junit.jupiter.api.Assertions.assertNull(value);
    }
}
