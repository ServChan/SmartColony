package org.lts.smartcolony.core;

public final class Pier {
    public static final int MAX_DEPTH = 4;
    public static final int POST_SPACING = 3;
    public static final int LAMP_SPACING = 8;
    public static final int POST = 1;
    public static final int LAMP = 2;
    public static final int MAX_ROUTE_CELLS = 48;
    public static final double DECK_COST = 3.0;
    public static final double DEPTH_COST = 0.4;
    public static final double MIN_WATER_SHARE = 0.3;
    public static final double WATER_VILLAGE_SHARE = 0.08;
    public static final String DOCK_KIND = "dock";
    public static final String FISHER_KIND = "fisher";
    public static final String DOCK_ID = "dock";
    public static final String FISHER_ID = "fisher_hut";

    private Pier() {
    }

    public static boolean shallow(int depth) {
        return depth >= 1 && depth <= MAX_DEPTH;
    }

    public static double cost(int depth) {
        return DECK_COST + DEPTH_COST * depth;
    }

    public static boolean waterKind(String kind) {
        return "house".equals(kind) || needsWater(kind);
    }

    public static boolean needsWater(String kind) {
        return DOCK_KIND.equals(kind) || FISHER_KIND.equals(kind);
    }

    public static double platformPenalty(int waterColumns) {
        return waterColumns == 0 ? 0.0 : 6.0 + 0.35 * waterColumns;
    }

    public static boolean waterVillage(int waterColumns, int columns) {
        return columns > 0 && (double) waterColumns / columns >= WATER_VILLAGE_SHARE;
    }

    public static boolean enoughWater(String kind, int waterColumns, int columns) {
        return !needsWater(kind) || columns > 0 && (double) waterColumns / columns >= MIN_WATER_SHARE;
    }

    public static int[] markers(boolean[] water) {
        int[] flags = new int[water.length];
        int i = 0;
        while (i < water.length) {
            if (!water[i]) {
                i++;
                continue;
            }
            int end = i;
            while (end + 1 < water.length && water[end + 1]) {
                end++;
            }
            for (int k = i; k <= end; k++) {
                int run = k - i;
                boolean lamp = run % LAMP_SPACING == LAMP_SPACING / 2;
                if (run == 0 || k == end || run % POST_SPACING == 0 || lamp) {
                    flags[k] |= POST;
                }
                if (lamp) {
                    flags[k] |= LAMP;
                }
            }
            i = end + 1;
        }
        return flags;
    }

    public static boolean[] railing(boolean north, boolean east, boolean south, boolean west) {
        boolean[] open = {!north, !east, !south, !west};
        int walkable = (north ? 1 : 0) + (east ? 1 : 0) + (south ? 1 : 0) + (west ? 1 : 0);
        boolean[] rails = new boolean[4];
        if (walkable == 0 || walkable == 4) {
            return rails;
        }
        for (int side = 0; side < 4; side++) {
            rails[side] = open[side];
        }
        if (walkable == 1) {
            int from = north ? 0 : east ? 1 : south ? 2 : 3;
            rails[(from + 2) % 4] = false;
        }
        return rails;
    }

    public static boolean isPost(boolean[][] floor, int x, int z) {
        if (!floor[x][z]) {
            return false;
        }
        boolean east = solid(floor, x + 1, z);
        boolean west = solid(floor, x - 1, z);
        boolean south = solid(floor, x, z + 1);
        boolean north = solid(floor, x, z - 1);
        int neighbours = (east ? 1 : 0) + (west ? 1 : 0) + (south ? 1 : 0) + (north ? 1 : 0);
        boolean straight = east && west || north && south;
        return neighbours <= 1 || neighbours == 2 && !straight || (x + z) % POST_SPACING == 0;
    }

    private static boolean solid(boolean[][] floor, int x, int z) {
        return x >= 0 && z >= 0 && x < floor.length && z < floor[0].length && floor[x][z];
    }
}
