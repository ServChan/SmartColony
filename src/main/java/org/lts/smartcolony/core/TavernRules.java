package org.lts.smartcolony.core;

import java.util.EnumMap;
import java.util.List;

public final class TavernRules {
    public static final String ID = "tavern";
    public static final String KIND = "tavern";
    public static final int FOUNDING_ADULTS = 10;
    public static final int FOOD_PER_HEAD = 30;
    public static final int FOOD_BASE = 60;
    public static final int DINNER_SPAN = 2400;
    public static final int FED_TICKS = 20000;
    public static final float FED_BONUS = 0.08F;
    public static final long DAY = 24000L;
    public static final int MAX_GUESTS = 2;
    public static final int DEATH_WINDOW_DAYS = 3;

    public static final List<String> MEALS = List.of(
            "cooked_beef", "cooked_porkchop", "cooked_mutton", "cooked_chicken", "cooked_salmon", "cooked_rabbit",
            "cooked_cod", "bread", "baked_potato", "carrot", "apple", "melon_slice", "sweet_berries");

    private TavernRules() {
    }

    public static boolean foodStable(int foodPoints, int adults) {
        return foodPoints >= FOOD_BASE + adults * FOOD_PER_HEAD;
    }

    public static boolean needed(int adults, long builtOrPlanned, boolean foodStable) {
        return builtOrPlanned == 0 && adults >= FOUNDING_ADULTS && foodStable;
    }

    public static EnumMap<Role, Integer> withInnkeeper(EnumMap<Role, Integer> base, boolean tavernBuilt, int adults) {
        EnumMap<Role, Integer> result = new EnumMap<>(base);
        result.putIfAbsent(Role.INNKEEPER, 0);
        if (!tavernBuilt || adults < 6 || result.get(Role.INNKEEPER) > 0) {
            return result;
        }
        Role donor = null;
        int most = 1;
        for (Role role : new Role[] {Role.LUMBERJACK, Role.MINER, Role.FISHERMAN, Role.HERDER, Role.FARMER}) {
            int count = result.getOrDefault(role, 0);
            if (count > most) {
                most = count;
                donor = role;
            }
        }
        if (donor == null) {
            return result;
        }
        result.merge(donor, -1, Integer::sum);
        result.put(Role.INNKEEPER, 1);
        return result;
    }

    public static boolean serviceHours(long dayTime, int workEnd) {
        long since = Math.floorMod(dayTime - workEnd, DAY);
        return since < DINNER_SPAN;
    }

    public static String meal(Stock stock) {
        for (String meal : MEALS) {
            if (stock.get(Ids.mc(meal)) > 0) {
                return Ids.mc(meal);
            }
        }
        return null;
    }

    public static boolean welcoming(int freeBeds, int foodPoints, int members, int recentDeaths) {
        return freeBeds >= 1 && members >= 1 && recentDeaths <= 1 && foodPoints >= FOOD_BASE + members * FOOD_PER_HEAD;
    }

    public static boolean flourishing(int foodPoints, int members, int recentDeaths) {
        return recentDeaths == 0 && foodPoints >= FOOD_BASE + members * FOOD_PER_HEAD * 2;
    }

    public static int arrivals(boolean welcoming, boolean flourishing, int freeBeds, boolean innkeeper, double roll) {
        if (!welcoming) {
            return 0;
        }
        double chance = innkeeper ? 0.6 : 0.3;
        if (roll >= chance) {
            return 0;
        }
        return flourishing && freeBeds >= 2 ? 2 : 1;
    }

    public static boolean stays(boolean welcoming, double roll) {
        return welcoming && roll < 0.7;
    }

    public static int[] rotateDeathWindow(int day, int deaths, int markDay, int mark, int previous) {
        if (day - markDay >= DEATH_WINDOW_DAYS) {
            return new int[] {day, deaths, mark};
        }
        return new int[] {markDay, mark, previous};
    }

    public static int recentDeaths(int deaths, int previousMark) {
        return Math.max(0, deaths - previousMark);
    }
}
