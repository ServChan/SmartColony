package org.lts.smartcolony.core;

public final class Professions {
    public static final long HOLD_TICKS = 2400;
    public static final String NONE = "none";
    public static final String NITWIT = "nitwit";
    private static final int[] MIN_XP = {0, 10, 70, 150, 250};

    private Professions() {
    }

    public record Plan(String profession, int level, boolean professionChange) {
    }

    public static String forRole(Role role) {
        return switch (role) {
            case FARMER -> "farmer";
            case FISHERMAN -> "fisherman";
            case HERDER -> "shepherd";
            case FLETCHER -> "fletcher";
            case GUARD -> "weaponsmith";
            case SMITH -> "armorer";
            case MINER -> "toolsmith";
            case BUILDER, MASON -> "mason";
            case COOK, INNKEEPER -> "butcher";
            case MEDIC, UNDERTAKER, ALCHEMIST -> "cleric";
            case TEACHER, SCRIBE -> "librarian";
            case SCOUT -> "cartographer";
            case PORTER, LUMBERJACK -> "leatherworker";
            case BEEKEEPER -> "farmer";
            case IDLE -> null;
        };
    }

    public static int levelFor(int skill) {
        return Math.max(1, Math.min(5, skill));
    }

    public static int minXp(int level) {
        return MIN_XP[Math.max(1, Math.min(5, level)) - 1];
    }

    public static int clampXp(int level, int xp) {
        int lo = Math.max(1, minXp(level));
        if (level >= 5) {
            return Math.max(lo, xp);
        }
        int hi = Math.max(lo, minXp(level + 1) - 1);
        return Math.max(lo, Math.min(hi, xp));
    }

    public static Plan plan(String currentProfession, int currentLevel, Role role, int skill, long heldTicks) {
        String target = forRole(role);
        if (target == null || NITWIT.equals(currentProfession)) {
            return null;
        }
        int level = levelFor(skill);
        boolean same = target.equals(currentProfession);
        if (same && level == currentLevel) {
            return null;
        }
        if (!NONE.equals(currentProfession) && heldTicks < HOLD_TICKS) {
            return null;
        }
        return new Plan(target, level, !same);
    }

    public static boolean incremental(boolean professionChange, int currentLevel, int targetLevel) {
        return !professionChange && targetLevel > currentLevel;
    }
}
