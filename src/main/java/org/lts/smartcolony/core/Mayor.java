package org.lts.smartcolony.core;

import java.util.Comparator;
import java.util.List;

public final class Mayor {
    public static final int HOUSE_STAGE = 3;

    public record Candidate(String id, long since, int totalXp) {
    }

    private Mayor() {
    }

    public static String choose(List<Candidate> candidates) {
        return candidates.stream()
                .min(Comparator.comparingInt((Candidate c) -> -c.totalXp()).thenComparingLong(Candidate::since).thenComparing(Candidate::id))
                .map(Candidate::id)
                .orElse("");
    }

    public static boolean keeps(String head, List<Candidate> candidates) {
        return head != null && !head.isEmpty() && candidates.stream().anyMatch(c -> c.id().equals(head));
    }

    public static boolean movesIn(int hallStage, boolean hasBed) {
        return hallStage >= HOUSE_STAGE && hasBed;
    }

    public static int totalXp(java.util.Map<String, Integer> xp) {
        int sum = 0;
        for (int value : xp.values()) {
            sum += Math.max(0, value);
        }
        return sum;
    }
}
