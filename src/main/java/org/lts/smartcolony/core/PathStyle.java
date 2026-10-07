package org.lts.smartcolony.core;

public final class PathStyle {
    public static final String PLAIN = "plain";
    public static final String SIDE = "side";
    public static final String MAIN = "main";
    public static final String STEP = "+step";
    public static final String STONE = "+stone";

    public static final String DIRT_PATH = "path";
    public static final String COBBLESTONE = "cobblestone";
    public static final String STONE_BRICKS = "stone_bricks";
    public static final String GRAVEL = "gravel";

    private PathStyle() {
    }

    public static String base(String style) {
        int plus = style.indexOf('+');
        return plus < 0 ? style : style.substring(0, plus);
    }

    public static boolean step(String style) {
        return style.contains(STEP);
    }

    public static boolean paved(String style) {
        return style.contains(STONE);
    }

    public static String with(String style, String flag) {
        return style.contains(flag) ? style : style + flag;
    }

    public static int hash(int x, int z) {
        int h = x * 73856093 ^ z * 19349663;
        return (h ^ (h >>> 15)) & 0x7FFFFFFF;
    }

    public static String material(String style, boolean ironAge, int x, int z, boolean bricks, boolean gravel) {
        String base = base(style);
        if (base.equals(MAIN) && ironAge) {
            return bricks && hash(x, z) % 3 == 0 ? STONE_BRICKS : COBBLESTONE;
        }
        if (gravel && !base.equals(PLAIN) && hash(x, z) % (base.equals(MAIN) ? 7 : 5) == 0) {
            return GRAVEL;
        }
        return DIRT_PATH;
    }
}
