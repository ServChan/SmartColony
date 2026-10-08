package org.lts.smartcolony.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class CrafterNeeds {
    public record Want(String item, int count, boolean smelt) {
    }

    public record Context(Stock stock, Map<Role, Integer> roles, int population, int unarmedGuards) {
        int roleCount(Role role) {
            return roles.getOrDefault(role, 0);
        }
    }

    private static final int IRON_STOCK_TARGET = 64;
    private static final int ARMOR_INGOTS = 24;
    private static final List<String> TIERS = List.of("iron", "stone", "wooden");
    private static final List<String> ARMOR = List.of("helmet", "chestplate", "leggings", "boots");
    private static final List<String> RAW_FOOD = List.of("cod", "salmon", "beef", "porkchop", "mutton", "chicken", "rabbit");

    private CrafterNeeds() {
    }

    public static boolean crafter(Role role) {
        return role == Role.FLETCHER || role == Role.SMITH || role == Role.COOK || role == Role.MASON;
    }

    public static List<Want> wants(Role role, Context ctx) {
        return switch (role) {
            case FLETCHER -> fletcher(ctx);
            case SMITH -> smith(ctx);
            case COOK -> cook(ctx);
            case MASON -> mason(ctx);
            default -> List.of();
        };
    }

    public static boolean pending(Role role, Context ctx) {
        for (Want want : wants(role, ctx)) {
            if (CraftPlanner.plan(ctx.stock(), want.item(), 1).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private static List<Want> fletcher(Context ctx) {
        List<Want> result = new ArrayList<>();
        int guards = ctx.roleCount(Role.GUARD);
        if (guards <= 0) {
            return result;
        }
        int bowTarget = (guards + 1) / 2;
        int bows = ctx.stock().get(Ids.mc("bow"));
        if (bows < bowTarget) {
            result.add(new Want(Ids.mc("bow"), bowTarget - bows, false));
        }
        int arrowTarget = Math.min(256, 16 * bowTarget);
        int arrows = ctx.stock().get(Ids.mc("arrow"));
        if (arrows < arrowTarget) {
            result.add(new Want(Ids.mc("arrow"), Math.min(16, arrowTarget - arrows), false));
        }
        return result;
    }

    private static int iron(Stock stock) {
        return stock.get(Ids.mc("iron_ingot")) + stock.get(Ids.mc("raw_iron"));
    }

    private static int owned(Stock stock, String suffix) {
        return stock.sum(id -> Ids.path(id).endsWith("_" + suffix));
    }

    static String bestTool(Stock stock, String suffix) {
        for (String tier : TIERS) {
            if (tier.equals("iron") && iron(stock) < 10) {
                continue;
            }
            String item = Ids.mc(tier + "_" + suffix);
            if (CraftPlanner.plan(stock, item, 1).isPresent()) {
                return item;
            }
        }
        return null;
    }

    private static void spare(List<Want> result, Stock stock, String suffix, int users) {
        if (users <= 0 || owned(stock, suffix) >= 1) {
            return;
        }
        String item = bestTool(stock, suffix);
        if (item != null) {
            result.add(new Want(item, 1, false));
        }
    }

    private static List<Want> smith(Context ctx) {
        List<Want> result = new ArrayList<>();
        Stock stock = ctx.stock();
        int rawIron = stock.get(Ids.mc("raw_iron"));
        if (stock.has(Tech.FORGE) && rawIron > 0 && stock.get(Ids.mc("iron_ingot")) < IRON_STOCK_TARGET) {
            result.add(new Want(Ids.mc("iron_ingot"), Math.min(8, rawIron), true));
        }
        int swordsMissing = ctx.unarmedGuards() - owned(stock, "sword");
        if (swordsMissing > 0) {
            String sword = bestTool(stock, "sword");
            if (sword != null) {
                result.add(new Want(sword, Math.min(swordsMissing, 4), false));
            }
        }
        spare(result, stock, "pickaxe", ctx.roleCount(Role.MINER));
        spare(result, stock, "axe", ctx.roleCount(Role.LUMBERJACK));
        spare(result, stock, "hoe", ctx.roleCount(Role.FARMER));
        if (ctx.roleCount(Role.HERDER) > 0 && stock.get(Ids.mc("shears")) < 1) {
            result.add(new Want(Ids.mc("shears"), 1, false));
        }
        int guards = ctx.roleCount(Role.GUARD);
        if (guards > 0 && stock.get(Ids.mc("iron_ingot")) >= ARMOR_INGOTS) {
            for (String piece : ARMOR) {
                if (stock.get(Ids.mc("iron_" + piece)) < guards) {
                    result.add(new Want(Ids.mc("iron_" + piece), 1, false));
                }
            }
        }
        if (iron(stock) >= 24 && stock.get(Ids.mc("bucket")) < 1) {
            result.add(new Want(Ids.mc("bucket"), 1, false));
        }
        return result;
    }

    private static List<Want> mason(Context ctx) {
        List<Want> result = new ArrayList<>();
        Stock stock = ctx.stock();
        int sand = stock.get(Ids.mc("sand")) + stock.get(Ids.mc("red_sand"));
        if (stock.get(Ids.mc("glass")) < 48 && sand > 0) {
            result.add(new Want(Ids.mc("glass"), Math.min(8, sand), true));
        }
        int clay = stock.get(Ids.mc("clay_ball"));
        if (stock.get(Ids.mc("bricks")) < 48 && clay >= 4) {
            result.add(new Want(Ids.mc("bricks"), Math.min(4, clay / 4), false));
        }
        int stone = stock.get(Ids.mc("cobblestone")) + stock.get(Ids.mc("stone"));
        if (stock.get(Ids.mc("stone_bricks")) < 64 && stone >= 96) {
            result.add(new Want(Ids.mc("stone_bricks"), 8, false));
        }
        if (stock.get(Ids.mc("glass_pane")) < 32 && stock.get(Ids.mc("glass")) >= 12) {
            result.add(new Want(Ids.mc("glass_pane"), 16, false));
        }
        if (stock.get(Ids.mc("glass_bottle")) < 6 && stock.get(Ids.mc("glass")) >= 6) {
            result.add(new Want(Ids.mc("glass_bottle"), 3, false));
        }
        if (stock.get(Ids.mc("flower_pot")) < 4 && stock.get(Ids.mc("brick")) >= 6) {
            result.add(new Want(Ids.mc("flower_pot"), 2, false));
        }
        return result;
    }

    public static boolean masonGathers(Stock stock) {
        return stock.get(Ids.mc("sand")) + stock.get(Ids.mc("red_sand")) < 24 || stock.get(Ids.mc("clay_ball")) < 16;
    }

    private static List<Want> cook(Context ctx) {
        List<Want> result = new ArrayList<>();
        Stock stock = ctx.stock();
        for (String raw : RAW_FOOD) {
            int have = stock.get(Ids.mc(raw));
            if (have > 0) {
                result.add(new Want(Ids.mc("cooked_" + raw), Math.min(8, have), true));
            }
        }
        int breadTarget = ctx.population() * 2;
        int bread = stock.get(Ids.mc("bread"));
        int wheat = stock.get(Ids.mc("wheat"));
        if (bread < breadTarget && wheat >= 3) {
            result.add(new Want(Ids.mc("bread"), Math.min(Math.min(8, breadTarget - bread), wheat / 3), false));
        }
        return result;
    }
}
