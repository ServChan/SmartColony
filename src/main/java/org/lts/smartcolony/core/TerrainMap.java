package org.lts.smartcolony.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class TerrainMap {
    public enum Surface {
        UNKNOWN('?'),
        GROUND('.'),
        PATH(','),
        ROAD('='),
        WATER('~'),
        TREE('T'),
        FARMLAND('f'),
        STRUCTURE('#'),
        BUILDING('B'),
        QUARRY('Q');

        private final char symbol;

        Surface(char symbol) {
            this.symbol = symbol;
        }

        public char symbol() {
            return symbol;
        }

        public boolean buildable() {
            return this == GROUND || this == PATH;
        }

        public boolean foreign() {
            return this == BUILDING || this == STRUCTURE || this == FARMLAND;
        }
    }

    public record Column(int ground, Surface surface, int depth) {
    }

    public record Stats(int columns, int known, int min, int max, int median, int maxStep, int[] counts) {
        public int count(Surface surface) {
            return counts[surface.ordinal()];
        }

        public boolean complete() {
            return columns > 0 && known == columns;
        }
    }

    public record Change(int x, int z, Column before, Column after) {
    }

    public record Diff(int compared, int unknown, List<Change> changes) {
    }

    public interface Sampler {
        Column sample(int x, int z);
    }

    private static final Column UNKNOWN = new Column(0, Surface.UNKNOWN, 0);
    private static final Surface[] SURFACES = Surface.values();

    private final int centerX;
    private final int centerZ;
    private final int radius;
    private final int size;
    private final int minX;
    private final int minZ;
    private final int[] ground;
    private final byte[] surface;
    private final byte[] depth;
    private final long[] scanned;
    private int[] order;
    private int cursor;
    private int wraps;

    public TerrainMap(int centerX, int centerZ, int radius) {
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.radius = Math.max(1, radius);
        this.size = this.radius * 2 + 1;
        this.minX = centerX - this.radius;
        this.minZ = centerZ - this.radius;
        int total = size * size;
        this.ground = new int[total];
        this.surface = new byte[total];
        this.depth = new byte[total];
        this.scanned = new long[total];
    }

    public int centerX() {
        return centerX;
    }

    public int centerZ() {
        return centerZ;
    }

    public int radius() {
        return radius;
    }

    public int size() {
        return size;
    }

    public int columns() {
        return size * size;
    }

    public int minX() {
        return minX;
    }

    public int minZ() {
        return minZ;
    }

    public boolean contains(int x, int z) {
        return x >= minX && z >= minZ && x < minX + size && z < minZ + size;
    }

    private int index(int x, int z) {
        return (z - minZ) * size + (x - minX);
    }

    public int xOf(int index) {
        return minX + index % size;
    }

    public int zOf(int index) {
        return minZ + index / size;
    }

    public void set(int x, int z, Column column, long time) {
        if (!contains(x, z)) {
            return;
        }
        int i = index(x, z);
        ground[i] = column.ground();
        surface[i] = (byte) column.surface().ordinal();
        depth[i] = (byte) Math.max(0, Math.min(127, column.depth()));
        scanned[i] = time + 1;
    }

    public boolean known(int x, int z) {
        if (!contains(x, z)) {
            return false;
        }
        int i = index(x, z);
        return scanned[i] != 0 && surface[i] != Surface.UNKNOWN.ordinal();
    }

    public long scannedAt(int x, int z) {
        return contains(x, z) && scanned[index(x, z)] != 0 ? scanned[index(x, z)] - 1 : -1;
    }

    public Column column(int x, int z) {
        if (!known(x, z)) {
            return UNKNOWN;
        }
        int i = index(x, z);
        return new Column(ground[i], SURFACES[surface[i]], depth[i]);
    }

    public void paint(int x0, int z0, int x1, int z1, Surface value, long time) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
            for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                if (known(x, z)) {
                    Column old = column(x, z);
                    set(x, z, new Column(old.ground(), value, old.depth()), time);
                }
            }
        }
    }

    public Stats region(int x0, int z0, int x1, int z1) {
        int lowX = Math.min(x0, x1);
        int highX = Math.max(x0, x1);
        int lowZ = Math.min(z0, z1);
        int highZ = Math.max(z0, z1);
        int columns = (highX - lowX + 1) * (highZ - lowZ + 1);
        int[] counts = new int[SURFACES.length];
        int[] grounds = new int[columns];
        int known = 0;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        int maxStep = 0;
        for (int x = lowX; x <= highX; x++) {
            for (int z = lowZ; z <= highZ; z++) {
                if (!known(x, z)) {
                    counts[Surface.UNKNOWN.ordinal()]++;
                    continue;
                }
                int i = index(x, z);
                counts[surface[i]]++;
                grounds[known++] = ground[i];
                min = Math.min(min, ground[i]);
                max = Math.max(max, ground[i]);
                if (x > lowX && known(x - 1, z)) {
                    maxStep = Math.max(maxStep, Math.abs(ground[i] - ground[index(x - 1, z)]));
                }
                if (z > lowZ && known(x, z - 1)) {
                    maxStep = Math.max(maxStep, Math.abs(ground[i] - ground[index(x, z - 1)]));
                }
            }
        }
        if (known == 0) {
            return new Stats(columns, 0, 0, 0, 0, 0, counts);
        }
        Arrays.sort(grounds, 0, known);
        return new Stats(columns, known, min, max, grounds[known / 2], maxStep, counts);
    }

    public Diff diff(int x0, int z0, int x1, int z1, Sampler fresh) {
        List<Change> changes = new ArrayList<>();
        int compared = 0;
        int unknown = 0;
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
            for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                Column now = fresh.sample(x, z);
                if (now == null || !known(x, z)) {
                    unknown++;
                    continue;
                }
                compared++;
                Column before = column(x, z);
                if (before.ground() != now.ground() || before.surface() != now.surface()) {
                    changes.add(new Change(x, z, before, now));
                }
            }
        }
        return new Diff(compared, unknown, changes);
    }

    public int knownCount() {
        int known = 0;
        for (int i = 0; i < scanned.length; i++) {
            if (scanned[i] != 0 && surface[i] != Surface.UNKNOWN.ordinal()) {
                known++;
            }
        }
        return known;
    }

    public int[] counts() {
        int[] counts = new int[SURFACES.length];
        for (int i = 0; i < scanned.length; i++) {
            counts[scanned[i] == 0 ? Surface.UNKNOWN.ordinal() : surface[i]]++;
        }
        return counts;
    }

    public void copyFrom(TerrainMap other) {
        for (int i = 0; i < other.scanned.length; i++) {
            if (other.scanned[i] == 0) {
                continue;
            }
            int x = other.xOf(i);
            int z = other.zOf(i);
            if (contains(x, z)) {
                int j = index(x, z);
                ground[j] = other.ground[i];
                surface[j] = other.surface[i];
                depth[j] = other.depth[i];
                scanned[j] = other.scanned[i];
            }
        }
    }

    private int[] order() {
        if (order == null) {
            long[] keys = new long[columns()];
            for (int i = 0; i < keys.length; i++) {
                long dx = xOf(i) - centerX;
                long dz = zOf(i) - centerZ;
                keys[i] = ((dx * dx + dz * dz) << 24) | i;
            }
            Arrays.sort(keys);
            order = new int[keys.length];
            for (int i = 0; i < keys.length; i++) {
                order[i] = (int) (keys[i] & 0xFFFFFF);
            }
        }
        return order;
    }

    public int next() {
        int[] sequence = order();
        int index = sequence[cursor];
        cursor++;
        if (cursor >= sequence.length) {
            cursor = 0;
            wraps++;
        }
        return index;
    }

    public int wraps() {
        return wraps;
    }

    public String ascii(int cx, int cz, int reach) {
        StringBuilder out = new StringBuilder();
        for (int z = cz - reach; z <= cz + reach; z++) {
            for (int x = cx - reach; x <= cx + reach; x++) {
                out.append(column(x, z).surface().symbol());
            }
            out.append('\n');
        }
        return out.toString();
    }
}
