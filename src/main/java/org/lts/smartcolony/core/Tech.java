package org.lts.smartcolony.core;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

public final class Tech {
    public static final String STONE_AGE = "stone";
    public static final String IRON_AGE = "iron";
    public static final String FORGE = "forge";
    public static final String FORGE_BLUEPRINT = "blacksmith";
    public static final String FLETCHER_BLUEPRINT = "fletcher_hut";
    public static final String BAKERY_BLUEPRINT = "bakery";

    public static final int[] FORGE_ANVIL = {7, 1, 4};
    public static final int[] FORGE_SMITHING_TABLE = {3, 1, 6};
    public static final int FORGE_UPGRADE_INGOTS = 36;

    private Tech() {
    }

    public static Set<String> capabilities(Collection<String> blueprints) {
        Set<String> result = new LinkedHashSet<>();
        if (blueprints.contains(FORGE_BLUEPRINT)) {
            result.add(FORGE);
        }
        return result;
    }

    public static boolean ironAge(String age) {
        return IRON_AGE.equals(age);
    }
}
