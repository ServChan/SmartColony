package org.lts.smartcolony.core;

public final class GuardDrill {
    public static final String ID = "barracks";
    public static final int COTS = 4;
    public static final int TRAIN_CAP = 3;
    public static final int WINDOW_TICKS = 1200;
    public static final int SESSION_SHOTS = 8;
    public static final int SESSION_SWINGS = 20;
    public static final int MIN_ARROWS = 12;
    public static final int[][] DUMMIES = {{4, 7}, {10, 7}};
    public static final int[][] DUMMY_HEADS = {{4, 6}, {10, 6}};
    public static final int[] LANE_STAND = {13, 2};
    public static final int[] LANE_TARGET = {1, 2};
    public static final int[][] COT_SPOTS = {{3, 15}, {5, 15}, {9, 15}, {11, 15}};

    private GuardDrill() {
    }

    public static boolean needed(int guards, double threat, int adults, boolean existsOrPlanned) {
        if (existsOrPlanned || adults < 8) {
            return false;
        }
        return guards >= 4 || (guards >= 2 && threat >= 0.5);
    }

    public static boolean nightShift(int index, int count) {
        return count >= 3 && index % 2 == 0;
    }

    public static boolean hasCot(int index) {
        return index >= 0 && index < COTS;
    }

    public static boolean trainsNow(long time, int index) {
        return Math.floorMod(time / WINDOW_TICKS + index, 2L) == 0;
    }

    public static boolean standDown(boolean alarmed, double threat) {
        return alarmed || threat >= 0.5;
    }

    public static int trainingXp(int skill) {
        return skill < TRAIN_CAP ? 1 : 0;
    }
}
