package org.lts.smartcolony.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class QuarryPlan implements DigPlan {
    public static final int TOP_CLEARANCE = 3;
    private static final int ROUTE_STEP = 3;

    private record Entry(int x, int z, int parentX, int parentZ) {
    }

    private final Pos corner;
    private final int width;
    private final int layers;
    private final List<int[]> perimeter = new ArrayList<>();
    private final Map<Integer, List<Entry>> orders = new HashMap<>();

    public QuarryPlan(Pos corner, int width, int layers) {
        if (width < 4) {
            throw new IllegalArgumentException("quarry must be at least 4 wide");
        }
        this.corner = corner;
        this.width = width;
        this.layers = Math.max(1, layers);
        for (int x = 0; x < width - 1; x++) {
            perimeter.add(new int[]{x, 0});
        }
        for (int z = 0; z < width - 1; z++) {
            perimeter.add(new int[]{width - 1, z});
        }
        for (int x = width - 1; x > 0; x--) {
            perimeter.add(new int[]{x, width - 1});
        }
        for (int z = width - 1; z > 0; z--) {
            perimeter.add(new int[]{0, z});
        }
    }

    public static int layersFor(int topY, int targetY) {
        return Math.max(1, topY - targetY);
    }

    public Pos corner() {
        return corner;
    }

    public int width() {
        return width;
    }

    public int layers() {
        return layers;
    }

    public int perLayer() {
        return width * width - 1;
    }

    @Override
    public int size() {
        return layers * perLayer();
    }

    public int layerOf(int index) {
        return index / perLayer();
    }

    public int layerY(int layer) {
        return corner.y() - layer;
    }

    public int[] stepColumn(int layer) {
        return perimeter.get(Math.floorMod(layer, perimeter.size()));
    }

    public Pos step(int layer) {
        int[] column = stepColumn(layer);
        return new Pos(corner.x() + column[0], layerY(layer), corner.z() + column[1]);
    }

    public Pos stepFeet(int layer) {
        return step(layer).offset(0, 1, 0);
    }

    @Override
    public Pos entrance() {
        int[] first = perimeter.getFirst();
        return new Pos(corner.x() + first[0] - 1, corner.y() + 1, corner.z() + first[1]);
    }

    public boolean inside(Pos pos) {
        return pos.x() >= corner.x() && pos.x() < corner.x() + width && pos.z() >= corner.z() && pos.z() < corner.z() + width;
    }

    @Override
    public MinePlan.Cell cell(int index) {
        check(index);
        int layer = layerOf(index);
        Entry entry = order(layer).get(index % perLayer());
        return new MinePlan.Cell(index, MinePlan.Kind.QUARRY,
                new Pos(corner.x() + entry.x(), layerY(layer), corner.z() + entry.z()), layer == 0 ? TOP_CLEARANCE : 1);
    }

    @Override
    public Pos standFor(int index) {
        check(index);
        int layer = layerOf(index);
        Entry entry = order(layer).get(index % perLayer());
        if (entry.parentX() < 0) {
            return stepFeet(layer);
        }
        return new Pos(corner.x() + entry.parentX(), layerY(layer), corner.z() + entry.parentZ());
    }

    @Override
    public List<Pos> routeTo(int index) {
        check(index);
        List<Pos> route = new ArrayList<>();
        route.add(entrance());
        int layer = layerOf(index);
        for (int s = ROUTE_STEP; s < layer; s += ROUTE_STEP) {
            route.add(stepFeet(s));
        }
        if (layer > 0) {
            Pos last = stepFeet(layer);
            if (!route.getLast().equals(last)) {
                route.add(last);
            }
        }
        Pos stand = standFor(index);
        if (!route.getLast().equals(stand)) {
            route.add(stand);
        }
        return route;
    }

    public int[] ladderColumn() {
        return perimeter.getLast();
    }

    public int ladderLayers() {
        return layers < perimeter.size() ? layers : perimeter.size() - 3;
    }

    public Pos ladderCell(int layer) {
        int[] column = ladderColumn();
        return new Pos(corner.x() + column[0], layerY(layer), corner.z() + column[1]);
    }

    public Pos ladderWall(int layer) {
        return ladderCell(layer).offset(-1, 0, 0);
    }

    public Pos ladderExit() {
        int[] column = ladderColumn();
        return new Pos(corner.x() - 1, corner.y() + 1, corner.z() + column[1]);
    }

    public List<Pos> ladderCells(int completedLayers) {
        int done = Math.min(completedLayers, layers);
        List<Pos> cells = new ArrayList<>();
        for (int layer = 0; layer < ladderLayers(); layer++) {
            if (layer + 2 <= done || done >= layers) {
                cells.add(ladderCell(layer));
            }
        }
        return cells;
    }

    private void check(int index) {
        if (index < 0 || index >= size()) {
            throw new IndexOutOfBoundsException(index);
        }
    }

    private List<Entry> order(int layer) {
        int p = perimeter.size();
        int step = Math.floorMod(layer, p);
        int key = layer == 0 ? -1 : step;
        return orders.computeIfAbsent(key, k -> buildOrder(perimeter.get(step), perimeter.get(Math.floorMod(step + 1, p)),
                layer == 0 ? null : perimeter.get(Math.floorMod(step - 1, p))));
    }

    private List<Entry> buildOrder(int[] skip, int[] start, int[] noExpand) {
        boolean[][] seen = new boolean[width][width];
        seen[skip[0]][skip[1]] = true;
        List<Entry> result = new ArrayList<>();
        ArrayDeque<Entry> queue = new ArrayDeque<>();
        queue.add(new Entry(start[0], start[1], -1, -1));
        seen[start[0]][start[1]] = true;
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!queue.isEmpty()) {
            Entry entry = queue.poll();
            result.add(entry);
            if (noExpand != null && entry.x() == noExpand[0] && entry.z() == noExpand[1]) {
                continue;
            }
            for (int[] d : directions) {
                int nx = entry.x() + d[0];
                int nz = entry.z() + d[1];
                if (nx < 0 || nz < 0 || nx >= width || nz >= width || seen[nx][nz]) {
                    continue;
                }
                seen[nx][nz] = true;
                queue.add(new Entry(nx, nz, entry.x(), entry.z()));
            }
        }
        return result;
    }
}
