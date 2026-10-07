package org.lts.smartcolony.core;

import java.util.ArrayList;
import java.util.List;

public final class TreeFarmPlan {
    public static final String ID = "tree_farm";
    public static final String KIND = "treefarm";
    public static final char PLOT = 'p';
    public static final int MAX_FILL = 4;
    public static final int MAX_CUT = 5;
    public static final int FIRST_FARM_ADULTS = 6;
    public static final int THIN_FOREST_ADULTS = 4;
    public static final int SECOND_FARM_ADULTS = 25;
    public static final int FOREST_THIN_BELOW = 6;

    private TreeFarmPlan() {
    }

    public static int wanted(int adults, boolean forestThin) {
        if (adults >= SECOND_FARM_ADULTS) {
            return 2;
        }
        if (adults >= FIRST_FARM_ADULTS || adults >= THIN_FOREST_ADULTS && forestThin) {
            return 1;
        }
        return 0;
    }

    public static boolean needed(int adults, int existing, boolean basicsBuilt, boolean forestThin) {
        return basicsBuilt && existing < wanted(adults, forestThin);
    }

    public static final int DIRT_STOCK_CAP = 96;
    public static final int DIRT_CARRY_CAP = 64;

    public static boolean keepDirt(int carried, int stock) {
        return stock < DIRT_STOCK_CAP && carried < DIRT_CARRY_CAP;
    }

    public static boolean forestThin(int knownTrees) {
        return knownTrees < FOREST_THIN_BELOW;
    }

    public static List<int[]> plots(Blueprint blueprint) {
        List<int[]> plots = new ArrayList<>();
        for (int z = 0; z < blueprint.sizeZ(); z++) {
            for (int x = 0; x < blueprint.sizeX(); x++) {
                if (blueprint.at(x, 0, z) == PLOT) {
                    plots.add(new int[]{x, z});
                }
            }
        }
        return plots;
    }
}
