package org.lts.smartcolony.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class QuarryFit {
    public static final int LAG = 2;
    public static final int BEAM_FIRST = 3;
    public static final int BEAM_SPACING = 4;
    public static final int LANDING_SPACING = 4;

    private QuarryFit() {
    }

    public static boolean beamLayer(int layer) {
        return layer >= BEAM_FIRST && (layer - BEAM_FIRST) % BEAM_SPACING == 0;
    }

    public static boolean landing(int layer) {
        return layer % LANDING_SPACING == 0;
    }

    public static int[] inner(QuarryPlan plan, int[] ring) {
        int last = plan.width() - 1;
        int x = ring[0] == 0 ? 1 : ring[0] == last ? last - 1 : ring[0];
        int z = ring[1] == 0 ? 1 : ring[1] == last ? last - 1 : ring[1];
        return new int[]{x, z};
    }

    public static List<Fit> pit(QuarryPlan plan, int completed) {
        int done = Math.max(0, Math.min(completed, plan.layers()));
        Map<Pos, Fit> placed = new HashMap<>();
        int w = plan.width();
        int low = 1;
        int high = w - 2;
        int mid = w / 2;
        int deepest = done - LAG;
        int[][] pillars = {{low, low}, {high, low}, {low, high}, {high, high}};
        for (int layer = -1; deepest >= 0 && layer <= deepest; layer++) {
            for (int[] column : pillars) {
                put(plan, placed, column[0], layer, column[1], Fit.Part.LOG_Y, null);
            }
        }
        for (int layer = BEAM_FIRST; layer <= deepest; layer += BEAM_SPACING) {
            for (int i = low + 1; i < high; i++) {
                put(plan, placed, i, layer, low, Fit.Part.LOG_X, null);
                put(plan, placed, i, layer, high, Fit.Part.LOG_X, null);
                put(plan, placed, low, layer, i, Fit.Part.LOG_Z, null);
                put(plan, placed, high, layer, i, Fit.Part.LOG_Z, null);
            }
            for (int i = low + 1; i < high; i++) {
                put(plan, placed, i, layer, mid, Fit.Part.LOG_X, null);
                if (i != mid) {
                    put(plan, placed, mid, layer, i, Fit.Part.LOG_Z, null);
                }
            }
            int[][] lamps = {{mid, low}, {mid, high}, {low, mid}, {high, mid}, {mid, mid}};
            for (int[] lamp : lamps) {
                put(plan, placed, lamp[0], layer - 1, lamp[1], Fit.Part.LAMP, null);
            }
        }
        int stairs = Math.min(done - 1, plan.layers() - 1);
        for (int layer = 0; layer <= stairs; layer++) {
            int[] ring = plan.stepColumn(layer);
            Fit.Part part = landing(layer) ? Fit.Part.LANDING : Fit.Part.STAIR;
            Fit.Dir dir = null;
            if (part == Fit.Part.STAIR) {
                int[] before = plan.stepColumn(layer - 1);
                dir = Fit.Dir.of(before[0] - ring[0], before[1] - ring[1]);
            }
            Pos at = plan.step(layer);
            Fit fit = new Fit(at, part, dir);
            placed.put(at, fit);
        }
        for (int layer = 1; layer <= stairs; layer++) {
            int[] column = inner(plan, plan.stepColumn(layer));
            put(plan, placed, column[0], layer - 1, column[1], Fit.Part.FENCE, null);
            put(plan, placed, column[0], layer - 2, column[1], Fit.Part.FENCE, null);
        }
        List<Fit> result = new ArrayList<>(placed.values());
        result.sort(Comparator.<Fit>comparingInt(fit -> -fit.pos().y()).thenComparingInt(fit -> fit.part().ordinal())
                .thenComparingInt(fit -> fit.pos().x()).thenComparingInt(fit -> fit.pos().z()));
        return result;
    }

    private static void put(QuarryPlan plan, Map<Pos, Fit> placed, int lx, int layer, int lz,
                            Fit.Part part, Fit.Dir dir) {
        Pos at = new Pos(plan.corner().x() + lx, plan.layerY(layer), plan.corner().z() + lz);
        if (placed.containsKey(at)) {
            return;
        }
        Fit fit = new Fit(at, part, dir);
        placed.put(at, fit);
    }
}
