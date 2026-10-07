package org.lts.smartcolony.core;

public final class WallPlanner {
    public static final double UNLOADED = 1000;
    public static final int MAX_WATER_DEPTH = 3;
    public static final double DEEP_WATER = 30;
    public static final double SHALLOW_WATER = 3;
    public static final double OBSTACLE = 40;

    private static final double SHIFT_COST = 0.6;
    private static final double CURVATURE_COST = 0.05;
    private static final double SMOOTH_TOLERANCE = 2;
    private static final int SAMPLE_STEP = 2;

    public interface Terrain {
        boolean loaded(int x, int z);

        int height(int x, int z);

        double penalty(int x, int z);
    }

    private WallPlanner() {
    }

    public static double waterPenalty(int depth) {
        if (depth <= 0) {
            return 0;
        }
        return depth > MAX_WATER_DEPTH ? DEEP_WATER : SHALLOW_WATER * depth;
    }

    public static double roughness(int[] heights, int count) {
        double total = 0;
        for (int i = 1; i < count; i++) {
            int step = Math.abs(heights[i] - heights[i - 1]);
            total += step > 2 ? 6.0 * step : 0.5 * step * step;
        }
        return total;
    }

    public static double segmentCost(Terrain terrain, int x0, int z0, int x1, int z1) {
        int dx = x1 - x0;
        int dz = z1 - z0;
        int length = Math.max(Math.abs(dx), Math.abs(dz));
        int samples = Math.max(1, length / SAMPLE_STEP) + 1;
        int[] heights = new int[samples];
        double total = 0;
        for (int i = 0; i < samples; i++) {
            double t = samples == 1 ? 0 : (double) i / (samples - 1);
            int x = (int) Math.round(x0 + dx * t);
            int z = (int) Math.round(z0 + dz * t);
            if (!terrain.loaded(x, z)) {
                return UNLOADED;
            }
            heights[i] = terrain.height(x, z);
            total += terrain.penalty(x, z);
        }
        return total + roughness(heights, samples);
    }

    public static double[] optimize(int cx, int cz, double[] base, Terrain terrain, int window, int sweeps) {
        int n = base.length;
        double[] radii = base.clone();
        boolean[] movable = new boolean[n];
        for (int k = 0; k < n; k++) {
            movable[k] = terrain.loaded(vx(cx, k, n, radii[k]), vz(cz, k, n, radii[k]));
        }
        for (int sweep = 0; sweep < sweeps; sweep++) {
            boolean changed = false;
            for (int k = 0; k < n; k++) {
                if (!movable[k]) {
                    continue;
                }
                double best = base[k];
                double bestCost = vertexCost(cx, cz, radii, base, k, base[k], terrain);
                for (int shift = 1; shift <= window; shift++) {
                    double candidate = base[k] + shift;
                    double cost = vertexCost(cx, cz, radii, base, k, candidate, terrain);
                    if (cost < bestCost - 1e-9) {
                        bestCost = cost;
                        best = candidate;
                    }
                }
                if (best != radii[k]) {
                    radii[k] = best;
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        for (int pass = 0; pass < 2; pass++) {
            double[] next = radii.clone();
            for (int k = 0; k < n; k++) {
                if (!movable[k]) {
                    continue;
                }
                double smooth = (radii[Math.floorMod(k - 1, n)] + radii[k] * 2 + radii[(k + 1) % n]) / 4.0;
                double current = vertexCost(cx, cz, radii, base, k, radii[k], terrain);
                if (vertexCost(cx, cz, radii, base, k, smooth, terrain) <= current + SMOOTH_TOLERANCE) {
                    next[k] = smooth;
                }
            }
            radii = next;
        }
        return radii;
    }

    private static double vertexCost(int cx, int cz, double[] radii, double[] base, int k, double r, Terrain terrain) {
        int n = radii.length;
        int prev = Math.floorMod(k - 1, n);
        int next = (k + 1) % n;
        int x = vx(cx, k, n, r);
        int z = vz(cz, k, n, r);
        double cost = segmentCost(terrain, vx(cx, prev, n, radii[prev]), vz(cz, prev, n, radii[prev]), x, z)
                + segmentCost(terrain, x, z, vx(cx, next, n, radii[next]), vz(cz, next, n, radii[next]));
        double bend = 2 * r - radii[prev] - radii[next];
        return cost + CURVATURE_COST * bend * bend + SHIFT_COST * Math.max(0, r - base[k]);
    }

    public static int vx(int cx, int k, int n, double r) {
        return cx + (int) Math.round(Math.cos(2 * Math.PI * k / n) * r);
    }

    public static int vz(int cz, int k, int n, double r) {
        return cz + (int) Math.round(Math.sin(2 * Math.PI * k / n) * r);
    }

    public static int[] heights(int[] ground, int[] wanted, boolean[] fixed, boolean[] linked, int maxStep, int cap) {
        int n = ground.length;
        int[] level = new int[n];
        for (int i = 0; i < n; i++) {
            level[i] = ground[i] + wanted[i];
        }
        boolean changed = true;
        int guard = 0;
        while (changed && guard++ < n + 4) {
            changed = false;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                if (!linked[i] || n < 2) {
                    continue;
                }
                if (!fixed[j] && level[j] < level[i] - maxStep && level[i] - maxStep - ground[j] <= cap) {
                    level[j] = level[i] - maxStep;
                    changed = true;
                } else if (!fixed[j] && level[j] < level[i] - maxStep) {
                    int lifted = ground[j] + cap;
                    if (lifted > level[j]) {
                        level[j] = lifted;
                        changed = true;
                    }
                }
                if (!fixed[i] && level[i] < level[j] - maxStep && level[j] - maxStep - ground[i] <= cap) {
                    level[i] = level[j] - maxStep;
                    changed = true;
                } else if (!fixed[i] && level[i] < level[j] - maxStep) {
                    int lifted = ground[i] + cap;
                    if (lifted > level[i]) {
                        level[i] = lifted;
                        changed = true;
                    }
                }
            }
        }
        int[] heights = new int[n];
        for (int i = 0; i < n; i++) {
            heights[i] = level[i] - ground[i];
        }
        return heights;
    }
}
