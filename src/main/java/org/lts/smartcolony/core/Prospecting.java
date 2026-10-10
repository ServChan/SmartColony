package org.lts.smartcolony.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Prospecting {
    public static final int MAX_MINES = 3;
    public static final int MINERS_PER_MINE = 2;
    public static final int RICH_VALUE = 6;
    public static final int MAX_DISTANCE = 192;
    public static final int MINE_REACH = 56;
    public static final int MAX_DRY = 2;
    public static final int PROSPECTOR = -1;

    public record Deposit(Pos pos, int value, double distance) {
    }

    private Prospecting() {
    }

    public static boolean rich(String kind, int value, long worked, int dry) {
        return Expedition.ORE.equals(kind) && value >= RICH_VALUE && worked == 0 && dry < MAX_DRY;
    }

    public static boolean inRange(double distance) {
        return distance <= MAX_DISTANCE;
    }

    public static int prospectors(int miners, int deposits) {
        if (deposits <= 0 || miners < 2) {
            return 0;
        }
        return Math.min(deposits, miners >= 5 ? 2 : 1);
    }

    public static int mines(int diggers) {
        if (diggers <= 0) {
            return 1;
        }
        return Math.max(1, Math.min(MAX_MINES, (diggers + MINERS_PER_MINE - 1) / MINERS_PER_MINE));
    }

    public static Map<String, Integer> assign(List<String> miners, int mines, int prospectors) {
        List<String> sorted = new ArrayList<>(miners);
        sorted.sort(String::compareTo);
        int scouts = Math.max(0, Math.min(prospectors, sorted.size() - 1));
        int diggers = sorted.size() - scouts;
        int shafts = Math.max(1, mines);
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            result.put(sorted.get(i), i >= diggers ? PROSPECTOR : i % shafts);
        }
        return result;
    }

    public static Deposit pick(List<Deposit> deposits, Collection<Pos> taken) {
        Deposit best = null;
        double bestScore = Double.MAX_VALUE;
        for (Deposit deposit : deposits) {
            if (taken.contains(deposit.pos()) || !inRange(deposit.distance())) {
                continue;
            }
            double score = deposit.distance() - Math.min(48, deposit.value()) * 2.0;
            if (score < bestScore) {
                bestScore = score;
                best = deposit;
            }
        }
        return best;
    }

    public static Deposit anchor(List<Deposit> deposits, int colonyRadius, Collection<Pos> mines) {
        Deposit best = null;
        for (Deposit deposit : deposits) {
            if (deposit.distance() > colonyRadius + MINE_REACH) {
                continue;
            }
            boolean served = false;
            for (Pos mine : mines) {
                if (mine.horizontalDistSq(deposit.pos()) < 32 * 32) {
                    served = true;
                    break;
                }
            }
            if (!served && (best == null || deposit.value() > best.value()
                    || deposit.value() == best.value() && deposit.distance() < best.distance())) {
                best = deposit;
            }
        }
        return best;
    }

    public static EnumMap<Role, Integer> settle(Map<Role, Integer> desired, int deposits, int adults, boolean minerEquippable) {
        EnumMap<Role, Integer> result = new EnumMap<>(Role.class);
        for (Role role : Role.values()) {
            result.put(role, desired.getOrDefault(role, 0));
        }
        if (deposits <= 0 || adults < 6 || !minerEquippable || result.get(Role.MINER) >= 2) {
            return result;
        }
        Role donor = null;
        for (Role role : new Role[] {Role.IDLE, Role.LUMBERJACK, Role.FISHERMAN, Role.FARMER}) {
            int floor = role == Role.IDLE ? 0 : role == Role.FARMER ? 2 : 1;
            if (result.get(role) > floor && (donor == null || result.get(role) > result.get(donor))) {
                donor = role;
            }
        }
        if (donor == null) {
            return result;
        }
        result.merge(donor, -1, Integer::sum);
        result.merge(Role.MINER, 1, Integer::sum);
        return result;
    }
}
