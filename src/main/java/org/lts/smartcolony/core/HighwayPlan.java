package org.lts.smartcolony.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

public final class HighwayPlan {
    public static final int MAX_LENGTH = 640;
    public static final int SEGMENT = 48;
    public static final int CORRIDOR = 28;
    public static final int MAX_NODES = 9000;
    public static final int CAMP_FROM = 200;
    public static final int CAMP_SPACING = 160;
    public static final int GOAL_REACH = 3;
    public static final int[] SIDESTEPS = {0, 14, -14, 28, -28};

    public interface Grid {
        double cost(int x, int z);

        int height(int x, int z);
    }

    private HighwayPlan() {
    }

    public static int[] edge(int cx, int cz, int tx, int tz, int radius) {
        double dx = tx - cx;
        double dz = tz - cz;
        double length = Math.max(1.0, Math.hypot(dx, dz));
        return new int[] {cx + (int) Math.round(dx / length * radius), cz + (int) Math.round(dz / length * radius)};
    }

    public static List<int[]> waypoints(int ax, int az, int bx, int bz, int step) {
        List<int[]> points = new ArrayList<>();
        double length = Math.hypot(bx - ax, bz - az);
        int parts = Math.max(1, (int) Math.ceil(length / step));
        for (int i = 1; i <= parts; i++) {
            double t = (double) i / parts;
            points.add(new int[] {ax + (int) Math.round((bx - ax) * t), az + (int) Math.round((bz - az) * t)});
        }
        return points;
    }

    public static int[] sidestep(int ax, int az, int bx, int bz, int[] point, int offset) {
        if (offset == 0) {
            return point;
        }
        double dx = bx - ax;
        double dz = bz - az;
        double length = Math.max(1.0, Math.hypot(dx, dz));
        return new int[] {point[0] + (int) Math.round(-dz / length * offset), point[1] + (int) Math.round(dx / length * offset)};
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static final int[][] STEPS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    public static List<int[]> route(Grid grid, int sx, int sz, int gx, int gz, int corridor, int maxNodes) {
        double lineX = gx - sx;
        double lineZ = gz - sz;
        double lineLength = Math.max(1.0, Math.hypot(lineX, lineZ));
        Map<Long, Double> best = new HashMap<>();
        Map<Long, long[]> parent = new HashMap<>();
        PriorityQueue<double[]> open = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        if (grid.cost(sx, sz) < 0) {
            return List.of();
        }
        best.put(key(sx, sz), 0.0);
        open.add(new double[] {Math.hypot(gx - sx, gz - sz), 0.0, sx, sz});
        int expanded = 0;
        while (!open.isEmpty() && expanded < maxNodes) {
            double[] node = open.poll();
            int x = (int) node[2];
            int z = (int) node[3];
            double g = node[1];
            if (g > best.getOrDefault(key(x, z), Double.MAX_VALUE)) {
                continue;
            }
            expanded++;
            if (Math.abs(x - gx) <= GOAL_REACH && Math.abs(z - gz) <= GOAL_REACH) {
                return trace(parent, x, z, sx, sz);
            }
            int y = grid.height(x, z);
            for (int[] step : STEPS) {
                int nx = x + step[0];
                int nz = z + step[1];
                double off = Math.abs((nx - sx) * lineZ - (nz - sz) * lineX) / lineLength;
                if (off > corridor) {
                    continue;
                }
                double cost = grid.cost(nx, nz);
                if (cost < 0) {
                    continue;
                }
                int ny = grid.height(nx, nz);
                if (Math.abs(ny - y) > 1) {
                    continue;
                }
                boolean diagonal = step[0] != 0 && step[1] != 0;
                if (diagonal && (grid.cost(x + step[0], z) < 0 || grid.cost(x, z + step[1]) < 0
                        || Math.abs(grid.height(x + step[0], z) - y) > 1 || Math.abs(grid.height(x, z + step[1]) - y) > 1)) {
                    continue;
                }
                double move = (diagonal ? 1.42 : 1.0) * cost + (ny != y ? 0.6 : 0.0);
                double total = g + move;
                long k = key(nx, nz);
                if (total < best.getOrDefault(k, Double.MAX_VALUE)) {
                    best.put(k, total);
                    parent.put(k, new long[] {x, z});
                    open.add(new double[] {total + Math.hypot(gx - nx, gz - nz) * 0.9, total, nx, nz});
                }
            }
        }
        return List.of();
    }

    private static List<int[]> trace(Map<Long, long[]> parent, int x, int z, int sx, int sz) {
        List<int[]> path = new ArrayList<>();
        int cx = x;
        int cz = z;
        path.add(new int[] {cx, cz});
        while (cx != sx || cz != sz) {
            long[] p = parent.get(key(cx, cz));
            if (p == null) {
                break;
            }
            cx = (int) p[0];
            cz = (int) p[1];
            path.add(new int[] {cx, cz});
        }
        java.util.Collections.reverse(path);
        return path;
    }

    public static List<int[]> orthogonal(List<int[]> path) {
        List<int[]> result = new ArrayList<>();
        for (int i = 0; i < path.size(); i++) {
            int[] cell = path.get(i);
            if (i > 0) {
                int[] prev = result.getLast();
                if (prev[0] != cell[0] && prev[1] != cell[1]) {
                    result.add(new int[] {cell[0], prev[1]});
                }
            }
            result.add(cell);
        }
        return result;
    }

    public static List<Integer> camps(int length) {
        List<Integer> marks = new ArrayList<>();
        if (length < CAMP_FROM) {
            return marks;
        }
        int count = Math.max(1, length / CAMP_SPACING);
        for (int i = 1; i <= count; i++) {
            marks.add((int) Math.round((double) length * i / (count + 1)));
        }
        return marks;
    }

    public static int middle(int length) {
        return length / 2;
    }

    public static boolean wanted(Diplomacy.Treaty treaty, int standing, double distance) {
        return distance <= MAX_LENGTH && (treaty == Diplomacy.Treaty.TRADE || treaty == Diplomacy.Treaty.ALLIANCE
                || treaty == Diplomacy.Treaty.NONE && standing >= Diplomacy.FRIENDLY_AT);
    }
}
