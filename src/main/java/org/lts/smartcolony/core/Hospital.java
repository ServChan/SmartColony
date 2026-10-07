package org.lts.smartcolony.core;

public final class Hospital {
    public static final String KIND = "infirmary";
    public static final String PREFIX = "infirmary_";
    public static final int STAGES = 3;
    public static final int[] DEFAULT_ADULTS = {8, 16, 30};
    public static final int DEFAULT_ADULTS_PER_MEDIC = 15;
    public static final int MIN_ADULTS_AFTER_INCIDENT = 4;
    public static final int MIN_BUILDINGS = 2;

    public static final String BANDAGE = Ids.mc("paper");
    public static final String REMEDY = Ids.mc("suspicious_stew");
    public static final String GOLDEN = Ids.mc("golden_apple");

    public static final double ADMIT_AFTER_SHIFT = 0.6;
    public static final double ADMIT_NOW = 0.3;
    public static final double DISCHARGE = 0.9;
    public static final double HEAVY = 0.15;

    public enum Care {
        NONE,
        BANDAGE,
        REMEDY,
        GOLDEN
    }

    public enum Triage {
        NONE,
        AFTER_SHIFT,
        NOW
    }

    private Hospital() {
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

    public static int next(int finished, boolean busy, int adults, int colonyBuildings, boolean incident, int[] configured) {
        if (busy || finished < 0 || finished >= STAGES || colonyBuildings < MIN_BUILDINGS) {
            return 0;
        }
        int stage = finished + 1;
        if (adults >= requiredAdults(stage, configured)) {
            return stage;
        }
        if (stage == 1 && incident && adults >= MIN_ADULTS_AFTER_INCIDENT) {
            return stage;
        }
        return 0;
    }

    public static int cots(int stage) {
        return switch (stage) {
            case 1 -> 2;
            case 2 -> 4;
            case 3 -> 6;
            default -> 0;
        };
    }

    public static int medicsFor(int adults, boolean built, int adultsPerMedic) {
        if (!built || adults <= 0) {
            return 0;
        }
        int per = Math.max(1, adultsPerMedic);
        return Math.max(1, adults / per);
    }

    public static Triage triage(float health, float maxHealth, boolean onShift) {
        if (maxHealth <= 0 || health >= maxHealth) {
            return Triage.NONE;
        }
        double ratio = (double) health / maxHealth;
        if (ratio < ADMIT_NOW) {
            return Triage.NOW;
        }
        if (ratio < ADMIT_AFTER_SHIFT && !onShift) {
            return Triage.AFTER_SHIFT;
        }
        return Triage.NONE;
    }

    public static boolean discharged(float health, float maxHealth) {
        return maxHealth > 0 && (double) health / maxHealth >= DISCHARGE;
    }

    public static boolean critical(float health, float maxHealth) {
        return maxHealth > 0 && (double) health / maxHealth < ADMIT_NOW;
    }

    public static Care chooseCare(float health, float maxHealth, int bandages, int remedies, int golden) {
        if (maxHealth <= 0) {
            return Care.NONE;
        }
        double ratio = (double) health / maxHealth;
        if (ratio < HEAVY && golden > 0) {
            return Care.GOLDEN;
        }
        if (ratio < ADMIT_NOW && bandages > 0) {
            return Care.BANDAGE;
        }
        if (remedies > 0 && (ratio >= ADMIT_NOW || bandages == 0)) {
            return Care.REMEDY;
        }
        if (bandages > 0) {
            return Care.BANDAGE;
        }
        return Care.NONE;
    }

    public static float healAmount(Care care, float maxHealth) {
        return switch (care) {
            case GOLDEN -> maxHealth;
            case BANDAGE -> maxHealth * 0.5F;
            case REMEDY -> maxHealth * 0.1F;
            case NONE -> 0.0F;
        };
    }

    public static int regenerationTicks(Care care) {
        return switch (care) {
            case GOLDEN -> 600;
            case REMEDY -> 900;
            case BANDAGE -> 100;
            case NONE -> 0;
        };
    }

    public static int regenerationAmplifier(Care care) {
        return care == Care.GOLDEN ? 1 : 0;
    }

    public static String supplyItem(Care care) {
        return switch (care) {
            case BANDAGE -> BANDAGE;
            case REMEDY -> REMEDY;
            case GOLDEN -> GOLDEN;
            case NONE -> "";
        };
    }

    public static float restHeal(long restedTicks) {
        return restedTicks > 0 && restedTicks % 200 == 0 ? 1.0F : 0.0F;
    }

    public static int bandageTarget(int adults) {
        return Math.max(4, Math.min(24, adults / 2));
    }

    public static int remedyTarget(int adults) {
        return Math.max(2, Math.min(12, adults / 4));
    }

    public static boolean cureReady(boolean medicExists, int golden, boolean goldenCraftable) {
        return medicExists && (golden > 0 || goldenCraftable);
    }

    public static boolean sparesZombie(boolean cureReady, double distanceToCenterSq, int radius) {
        return cureReady && distanceToCenterSq <= (double) radius * radius;
    }

    public static boolean wantsFieldRescue(float health, float maxHealth, double distanceFromWard, int airSupply, int maxAir,
                                           boolean inWater) {
        if (maxHealth <= 0) {
            return false;
        }
        boolean drowning = inWater && maxAir > 0 && airSupply < maxAir / 3;
        boolean downed = (double) health / maxHealth < ADMIT_NOW && distanceFromWard > 12.0;
        return drowning || downed;
    }
}
