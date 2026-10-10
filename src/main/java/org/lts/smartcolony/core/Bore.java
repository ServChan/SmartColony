package org.lts.smartcolony.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class Bore {
    public static final int MAX_STEPS = 24;
    public static final int HEIGHT = 3;
    private static final int[][] RING = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};

    public record Step(Pos feet, int height) {
    }

    private Bore() {
    }

    public static boolean reached(Pos feet, Pos ore) {
        int dy = ore.y() - feet.y();
        return Math.max(Math.abs(ore.x() - feet.x()), Math.abs(ore.z() - feet.z())) <= 1 && dy >= -1 && dy <= 2;
    }

    public static List<Step> route(Pos start, Pos ore, int maxSteps) {
        List<Pos> path = new ArrayList<>();
        Set<Pos> seen = new HashSet<>();
        Pos current = start;
        seen.add(start);
        while (!reached(current, ore)) {
            if (path.size() >= maxSteps) {
                return null;
            }
            int dx = ore.x() - current.x();
            int dz = ore.z() - current.z();
            int nx = current.x();
            int nz = current.z();
            if (Math.max(Math.abs(dx), Math.abs(dz)) > 1) {
                if (Math.abs(dx) >= Math.abs(dz)) {
                    nx += Integer.signum(dx);
                } else {
                    nz += Integer.signum(dz);
                }
            } else {
                int index = ringIndex(current.x() - ore.x(), current.z() - ore.z());
                int[] next = RING[(index + 1) % RING.length];
                nx = ore.x() + next[0];
                nz = ore.z() + next[1];
            }
            int ny = current.y() + Integer.signum(ore.y() - current.y());
            Pos step = new Pos(nx, ny, nz);
            if (!seen.add(step)) {
                return null;
            }
            path.add(step);
            current = step;
        }
        List<Step> steps = new ArrayList<>();
        if (!path.isEmpty() && path.getFirst().y() > start.y()) {
            steps.add(new Step(start, HEIGHT + 1));
        }
        for (int i = 0; i < path.size(); i++) {
            Pos feet = path.get(i);
            boolean climbs = i + 1 < path.size() && path.get(i + 1).y() > feet.y();
            steps.add(new Step(feet, climbs ? HEIGHT + 1 : HEIGHT));
        }
        return steps;
    }

    private static int ringIndex(int dx, int dz) {
        for (int i = 0; i < RING.length; i++) {
            if (RING[i][0] == dx && RING[i][1] == dz) {
                return i;
            }
        }
        return RING.length - 1;
    }
}
