package org.lts.smartcolony.core;

import java.util.List;
import java.util.Map;

public final class Expedition {
    public static final int DIRECTIONS = 8;
    public static final int FIRST_RING = 40;
    public static final int RING_STEP = 32;
    public static final int MAX_RINGS = 4;
    public static final int SURVEY_RADIUS = 14;
    public static final int VEIN_LIMIT = 24;
    public static final int MAX_DISCOVERIES = 48;
    public static final int RECRUIT_COOLDOWN_DAYS = 2;
    public static final long VILLAGE_FORGET = 24000L * 10;

    public static final String ORE = "ore";
    public static final String VILLAGE = "village";
    public static final String NEST = "nest";
    public static final String CANE = "cane";

    private static final Map<String, Integer> ORE_VALUE = Map.of(
            "coal_ore", 1, "deepslate_coal_ore", 1,
            "copper_ore", 1, "deepslate_copper_ore", 1,
            "iron_ore", 3, "deepslate_iron_ore", 3,
            "gold_ore", 2, "deepslate_gold_ore", 2,
            "lapis_ore", 2, "deepslate_lapis_ore", 2);
    private static final List<String> RARE = List.of("diamond_ore", "deepslate_diamond_ore", "emerald_ore", "deepslate_emerald_ore");

    public record Target(int dx, int dz, int ring, int direction) {
    }

    private Expedition() {
    }

    public static Target target(int trip, int colonyRadius) {
        int direction = Math.floorMod(trip * 3, DIRECTIONS);
        int ring = Math.floorMod(trip / DIRECTIONS, MAX_RINGS);
        int distance = colonyRadius + FIRST_RING + ring * RING_STEP;
        double angle = direction * Math.PI * 2.0 / DIRECTIONS;
        return new Target((int) Math.round(Math.cos(angle) * distance), (int) Math.round(Math.sin(angle) * distance), ring, direction);
    }

    public static Target shorter(Target target, int colonyRadius) {
        double length = Math.hypot(target.dx(), target.dz());
        double next = length - RING_STEP / 2.0;
        if (next < colonyRadius + 16) {
            return null;
        }
        double scale = next / length;
        return new Target((int) Math.round(target.dx() * scale), (int) Math.round(target.dz() * scale), target.ring(), target.direction());
    }

    public static int oreValue(String path) {
        if (RARE.contains(path)) {
            return 6;
        }
        return ORE_VALUE.getOrDefault(path, 0);
    }

    public static boolean isOre(String path) {
        return oreValue(path) > 0;
    }

    public static boolean worthVein(int value, int blocks) {
        return value * blocks >= 6 || value >= 6;
    }

    public static int recruitsWanted(int freeBeds, int adults, int maxAdults) {
        if (adults >= maxAdults) {
            return 0;
        }
        return Math.max(0, Math.min(2, freeBeds));
    }
}
