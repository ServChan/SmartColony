package org.lts.smartcolony.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiPredicate;

public final class MaterialSwaps {
    public static final String WOOD_FENCE = "minecraft:$wood_fence";
    public static final String WOOD_PLANKS = "minecraft:$wood_planks";
    public static final String WOOD_PLATE = "minecraft:$wood_pressure_plate";
    public static final String TORCH = "minecraft:torch";

    private MaterialSwaps() {
    }

    public static Map<String, String> propose(Map<String, Integer> required, Map<String, String> existing, boolean ironAge,
                                              BiPredicate<String, Integer> available) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : required.entrySet()) {
            String item = entry.getKey();
            int count = entry.getValue();
            if (existing.containsKey(item) || available.test(item, count)) {
                continue;
            }
            String path = Ids.path(item);
            String target = null;
            if (path.equals("glass_pane")) {
                target = WOOD_FENCE;
            } else if (path.equals("lantern")) {
                target = ironAge ? null : TORCH;
            } else if (path.equals("hay_block")) {
                target = WOOD_PLANKS;
            } else if (path.endsWith("_carpet")) {
                target = WOOD_PLATE;
            } else if (path.endsWith("_wool")) {
                target = WOOD_PLANKS;
            } else if (path.endsWith("_bed")) {
                for (String color : Ids.COLORS) {
                    String bed = Ids.mc(color + "_bed");
                    if (!bed.equals(item) && available.test(bed, count)) {
                        target = bed;
                        break;
                    }
                }
            }
            if (target != null) {
                result.put(item, target);
            }
        }
        return result;
    }

    public static String resolve(String target, String wood) {
        return target.replace("$wood", wood);
    }
}
