package org.lts.smartcolony.core;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

public final class PorterPlan {
    public static final int MAX_BACKOFF_SHIFT = 4;
    public static final String OUTPOST_ID = "outpost";
    public static final String OUTPOST_KIND = "outpost";
    public static final String MINE = "mine";
    public static final String FOREST = "forest";
    public static final double LONG_WALK = 24.0;
    public static final double HEIGHT_WEIGHT = 1.5;
    public static final double MIN_WALK = 10.0;
    public static final int TROUBLE_LIMIT = 3;
    public static final double TROUBLE_WALK = 14.0;
    public static final int FOREST_SAMPLES = 6;
    public static final double FOREST_FAR = 40.0;
    public static final double FOREST_EDGE = 10.0;
    public static final double FOREST_MIN_ANCHOR = 18.0;
    public static final int MIN_ADULTS = 6;
    public static final int MAX_OUTPOSTS = 4;
    public static final double CLOSER_BY = 8.0;
    public static final int MIN_FREE_SLOTS = 3;
    public static final int HAUL_ITEMS = 24;
    public static final long HAUL_PATIENCE = 2400;
    public static final long RECENT_USE = 4800;
    public static final int PICKUP_SLOTS = 3;
    public static final double FAR_SITE = 24.0;
    public static final double PULL_WEIGHT = 0.1;

    private static final Role[] DONORS = {Role.FISHERMAN, Role.HERDER, Role.FARMER, Role.MINER, Role.LUMBERJACK};

    private PorterPlan() {
    }

    public static double walkCost(double horizontal, int heightDifference) {
        return horizontal + HEIGHT_WEIGHT * Math.abs(heightDifference);
    }

    public static boolean longWalk(double horizontal, int heightDifference, int trouble) {
        if (horizontal < MIN_WALK) {
            return false;
        }
        if (walkCost(horizontal, heightDifference) > LONG_WALK) {
            return true;
        }
        return trouble >= TROUBLE_LIMIT && horizontal > TROUBLE_WALK;
    }

    public static boolean forestFar(int samples, double averageDistance) {
        return samples >= FOREST_SAMPLES && averageDistance > FOREST_FAR;
    }

    public static Pos forestAnchor(Pos depot, double sumDx, double sumDz, int samples) {
        if (samples <= 0) {
            return depot;
        }
        double dx = sumDx / samples;
        double dz = sumDz / samples;
        double length = Math.hypot(dx, dz);
        if (length < 1.0) {
            return depot;
        }
        double edge = Math.max(FOREST_MIN_ANCHOR, length - FOREST_EDGE);
        double scale = edge / length;
        return new Pos(depot.x() + (int) Math.round(dx * scale), depot.y(), depot.z() + (int) Math.round(dz * scale));
    }

    public record Candidate(int x, int y, int z, int freeSlots, boolean usable) {
    }

    public static int pickDrop(int fromX, int fromY, int fromZ, int depotX, int depotY, int depotZ, List<Candidate> candidates) {
        double depotCost = walkCost(Math.hypot(fromX - depotX, fromZ - depotZ), fromY - depotY);
        int best = -1;
        double bestCost = Double.MAX_VALUE;
        for (int i = 0; i < candidates.size(); i++) {
            Candidate candidate = candidates.get(i);
            if (!candidate.usable() || candidate.freeSlots() < MIN_FREE_SLOTS) {
                continue;
            }
            double cost = walkCost(Math.hypot(fromX - candidate.x(), fromZ - candidate.z()), fromY - candidate.y());
            if (cost + CLOSER_BY < depotCost && cost < bestCost) {
                bestCost = cost;
                best = i;
            }
        }
        return best;
    }

    public static int savedBlocks(double depotCost, double outpostCost) {
        return (int) Math.max(0, Math.round(2.0 * (depotCost - outpostCost)));
    }

    public static boolean worthHauling(int items, long idleTicks) {
        return items >= HAUL_ITEMS || items > 0 && idleTicks >= HAUL_PATIENCE;
    }

    public static boolean busy(int items, long sinceUse) {
        return items > 0 || sinceUse >= 0 && sinceUse < RECENT_USE;
    }

    public static int portersWanted(int haulOutposts, int farSites, int adults) {
        int raw = Math.max(0, haulOutposts) + (Math.max(0, farSites) + 1) / 2;
        if (raw <= 0 || adults < MIN_ADULTS - 2) {
            return 0;
        }
        int cap = Math.max(1, adults / 5);
        return Math.min(raw, cap);
    }

    public static EnumMap<Role, Integer> settle(EnumMap<Role, Integer> desired, int porters) {
        EnumMap<Role, Integer> result = new EnumMap<>(Role.class);
        for (Role role : Role.values()) {
            result.put(role, desired.getOrDefault(role, 0));
        }
        int have = result.get(Role.PORTER);
        int wanted = Math.max(0, porters);
        if (have > wanted) {
            result.put(Role.PORTER, wanted);
            result.merge(Role.LUMBERJACK, have - wanted, Integer::sum);
            return result;
        }
        for (int i = have; i < wanted; i++) {
            Role donor = null;
            for (Role role : DONORS) {
                if (result.get(role) > 1 && (donor == null || result.get(role) > result.get(donor))) {
                    donor = role;
                }
            }
            if (donor == null) {
                break;
            }
            result.merge(donor, -1, Integer::sum);
            result.merge(Role.PORTER, 1, Integer::sum);
        }
        return result;
    }

    public static boolean overlaps(int minX, int minZ, int maxX, int maxZ, List<int[]> rectangles) {
        for (int[] rect : rectangles) {
            if (minX <= rect[2] && maxX >= rect[0] && minZ <= rect[3] && maxZ >= rect[1]) {
                return true;
            }
        }
        return false;
    }

    public static List<int[]> quarryForbidden(int cornerX, int cornerZ, int width, Pos shed, int shedLength, int shedWidth) {
        List<int[]> result = new ArrayList<>();
        result.add(new int[] {cornerX - 4, cornerZ - 4, cornerX + width + 4, cornerZ + width + 4});
        if (shed != null) {
            result.add(new int[] {shed.x() - 2, shed.z() - 2, shed.x() + shedLength + 1, shed.z() + shedWidth + 1});
        }
        return result;
    }

    public static List<int[]> tunnelForbidden(Pos entrance, int dirX, int dirZ, int depth) {
        List<int[]> result = new ArrayList<>();
        result.add(new int[] {entrance.x() - 5, entrance.z() - 5, entrance.x() + 5, entrance.z() + 5});
        int length = Math.min(Math.max(depth, 8), 40);
        int endX = entrance.x() + dirX * length;
        int endZ = entrance.z() + dirZ * length;
        result.add(new int[] {Math.min(entrance.x(), endX) - 3, Math.min(entrance.z(), endZ) - 3,
                Math.max(entrance.x(), endX) + 3, Math.max(entrance.z(), endZ) + 3});
        return result;
    }

    public static long retryDelay(long base, int misses) {
        return base * (1L << Math.max(0, Math.min(misses - 1, MAX_BACKOFF_SHIFT)));
    }
}
