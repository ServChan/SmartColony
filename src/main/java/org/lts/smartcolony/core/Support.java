package org.lts.smartcolony.core;

import java.util.function.IntFunction;

public final class Support {
    public enum Cell {
        SOLID,
        EMPTY,
        BLOCKED
    }

    private Support() {
    }

    public static int gap(IntFunction<Cell> below, int maxGap) {
        for (int depth = 1; depth <= maxGap + 1; depth++) {
            Cell cell = below.apply(depth);
            if (cell == Cell.SOLID) {
                return depth - 1;
            }
            if (cell == Cell.BLOCKED) {
                return -1;
            }
        }
        return -1;
    }
}
