package org.lts.smartcolony.core;

import java.util.Locale;
import java.util.Map;

public final class Reputation {
    public static final int MIN = -100;
    public static final int MAX = 100;
    public static final int ENEMY_AT = -50;
    public static final int SUSPECT_AT = -10;
    public static final int ALLY_AT = 30;

    public enum Status {
        ENEMY,
        SUSPECT,
        GUEST,
        ALLY;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private Reputation() {
    }

    public static int clamp(int value) {
        return Math.max(MIN, Math.min(MAX, value));
    }

    public static Status status(int value) {
        if (value <= ENEMY_AT) {
            return Status.ENEMY;
        }
        if (value <= SUSPECT_AT) {
            return Status.SUSPECT;
        }
        if (value >= ALLY_AT) {
            return Status.ALLY;
        }
        return Status.GUEST;
    }

    public static int theftPenalty(long valueMillis) {
        if (valueMillis <= 0) {
            return 0;
        }
        return (int) Math.min(60, Math.max(5, valueMillis / 100));
    }

    public static int donationBonus(long valueMillis) {
        if (valueMillis <= 0) {
            return 0;
        }
        return (int) Math.min(10, valueMillis / 200);
    }

    public static int recover(int value) {
        if (value < 0) {
            return value + 1;
        }
        return value;
    }

    public record Exchange(Map<String, Integer> taken, Map<String, Integer> given, long takenValue, long givenValue) {
        public long net() {
            return takenValue - givenValue;
        }

        public boolean isTheft() {
            return net() > 0;
        }

        public boolean isEmpty() {
            return taken.isEmpty() && given.isEmpty();
        }
    }

    public static Exchange exchange(Map<String, Integer> before, Map<String, Integer> after) {
        Map<String, Integer> delta = Stock.difference(before, after);
        Map<String, Integer> taken = new java.util.TreeMap<>();
        Map<String, Integer> given = new java.util.TreeMap<>();
        delta.forEach((id, change) -> {
            if (change < 0) {
                taken.put(id, -change);
            } else {
                given.put(id, change);
            }
        });
        return new Exchange(taken, given, PriceTable.total(taken), PriceTable.total(given));
    }
}
