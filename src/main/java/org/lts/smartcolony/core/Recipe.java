package org.lts.smartcolony.core;

import java.util.LinkedHashMap;
import java.util.Map;

public record Recipe(String output, int count, Map<String, Integer> inputs, boolean smelting, String requires) {
    public static final String GROUP_PLANKS = "#planks";
    public static final String GROUP_LOGS = "#logs";
    public static final String GROUP_COAL = "#coal";
    public static final String GROUP_STONE = "#stone_tool";
    public static final String GROUP_WOODEN_SLAB = "#wooden_slab";

    public Recipe {
        inputs = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        requires = requires == null ? "" : requires;
    }

    public Recipe(String output, int count, Map<String, Integer> inputs, boolean smelting) {
        this(output, count, inputs, smelting, "");
    }

    public static boolean isGroup(String token) {
        return token.startsWith("#");
    }

    public static boolean inGroup(String group, String id) {
        return switch (group) {
            case GROUP_PLANKS -> Ids.isPlanks(id);
            case GROUP_LOGS -> Ids.isLog(id);
            case GROUP_COAL -> Ids.isCoal(id);
            case GROUP_STONE -> Ids.isStoneToolMaterial(id);
            case GROUP_WOODEN_SLAB -> Ids.path(id).endsWith("_slab") && Ids.woodOf(id) != null;
            default -> false;
        };
    }
}
