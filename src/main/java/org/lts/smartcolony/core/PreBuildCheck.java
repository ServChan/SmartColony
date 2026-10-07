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
