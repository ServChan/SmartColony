package org.lts.smartcolony.core;

import java.util.Map;

public final class HouseTier {
    public static final String SUFFIX = "_2";
    public static final int MIN_ADULTS = 10;
    public static final int MIN_HOUSES = 3;
    public static final int STONE_RESERVE = 160;
    private static final Map<String, String> NEXT = Map.of(
            "house_small", "house_small_2",
            "house_family", "house_family_2",
            "house_garden", "house_garden_2");

    private HouseTier() {
    }

    public static String upgradeOf(String blueprint) {
        return NEXT.get(blueprint);
    }

    public static boolean isUpgrade(String blueprint) {
        return blueprint != null && NEXT.containsValue(blueprint);
    }

    public static String baseOf(String upgrade) {
        for (Map.Entry<String, String> entry : NEXT.entrySet()) {
            if (entry.getValue().equals(upgrade)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public static boolean wanted(int adults, int upgradable, boolean inProgress, boolean housingShort, int stone, boolean landShort) {
        if (inProgress || upgradable <= 0 || adults < MIN_ADULTS) {
            return false;
        }
        if (housingShort && (landShort || upgradable >= MIN_HOUSES)) {
            return true;
        }
        return stone >= STONE_RESERVE + adults * 4;
    }

    public static boolean preferOverNewHouse(boolean housingShort, boolean landShort, int upgradable, int houses) {
        return housingShort && upgradable > 0 && (landShort || houses >= 6 && houses % 2 == 0);
    }
}
