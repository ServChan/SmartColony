package org.lts.smartcolony.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class BunkerPlan {
    public static final int DEPTH = 10;
    public static final int VERSION = 5;
    public static final int LANTERN_STEP = 4;
    public static final int HUT_ANCHOR_Z = 2;
    private static final int PEOPLE_PER_FREE_CELL = 3;
    private static final String FLOOR_STAIR = "minecraft:stone_brick_stairs[facing=north,half=bottom]";
    private static final String LINING = "minecraft:stone_bricks";
    private static final String NICHE = "minecraft:lantern[hanging=false]";
    private static final Set<String> REQUIRED = Set.of("stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks",
            "chiseled_stone_bricks", "stone_brick_stairs", "stone_brick_slab", "planks", "log", "door");
    private static final Map<Blueprint, RoomPlan> PLANS = Collections.synchronizedMap(new WeakHashMap<>());

    public enum Kind {
        DIG,
        BLOCK,
        FURNISH
    }

    public record Cell(int x, int y, int z, Kind kind, String state, int rotation, int standX, int standY, int standZ) {
    }

    public record Layout(int x, int y, int z, int dirX, int dirZ, int depth) {
        public Layout {
            if (Math.abs(dirX) + Math.abs(dirZ) != 1) {
                throw new IllegalArgumentException("direction must be cardinal");
            }
            if (depth < 4) {
                throw new IllegalArgumentException("bunker too shallow");
            }
        }

        public static Layout standard(int x, int y, int z, int dirX, int dirZ, int depth) {
            return new Layout(x, y, z, dirX, dirZ, depth);
        }

        public int rotation() {
            if (dirZ > 0) {
                return 0;
            }
            if (dirX < 0) {
                return 1;
            }
            return dirZ < 0 ? 2 : 3;
        }

        int sideX() {
            return -dirZ;
        }

        int sideZ() {
            return dirX;
        }

        int wx(int along, int across) {
            return x + dirX * along + sideX() * across;
        }

        int wz(int along, int across) {
            return z + dirZ * along + sideZ() * across;
        }

        public int floorY() {
            return y - depth;
        }
    }

    public record Templates(Blueprint hut, Blueprint room) {
    }

    private record RoomPlan(int entryY, List<int[]> digs, List<int[]> free, Map<Long, int[]> stands, int[] spot) {
    }

    private record Column(int x, int z, int base, int top) {
        boolean walkable() {
            return top > base;
        }
    }

    private BunkerPlan() {
    }

    private static int[] place(Layout layout, Blueprint template, int tx, int ty, int tz, int anchorZ, int anchorY, int along, int baseY) {
        int r = layout.rotation();
        int[] rotated = Blueprint.rotate(tx, tz, template.sizeX(), template.sizeZ(), r);
        int[] anchor = Blueprint.rotate(template.doorX(), anchorZ, template.sizeX(), template.sizeZ(), r);
        return new int[] {layout.wx(along, 0) + rotated[0] - anchor[0], baseY + ty - anchorY, layout.wz(along, 0) + rotated[1] - anchor[1]};
    }

    private static int[] hutCell(Layout layout, Blueprint hut, int tx, int ty, int tz) {
        return place(layout, hut, tx, ty, tz, HUT_ANCHOR_Z, 1, 0, layout.y());
    }

    private static int[] roomCell(Layout layout, Blueprint room, int tx, int ty, int tz) {
        return place(layout, room, tx, ty, tz, 0, plan(room).entryY(), layout.depth() + 1, layout.floorY());
    }

    private static RoomPlan plan(Blueprint room) {
        RoomPlan plan = PLANS.get(room);
        if (plan == null) {
            plan = analyse(room);
            PLANS.put(room, plan);
        }
        return plan;
    }

    private static int entryY(Blueprint room, Map<Long, String> states) {
        for (int ty = 0; ty < room.sizeY(); ty++) {
            String state = states.get(key(room.doorX(), ty, 0));
            if (state != null && Blueprint.blockId(state).endsWith("_door") && !"upper".equals(Blueprint.properties(state).get("half"))) {
                return ty;
            }
        }
        return 1;
    }

    private static RoomPlan analyse(Blueprint room) {
        int sx = room.sizeX();
        int sy = room.sizeY();
        int sz = room.sizeZ();
        Map<Long, String> states = new HashMap<>();
        for (Blueprint.Cell cell : room.cells("oak")) {
            states.put(key(cell.x(), cell.y(), cell.z()), cell.state());
        }
        int entry = entryY(room, states);
        boolean[][][] hollow = new boolean[sy][sz][sx];
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    char c = room.at(x, y, z);
                    if (c == Blueprint.AIR) {
                        hollow[y][z][x] = true;
                        continue;
                    }
                    String state = states.get(key(x, y, z));
                    hollow[y][z][x] = state != null && (kind(state) == Kind.FURNISH || Blueprint.blockId(state).endsWith("_door"));
                }
            }
        }
        List<Column> columns = new ArrayList<>();
        int[][][] owner = new int[sy][sz][sx];
        for (int z = 0; z < sz; z++) {
            for (int x = 0; x < sx; x++) {
                for (int y = 0; y < sy; y++) {
                    owner[y][z][x] = -1;
                    if (!hollow[y][z][x] || y > 0 && hollow[y - 1][z][x]) {
                        continue;
                    }
                    int top = y;
                    while (top + 1 < sy && hollow[top + 1][z][x]) {
                        top++;
                    }
                    columns.add(new Column(x, z, y, top));
                }
            }
        }
        for (int i = 0; i < columns.size(); i++) {
            Column column = columns.get(i);
            for (int y = column.base(); y <= column.top(); y++) {
                owner[y][column.z()][column.x()] = i;
            }
        }
        boolean[][][] dug = new boolean[sy][sz][sx];
        boolean[] done = new boolean[columns.size()];
        List<int[]> digs = new ArrayList<>();
        int[] door = {room.doorX(), entry, -1};
        int seed = owner[entry][0][room.doorX()];
        if (seed >= 0) {
            dig(columns.get(seed), entry, door, dug, digs);
            done[seed] = true;
        }
        while (true) {
            int best = -1;
            int[] bestStand = null;
            for (int i = 0; i < columns.size(); i++) {
                if (done[i] || best >= 0 && !before(columns.get(i), columns.get(best), entry)) {
                    continue;
                }
                int[] stand = parent(columns, owner, done, i, sx, sz);
                if (stand != null) {
                    best = i;
                    bestStand = stand;
                }
            }
            if (best < 0) {
                for (int i = 0; i < columns.size() && best < 0; i++) {
                    if (!done[i] && lowestTouching(columns.get(i), dug, sx, sz) >= 0) {
                        best = i;
                        Column column = columns.get(i);
                        bestStand = nearestStand(columns, done, column.x(), column.base(), column.z(), door);
                    }
                }
                if (best < 0) {
                    break;
                }
            }
            Column column = columns.get(best);
            dig(column, Math.max(column.base(), lowestTouching(column, dug, sx, sz)), bestStand, dug, digs);
            done[best] = true;
        }
        List<int[]> free = new ArrayList<>();
        for (Column column : columns) {
            if (column.walkable() && open(room, states, column.x(), column.base(), column.z())
                    && room.at(column.x(), column.base() + 1, column.z()) == Blueprint.AIR) {
                free.add(new int[] {column.x(), column.base(), column.z()});
            }
        }
        Map<Long, int[]> stands = new HashMap<>();
        for (Blueprint.Cell cell : room.cells("oak")) {
            stands.put(key(cell.x(), cell.y(), cell.z()), nearestFree(free, cell.x(), cell.y(), cell.z()));
        }
        int[] spot = nearestFree(free, room.doorX(), entry, sz / 2);
        int[] level = null;
        double best = Double.MAX_VALUE;
        for (int[] f : free) {
            if (f[1] != entry) {
                continue;
            }
            double d = sq(f[0] - room.doorX()) + sq(f[2] - sz / 2);
            if (d < best) {
                best = d;
                level = f;
            }
        }
        return new RoomPlan(entry, digs, free, stands, level != null ? level : spot);
    }

    private static boolean open(Blueprint room, Map<Long, String> states, int x, int y, int z) {
        if (room.at(x, y, z) == Blueprint.AIR) {
            return true;
        }
        String state = states.get(key(x, y, z));
        return state != null && (Blueprint.blockId(state).endsWith("_carpet") || Blueprint.blockId(state).endsWith("_pressure_plate"));
    }

    private static int lowestTouching(Column column, boolean[][][] dug, int sx, int sz) {
        for (int y = column.base(); y <= column.top(); y++) {
            for (int[] side : new int[][] {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}}) {
                int nx = column.x() + side[0];
                int ny = y + side[1];
                int nz = column.z() + side[2];
                if (nx >= 0 && nz >= 0 && ny >= 0 && nx < sx && nz < sz && ny < dug.length && dug[ny][nz][nx]) {
                    return y;
                }
            }
        }
        return -1;
    }

    private static void dig(Column column, int start, int[] stand, boolean[][][] dug, List<int[]> digs) {
        for (int y = start; y <= column.top(); y++) {
            dug[y][column.z()][column.x()] = true;
            digs.add(new int[] {column.x(), y, column.z(), stand[0], stand[1], stand[2]});
        }
        for (int y = start - 1; y >= column.base(); y--) {
            dug[y][column.z()][column.x()] = true;
            digs.add(new int[] {column.x(), y, column.z(), stand[0], stand[1], stand[2]});
        }
    }

    private static boolean before(Column a, Column b, int entry) {
        int levelA = a.base() >= entry - 1 ? 0 : 1;
        int levelB = b.base() >= entry - 1 ? 0 : 1;
        if (levelA != levelB) {
            return levelA < levelB;
        }
        if (a.z() != b.z()) {
            return a.z() < b.z();
        }
        int sweep = a.z() % 2 == 0 ? 1 : -1;
        return a.x() * sweep < b.x() * sweep;
    }

    private static int[] parent(List<Column> columns, int[][][] owner, boolean[] done, int index, int sx, int sz) {
        Column column = columns.get(index);
        int[] across = null;
        for (int y = column.base(); y <= column.top(); y++) {
            for (int[] side : new int[][] {{1, 0}, {-1, 0}, {0, -1}, {0, 1}}) {
                int nx = column.x() + side[0];
                int nz = column.z() + side[1];
                if (nx < 0 || nz < 0 || nx >= sx || nz >= sz || owner[y][nz][nx] < 0 || !done[owner[y][nz][nx]]) {
                    continue;
                }
                Column near = columns.get(owner[y][nz][nx]);
                if (!near.walkable() || column.walkable() && Math.abs(near.base() - column.base()) > 1) {
                    continue;
                }
                int[] stand = {near.x(), near.base(), near.z()};
                if (side[1] == 0) {
                    return stand;
                }
                across = stand;
            }
        }
        return across;
    }

    private static int[] nearestStand(List<Column> columns, boolean[] done, int x, int y, int z, int[] fallback) {
        int[] best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < columns.size(); i++) {
            Column column = columns.get(i);
            if (!done[i] || !column.walkable()) {
                continue;
            }
            double d = sq(column.x() - x) + sq(column.base() + 1.6 - y) + sq(column.z() - z);
            if (d < bestDistance) {
                bestDistance = d;
                best = new int[] {column.x(), column.base(), column.z()};
            }
        }
        return best != null ? best : fallback;
    }

    private static int[] nearestFree(List<int[]> free, int x, int y, int z) {
        int[] best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int[] f : free) {
            if (f[0] == x && f[2] == z && (y == f[1] || y == f[1] + 1)) {
                continue;
            }
            double d = sq(f[0] - x) + sq(f[1] + 1.6 - y) + sq(f[2] - z);
            if (d < bestDistance) {
                bestDistance = d;
                best = f;
            }
        }
        return best;
    }

    private static double sq(double v) {
        return v * v;
    }

    public static List<Cell> cells(Layout layout, Templates templates, String wood) {
        Blueprint hut = templates.hut();
        Blueprint room = templates.room();
        RoomPlan plan = plan(room);
        Map<Long, Cell> digs = new LinkedHashMap<>();
        int r = layout.rotation();
        for (int ty = hut.sizeY() - 1; ty >= 1; ty--) {
            for (int tz = 0; tz < hut.sizeZ(); tz++) {
                for (int tx = 0; tx < hut.sizeX(); tx++) {
                    if (hut.at(tx, ty, tz) == Blueprint.AIR) {
                        int[] w = hutCell(layout, hut, tx, ty, tz);
                        digs.putIfAbsent(key(w[0], w[1], w[2]), shaftCell(layout, templates, w[0], w[1], w[2], Kind.DIG, "", r));
                    }
                }
            }
        }
        for (int step = 1; step <= layout.depth(); step++) {
            int feet = layout.y() - step;
            for (int y = feet + 2; y >= feet; y--) {
                int x = layout.wx(step, 0);
                int z = layout.wz(step, 0);
                digs.putIfAbsent(key(x, y, z), shaftCell(layout, templates, x, y, z, Kind.DIG, "", r));
            }
        }
        for (int[] d : plan.digs()) {
            int[] w = roomCell(layout, room, d[0], d[1], d[2]);
            int[] s = roomCell(layout, room, d[3], d[4], d[5]);
            digs.putIfAbsent(key(w[0], w[1], w[2]), new Cell(w[0], w[1], w[2], Kind.DIG, "", r, s[0], s[1], s[2]));
        }
        List<Cell> cells = new ArrayList<>(digs.values());
        Set<Long> taken = new HashSet<>();
        int hutTop = layout.y() - 1;
        for (int step = 1; step <= layout.depth(); step++) {
            int feet = layout.y() - step;
            add(cells, taken, digs, shaftCell(layout, templates, layout.wx(step, 0), feet - 1, layout.wz(step, 0), Kind.BLOCK, FLOOR_STAIR, r));
            for (int across : new int[] {1, -1}) {
                for (int y = feet; y <= feet + 2; y++) {
                    if (y >= hutTop) {
                        continue;
                    }
                    boolean niche = across == 1 && y == feet + 1 && step % LANTERN_STEP == 2;
                    add(cells, taken, digs, shaftCell(layout, templates, layout.wx(step, across), y, layout.wz(step, across),
                            niche ? Kind.FURNISH : Kind.BLOCK, niche ? NICHE : LINING, r));
                }
            }
            if (feet + 3 < hutTop) {
                add(cells, taken, digs, shaftCell(layout, templates, layout.wx(step, 0), feet + 3, layout.wz(step, 0), Kind.BLOCK, LINING, r));
            }
        }
        for (Blueprint.Cell cell : room.cells(wood)) {
            if (skip(cell.state())) {
                continue;
            }
            int[] w = roomCell(layout, room, cell.x(), cell.y(), cell.z());
            int[] f = plan.stands().get(key(cell.x(), cell.y(), cell.z()));
            int[] s = f == null ? roomCell(layout, room, room.doorX(), plan.entryY(), -1) : roomCell(layout, room, f[0], f[1], f[2]);
            add(cells, taken, null, new Cell(w[0], w[1], w[2], kind(cell.state()), cell.state(), r, s[0], s[1], s[2]));
        }
        for (Blueprint.Cell cell : hut.cells(wood)) {
            if (skip(cell.state())) {
                continue;
            }
            int[] w = hutCell(layout, hut, cell.x(), cell.y(), cell.z());
            add(cells, taken, digs, shaftCell(layout, templates, w[0], w[1], w[2], kind(cell.state()), cell.state(), r));
        }
        return cells;
    }

    private static Cell shaftCell(Layout layout, Templates templates, int x, int y, int z, Kind kind, String state, int r) {
        int dx = x - layout.x();
        int dz = z - layout.z();
        int along = dx * layout.dirX() + dz * layout.dirZ();
        int[] stand;
        if (y >= layout.y() - 1 && along <= 4) {
            stand = kind == Kind.DIG && along >= 1 ? new int[] {layout.wx(0, 0), layout.y(), layout.wz(0, 0)} : outside(layout, templates);
        } else {
            int back = Math.max(0, Math.min(layout.depth(), kind == Kind.DIG ? along - 1 : along));
            int feet = back <= 0 ? layout.y() : layout.y() - back;
            stand = new int[] {layout.wx(back, 0), feet, layout.wz(back, 0)};
        }
        return new Cell(x, y, z, kind, state, r, stand[0], stand[1], stand[2]);
    }

    private static boolean skip(String state) {
        String id = Blueprint.blockId(state);
        Map<String, String> props = Blueprint.properties(state);
        return id.endsWith(":air") || id.endsWith("_door") && "upper".equals(props.get("half"))
                || id.endsWith("_bed") && "head".equals(props.get("part"));
    }

    private static void add(List<Cell> cells, Set<Long> taken, Map<Long, Cell> digs, Cell cell) {
        long key = key(cell.x(), cell.y(), cell.z());
        if (digs != null && digs.containsKey(key) || !taken.add(key)) {
            return;
        }
        cells.add(cell);
    }

    public static Kind kind(String state) {
        String path = Ids.path(Blueprint.blockId(state));
        for (String required : REQUIRED) {
            if (path.equals(required) || path.endsWith("_" + required)) {
                return Kind.BLOCK;
            }
        }
        return Kind.FURNISH;
    }

    public static int[] outside(Layout layout, Templates templates) {
        return hutCell(layout, templates.hut(), templates.hut().doorX(), 1, 0);
    }

    public static int[] door(Layout layout, Templates templates) {
        return hutCell(layout, templates.hut(), templates.hut().doorX(), 1, 1);
    }

    public static int[] spot(Layout layout, Templates templates) {
        int[] spot = plan(templates.room()).spot();
        return roomCell(layout, templates.room(), spot[0], spot[1], spot[2]);
    }

    public static List<int[]> shelterCells(Layout layout, Templates templates) {
        RoomPlan plan = plan(templates.room());
        int[] spot = plan.spot();
        List<int[]> free = new ArrayList<>(plan.free());
        free.sort((a, b) -> Double.compare(sq(a[0] - spot[0]) + sq((a[1] - spot[1]) * 3) + sq(a[2] - spot[2]),
                sq(b[0] - spot[0]) + sq((b[1] - spot[1]) * 3) + sq(b[2] - spot[2])));
        List<int[]> out = new ArrayList<>();
        for (int[] cell : free) {
            if (cell[2] > 1) {
                out.add(roomCell(layout, templates.room(), cell[0], cell[1], cell[2]));
            }
        }
        return out;
    }

    public static int capacity(Templates templates) {
        return plan(templates.room()).free().size() / PEOPLE_PER_FREE_CELL;
    }

    public static boolean inRoom(Layout layout, Templates templates, int x, int y, int z) {
        int[] a = roomCell(layout, templates.room(), 1, 1, 1);
        int[] b = roomCell(layout, templates.room(), templates.room().sizeX() - 2, templates.room().sizeY() - 2,
                templates.room().sizeZ() - 2);
        return x >= Math.min(a[0], b[0]) && x <= Math.max(a[0], b[0]) && y >= Math.min(a[1], b[1]) && y <= Math.max(a[1], b[1])
                && z >= Math.min(a[2], b[2]) && z <= Math.max(a[2], b[2]);
    }

    public static int[] bounds(List<Cell> cells) {
        int[] box = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (Cell cell : cells) {
            box[0] = Math.min(box[0], cell.x());
            box[1] = Math.min(box[1], cell.y());
            box[2] = Math.min(box[2], cell.z());
            box[3] = Math.max(box[3], cell.x());
            box[4] = Math.max(box[4], cell.y());
            box[5] = Math.max(box[5], cell.z());
        }
        return box;
    }

    public static int[] roomBox(Layout layout, Templates templates) {
        int[] a = roomCell(layout, templates.room(), 0, 0, 0);
        int[] b = roomCell(layout, templates.room(), templates.room().sizeX() - 1, templates.room().sizeY() - 1,
                templates.room().sizeZ() - 1);
        return new int[] {Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]), Math.max(a[0], b[0]), Math.max(a[1], b[1]),
                Math.max(a[2], b[2])};
    }

    public static int[] hutBox(Layout layout, Templates templates) {
        Blueprint hut = templates.hut();
        int[] a = hutCell(layout, hut, 0, 0, 0);
        int[] b = hutCell(layout, hut, hut.sizeX() - 1, hut.sizeY() - 1, hut.sizeZ() - 1);
        return new int[] {Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]), Math.max(a[0], b[0]), Math.max(a[1], b[1]),
                Math.max(a[2], b[2])};
    }

    public static int[] stand(Layout layout, Templates templates, Cell cell) {
        return new int[] {cell.standX(), cell.standY(), cell.standZ()};
    }

    public static Set<Long> digSet(List<Cell> cells) {
        Set<Long> dug = new HashSet<>();
        for (Cell cell : cells) {
            if (cell.kind() == Kind.DIG) {
                dug.add(key(cell.x(), cell.y(), cell.z()));
            }
        }
        return dug;
    }

    public static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
}
