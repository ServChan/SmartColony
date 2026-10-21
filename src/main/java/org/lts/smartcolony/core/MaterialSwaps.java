package org.lts.smartcolony.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.ToIntFunction;

public final class MaterialSwaps {
    public static final String WOOD_FENCE = "minecraft:$wood_fence";
    public static final String WOOD_PLANKS = "minecraft:$wood_planks";
    public static final String WOOD_PLATE = "minecraft:$wood_pressure_plate";
    public static final String TORCH = "minecraft:torch";
    public static final List<String> STONE_STAND_INS = List.of("minecraft:cobbled_deepslate", "minecraft:sandstone", "minecraft:andesite",
            "minecraft:diorite", "minecraft:granite", "minecraft:tuff", "minecraft:red_sandstone");
    public static final List<String> WOODS = List.of("pale_oak", "dark_oak", "mangrove", "cherry", "jungle", "acacia", "spruce", "birch",
            "poplar", "oak", "crimson", "warped");
    private static final String[][] WOOD_KINDS = {{"_fence_gate", "1.0"}, {"_fence", "0.42"}, {"_wall_sign", "0.55"}, {"_sign", "0.55"},
            {"_pressure_plate", "0.5"}, {"_trapdoor", "0.75"}, {"_door", "0.5"}, {"_button", "0.25"}, {"_stairs", "0.375"}, {"_slab", "0.125"},
            {"_planks", "0.25"}, {"_log", "1.0"}, {"_wood", "1.34"}, {"_stem", "1.0"}, {"_hyphae", "1.34"}};

    public record WoodItem(String prefix, String wood, String kind, double logs) {
        public String id(String species) {
            return "minecraft:" + prefix + species + kind;
        }
    }

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
            if (path.equals("cobblestone")) {
                for (String stone : STONE_STAND_INS) {
                    if (available.test(stone, count)) {
                        target = stone;
                        break;
                    }
                }
            } else if (path.equals("glass_pane")) {
                target = WOOD_FENCE;
            } else if (path.equals("lantern")) {
                target = ironAge ? null : TORCH;
            } else if (path.equals("hay_block")) {
                target = WOOD_PLANKS;
            } else if (path.endsWith("_carpet")) {
                target = WOOD_PLATE;
            } else if (path.endsWith("_wool")) {
                target = WOOD_PLANKS;
            } else if (path.equals("bookshelf") || path.equals("glass")) {
                target = WOOD_PLANKS;
            } else if (path.equals("flower_pot")) {
                target = WOOD_PLATE;
            } else if (path.equals("lectern")) {
                target = "minecraft:crafting_table";
            } else if (path.equals("candle")) {
                target = TORCH;
            } else if (path.equals("campfire")) {
                target = "minecraft:cobblestone_wall";
            } else if (path.equals("bricks") || path.equals("stone_bricks") || path.equals("terracotta") || path.equals("chiseled_stone_bricks")
                    || path.equals("polished_andesite") || path.equals("smooth_stone")) {
                target = "minecraft:cobblestone";
            } else if (path.equals("brick_stairs") || path.equals("stone_brick_stairs")) {
                target = "minecraft:cobblestone_stairs";
            } else if (path.equals("brick_slab") || path.equals("stone_brick_slab") || path.equals("smooth_stone_slab")) {
                target = "minecraft:cobblestone_slab";
            } else if (path.equals("brick_wall") || path.equals("stone_brick_wall")) {
                target = "minecraft:cobblestone_wall";
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

    public static boolean keepsShape(String from, String to) {
        for (String suffix : new String[] {"_bed", "_stairs", "_slab", "_wall"}) {
            if (from.endsWith(suffix) && to.endsWith(suffix)) {
                return true;
            }
        }
        WoodItem a = woodItem(from);
        WoodItem b = woodItem(to);
        return a != null && b != null && a.prefix().equals(b.prefix()) && a.kind().equals(b.kind());
    }

    public static boolean sameKin(String placed, String wanted) {
        if (placed.equals(wanted)) {
            return true;
        }
        if (Ids.path(placed).endsWith("_bed") && Ids.path(wanted).endsWith("_bed")) {
            return true;
        }
        WoodItem a = woodItem(placed);
        WoodItem b = woodItem(wanted);
        return a != null && b != null && a.prefix().equals(b.prefix()) && a.kind().equals(b.kind());
    }

    public static WoodItem woodItem(String id) {
        String path = Ids.path(id);
        String prefix = path.startsWith("stripped_") ? "stripped_" : "";
        String rest = path.substring(prefix.length());
        for (String wood : WOODS) {
            if (!rest.startsWith(wood + "_")) {
                continue;
            }
            String kind = rest.substring(wood.length());
            for (String[] known : WOOD_KINDS) {
                if (kind.equals(known[0])) {
                    return new WoodItem(prefix, wood, kind, Double.parseDouble(known[1]));
                }
            }
            return null;
        }
        return null;
    }

    public static Map<String, String> mixWood(Map<String, Integer> required, String wood, ToIntFunction<String> stock) {
        Map<String, Double> supply = new HashMap<>();
        for (String species : WOODS) {
            double logs = stock.applyAsInt("minecraft:" + species + "_log") + stock.applyAsInt("minecraft:" + species + "_stem")
                    + stock.applyAsInt("minecraft:" + species + "_planks") / 4.0;
            if (logs > 0) {
                supply.put(species, logs);
            }
        }
        record Demand(WoodItem item, String id, double logs) {
        }
        List<Demand> demands = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : required.entrySet()) {
            WoodItem item = woodItem(entry.getKey());
            if (item == null || !item.wood().equals(wood)) {
                continue;
            }
            boolean raw = item.prefix().isEmpty() && (item.kind().equals("_log") || item.kind().equals("_planks") || item.kind().equals("_stem"));
            int missing = raw ? entry.getValue() : Math.max(0, entry.getValue() - stock.applyAsInt(entry.getKey()));
            if (missing > 0) {
                demands.add(new Demand(item, entry.getKey(), missing * item.logs()));
            }
        }
        demands.sort(Comparator.comparingDouble(Demand::logs).reversed());
        double total = 0;
        for (Demand demand : demands) {
            total += demand.logs();
        }
        if (demands.isEmpty() || supply.getOrDefault(wood, 0.0) >= total) {
            return Map.of();
        }
        Map<String, String> swaps = new LinkedHashMap<>();
        for (Demand demand : demands) {
            String best = wood;
            double left = supply.getOrDefault(wood, 0.0);
            if (left < demand.logs()) {
                for (Map.Entry<String, Double> candidate : supply.entrySet()) {
                    if (candidate.getValue() >= demand.logs() && (best.equals(wood) || candidate.getValue() > supply.get(best))) {
                        best = candidate.getKey();
                    }
                }
            }
            supply.merge(best, -demand.logs(), Double::sum);
            if (best.equals(wood)) {
                continue;
            }
            swaps.put(demand.id(), demand.item().id(best));
            if (demand.item().kind().equals("_sign")) {
                swaps.put("minecraft:" + wood + "_wall_sign", "minecraft:" + best + "_wall_sign");
            }
        }
        return swaps;
    }

    public static boolean isWoodItem(String id) {
        return woodItem(id) != null;
    }

    public static String resolve(String target, String wood) {
        return target.replace("$wood", wood);
    }
}
