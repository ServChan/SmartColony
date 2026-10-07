package org.lts.smartcolony.core;

public final class StringSupply {
    public static final int ROD_RESERVE = 2;
    public static final int BOW_COST = 3;
    public static final int TRADE_TARGET = 4;

    private StringSupply() {
    }

    public static int reserve(boolean waterVillage) {
        return waterVillage ? ROD_RESERVE : 0;
    }

    public static boolean bowAllowed(int string, boolean waterVillage) {
        return string >= BOW_COST + reserve(waterVillage);
    }

    public static int tradeCount(int string, int rods, boolean waterVillage) {
        if (!waterVillage || rods > 0) {
            return 0;
        }
        return Math.max(0, TRADE_TARGET - string);
    }
}
