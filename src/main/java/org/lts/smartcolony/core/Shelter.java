package org.lts.smartcolony.core;

import java.util.List;
import java.util.function.Predicate;

public final class Shelter {
    public record Spot(int x, int y, int z, int load) {
    }

    private Shelter() {
    }

    public static boolean wants(Role role, boolean thundering, boolean alarmed, boolean keepsWorking) {
        if (thundering) {
            return !(role == Role.GUARD && alarmed);
        }
        if (keepsWorking) {
            return false;
        }
        return switch (role) {
            case MINER, FISHERMAN, GUARD, MEDIC -> false;
            default -> true;
        };
    }

    public static boolean keepsWorking(int hash, double ratio) {
        return (hash & 0xFFFF) / 65536.0 < ratio;
    }

    public static int pick(List<Spot> spots, int fromX, int fromY, int fromZ, double maxDistance, double loadWeight,
            Predicate<Spot> skip) {
        int best = -1;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < spots.size(); i++) {
            Spot spot = spots.get(i);
            if (skip.test(spot)) {
                continue;
            }
            double dx = spot.x() - fromX;
            double dy = (spot.y() - fromY) * 2.0;
            double dz = spot.z() - fromZ;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance > maxDistance) {
                continue;
            }
            double score = distance + loadWeight * spot.load();
            if (score < bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }
}
