package org.lts.smartcolony.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

public final class RoadRouter {
    public static final double STEP_COST = 0.8;

    public interface Grid {
        double cost(int x, int z);

        int height(int x, int z);

        boolean goal(int x, int z);
    }

    private record Node(int x, int z, double f) {
    }

    private RoadRouter() {
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static final int[][] STEPS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    public static List<int[]> route(Grid grid, int sx, int sz, int maxNodes) {
        if (grid.cost(sx, sz) < 0) {
            return List.of();
        }
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f(), b.f()));
        Map<Long, Double> best = new HashMap<>();
        Map<Long, long[]> parent = new HashMap<>();
        Set<Long> closed = new HashSet<>();
        open.add(new Node(sx, sz, 0));
        best.put(key(sx, sz), 0.0);
        int expanded = 0;
        while (!open.isEmpty() && expanded < maxNodes) {
            Node node = open.poll();
            long nodeKey = key(node.x(), node.z());
            if (!closed.add(nodeKey)) {
                continue;
            }
            expanded++;
            if (grid.goal(node.x(), node.z())) {
                return trace(parent, node.x(), node.z());
            }
            int h = grid.height(node.x(), node.z());
            for (int[] step : STEPS) {
                int nx = node.x() + step[0];
                int nz = node.z() + step[1];
                if (closed.contains(key(nx, nz))) {
                    continue;
                }
                double cost = grid.cost(nx, nz);
                if (cost < 0) {
                    continue;
                }
                int dh = grid.height(nx, nz) - h;
                if (Math.abs(dh) > 1) {
                    continue;
                }
                double next = node.f() + cost + (dh != 0 ? STEP_COST : 0);
                long k = key(nx, nz);
                if (next < best.getOrDefault(k, Double.MAX_VALUE)) {
                    best.put(k, next);
                    parent.put(k, new long[]{node.x(), node.z()});
                    open.add(new Node(nx, nz, next));
                }
            }
        }
        return List.of();
    }

    private static List<int[]> trace(Map<Long, long[]> parent, int gx, int gz) {
        List<int[]> path = new ArrayList<>();
        int cx = gx;
        int cz = gz;
        while (true) {
            path.add(new int[]{cx, cz});
            long[] from = parent.get(key(cx, cz));
            if (from == null) {
                break;
            }
            cx = (int) from[0];
            cz = (int) from[1];
        }
        Collections.reverse(path);
        return path;
    }

    public static List<int[]> stepCells(List<int[]> path, Grid grid) {
        List<int[]> lower = new ArrayList<>();
        for (int i = 0; i + 1 < path.size(); i++) {
            int[] a = path.get(i);
            int[] b = path.get(i + 1);
            int ha = grid.height(a[0], a[1]);
            int hb = grid.height(b[0], b[1]);
            if (ha != hb) {
                lower.add(ha < hb ? a : b);
            }
        }
        return lower;
    }

    public static List<int[]> widen(List<int[]> path, Grid grid) {
        List<int[]> sides = new ArrayList<>();
        Set<Long> taken = new HashSet<>();
        for (int[] cell : path) {
            taken.add(key(cell[0], cell[1]));
        }
        for (int i = 0; i < path.size(); i++) {
            int[] before = path.get(Math.max(0, i - 1));
            int[] after = path.get(Math.min(path.size() - 1, i + 1));
            int[] here = path.get(i);
            boolean alongX = Math.abs(after[0] - before[0]) >= Math.abs(after[1] - before[1]);
            int h = grid.height(here[0], here[1]);
            for (int sign = -1; sign <= 1; sign += 2) {
                int x = here[0] + (alongX ? 0 : sign);
                int z = here[1] + (alongX ? sign : 0);
                if (taken.contains(key(x, z)) || grid.cost(x, z) < 0 || grid.height(x, z) != h) {
                    continue;
                }
                taken.add(key(x, z));
                sides.add(new int[]{x, z});
            }
        }
        return sides;
    }
}
