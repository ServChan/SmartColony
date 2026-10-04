package org.lts.smartcolony.core;

import java.util.ArrayList;
import java.util.List;

public final class MinePlan implements DigPlan {
    public enum Kind {
        STAIR,
        MAIN,
        BRANCH,
        QUARRY
    }

    public record Cell(int index, Kind kind, Pos feet, int height) {
    }

    public static final int SPACING = 4;
    public static final int BRANCH_LENGTH = 12;
    public static final int SEGMENTS = 12;
    private static final int CHECKPOINT = 6;

    private final Pos entrance;
    private final int dirX;
    private final int dirZ;
    private final int depth;

    public MinePlan(Pos entrance, int dirX, int dirZ, int depth) {
        if (Math.abs(dirX) + Math.abs(dirZ) != 1) {
            throw new IllegalArgumentException("direction must be cardinal");
        }
        this.entrance = entrance;
        this.dirX = dirX;
        this.dirZ = dirZ;
        this.depth = Math.max(1, depth);
    }

    public static int depthFor(int entranceY, int targetY) {
        return Math.max(1, entranceY - targetY);
    }

    public int depth() {
        return depth;
    }

    public Pos entrance() {
        return entrance;
    }

    public int size() {
        return depth + SEGMENTS * segmentSize();
    }

    private static int segmentSize() {
        return SPACING + 2 * BRANCH_LENGTH;
    }

    public Pos stair(int k) {
        if (k <= 0) {
            return entrance;
        }
        return entrance.offset(dirX * k, -k, dirZ * k);
    }

    public Pos bottom() {
        return stair(depth);
    }

    public Pos main(int j) {
        if (j <= 0) {
            return bottom();
        }
        Pos b = bottom();
        return b.offset(dirX * j, 0, dirZ * j);
    }

    public Pos branch(int junction, int side, int b) {
        Pos m = main(junction);
        int sx = side > 0 ? dirZ : -dirZ;
        int sz = side > 0 ? -dirX : dirX;
        return m.offset(sx * b, 0, sz * b);
    }

    public Cell cell(int index) {
        if (index < 0 || index >= size()) {
            throw new IndexOutOfBoundsException(index);
        }
        if (index < depth) {
            return new Cell(index, Kind.STAIR, stair(index + 1), 3);
        }
        int r = index - depth;
        int segment = r / segmentSize();
        int offset = r % segmentSize();
        if (offset < SPACING) {
            return new Cell(index, Kind.MAIN, main(segment * SPACING + offset + 1), 2);
        }
        int junction = (segment + 1) * SPACING;
        if (offset < SPACING + BRANCH_LENGTH) {
            return new Cell(index, Kind.BRANCH, branch(junction, 1, offset - SPACING + 1), 2);
        }
        return new Cell(index, Kind.BRANCH, branch(junction, -1, offset - SPACING - BRANCH_LENGTH + 1), 2);
    }

    public Pos standFor(int index) {
        if (index < depth) {
            return stair(index);
        }
        int r = index - depth;
        int segment = r / segmentSize();
        int offset = r % segmentSize();
        if (offset < SPACING) {
            return main(segment * SPACING + offset);
        }
        int junction = (segment + 1) * SPACING;
        if (offset < SPACING + BRANCH_LENGTH) {
            int b = offset - SPACING + 1;
            return b == 1 ? main(junction) : branch(junction, 1, b - 1);
        }
        int b = offset - SPACING - BRANCH_LENGTH + 1;
        return b == 1 ? main(junction) : branch(junction, -1, b - 1);
    }

    public List<Pos> routeTo(int index) {
        List<Pos> route = new ArrayList<>();
        route.add(entrance);
        Pos stand = standFor(index);
        if (index < depth) {
            for (int k = CHECKPOINT; k < index; k += CHECKPOINT) {
                route.add(stair(k));
            }
            addIfNew(route, stand);
            return route;
        }
        for (int k = CHECKPOINT; k < depth; k += CHECKPOINT) {
            route.add(stair(k));
        }
        addIfNew(route, bottom());
        int r = index - depth;
        int segment = r / segmentSize();
        int offset = r % segmentSize();
        int mainTarget = offset < SPACING ? segment * SPACING + offset : (segment + 1) * SPACING;
        for (int j = CHECKPOINT; j < mainTarget; j += CHECKPOINT) {
            route.add(main(j));
        }
        addIfNew(route, main(mainTarget));
        if (offset >= SPACING) {
            int side = offset < SPACING + BRANCH_LENGTH ? 1 : -1;
            int b = side > 0 ? offset - SPACING + 1 : offset - SPACING - BRANCH_LENGTH + 1;
            for (int step = CHECKPOINT; step < b - 1; step += CHECKPOINT) {
                route.add(branch(mainTarget, side, step));
            }
        }
        addIfNew(route, stand);
        return route;
    }

    private static void addIfNew(List<Pos> route, Pos pos) {
        if (!route.getLast().equals(pos)) {
            route.add(pos);
        }
    }
}
