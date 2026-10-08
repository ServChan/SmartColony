package org.lts.smartcolony.core;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Brewing {
    public static final String WATER = "water";
    public static final String AWKWARD = "awkward";
    public static final String WART = Ids.mc("nether_wart");
    public static final String FUEL = Ids.mc("blaze_powder");
    public static final int BREW_TICKS = 420;
    public static final int FUEL_USES = 20;

    public record Step(String base, String ingredient, String result) {
    }

    private static final Set<String> HEALING = Set.of("healing", "strong_healing", "regeneration", "long_regeneration", "strong_regeneration");

    private static final Map<String, Step> FINAL = Map.of(
            "healing", new Step(AWKWARD, Ids.mc("glistering_melon_slice"), "healing"),
            "regeneration", new Step(AWKWARD, Ids.mc("ghast_tear"), "regeneration"),
            "fire_resistance", new Step(AWKWARD, Ids.mc("magma_cream"), "fire_resistance"),
            "strength", new Step(AWKWARD, Ids.mc("blaze_powder"), "strength"),
            "swiftness", new Step(AWKWARD, Ids.mc("sugar"), "swiftness"),
            "weakness", new Step(WATER, Ids.mc("fermented_spider_eye"), "weakness"));

    private static final List<String> PRIORITY = List.of("healing", "regeneration", "strength", "fire_resistance", "swiftness");

    private Brewing() {
    }

    public static boolean healing(String potion) {
        return HEALING.contains(potion);
    }

    public static Step finalStep(String potion) {
        return FINAL.get(potion);
    }

    public static Step awkwardStep() {
        return new Step(WATER, WART, AWKWARD);
    }

    public static int healingTarget(int guards, int adults) {
        return Math.max(3, Math.min(24, guards * 2 + adults / 6));
    }

    public static String wanted(Stock stock, int healingPotions, int target, boolean fuel) {
        if (!fuel) {
            return null;
        }
        if (healingPotions >= target) {
            return null;
        }
        boolean wart = stock.get(WART) > 0;
        for (String potion : PRIORITY) {
            Step step = FINAL.get(potion);
            if (!healing(potion) && healingPotions < target) {
                continue;
            }
            if (wart && stock.get(step.ingredient()) > 0) {
                return potion;
            }
        }
        return null;
    }

    public static boolean needsWart(Stock stock) {
        return stock.get(WART) == 0;
    }
}
