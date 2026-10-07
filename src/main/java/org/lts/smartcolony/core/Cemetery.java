package org.lts.smartcolony.core;

import java.util.ArrayList;
import java.util.List;

public final class Cemetery {
    public static final String ID = "cemetery";
    public static final int WIDTH = 13;
    public static final int DEPTH = 13;
    public static final int[] COLUMNS = {2, 4, 8, 10};
    public static final int[] ROWS = {2, 5, 8};
    public static final int ADULT_THRESHOLD = 12;

    public record Slot(int index, int x, int z) {
        public int standX() {
            return x < WIDTH / 2 ? 3 : 9;
        }

        public int standZ() {
            return z + 1;
        }
    }

    private Cemetery() {
    }

    public static List<Slot> slots() {
        List<Slot> slots = new ArrayList<>();
        for (int z : ROWS) {
            for (int x : COLUMNS) {
                slots.add(new Slot(slots.size(), x, z));
            }
        }
        return slots;
    }

    public static int capacity() {
        return COLUMNS.length * ROWS.length;
    }

    public static boolean needed(int adults, int deaths, int cemeteries, int projects, int freeSlots, int unburied) {
        if (projects > 0) {
            return false;
        }
        if (cemeteries == 0) {
            return deaths > 0 || adults >= ADULT_THRESHOLD;
        }
        return freeSlots <= 0 && unburied > 0;
    }

    public static boolean undertakerWanted(int pendingRemains, int unburied, int freeSlots) {
        return pendingRemains > 0 || unburied > 0 && freeSlots > 0;
    }
}
