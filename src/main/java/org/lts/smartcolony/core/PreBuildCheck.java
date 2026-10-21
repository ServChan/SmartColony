package org.lts.smartcolony.core;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class PreBuildCheck {
    public enum Verdict {
        KEEP,
        REFIT,
        RELOCATE
    }

    private PreBuildCheck() {
    }

    public static Verdict decide(boolean feasible, int oldBase, Collection<Pos> oldFill, int newBase, Collection<Pos> newFill) {
        if (!feasible) {
            return Verdict.RELOCATE;
        }
        if (oldBase != newBase) {
            return Verdict.REFIT;
        }
        Set<Pos> before = new HashSet<>(oldFill);
        Set<Pos> after = new HashSet<>(newFill);
        return before.equals(after) ? Verdict.KEEP : Verdict.REFIT;
    }

    public static Map<String, Integer> deficit(Map<String, Integer> required, Map<String, Integer> reserved) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : required.entrySet()) {
            int missing = entry.getValue() - reserved.getOrDefault(entry.getKey(), 0);
            if (missing > 0) {
                result.put(entry.getKey(), missing);
            }
        }
        return result;
    }

    private static final java.util.List<String> LATE_SUFFIXES = java.util.List.of("_bed", "_carpet", "_banner", "glass_pane", "lantern",
            "flower_pot", "_candle", "candle", "campfire");

    public static final long HAND_OVER_AFTER = 6000;
    private static final java.util.List<String> WOOL_GOODS = java.util.List.of("_bed", "_carpet", "_banner", "_wool");

    public static boolean furnishing(String item) {
        String path = Ids.path(item);
        for (String suffix : WOOL_GOODS) {
            if (path.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    public static boolean handOver(java.util.Collection<String> waitingFor, long waited) {
        return !waitingFor.isEmpty() && waited >= HAND_OVER_AFTER && waitingFor.stream().allMatch(PreBuildCheck::furnishing);
    }

    public static boolean lateItem(String item) {
        String path = Ids.path(item);
        for (String suffix : LATE_SUFFIXES) {
            if (path.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    public static Map<String, Integer> late(Map<String, Integer> need) {
        Map<String, Integer> result = new LinkedHashMap<>();
        need.forEach((item, count) -> {
            if (lateItem(item) && count > 0) {
                result.put(item, count);
            }
        });
        return result;
    }

    public static Map<String, Integer> without(Map<String, Integer> need, Map<String, Integer> removed) {
        Map<String, Integer> result = new LinkedHashMap<>(need);
        removed.keySet().forEach(result::remove);
        return result;
    }

    public static Map<String, Integer> surplus(Map<String, Integer> required, Map<String, Integer> reserved) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : reserved.entrySet()) {
            int extra = entry.getValue() - required.getOrDefault(entry.getKey(), 0);
            if (extra > 0) {
                result.put(entry.getKey(), extra);
            }
        }
        return result;
    }
}
