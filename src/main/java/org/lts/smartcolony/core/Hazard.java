package org.lts.smartcolony.core;

import java.util.Arrays;
import java.util.List;

public final class Hazard {
    public enum Kind {
        NONE,
        MAGMA,
        CAMPFIRE,
        FIRE,
        LAVA;

        public boolean burns() {
            return this == FIRE || this == LAVA || this == CAMPFIRE;
        }
    }

    public static final int CUBE = 27;
    public static final int CENTER = 13;

    private Hazard() {
    }

    public static int index(int dx, int dy, int dz) {
        return (dy + 1) * 9 + (dz + 1) * 3 + (dx + 1);
    }

    public static Kind classify(boolean lava, boolean fire, boolean litCampfire, boolean magma) {
        if (lava) {
            return Kind.LAVA;
        }
        if (fire) {
            return Kind.FIRE;
        }
        if (litCampfire) {
            return Kind.CAMPFIRE;
        }
        return magma ? Kind.MAGMA : Kind.NONE;
    }

    public static Kind[] emptyCube() {
        Kind[] cube = new Kind[CUBE];
        Arrays.fill(cube, Kind.NONE);
        return cube;
    }

    public static boolean threatened(Kind[] cube, boolean ablaze, boolean tolerateAdjacent) {
        if (ablaze || inCell(cube)) {
            return true;
        }
        return !tolerateAdjacent && ringBurns(cube);
    }

    public static boolean unsafeTarget(Kind[] cube, boolean tolerateAdjacent) {
        if (inCell(cube)) {
            return true;
        }
        if (tolerateAdjacent) {
            return false;
        }
        if (ringBurns(cube)) {
            return true;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if ((dx != 0 || dz != 0) && cube[index(dx, -1, dz)] == Kind.LAVA) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean inCell(Kind[] cube) {
        return cube[CENTER].burns() || cube[index(0, 1, 0)].burns() || cube[index(0, -1, 0)] != Kind.NONE;
    }

    private static boolean ringBurns(Kind[] cube) {
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if ((dx != 0 || dz != 0) && cube[index(dx, dy, dz)].burns()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static boolean farFrom(int x, int y, int z, List<int[]> sources, double minimum) {
        double limit = minimum * minimum;
        for (int[] source : sources) {
            int dx = source[0] - x;
            int dy = source[1] - y;
            int dz = source[2] - z;
            if (dx * dx + dy * dy + dz * dz < limit) {
                return false;
            }
        }
        return true;
    }
}
