package org.lts.smartcolony.core;

public final class CombatMath {
    public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        public Box inflate(double amount) {
            return new Box(minX - amount, minY - amount, minZ - amount, maxX + amount, maxY + amount, maxZ + amount);
        }
    }

    private CombatMath() {
    }

    public static boolean segmentHits(double ax, double ay, double az, double bx, double by, double bz, Box box) {
        double tMin = 0.0;
        double tMax = 1.0;
        double[] origin = {ax, ay, az};
        double[] delta = {bx - ax, by - ay, bz - az};
        double[] min = {box.minX(), box.minY(), box.minZ()};
        double[] max = {box.maxX(), box.maxY(), box.maxZ()};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(delta[i]) < 1.0E-9) {
                if (origin[i] < min[i] || origin[i] > max[i]) {
                    return false;
                }
                continue;
            }
            double t1 = (min[i] - origin[i]) / delta[i];
            double t2 = (max[i] - origin[i]) / delta[i];
            if (t1 > t2) {
                double swap = t1;
                t1 = t2;
                t2 = swap;
            }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) {
                return false;
            }
        }
        return true;
    }

    public static double[] retreatPoint(double gx, double gz, double tx, double tz, double distance) {
        double dx = gx - tx;
        double dz = gz - tz;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-6) {
            dx = 1.0;
            dz = 0.0;
            length = 1.0;
        }
        return new double[]{gx + dx / length * distance, gz + dz / length * distance};
    }

    public static double[] strafePoint(double gx, double gz, double tx, double tz, double distance, boolean left) {
        double dx = tx - gx;
        double dz = tz - gz;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-6) {
            return new double[]{gx + distance, gz};
        }
        double px = -dz / length;
        double pz = dx / length;
        double sign = left ? 1.0 : -1.0;
        return new double[]{gx + px * distance * sign, gz + pz * distance * sign};
    }

    public static int shotCooldown(int skill) {
        return Math.max(14, 25 - 2 * skill);
    }

    public static int meleeCooldown(int skill) {
        return Math.max(12, 20 - skill);
    }

    public static float shotSpread(int skill) {
        return Math.max(1.0F, 4.0F - 0.6F * skill);
    }

    public enum Stance {
        RETREAT,
        KITE,
        SHOOT,
        APPROACH,
        MELEE
    }

    public static Stance stance(boolean ranged, double distance, float healthFraction, double retreatBelow) {
        if (healthFraction < retreatBelow) {
            return Stance.RETREAT;
        }
        if (ranged) {
            if (distance < 6.0) {
                return Stance.KITE;
            }
            if (distance > 16.0) {
                return Stance.APPROACH;
            }
            return Stance.SHOOT;
        }
        return distance <= 2.4 ? Stance.MELEE : Stance.APPROACH;
    }
}
