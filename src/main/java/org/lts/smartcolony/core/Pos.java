package org.lts.smartcolony.core;

public record Pos(int x, int y, int z) {
    public Pos offset(int dx, int dy, int dz) {
        return new Pos(x + dx, y + dy, z + dz);
    }

    public Pos above(int n) {
        return new Pos(x, y + n, z);
    }

    public int distManhattan(Pos other) {
        return Math.abs(x - other.x) + Math.abs(y - other.y) + Math.abs(z - other.z);
    }

    public double distSq(Pos other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public double horizontalDistSq(Pos other) {
        double dx = x - other.x;
        double dz = z - other.z;
        return dx * dx + dz * dz;
    }

    @Override
    public String toString() {
        return x + " " + y + " " + z;
    }
}
