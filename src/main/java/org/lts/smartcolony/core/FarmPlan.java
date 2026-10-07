package org.lts.smartcolony.core;

import java.util.ArrayList;
import java.util.List;

public final class FarmPlan {
    public static final int HARD_CAP = 8;
    public static final int LOW_FOOD_PER_HEAD = 30;
    public static final int STARVING_FOOD_PER_HEAD = 10;
    public static final int COMFORT_BASE = 120;
    public static final int COMFORT_PER_ADULT = 40;
    public static final int FALLOW_SLACK = 2;
    public static final int MIN_SPACING = 8;
    public static final int CLUSTER_REACH = 14;

    private FarmPlan() {
    }

    public static int base(int adults) {
        return Math.max(1, (Math.max(0, adults) * 2 + 8) / 9);
    }

    public static int budget(int adults, int foodPoints) {
        int safe = Math.max(0, adults);
        int extra = 0;
        if (foodPoints < safe * STARVING_FOOD_PER_HEAD) {
            extra = 2;
        } else if (foodPoints < safe * LOW_FOOD_PER_HEAD) {
            extra = 1;
        }
        return Math.min(HARD_CAP, base(adults) + extra);
    }

    public static int ceiling(int adults) {
        return Math.min(HARD_CAP, base(adults) + FALLOW_SLACK);
    }

    public static boolean comfortable(int adults, int foodPoints) {
        return foodPoints >= COMFORT_BASE + Math.max(0, adults) * COMFORT_PER_ADULT;
    }

    public static boolean mayAddAnchor(int anchors, int adults, int foodPoints) {
        return !comfortable(adults, foodPoints) && anchors < budget(adults, foodPoints);
    }

    public static double nearestSq(int x, int z, List<Pos> anchors) {
        double best = Double.MAX_VALUE;
        for (Pos anchor : anchors) {
            double dx = anchor.x() - x;
            double dz = anchor.z() - z;
            best = Math.min(best, dx * dx + dz * dz);
        }
        return best;
    }

    public static boolean clusterOk(int x, int z, List<Pos> anchors) {
        if (anchors.isEmpty()) {
            return true;
        }
        double nearest = nearestSq(x, z, anchors);
        return nearest >= (double) MIN_SPACING * MIN_SPACING && nearest <= (double) CLUSTER_REACH * CLUSTER_REACH;
    }

    public static boolean[] fallow(List<Pos> anchors, int centerX, int centerZ, int ceiling) {
        boolean[] result = new boolean[anchors.size()];
        int keep = Math.max(1, ceiling);
        if (anchors.size() <= keep) {
            return result;
        }
        List<Integer> remaining = new ArrayList<>();
        for (int i = 0; i < anchors.size(); i++) {
            remaining.add(i);
        }
        List<Pos> kept = new ArrayList<>();
        while (kept.size() < keep && !remaining.isEmpty()) {
            int bestIndex = -1;
            double bestDistance = Double.MAX_VALUE;
            for (int index : remaining) {
                Pos anchor = anchors.get(index);
                double distance;
                if (kept.isEmpty()) {
                    double dx = anchor.x() - centerX;
                    double dz = anchor.z() - centerZ;
                    distance = dx * dx + dz * dz;
                } else {
                    distance = nearestSq(anchor.x(), anchor.z(), kept);
                }
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestIndex = index;
                }
            }
            kept.add(anchors.get(bestIndex));
            remaining.remove(Integer.valueOf(bestIndex));
        }
        double reach = (double) CLUSTER_REACH * CLUSTER_REACH;
        for (int index : remaining) {
            Pos anchor = anchors.get(index);
            if (nearestSq(anchor.x(), anchor.z(), kept) > reach) {
                result[index] = true;
            }
        }
        return result;
    }
}
