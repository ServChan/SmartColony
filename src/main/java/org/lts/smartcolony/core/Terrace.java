package org.lts.smartcolony.core;

public final class Terrace {
    public static final int MAX_CUT = 8;
    public static final int MARGIN = 9;
    public static final int APRON = 1;
    public static final int FILL_RINGS = 3;
    public static final int DOOR_DEPTH = 3;
    public static final int DOOR_FILL = 3;

    public record Plan(int[][] target, int cut, int fill, int maxCut) {
        public int at(int x, int z) {
            return target[x][z];
        }
    }

    public record Door(int x, int z, int dx, int dz) {
    }

    private Terrace() {
    }

    public static int ring(int x, int z, int margin, int sx, int sz) {
        int dx = Math.max(Math.max(margin - x, x - (margin + sx - 1)), 0);
        int dz = Math.max(Math.max(margin - z, z - (margin + sz - 1)), 0);
        return Math.max(dx, dz);
    }

    public static boolean inDoorway(int x, int z, Door door) {
        if (door == null) {
            return false;
        }
        for (int step = 0; step < DOOR_DEPTH; step++) {
            int cx = door.x() + door.dx() * step;
            int cz = door.z() + door.dz() * step;
            if (door.dx() != 0 && x == cx && Math.abs(z - cz) <= 1 || door.dz() != 0 && z == cz && Math.abs(x - cx) <= 1) {
                return true;
            }
        }
        return false;
    }

    public static int target(int ground, int ring, int base, boolean doorway) {
        if (ring == 0) {
            return Math.min(ground, base);
        }
        if (doorway) {
            if (ground > base || base - ground <= DOOR_FILL) {
                return base;
            }
            return ground;
        }
        int step = Math.max(0, ring - APRON);
        if (ground > base + step) {
            return base + step;
        }
        if (ring <= FILL_RINGS && ground < base - step) {
            return base - step;
        }
        return ground;
    }

    public static Plan plan(int[][] ground, boolean[][] fixed, int margin, int sx, int sz, int base, Door door) {
        int width = ground.length;
        int depth = ground[0].length;
        int[][] target = new int[width][depth];
        int cut = 0;
        int fill = 0;
        int maxCut = 0;
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                int g = ground[x][z];
                int want = fixed[x][z] ? g : target(g, ring(x, z, margin, sx, sz), base, inDoorway(x, z, door));
                target[x][z] = want;
                if (want < g) {
                    cut += g - want;
                    maxCut = Math.max(maxCut, g - want);
                } else {
                    fill += want - g;
                }
            }
        }
        return new Plan(target, cut, fill, maxCut);
    }

    public static double cost(int footprintFill, int footprintCut, int maxCut) {
        return footprintFill * 2.0 + footprintCut * 0.2 + maxCut * 1.5;
    }
}
