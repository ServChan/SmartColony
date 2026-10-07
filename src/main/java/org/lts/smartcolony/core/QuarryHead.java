package org.lts.smartcolony.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntBinaryOperator;

public final class QuarryHead {
    public static final int SHED_LENGTH = 4;
    public static final int SHED_WIDTH = 3;
    public static final int GATE_HEIGHT = 4;
    public static final int FRAME_HEIGHT = 6;

    private final QuarryPlan plan;
    private final IntBinaryOperator ground;
    private final Map<Pos, Fit> placed = new LinkedHashMap<>();

    private QuarryHead(QuarryPlan plan, IntBinaryOperator ground) {
        this.plan = plan;
        this.ground = ground;
    }

    public static List<Fit> head(QuarryPlan plan, IntBinaryOperator ground, Pos shed) {
        QuarryHead head = new QuarryHead(plan, ground);
        head.gate();
        head.frame();
        head.rim();
        if (shed != null) {
            head.shed(shed.x(), shed.z());
        }
        return new ArrayList<>(head.placed.values());
    }

    private int g(int x, int z) {
        return ground.applyAsInt(x, z);
    }

    private void put(int x, int y, int z, Fit.Part part, Fit.Dir dir) {
        Pos at = new Pos(x, y, z);
        placed.putIfAbsent(at, new Fit(at, part, dir));
    }

    private void column(int x, int z, int from, int to, Fit.Part part) {
        for (int y = from; y <= to; y++) {
            put(x, y, z, part, null);
        }
    }

    private void gate() {
        int cx = plan.corner().x();
        Pos entrance = plan.entrance();
        Pos exit = plan.ladderExit();
        int low = Math.min(entrance.z(), exit.z());
        int high = Math.max(entrance.z(), exit.z());
        int x = cx - 1;
        int top = Math.max(Math.max(g(x, low - 1), g(x, high + 1)), Math.max(g(x, low), g(x, high))) + GATE_HEIGHT;
        for (int z : new int[]{low - 1, high + 1}) {
            put(x, g(x, z) + 1, z, Fit.Part.CURB, null);
            column(x, z, g(x, z) + 2, top, Fit.Part.LOG_Y);
        }
        for (int z = low; z <= high; z++) {
            put(x, top, z, Fit.Part.LOG_Z, null);
        }
        for (int z : new int[]{low - 1, high + 1}) {
            put(x, top + 1, z, Fit.Part.LAMP, null);
        }
    }

    private void frame() {
        int cx = plan.corner().x();
        int cz = plan.corner().z();
        int w = plan.width();
        int east = cx + w;
        int mid = cz + w / 2;
        int[] legs = {cz + 1, cz + w - 2};
        int base = plan.corner().y();
        for (int z : new int[]{legs[0], legs[1], mid}) {
            base = Math.max(base, g(east, z));
        }
        int top = base + FRAME_HEIGHT;
        for (int z : legs) {
            column(east, z, g(east, z) + 1, top, Fit.Part.LOG_Y);
        }
        column(east, mid, g(east, mid) + 1, top - 1, Fit.Part.LOG_Y);
        for (int z = legs[0] + 1; z < legs[1]; z++) {
            if (z != mid) {
                put(east, top, z, Fit.Part.LOG_Z, null);
            }
        }
        for (int x = east; x >= east - 3; x--) {
            put(x, top, mid, Fit.Part.LOG_X, null);
        }
        put(east - 1, top - 1, mid, Fit.Part.STAIR_TOP, Fit.Dir.EAST);
        put(east - 3, top - 1, mid, Fit.Part.ROPE, null);
        put(east - 3, top - 2, mid, Fit.Part.ROPE, null);
        put(east - 3, top - 3, mid, Fit.Part.HANG_LAMP, null);
        int deck = east + 1;
        for (int z = legs[0]; z <= legs[1]; z++) {
            put(deck, g(deck, z) + 1, z, Fit.Part.PLANK, null);
        }
        for (int z : new int[]{legs[0] + 1, legs[1] - 1}) {
            put(deck, g(deck, z) + 2, z, Fit.Part.BARREL, null);
        }
    }

    private void rim() {
        int cx = plan.corner().x();
        int cz = plan.corner().z();
        int w = plan.width();
        Pos entrance = plan.entrance();
        Pos exit = plan.ladderExit();
        for (int x = cx - 1; x <= cx + w; x++) {
            for (int z = cz - 1; z <= cz + w; z++) {
                if (x != cx - 1 && x != cx + w && z != cz - 1 && z != cz + w) {
                    continue;
                }
                if (x == entrance.x() && z == entrance.z() || x == exit.x() && z == exit.z()) {
                    continue;
                }
                int y = g(x, z);
                put(x, y + 1, z, Fit.Part.CURB, null);
                boolean corner = (x == cx - 1 || x == cx + w) && (z == cz - 1 || z == cz + w);
                if (corner) {
                    column(x, z, y + 2, y + 4, Fit.Part.LOG_Y);
                    put(x, y + 5, z, Fit.Part.LAMP, null);
                } else {
                    put(x, y + 2, z, Fit.Part.FENCE, null);
                }
            }
        }
    }

    private void shed(int minX, int minZ) {
        int high = Integer.MIN_VALUE;
        for (int i = 0; i < SHED_LENGTH; i++) {
            for (int j = 0; j < SHED_WIDTH; j++) {
                high = Math.max(high, g(minX + i, minZ + j));
            }
        }
        int roof = high + 4;
        boolean north = minZ < plan.corner().z();
        int back = north ? 0 : SHED_WIDTH - 1;
        for (int i : new int[]{0, SHED_LENGTH - 1}) {
            for (int j : new int[]{0, SHED_WIDTH - 1}) {
                column(minX + i, minZ + j, g(minX + i, minZ + j) + 1, roof - 1, Fit.Part.LOG_Y);
            }
        }
        for (int i = 1; i < SHED_LENGTH - 1; i++) {
            column(minX + i, minZ + back, g(minX + i, minZ + back) + 1, roof - 1, Fit.Part.PLANK);
        }
        for (int i : new int[]{0, SHED_LENGTH - 1}) {
            column(minX + i, minZ + 1, g(minX + i, minZ + 1) + 1, g(minX + i, minZ + 1) + 2, Fit.Part.PLANK);
        }
        for (int i = 0; i < SHED_LENGTH; i++) {
            for (int j = 0; j < SHED_WIDTH; j++) {
                put(minX + i, roof, minZ + j, Fit.Part.SLAB, null);
            }
        }
        int row = minZ + 1;
        put(minX + 1, g(minX + 1, row) + 1, row, Fit.Part.BARREL, null);
        put(minX + 1, g(minX + 1, row) + 2, row, Fit.Part.BARREL, null);
        put(minX + 2, g(minX + 2, row) + 1, row, Fit.Part.BARREL, null);
        put(minX + 2, g(minX + 2, row) + 2, row, Fit.Part.LAMP, null);
    }
}
