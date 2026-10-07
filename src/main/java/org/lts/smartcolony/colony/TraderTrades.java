package org.lts.smartcolony.colony;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import org.lts.smartcolony.SmartColonyConfig;
import org.lts.smartcolony.core.Category;
import org.lts.smartcolony.core.CrafterNeeds;
import org.lts.smartcolony.core.Ids;
import org.lts.smartcolony.core.ItemClassifier;
import org.lts.smartcolony.core.Reputation;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.core.Stock;
import org.lts.smartcolony.core.TradePlanner;
import org.lts.smartcolony.util.ItemIds;

final class TraderTrades {
    private static final int REQUEST_QUANTITY = 8;
    private static final int STONE_RESERVE = 384;
    private static final int FOOD_PER_ADULT = 8;

    private TraderTrades() {
    }

    static boolean tradable(String id) {
        ItemStack stack = ItemIds.stack(id, 1);
        return !stack.isEmpty() && !stack.isDamageableItem() && stack.getMaxStackSize() > 1;
    }

    static int stackSize(String id) {
        ItemStack stack = ItemIds.stack(id, 1);
        return stack.isEmpty() ? 1 : stack.getMaxStackSize();
    }

    static TradePlanner.Plan plan(ServerLevel level, Colony colony, Reputation.Status status) {
        if (status == Reputation.Status.ENEMY) {
            return TradePlanner.Plan.EMPTY;
        }
        Stock stock = colony.warehouse().stock(level);
        Map<String, Integer> missing = colony.construction().missing();
        List<TradePlanner.Need> needs = new ArrayList<>();
        missing.forEach((id, count) -> needs.add(new TradePlanner.Need(id, count, TradePlanner.PRIORITY_CONSTRUCTION)));
        for (String request : colony.requests()) {
            needs.add(new TradePlanner.Need(request, REQUEST_QUANTITY, TradePlanner.PRIORITY_REQUEST));
        }
        List<String> products = new ArrayList<>();
        CrafterNeeds.Context context = colony.crafterContext(level);
        for (Role role : List.of(Role.FLETCHER, Role.SMITH, Role.COOK)) {
            for (CrafterNeeds.Want want : CrafterNeeds.wants(role, context)) {
                products.add(want.item());
            }
        }
        needs.addAll(TradePlanner.crafterNeeds(stock, products));
        needs.addAll(TradePlanner.essentials(stock));
        needs.removeIf(need -> !tradable(need.id()));
        int adults = colony.adultCount(level);
        Map<String, Integer> surplus = new HashMap<>();
        for (Map.Entry<String, Integer> entry : stock.view().entrySet()) {
            String id = entry.getKey();
            if (id.equals(TradePlanner.EMERALD_ID) || !tradable(id) || ItemClassifier.classify(id) == Category.ARSENAL) {
                continue;
            }
            int reserve = reserve(id, adults) + missing.getOrDefault(id, 0);
            if (entry.getValue() > reserve) {
                surplus.put(id, entry.getValue() - reserve);
            }
        }
        return TradePlanner.plan(stock, needs, surplus, status, TraderTrades::stackSize);
    }

    static int reserve(String id, int adults) {
        if (Ids.isLog(id) || Ids.isPlanks(id)) {
            return SmartColonyConfig.get().woodSurplus;
        }
        String path = Ids.path(id);
        if (path.equals("cobblestone") || path.equals("cobbled_deepslate")) {
            return STONE_RESERVE;
        }
        int keep = Market.keepLevel(id);
        if (path.startsWith("cooked_") || path.equals("bread") || path.equals("baked_potato")) {
            return Math.max(keep, adults * FOOD_PER_ADULT);
        }
        return keep;
    }

    static MerchantOffer toMerchantOffer(TradePlanner.Offer offer) {
        ItemStack result = ItemIds.stack(offer.result(), offer.resultCount());
        ItemCost cost = new ItemCost(ItemIds.item(offer.give()), offer.giveCount());
        return new MerchantOffer(cost, Optional.empty(), result, 0, Math.max(1, offer.maxUses()), 0, 0.0F);
    }
}
