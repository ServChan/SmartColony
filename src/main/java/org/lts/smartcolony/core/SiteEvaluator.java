package org.lts.smartcolony.core;

import java.util.Arrays;

public final class SiteEvaluator {
    public static final int MAX_FILL = 3;
    public static final int MAX_CUT = 4;

    public record Result(boolean ok, int baseY, int fill, int cut, int maxFill, int maxCut, double score) {
    }

    private SiteEvaluator() {
    }

    public static Result evaluate(int[][] ground, boolean[][] blocked) {
        return evaluate(ground, blocked, MAX_FILL, MAX_CUT);
    }

    public static Result evaluate(int[][] ground, boolean[][] blocked, int fillLimit, int cutLimit) {
        int sizeX = ground.length;
        int sizeZ = ground[0].length;
        int[] all = new int[sizeX * sizeZ];
        int n = 0;
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                if (blocked[x][z]) {
                    return new Result(false, 0, 0, 0, 0, 0, Double.MAX_VALUE);
                }
                all[n++] = ground[x][z];
            }
        }
        Arrays.sort(all);
        int base = all[all.length / 2];
        int fill = 0;
        int cut = 0;
        int maxFill = 0;
        int maxCut = 0;
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                int diff = ground[x][z] - base;
                if (diff < 0) {
                    fill += -diff;
                    maxFill = Math.max(maxFill, -diff);
                } else if (diff > 0) {
                    cut += diff;
                    maxCut = Math.max(maxCut, diff);
                }
            }
        }
        boolean ok = maxFill <= fillLimit && maxCut <= cutLimit;
        return new Result(ok, base, fill, cut, maxFill, maxCut, fill * 2.0 + cut);
    }
}
