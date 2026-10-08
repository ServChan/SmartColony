package org.lts.smartcolony.core;

import java.util.List;

public final class Clearance {
    public static final int DEPTH = 4;
    public static final int HALF_WIDTH = 1;
    public static final int GAP = 1;

    public record Box(int minX, int minZ, int maxX, int maxZ) {
        public static Box of(int x0, int z0, int x1, int z1) {
            return new Box(Math.min(x0, x1), Math.min(z0, z1), Math.max(x0, x1), Math.max(z0, z1));
        }

        public Box grow(int margin) {
            return new Box(minX - margin, minZ - margin, maxX + margin, maxZ + margin);
        }

        public boolean intersects(Box other) {
            return minX <= other.maxX && maxX >= other.minX && minZ <= other.maxZ && maxZ >= other.minZ;
        }

        public boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    public record Door(int x, int z, int dx, int dz) {
        public Box corridor(int depth, int halfWidth) {
            int ex = x + dx * (depth - 1);
            int ez = z + dz * (depth - 1);
            int sideX = dz != 0 ? halfWidth : 0;
            int sideZ = dx != 0 ? halfWidth : 0;
            return Box.of(x - sideX, z - sideZ, ex + sideX, ez + sideZ);
        }

        public Box corridor() {
            return corridor(DEPTH, HALF_WIDTH);
        }
    }

    private Clearance() {
    }

    public static int[] outward(int doorX, int doorZ, int sizeX, int sizeZ, int rotation) {
        int[] inside = Blueprint.rotate(doorX, doorZ, sizeX, sizeZ, rotation);
        int[] front = Blueprint.rotate(doorX, doorZ - 1, sizeX, sizeZ, rotation);
        return new int[] {Integer.signum(front[0] - inside[0]), Integer.signum(front[1] - inside[1])};
    }

    public static boolean blocksDoor(Box footprint, List<Door> doors) {
        Box grown = footprint.grow(GAP);
        for (Door door : doors) {
            Box corridor = door.corridor();
            if (grown.intersects(corridor) && !footprint.contains(door.x(), door.z())) {
                return true;
            }
        }
        return false;
    }

    public static boolean doorObstructed(Door own, Box ownFootprint, List<Box> obstacles) {
        Box corridor = own.corridor();
        for (Box obstacle : obstacles) {
            if (obstacle.grow(GAP).intersects(corridor) && !obstacle.equals(ownFootprint)) {
                return true;
            }
        }
        return false;
    }

    public static boolean fits(Box footprint, Door own, List<Box> obstacles, List<Door> doors) {
        return !blocksDoor(footprint, doors) && (own == null || !doorObstructed(own, footprint, obstacles));
    }

    public static boolean inCorridor(int x, int z, List<Door> doors) {
        for (Door door : doors) {
            if (door.corridor().contains(x, z)) {
                return true;
            }
        }
        return false;
    }
}
