package org.lts.smartcolony.core;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RoleAllocator {
    public record Needs(int adults, int wood, int stone, int iron, int foodPoints, int animal, int builderBacklog,
                        boolean penReady, Set<Role> equippable, double threat,
                        boolean fletcherReady, boolean smithReady, boolean cookReady, boolean teacherReady) {
        public Needs(int adults, int wood, int stone, int iron, int foodPoints, int animal, int builderBacklog,
                     boolean penReady, Set<Role> equippable, double threat,
                     boolean fletcherReady, boolean smithReady, boolean cookReady) {
            this(adults, wood, stone, iron, foodPoints, animal, builderBacklog, penReady, equippable, threat,
                    fletcherReady, smithReady, cookReady, false);
        }
    }

    static final int STONE_SHORT = 32;

    private RoleAllocator() {
    }

    static double pressure(int stock, int target) {
        double value = target / (stock + target * 0.1);
        return Math.max(0.3, Math.min(3.0, value));
    }

    static double miningPressure(int stone, int iron) {
        double mixed = pressure(stone + iron * 8, 192);
        return stone < STONE_SHORT ? Math.max(mixed, pressure(stone, 192)) : mixed;
    }

    public static EnumMap<Role, Integer> desired(Needs needs) {
        EnumMap<Role, Integer> result = new EnumMap<>(Role.class);
        for (Role role : Role.values()) {
            result.put(role, 0);
        }
        int adults = needs.adults();
        if (adults <= 0) {
            return result;
        }
        int guards = 0;
        if (adults >= 3) {
            guards = (int) Math.round(adults * 0.2 + needs.threat());
            guards = Math.max(1, Math.min(guards, Math.max(1, adults / 3)));
            if (!needs.equippable().contains(Role.GUARD)) {
                guards = 1;
            }
        }
        int builders = 0;
        if (needs.builderBacklog() > 0 && adults >= 4) {
            int cap = adults < 8 ? 1 : Math.max(2, adults / 4);
            builders = Math.min(cap, 1 + needs.builderBacklog() / 80);
            if (adults >= 16) {
                builders = Math.max(builders, 2);
            }
        }
        int teachers = needs.teacherReady() && adults >= 5 ? 1 : 0;
        int workers = adults - guards - builders - teachers;
        result.put(Role.TEACHER, teachers);
        result.put(Role.GUARD, guards);
        result.put(Role.BUILDER, builders);
        int crafterBudget = Math.max(0, workers - 3);
        int fletchers = needs.fletcherReady() && guards >= 1 && adults >= 6 ? (guards >= 10 ? 2 : 1) : 0;
        int smiths = needs.smithReady() && adults >= 12 ? (adults >= 24 ? 2 : 1) : 0;
        int cooks = needs.cookReady() && adults >= 10 ? 1 : 0;
        fletchers = Math.min(fletchers, crafterBudget);
        crafterBudget -= fletchers;
        smiths = Math.min(smiths, crafterBudget);
        crafterBudget -= smiths;
        cooks = Math.min(cooks, crafterBudget);
        result.put(Role.FLETCHER, fletchers);
        result.put(Role.SMITH, smiths);
        result.put(Role.COOK, cooks);
        workers -= fletchers + smiths + cooks;
        if (workers <= 0) {
            return result;
        }
        EnumMap<Role, Double> scores = new EnumMap<>(Role.class);
        scores.put(Role.LUMBERJACK, pressure(needs.wood(), 128));
        scores.put(Role.FARMER, pressure(needs.foodPoints(), 300));
        scores.put(Role.MINER, needs.equippable().contains(Role.MINER) ? 0.9 * miningPressure(needs.stone(), needs.iron()) : 0.0);
        scores.put(Role.FISHERMAN, needs.equippable().contains(Role.FISHERMAN) ? 0.6 * pressure(needs.foodPoints(), 300) : 0.0);
        scores.put(Role.HERDER, needs.penReady() ? 0.5 * pressure(needs.animal(), 64) : 0.0);
        EnumMap<Role, Integer> caps = new EnumMap<>(Role.class);
        caps.put(Role.HERDER, 1 + adults / 20);
        caps.put(Role.FISHERMAN, 1 + adults / 8);
        caps.put(Role.MINER, Math.max(1, Math.min(6, adults / 5)));
        caps.put(Role.FARMER, Math.max(2, adults / 4));
        if (needs.wood() >= 256) {
            caps.put(Role.LUMBERJACK, Math.max(1, adults / 12));
        }
        result.put(Role.LUMBERJACK, 1);
        int left = workers - 1;
        if (left > 0) {
            result.put(Role.FARMER, 1);
            left--;
        }
        while (left > 0) {
            Role best = null;
            double bestValue = 0.0;
            for (Map.Entry<Role, Double> entry : scores.entrySet()) {
                Role role = entry.getKey();
                int assigned = result.get(role);
                Integer cap = caps.get(role);
                if (cap != null && assigned >= cap) {
                    continue;
                }
                double value = entry.getValue() / (assigned + 1);
                if (value > bestValue) {
                    bestValue = value;
                    best = role;
                }
            }
            if (best == null) {
                best = needs.wood() >= 256 ? Role.IDLE : Role.LUMBERJACK;
                for (Role spare : new Role[] {Role.MINER, Role.FARMER, Role.FISHERMAN}) {
                    Integer cap = caps.get(spare);
                    boolean usable = spare == Role.FARMER || needs.equippable().contains(spare);
                    if (usable && (cap == null || result.get(spare) < cap)) {
                        best = spare;
                        break;
                    }
                }
            }
            result.merge(best, 1, Integer::sum);
            left--;
        }
        return result;
    }

    public static EnumMap<Role, Integer> withUndertaker(Map<Role, Integer> base, int wanted, int adults) {
        EnumMap<Role, Integer> result = new EnumMap<>(Role.class);
        for (Role role : Role.values()) {
            result.put(role, base.getOrDefault(role, 0));
        }
        result.put(Role.UNDERTAKER, 0);
        if (wanted <= 0 || adults < 2) {
            return result;
        }
        Role donor = null;
        for (Role role : new Role[] {Role.LUMBERJACK, Role.FARMER, Role.FISHERMAN, Role.HERDER, Role.MINER}) {
            if (result.get(role) > 0 && (donor == null || result.get(role) > result.get(donor))) {
                donor = role;
            }
        }
        if (donor == null) {
            return result;
        }
        result.merge(donor, -1, Integer::sum);
        result.put(Role.UNDERTAKER, 1);
        return result;
    }

    public static EnumMap<Role, Integer> reserveMedics(EnumMap<Role, Integer> base, int medics) {
        if (medics <= 0) {
            return base;
        }
        EnumMap<Role, Integer> result = new EnumMap<>(base);
        int total = 0;
        for (Map.Entry<Role, Integer> entry : result.entrySet()) {
            if (entry.getKey() != Role.IDLE && entry.getKey() != Role.MEDIC) {
                total += entry.getValue();
            }
        }
        int give = Math.min(medics, Math.max(0, total - 1));
        Role[] order = {Role.LUMBERJACK, Role.FARMER, Role.MINER, Role.FISHERMAN, Role.HERDER, Role.COOK, Role.FLETCHER, Role.SMITH,
                Role.BUILDER};
        int taken = 0;
        while (taken < give) {
            Role best = null;
            for (Role role : order) {
                if (result.get(role) > 0 && (best == null || result.get(role) > result.get(best))) {
                    best = role;
                }
            }
            if (best == null) {
                break;
            }
            result.merge(best, -1, Integer::sum);
            taken++;
        }
        result.merge(Role.MEDIC, taken, Integer::sum);
        return result;
    }

    static int targetCap(Role role, int adults) {
        return switch (role) {
            case GUARD, BUILDER -> Math.max(1, adults / 2);
            case MINER -> Math.max(1, adults / 3);
            case HERDER -> 3;
            case FLETCHER, SMITH, COOK -> Math.max(1, adults / 6);
            case INNKEEPER -> 1;
            case TEACHER -> 1;
            case MEDIC -> Math.max(1, adults / 8);
            case FISHERMAN -> Math.max(1, adults / 4);
            default -> adults;
        };
    }

    public static EnumMap<Role, Integer> withTargets(EnumMap<Role, Integer> base, Map<Role, Integer> targets, int adults) {
        if (targets.isEmpty() || adults <= 0) {
            return base;
        }
        EnumMap<Role, Integer> result = new EnumMap<>(Role.class);
        for (Role role : Role.values()) {
            result.put(role, 0);
        }
        int fixed = 0;
        for (Map.Entry<Role, Integer> entry : targets.entrySet()) {
            if (entry.getKey() == Role.IDLE) {
                continue;
            }
            int value = Math.max(0, Math.min(entry.getValue(), targetCap(entry.getKey(), adults)));
            result.put(entry.getKey(), value);
            fixed += value;
        }
        while (fixed > adults) {
            Role largest = null;
            for (Role role : targets.keySet()) {
                if (largest == null || result.get(role) > result.get(largest)) {
                    largest = role;
                }
            }
            result.merge(largest, -1, Integer::sum);
            fixed--;
        }
        int rest = adults - fixed;
        List<Role> free = new ArrayList<>();
        int baseSum = 0;
        for (Role role : Role.values()) {
            if (role != Role.IDLE && !targets.containsKey(role)) {
                free.add(role);
                baseSum += base.getOrDefault(role, 0);
            }
        }
        if (rest > 0 && free.isEmpty()) {
            Role first = targets.keySet().iterator().next();
            result.merge(first, rest, Integer::sum);
            return result;
        }
        if (rest > 0 && baseSum == 0) {
            Role sink = free.contains(Role.LUMBERJACK) ? Role.LUMBERJACK : free.getFirst();
            result.merge(sink, rest, Integer::sum);
            return result;
        }
        int given = 0;
        double[] remainders = new double[free.size()];
        for (int i = 0; i < free.size(); i++) {
            double share = rest * (double) base.getOrDefault(free.get(i), 0) / baseSum;
            int whole = (int) Math.floor(share);
            result.put(free.get(i), whole);
            remainders[i] = share - whole;
            given += whole;
        }
        while (given < rest) {
            int best = 0;
            for (int i = 1; i < remainders.length; i++) {
                if (remainders[i] > remainders[best]) {
                    best = i;
                }
            }
            result.merge(free.get(best), 1, Integer::sum);
            remainders[best] = -1;
            given++;
        }
        return result;
    }

    public static Map<String, Role> reassign(Map<String, Role> current, Map<String, Role> affinity, Set<String> locked,
                                             Map<Role, Integer> desired, int maxChanges) {
        EnumMap<Role, Integer> counts = new EnumMap<>(Role.class);
        for (Role role : Role.values()) {
            counts.put(role, 0);
        }
        current.values().forEach(role -> counts.merge(role, 1, Integer::sum));
        Map<String, Role> changes = new LinkedHashMap<>();
        int budget = maxChanges;
        while (true) {
            Role deficit = null;
            int worst = 0;
            for (Role role : Role.values()) {
                if (role == Role.IDLE) {
                    continue;
                }
                int lack = desired.getOrDefault(role, 0) - counts.get(role);
                if (lack > worst) {
                    worst = lack;
                    deficit = role;
                }
            }
            if (deficit == null) {
                break;
            }
            String pick = null;
            boolean pickIdle = false;
            List<String> candidates = new ArrayList<>(current.keySet());
            candidates.sort(String::compareTo);
            int bestRank = Integer.MAX_VALUE;
            for (String member : candidates) {
                if (locked.contains(member) || changes.containsKey(member)) {
                    continue;
                }
                Role role = current.get(member);
                boolean idle = role == Role.IDLE;
                boolean surplus = !idle && counts.get(role) > desired.getOrDefault(role, 0);
                if (!idle && !surplus) {
                    continue;
                }
                if (!idle && budget <= 0) {
                    continue;
                }
                int rank = (idle ? 0 : 2) + (affinity.get(member) == deficit ? 0 : 1);
                if (rank < bestRank) {
                    bestRank = rank;
                    pick = member;
                    pickIdle = idle;
                }
            }
            if (pick == null) {
                break;
            }
            Role old = current.get(pick);
            counts.merge(old, -1, Integer::sum);
            counts.merge(deficit, 1, Integer::sum);
            changes.put(pick, deficit);
            if (!pickIdle) {
                budget--;
            }
        }
        return changes;
    }
}
