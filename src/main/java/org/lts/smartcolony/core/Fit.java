package org.lts.smartcolony.core;

public record Fit(Pos pos, Part part, Dir dir) {
    public enum Part {
        LOG_X,
        LOG_Y,
        LOG_Z,
        FENCE,
        STAIR,
        STAIR_TOP,
        LANDING,
        PLANK,
        SLAB,
        CURB,
        LAMP,
        HANG_LAMP,
        ROPE,
        BARREL,
        TABLE
    }

    public enum Dir {
        NORTH(0, -1),
        EAST(1, 0),
        SOUTH(0, 1),
        WEST(-1, 0);

        private final int dx;
        private final int dz;

        Dir(int dx, int dz) {
            this.dx = dx;
            this.dz = dz;
        }

        public int dx() {
            return dx;
        }

        public int dz() {
            return dz;
        }

        public String key() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        public Dir opposite() {
            return values()[(ordinal() + 2) % 4];
        }

        public static Dir of(int dx, int dz) {
            for (Dir dir : values()) {
                if (dir.dx == Integer.signum(dx) && dir.dz == Integer.signum(dz) && Math.abs(dx) + Math.abs(dz) == 1) {
                    return dir;
                }
            }
            throw new IllegalArgumentException("not a unit step: " + dx + "," + dz);
        }
    }

    public Fit(Pos pos, Part part) {
        this(pos, part, null);
    }

    public boolean isLog() {
        return part == Part.LOG_X || part == Part.LOG_Y || part == Part.LOG_Z;
    }
}
