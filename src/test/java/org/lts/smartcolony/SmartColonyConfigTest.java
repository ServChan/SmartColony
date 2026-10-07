package org.lts.smartcolony;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SmartColonyConfigTest {
    @Test
    void surveySettingsAreClamped() {
        SmartColonyConfig config = new SmartColonyConfig();
        assertEquals(32, config.surveyMargin);
        config.surveyMargin = 500;
        config.surveyColumnsPerTick = 100000;
        config.surveyRefreshPerTick = -4;
        config.sanitize();
        assertEquals(64, config.surveyMargin);
        assertEquals(512, config.surveyColumnsPerTick);
        assertEquals(0, config.surveyRefreshPerTick);
        config.surveyMargin = -3;
        config.surveyColumnsPerTick = 0;
        config.sanitize();
        assertEquals(0, config.surveyMargin);
        assertEquals(8, config.surveyColumnsPerTick);
    }
}
