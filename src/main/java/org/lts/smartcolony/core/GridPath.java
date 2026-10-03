package org.lts.smartcolony.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

public final class GridPath {
    public interface Terrain {
        int height(int x, int z);

        boolean blocked(int x, int z);

        boolean preferred(int x, int z);
    }

    private record Node(int x, int z, double f) {
    }

    private GridPath() {
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    public static List<int[]> find(Terrain terrain, int sx, int sz, int gx, int gz, int maxNodes) {
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f(), b.f()));
        Map<Long, Double> cost = new HashMap<>();
        Map<Long, long[]> parent = new HashMap<>();
        open.add(new Node(sx, sz, heuristic(sx, sz, gx, gz)));
        cost.put(key(sx, sz), 0.0);
        int expanded = 0;
        int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!open.isEmpty() && expanded < maxNodes) {
            Node node = open.poll();
            expanded++;
            if (node.x() == gx && node.z() == gz) {
                List<int[]> path = new ArrayList<>();
                long current = key(gx, gz);
                int cx = gx;
                int cz = gz;
                while (true) {
                    path.add(new int[]{cx, cz});
                    long[] from = parent.get(current);
                    if (from == null) {
                        break;
                    }
                    cx = (int) from[0];
                    cz = (int) from[1];
                    current = key(cx, cz);
                }
                Collections.reverse(path);
                return path;
            }
            double base = cost.getOrDefault(key(node.x(), node.z()), Double.MAX_VALUE);
            int h = terrain.height(node.x(), node.z());
            for (int[] step : steps) {
                int nx = node.x() + step[0];
                int nz = node.z() + step[1];
                boolean goal = nx == gx && nz == gz;
                if (!goal && terrain.blocked(nx, nz)) {
                    continue;
                }
                int nh = terrain.height(nx, nz);
                if (Math.abs(nh - h) > 1) {
                    continue;
                }
                double move = terrain.preferred(nx, nz) ? 0.4 : 1.0;
                if (nh != h) {
                    move += 0.5;
                }
                double next = base + move;
                long k = key(nx, nz);
                if (next < cost.getOrDefault(k, Double.MAX_VALUE)) {
                    cost.put(k, next);
                    parent.put(k, new long[]{node.x(), node.z()});
                    open.add(new Node(nx, nz, next + heuristic(nx, nz, gx, gz)));
                }
            }
        }
        return List.of();
    }

    private static double heuristic(int x, int z, int gx, int gz) {
        return (Math.abs(x - gx) + Math.abs(z - gz)) * 0.4;
    }
}
