package org.lts.smartcolony.core;

public final class ChestCraft {
    public static final int PLANKS_PER_CHEST = 8;
    public static final int PLANKS_PER_LOG = 4;

    public record Spend(int planks, int logs, int planksBack) {
    }

    private ChestCraft() {
    }

    public static Spend plan(int planks, int logs) {
        int usePlanks = Math.min(planks, PLANKS_PER_CHEST);
        int missing = PLANKS_PER_CHEST - usePlanks;
        int useLogs = (missing + PLANKS_PER_LOG - 1) / PLANKS_PER_LOG;
        if (useLogs > logs) {
            return null;
        }
        return new Spend(usePlanks, useLogs, usePlanks + useLogs * PLANKS_PER_LOG - PLANKS_PER_CHEST);
    }
}
