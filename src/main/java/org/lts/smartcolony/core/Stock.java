package org.lts.smartcolony.core;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

public final class Stock {
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private final Set<String> capabilities = new LinkedHashSet<>();

    public Stock() {
    }

    public Stock(Map<String, Integer> initial) {
        initial.forEach(this::add);
    }

    public int get(String id) {
        return counts.getOrDefault(id, 0);
    }

    public void add(String id, int amount) {
        if (amount <= 0) {
            return;
        }
        counts.merge(id, amount, Integer::sum);
    }

    public int remove(String id, int amount) {
        int have = get(id);
        int taken = Math.min(have, Math.max(0, amount));
        if (taken == have) {
            counts.remove(id);
        } else if (taken > 0) {
            counts.put(id, have - taken);
        }
        return taken;
    }

    public int sum(Predicate<String> filter) {
        int total = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (filter.test(entry.getKey())) {
                total += entry.getValue();
            }
        }
        return total;
    }

    public int total() {
        return sum(id -> true);
    }

    public Map<String, Integer> view() {
        return java.util.Collections.unmodifiableMap(counts);
    }

    public Map<String, Integer> sorted() {
        return new TreeMap<>(counts);
    }

    public Stock copy() {
        Stock copy = new Stock(counts);
        copy.capabilities.addAll(capabilities);
        return copy;
    }

    public Stock grant(Collection<String> granted) {
        capabilities.addAll(granted);
        return this;
    }

    public boolean has(String capability) {
        return capability == null || capability.isEmpty() || capabilities.contains(capability);
    }

    public Set<String> capabilities() {
        return java.util.Collections.unmodifiableSet(capabilities);
    }

    public boolean isEmpty() {
        return counts.isEmpty();
    }

    public boolean containsAll(Map<String, Integer> required) {
        for (Map.Entry<String, Integer> entry : required.entrySet()) {
            if (get(entry.getKey()) < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    public Map<String, Integer> missing(Map<String, Integer> required) {
        Map<String, Integer> missing = new TreeMap<>();
        for (Map.Entry<String, Integer> entry : required.entrySet()) {
            int lack = entry.getValue() - get(entry.getKey());
            if (lack > 0) {
                missing.put(entry.getKey(), lack);
            }
        }
        return missing;
    }

    public static Map<String, Integer> difference(Map<String, Integer> before, Map<String, Integer> after) {
        Map<String, Integer> delta = new TreeMap<>();
        for (Map.Entry<String, Integer> entry : before.entrySet()) {
            int change = after.getOrDefault(entry.getKey(), 0) - entry.getValue();
            if (change != 0) {
                delta.put(entry.getKey(), change);
            }
        }
        for (Map.Entry<String, Integer> entry : after.entrySet()) {
            if (!before.containsKey(entry.getKey()) && entry.getValue() != 0) {
                delta.put(entry.getKey(), entry.getValue());
            }
        }
        return delta;
    }
}
