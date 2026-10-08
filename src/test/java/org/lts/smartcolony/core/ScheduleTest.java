package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ScheduleTest {
    private static long at(long day, int hour) {
        return day * 24000L + hour;
    }

    @Test
    void lunchHappensAtMiddayExceptOnFestivalDays() {
        assertEquals(Schedule.Slot.LUNCH, Schedule.slot(at(0, 6000), 1000, 10500, true, true, 7));
        assertEquals(Schedule.Slot.WORK, Schedule.slot(at(0, 3000), 1000, 10500, true, true, 7));
        assertEquals(Schedule.Slot.WORK, Schedule.slot(at(2, 6000), 1000, 10500, true, true, 7));
        assertEquals(Schedule.Slot.WORK, Schedule.slot(at(0, 6000), 1000, 10500, false, true, 7));
    }

    @Test
    void eveningGatheringFollowsTheWorkday() {
        assertEquals(Schedule.Slot.EVENING, Schedule.slot(at(0, 11000), 1000, 10500, true, true, 7));
        assertEquals(Schedule.Slot.OFF, Schedule.slot(at(0, 13000), 1000, 10500, true, true, 7));
        assertEquals(Schedule.Slot.OFF, Schedule.slot(at(0, 11000), 1000, 10500, true, false, 7));
        assertFalse(Schedule.evening(at(0, 12300), 12000, true));
    }

    @Test
    void restDaySplitsIntoFreeMorningAndGatheringAfternoon() {
        assertTrue(Schedule.restDay(at(6, 0), 7));
        assertEquals(Schedule.Slot.OFF, Schedule.slot(at(6, 3000), 1000, 10500, true, true, 7));
        assertEquals(Schedule.Slot.REST, Schedule.slot(at(6, 7000), 1000, 10500, true, true, 7));
        assertFalse(Schedule.restDay(at(6, 0), 0));
        assertFalse(Schedule.restDay(at(20, 0), 7));
    }

    @Test
    void essentialRolesKeepWorkingAndLunchIsAffordable() {
        assertTrue(Schedule.essential(Role.GUARD));
        assertTrue(Schedule.essential(Role.MEDIC));
        assertFalse(Schedule.essential(Role.FARMER));
        assertEquals(3, Schedule.lunchFood(5));
        assertFalse(Schedule.canAffordLunch(100, 10));
        assertTrue(Schedule.canAffordLunch(400, 10));
    }
}
