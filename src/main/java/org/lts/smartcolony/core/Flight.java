package org.lts.smartcolony.core;

import java.util.List;

public final class Flight {
    public static final double MELEE_RANGE = 5.0;
    public static final double HUNT_RANGE = 10.0;
    public static final double RANGED_RANGE = 16.0;
    public static final double CREEPER_RANGE = 7.0;
    public static final double SPOT_CLEAR = 5.0;
    public static final double ROUTE_CLEAR = 6.0;
    public static final double ROUTE_PENALTY = 6.0;
    public static final double CROWD_PENALTY = 4.0;
    public static final double HOME_BONUS = 6.0;
    public static final double PATH_CLEAR = 2.5;
    public static final double HOME_PULL = 0.15;
    public static final double BUNKER_BONUS = 3.0;
    public static final double BUNKER_SEVERE_BONUS = 40.0;
    public static final double BUNKER_REACH = 1.5;

    public record Threat(double x, double y, double z) {
    }

    public record Spot(int x, int y, int z, long room, int capacity, boolean home, int load, boolean bunker) {
        public Spot(int x, int y, int z, long room, int capacity, boolean home, int load) {
            this(x, y, z, room, capacity, home, load, false);
        }
    }

    private Flight() {
    }

    public static boolean dangerous(double distance, boolean sees, boolean targetsMe, boolean ranged, boolean creeper) {
        if (!sees) {
            return false;
        }
        if (creeper) {
            return distance <= CREEPER_RANGE;
        }
        if (ranged) {
            return targetsMe && distance <= RANGED_RANGE;
        }
        return distance <= MELEE_RANGE || targetsMe && distance <= HUNT_RANGE;
    }

    public static double segmentDistance(double ax, double az, double bx, double bz, double px, double pz) {
        double dx = bx - ax;
        double dz = bz - az;
        double length = dx * dx + dz * dz;
        double t = length == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (pz - az) * dz) / length));
        double cx = ax + dx * t - px;
        double cz = az + dz * t - pz;
        return Math.sqrt(cx * cx + cz * cz);
    }

    public static double score(Spot spot, double fromX, double fromY, double fromZ, List<Threat> threats, double maxDistance) {
        return score(spot, fromX, fromY, fromZ, threats, maxDistance, false);
    }

    public static double score(Spot spot, double fromX, double fromY, double fromZ, List<Threat> threats, double maxDistance,
            boolean severe) {
        double dx = spot.x() - fromX;
        double dy = spot.bunker() ? 0 : (spot.y() - fromY) * 2.0;
        double dz = spot.z() - fromZ;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance > (spot.bunker() && severe ? maxDistance * BUNKER_REACH : maxDistance)) {
            return Double.MAX_VALUE;
        }
        double score = distance;
        for (Threat threat : threats) {
            double tx = threat.x() - spot.x();
            double tz = threat.z() - spot.z();
            if (Math.sqrt(tx * tx + tz * tz) < SPOT_CLEAR && Math.abs(threat.y() - spot.y()) < 4) {
                return Double.MAX_VALUE;
            }
            double route = segmentDistance(fromX, fromZ, spot.x(), spot.z(), threat.x(), threat.z());
            if (route < ROUTE_CLEAR) {
                score += (ROUTE_CLEAR - route) * ROUTE_PENALTY;
            }
        }
        if (spot.load() >= spot.capacity()) {
            score += (spot.load() - spot.capacity() + 1) * CROWD_PENALTY;
        }
        if (spot.home()) {
            score -= HOME_BONUS;
        }
        if (spot.bunker()) {
            score -= severe ? BUNKER_SEVERE_BONUS : BUNKER_BONUS;
        }
        return score;
    }

    public static int pick(List<Spot> spots, double fromX, double fromY, double fromZ, List<Threat> threats, double maxDistance,
            java.util.function.Predicate<Spot> skip) {
        return pick(spots, fromX, fromY, fromZ, threats, maxDistance, false, skip);
    }

    public static int pick(List<Spot> spots, double fromX, double fromY, double fromZ, List<Threat> threats, double maxDistance,
            boolean severe, java.util.function.Predicate<Spot> skip) {
        int best = -1;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < spots.size(); i++) {
            Spot spot = spots.get(i);
            if (skip.test(spot)) {
                continue;
            }
            double score = score(spot, fromX, fromY, fromZ, threats, maxDistance, severe);
            if (score < bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    public static double[] away(double fromX, double fromZ, List<Threat> threats, double homeX, double homeZ) {
        double vx = 0;
        double vz = 0;
        for (Threat threat : threats) {
            double dx = fromX - threat.x();
            double dz = fromZ - threat.z();
            double d2 = Math.max(0.25, dx * dx + dz * dz);
            vx += dx / d2;
            vz += dz / d2;
        }
        double push = Math.sqrt(vx * vx + vz * vz);
        double hx = homeX - fromX;
        double hz = homeZ - fromZ;
        double home = Math.sqrt(hx * hx + hz * hz);
        if (push > 0) {
            vx /= push;
            vz /= push;
            if (home > 1) {
                vx += hx / home * HOME_PULL;
                vz += hz / home * HOME_PULL;
            }
        } else if (home > 1) {
            vx = hx / home;
            vz = hz / home;
        } else {
            return new double[] {0, 0};
        }
        double length = Math.sqrt(vx * vx + vz * vz);
        return length == 0 ? new double[] {0, 0} : new double[] {vx / length, vz / length};
    }

    public static boolean exposed(List<int[]> nodes, List<Threat> threats) {
        for (int[] node : nodes) {
            for (Threat threat : threats) {
                double dx = node[0] + 0.5 - threat.x();
                double dz = node[2] + 0.5 - threat.z();
                if (dx * dx + dz * dz < PATH_CLEAR * PATH_CLEAR && Math.abs(node[1] - threat.y()) < 3) {
                    return true;
                }
            }
        }
        return false;
    }
}
