package org.lts.smartcolony.core;

public final class Schedule {
    public static final int LUNCH_START = 5600;
    public static final int LUNCH_END = 6400;
    public static final int EVENING_END = 12400;
    public static final int MIN_EVENING = 600;
    public static final float FED_BONUS = 0.1F;
    public static final int FESTIVAL_EVERY = 3;
    public static final int REST_AFTERNOON = 6000;

    public enum Slot {
        WORK,
        LUNCH,
        EVENING,
        REST,
        OFF
    }

    private Schedule() {
    }

    public static long day(long time) {
        return Math.floorDiv(time, 24000L);
    }

    public static int hour(long time) {
        return (int) Math.floorMod(time, 24000L);
    }

    public static boolean festivalDay(long time) {
        return day(time) % FESTIVAL_EVERY == FESTIVAL_EVERY - 1;
    }

    public static boolean restDay(long time, int every) {
        if (every < 2) {
            return false;
        }
        long day = day(time);
        return day % every == every - 1 && !festivalDay(time);
    }

    public static boolean lunch(long time, boolean enabled) {
        if (!enabled || festivalDay(time)) {
            return false;
        }
        int hour = hour(time);
        return hour >= LUNCH_START && hour < LUNCH_END;
    }

    public static boolean evening(long time, int workEnd, boolean enabled) {
        if (!enabled) {
            return false;
        }
        int hour = hour(time);
        int start = Math.floorMod(workEnd, 24000);
        if (EVENING_END - start < MIN_EVENING) {
            return false;
        }
        return hour >= start && hour < EVENING_END;
    }

    public static Slot slot(long time, int workStart, int workEnd, boolean lunchBreak, boolean gathering, int restEvery) {
        int hour = hour(time);
        boolean working = workStart <= workEnd ? hour >= workStart && hour < workEnd : hour >= workStart || hour < workEnd;
        if (working && restDay(time, restEvery)) {
            return hour >= REST_AFTERNOON ? Slot.REST : Slot.OFF;
        }
        if (working && lunch(time, lunchBreak)) {
            return Slot.LUNCH;
        }
        if (working) {
            return Slot.WORK;
        }
        return evening(time, workEnd, gathering) ? Slot.EVENING : Slot.OFF;
    }

    public static boolean essential(Role role) {
        return switch (role) {
            case GUARD, MEDIC, INNKEEPER, HERDER -> true;
            default -> false;
        };
    }

    public static boolean skipsBreaks(Role role) {
        return role == Role.GUARD || role == Role.MEDIC || role == Role.IDLE;
    }

    public static int lunchFood(int eaters) {
        return (Math.max(0, eaters) + 1) / 2;
    }

    public static boolean canAffordLunch(int foodPoints, int adults) {
        return foodPoints >= 60 + adults * 20;
    }

    public static int seat(long uuidBits, int seats) {
        return seats <= 0 ? 0 : (int) Math.floorMod(uuidBits, (long) seats);
    }
}
