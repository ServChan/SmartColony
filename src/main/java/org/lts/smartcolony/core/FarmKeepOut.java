package org.lts.smartcolony.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class FarmKeepOut {
    public static final int ROAD_MARGIN = 1;
    public static final int FOREIGN_MARGIN = 2;

    private record Box(int minX, int minZ, int maxX, int maxZ) {
        boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    private record Disc(int x, int z, int radius) {
        boolean contains(int px, int pz) {
            long dx = px - x;
            long dz = pz - z;
            return dx * dx + dz * dz <= (long) radius * radius;
        }
    }

    private final List<Box> boxes = new ArrayList<>();
    private final List<Disc> discs = new ArrayList<>();
    private final Set<Long> cells = new HashSet<>();

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    public void addBox(int minX, int minZ, int maxX, int maxZ, int margin) {
        boxes.add(new Box(Math.min(minX, maxX) - margin, Math.min(minZ, maxZ) - margin,
                Math.max(minX, maxX) + margin, Math.max(minZ, maxZ) + margin));
    }

    public void addDisc(int x, int z, int radius) {
        discs.add(new Disc(x, z, radius));
    }

    public void addCell(int x, int z) {
        cells.add(key(x, z));
    }

    public int size() {
        return boxes.size() + discs.size() + cells.size();
    }

    public boolean blocked(int x, int z) {
        for (Box box : boxes) {
            if (box.contains(x, z)) {
                return true;
            }
        }
        for (Disc disc : discs) {
            if (disc.contains(x, z)) {
                return true;
            }
        }
        if (cells.isEmpty()) {
            return false;
        }
        for (int dx = -ROAD_MARGIN; dx <= ROAD_MARGIN; dx++) {
            for (int dz = -ROAD_MARGIN; dz <= ROAD_MARGIN; dz++) {
                if (cells.contains(key(x + dx, z + dz))) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean terrainBlocked(TerrainMap map, int x, int z) {
        if (map == null) {
            return false;
        }
        for (int dx = -FOREIGN_MARGIN; dx <= FOREIGN_MARGIN; dx++) {
            for (int dz = -FOREIGN_MARGIN; dz <= FOREIGN_MARGIN; dz++) {
                TerrainMap.Surface surface = map.column(x + dx, z + dz).surface();
                boolean near = Math.abs(dx) <= ROAD_MARGIN && Math.abs(dz) <= ROAD_MARGIN;
                if (surface == TerrainMap.Surface.STRUCTURE || surface == TerrainMap.Surface.QUARRY
                        || near && (surface == TerrainMap.Surface.ROAD || surface == TerrainMap.Surface.PATH)) {
                    return true;
                }
            }
        }
        return false;
    }
}
