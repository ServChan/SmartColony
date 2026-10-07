package org.lts.smartcolony.core;

import java.util.List;
import java.util.Map;

public final class Schooling {
    public static final String ID = "school";
    public static final int AGE_TICKS = 12000;
    public static final int MIN_POINTS = 40;
    public static final int XP_DIVISOR = 4;
    public static final int MAX_XP = 108;
    public static final int CLASS_X = 6;
    public static final int CLASS_Z = 12;
    public static final int TEACHER_X = 6;
    public static final int TEACHER_Z = 15;
    public static final double CLASS_RADIUS = 5.5;
    public static final List<Role> SUBJECTS = List.of(Role.LUMBERJACK, Role.MINER, Role.FARMER, Role.FISHERMAN, Role.HERDER,
            Role.BUILDER, Role.GUARD, Role.FLETCHER, Role.SMITH, Role.COOK);

    private Schooling() {
    }

    public static boolean needed(int children, int adults, boolean kindergartenBuilt, boolean existsOrPlanned) {
        if (existsOrPlanned) {
            return false;
        }
        return (children >= 3 && kindergartenBuilt) || adults >= 18;
    }

    public static boolean schoolAge(int age) {
        return age < 0 && age >= -AGE_TICKS;
    }

    public static Role pickSubject(Map<Role, Integer> desired, Map<Role, Integer> current, Map<Role, Integer> enrolled) {
        Role best = Role.LUMBERJACK;
        int bestScore = Integer.MIN_VALUE;
        for (Role role : SUBJECTS) {
            int score = desired.getOrDefault(role, 0) - current.getOrDefault(role, 0) - enrolled.getOrDefault(role, 0);
            if (score > bestScore) {
                bestScore = score;
                best = role;
            }
        }
        return best;
    }

    public static boolean keepSubject(Role subject, Map<Role, Integer> desired) {
        return subject != null && SUBJECTS.contains(subject) && desired.getOrDefault(subject, 0) > 0;
    }

    public static int points(boolean teacherPresent, int teacherSkill) {
        return teacherPresent ? 2 + Math.max(0, Math.min(5, teacherSkill)) / 2 : 1;
    }

    public static int graduateXp(int points) {
        return Math.max(0, Math.min(MAX_XP, points / XP_DIVISOR));
    }

    public static Role graduateRole(Map<String, Integer> study) {
        Role best = null;
        int bestPoints = MIN_POINTS - 1;
        for (Role role : SUBJECTS) {
            int points = study.getOrDefault(role.key(), 0);
            if (points > bestPoints) {
                bestPoints = points;
                best = role;
            }
        }
        return best;
    }

    public static int totalPoints(Map<String, Integer> study) {
        int total = 0;
        for (int value : study.values()) {
            total += value;
        }
        return total;
    }
}
