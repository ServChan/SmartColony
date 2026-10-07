package org.lts.smartcolony.core;

import java.util.List;

public final class Ids {
    public static final List<String> WOOD_TYPES = List.of(
            "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak", "poplar", "crimson", "warped");
    public static final List<String> COLORS = List.of(
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");

    private Ids() {
    }

    public static String mc(String path) {
        return "minecraft:" + path;
    }

    public static String path(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }

    public static String namespace(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? "minecraft" : id.substring(0, colon);
    }

    public static String woodOf(String id) {
        String path = path(id);
        if (path.startsWith("stripped_")) {
            path = path.substring("stripped_".length());
        }
        String best = null;
        for (String wood : WOOD_TYPES) {
            if (path.startsWith(wood + "_") && (best == null || wood.length() > best.length())) {
                best = wood;
            }
        }
        return best;
    }

    public static boolean isLog(String id) {
        String path = path(id);
        return path.endsWith("_log") || path.endsWith("_wood") || path.endsWith("_stem") || path.endsWith("_hyphae");
    }

    public static boolean isPlanks(String id) {
        return path(id).endsWith("_planks");
    }

    public static boolean isSapling(String id) {
        String path = path(id);
        return path.endsWith("_sapling") || path.equals("mangrove_propagule");
    }

    public static boolean isWool(String id) {
        return path(id).endsWith("_wool");
    }

    public static boolean isCoal(String id) {
        String path = path(id);
        return path.equals("coal") || path.equals("charcoal");
    }

    public static boolean isStoneToolMaterial(String id) {
        String path = path(id);
        return path.equals("cobblestone") || path.equals("cobbled_deepslate") || path.equals("blackstone");
    }

    public static String planksForLog(String logId) {
        String wood = woodOf(logId);
        return wood == null ? null : mc(wood + "_planks");
    }
}
