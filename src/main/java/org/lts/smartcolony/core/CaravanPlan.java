package org.lts.smartcolony.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CaravanPlan {
    public static final int MAX_KINDS = 4;
    public static final int MAX_AMOUNT = 64;
    public static final int MIN_AMOUNT = 8;
    public static final int MAX_DISTANCE = 3000;
    public static final long INTERVAL = 24000L;
    public static final int TICKS_PER_BLOCK = 6;
    public static final long MIN_TRIP = 1200L;

    public static final Map<String, Integer> TARGETS = targets();

    public record Load(String item, int amount) {
    }

    private CaravanPlan() {
    }

    private static Map<String, Integer> targets() {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put(Ids.mc("wheat"), 64);
        map.put(Ids.mc("bread"), 32);
        map.put(Ids.mc("carrot"), 32);
        map.put(Ids.mc("potato"), 32);
        map.put(Ids.mc("cobblestone"), 192);
        map.put(Ids.mc("iron_ingot"), 32);
        map.put(Ids.mc("raw_iron"), 32);
        map.put(Ids.mc("coal"), 32);
        map.put(Ids.mc("white_wool"), 16);
        map.put(Ids.mc("leather"), 16);
        map.put(Ids.mc("string"), 16);
        map.put(Ids.mc("feather"), 16);
        map.put(Ids.mc("sand"), 48);
        map.put(Ids.mc("glass"), 32);
        map.put(Ids.mc("clay_ball"), 32);
        map.put(Ids.mc("sugar_cane"), 24);
        map.put(Ids.mc("paper"), 24);
        map.put(Ids.mc("honey_bottle"), 8);
        map.put(Ids.mc("oak_log"), 96);
        map.put(Ids.mc("spruce_log"), 96);
        map.put(Ids.mc("birch_log"), 96);
        return map;
    }

    public static List<Load> loads(Stock from, Stock to) {
        List<Load> loads = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : TARGETS.entrySet()) {
            String item = entry.getKey();
            int target = entry.getValue();
            int surplus = from.get(item) - target * 2;
            int deficit = target - to.get(item);
            int amount = Math.min(MAX_AMOUNT, Math.min(surplus, deficit));
            if (amount >= MIN_AMOUNT) {
                loads.add(new Load(item, amount));
            }
        }
        loads.sort(Comparator.comparingInt((Load load) -> load.amount() * PriceTable.base(load.item())).reversed());
        return loads.size() > MAX_KINDS ? new ArrayList<>(loads.subList(0, MAX_KINDS)) : loads;
    }

    public static long travelTicks(double distance) {
        return Math.max(MIN_TRIP, Math.round(distance * TICKS_PER_BLOCK));
    }

    public static boolean due(long now, long last) {
        return now - last >= INTERVAL;
    }
}
