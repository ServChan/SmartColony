package org.lts.smartcolony.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class HallRest {
    public static final int HEAL_PERIOD = 100;
    public static final float HEAL_AMOUNT = 1.0F;
    private static final List<String> UNSTABLE_SUFFIXES = List.of(
            "_stairs", "_slab", "_fence", "_fence_gate", "_trapdoor", "_carpet", "_pane", "_wall", "_button", "_pressure_plate",
            "_bed", "_door", "_sign", "_hanging_sign", "_banner");
    private static final List<String> UNSTABLE_NAMES = List.of(
            "torch", "wall_torch", "ladder", "lantern", "flower_pot", "bell", "cauldron", "chain", "campfire");

    private HallRest() {
    }

    public static boolean stableFloor(String state) {
        String path = Ids.path(Blueprint.blockId(state));
        for (String suffix : UNSTABLE_SUFFIXES) {
            if (path.endsWith(suffix)) {
                return false;
            }
        }
        for (String name : UNSTABLE_NAMES) {
            if (path.equals(name)) {
                return false;
            }
        }
        return !path.equals("air");
    }

    public static List<int[]> cells(Blueprint blueprint) {
        Map<Long, String> placed = new HashMap<>();
        for (Blueprint.Cell cell : blueprint.cells("oak")) {
            placed.put(key(cell.x(), cell.y(), cell.z()), cell.state());
        }
        int doorX = blueprint.doorX();
        int doorZ = blueprint.doorZ();
        List<int[]> result = new ArrayList<>();
        for (int y = 1; y + 1 < blueprint.sizeY(); y++) {
            for (int z = 0; z < blueprint.sizeZ(); z++) {
                for (int x = 0; x < blueprint.sizeX(); x++) {
                    if (blueprint.at(x, y, z) != Blueprint.AIR || blueprint.at(x, y + 1, z) != Blueprint.AIR) {
                        continue;
                    }
                    if (Math.abs(x - doorX) <= 1 && Math.abs(z - doorZ) <= 1) {
                        continue;
                    }
                    String floor = placed.get(key(x, y - 1, z));
                    if (floor == null || !stableFloor(floor) || !roofed(blueprint, y + 2, x, z)) {
                        continue;
                    }
                    result.add(new int[]{x, y, z});
                }
            }
        }
        result.sort(Comparator.<int[]>comparingInt(c -> c[1])
                .thenComparingInt(c -> Math.abs(c[0] - doorX) + Math.abs(c[2] - doorZ))
                .thenComparingInt(c -> c[2]).thenComparingInt(c -> c[0]));
        return result;
    }

    private static boolean roofed(Blueprint blueprint, int fromY, int x, int z) {
        for (int y = fromY; y < blueprint.sizeY(); y++) {
            char c = blueprint.at(x, y, z);
            if (c != Blueprint.AIR && c != Blueprint.ANY) {
                return true;
            }
        }
        return false;
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0xFFFFF) << 40 | ((long) y & 0xFFFFF) << 20 | ((long) z & 0xFFFFF);
    }

    public static int slot(List<String> ids, String id, int cells) {
        if (cells <= 0) {
            return -1;
        }
        List<String> sorted = new ArrayList<>(ids);
        sorted.sort(String::compareTo);
        int rank = sorted.indexOf(id);
        if (rank < 0) {
            rank = Math.floorMod(id.hashCode(), cells);
        }
        return rank % cells;
    }

    public static boolean restsHere(boolean hallFinished, boolean bedless, boolean guard, boolean baby, boolean workTime) {
        return hallFinished && bedless && !guard && !baby && !workTime;
    }
}
