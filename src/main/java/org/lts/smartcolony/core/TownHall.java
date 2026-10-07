package org.lts.smartcolony.core;

public final class TownHall {
    public static final String KIND = "townhall";
    public static final String PREFIX = "townhall_";
    public static final int STAGES = 5;
    public static final int[] DEFAULT_ADULTS = {4, 10, 18, 28, 40};
    public static final int IRON_STAGE = 4;

    private TownHall() {
    }

    public static String blueprint(int stage) {
        return PREFIX + stage;
    }

    public static int stageOf(String blueprint) {
        if (blueprint == null || !blueprint.startsWith(PREFIX)) {
            return 0;
        }
        try {
            int stage = Integer.parseInt(blueprint.substring(PREFIX.length()));
            return stage >= 1 && stage <= STAGES ? stage : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static int stage(ColonyData data) {
        int best = 0;
        for (ColonyData.Building building : data.buildings) {
            best = Math.max(best, stageOf(building.blueprint));
        }
        return best;
    }

    public static boolean inProgress(ColonyData data) {
        for (ColonyData.Project project : data.projects) {
            if (stageOf(project.building.blueprint) > 0) {
                return true;
            }
        }
        return false;
    }

    public static int[] thresholds(int[] configured) {
        int[] result = new int[STAGES];
        for (int i = 0; i < STAGES; i++) {
            int value = configured != null && i < configured.length ? configured[i] : DEFAULT_ADULTS[i];
            value = Math.max(1, Math.min(500, value));
            result[i] = i == 0 ? value : Math.max(value, result[i - 1]);
        }
        return result;
    }

    public static int requiredAdults(int stage, int[] configured) {
        if (stage < 1 || stage > STAGES) {
            return Integer.MAX_VALUE;
        }
        return thresholds(configured)[stage - 1];
    }

    public static int next(int finished, boolean busy, int adults, boolean storageBuilt, boolean ironAge, int[] configured) {
        if (busy || finished >= STAGES || finished < 0) {
            return 0;
        }
        int stage = finished + 1;
        if (adults < requiredAdults(stage, configured)) {
            return 0;
        }
        if (stage == 1 && !storageBuilt) {
            return 0;
        }
        if (stage >= IRON_STAGE && !ironAge) {
            return 0;
        }
        return stage;
    }

    public static boolean prioritised(int finished, boolean busy) {
        return finished == 0 && !busy;
    }

    public static int projectBonus(int stage) {
        if (stage >= 5) {
            return 2;
        }
        return stage >= 3 ? 1 : 0;
    }
}
