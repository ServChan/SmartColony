package org.lts.smartcolony.colony;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import org.lts.smartcolony.core.Ids;
import org.lts.smartcolony.core.ItemClassifier;
import org.lts.smartcolony.core.Stock;
import org.lts.smartcolony.util.ItemIds;

public final class Market {
    private static final List<String> FAMILIES = List.of(
            "_pickaxe", "_axe", "_shovel", "_hoe", "_sword", "_bed", "_helmet", "_chestplate", "_leggings", "_boots");
    private static final Map<String, Integer> KEEP = Map.ofEntries(
            Map.entry(Ids.mc("wheat"), 96),
            Map.entry(Ids.mc("carrot"), 64),
            Map.entry(Ids.mc("potato"), 64),
            Map.entry(Ids.mc("beetroot"), 48),
            Map.entry(Ids.mc("cobblestone"), 384),
            Map.entry(Ids.mc("coal"), 96),
            Map.entry(Ids.mc("stick"), 128),
            Map.entry(Ids.mc("string"), 48),
            Map.entry(Ids.mc("feather"), 48),
            Map.entry(Ids.mc("leather"), 48),
            Map.entry(Ids.mc("rotten_flesh"), 0),
            Map.entry(Ids.mc("bone"), 32),
            Map.entry(Ids.mc("gunpowder"), 32),
            Map.entry(Ids.mc("spider_eye"), 0),
            Map.entry(Ids.mc("raw_copper"), 64),
            Map.entry(Ids.mc("copper_ingot"), 64),
            Map.entry(Ids.mc("gold_ingot"), 32),
            Map.entry(Ids.mc("flint"), 32),
            Map.entry(Ids.mc("paper"), 32));

    public record Deal(String sold, int soldCount, String bought, int boughtCount, String trader) {
    }

    private final Colony colony;
    private final List<Deal> ledger = new ArrayList<>();

    public Market(Colony colony) {
        this.colony = colony;
    }

    public List<Deal> ledger() {
        return ledger;
    }

    static int keepLevel(String id) {
        Integer fixed = KEEP.get(id);
        if (fixed != null) {
            return fixed;
        }
        if (Ids.isLog(id) || Ids.isPlanks(id)) {
            return 256;
        }
        return switch (ItemClassifier.classify(id)) {
            case ARSENAL -> 6;
            case FOOD -> 96;
            default -> 576;
        };
    }

    static boolean matchesRequest(String requested, String offered) {
        if (requested.equals(offered)) {
            return true;
        }
        for (String family : FAMILIES) {
            if (requested.endsWith(family) && offered.endsWith(family)) {
                return !(family.equals("_axe") && (requested.endsWith("_pickaxe") || offered.endsWith("_pickaxe")));
            }
        }
        return false;
    }

    public int run(ServerLevel level) {
        int trades = 0;
        for (Villager villager : colony.loadedVillagers(level)) {
            if (villager.isBaby() || villager.isTrading()) {
                continue;
            }
            String profession = villager.getVillagerData().profession().unwrapKey().map(k -> k.identifier().getPath()).orElse("none");
            if (profession.equals("none") || profession.equals("nitwit")) {
                continue;
            }
            for (MerchantOffer offer : villager.getOffers()) {
                if (offer.isOutOfStock()) {
                    continue;
                }
                ItemStack costA = offer.getCostA();
                ItemStack costB = offer.getCostB();
                ItemStack result = offer.getResult();
                String costId = ItemIds.id(costA);
                String resultId = ItemIds.id(result);
                if (result.is(Items.EMERALD) && costB.isEmpty() && !costA.is(Items.EMERALD)) {
                    for (int i = 0; i < 2 && !offer.isOutOfStock(); i++) {
                        Stock stock = colony.warehouse().stock(level);
                        if (stock.get(costId) - costA.getCount() < keepLevel(costId)) {
                            break;
                        }
                        if (execute(level, villager, offer, costA, costB, result, profession)) {
                            trades++;
                        }
                    }
                    continue;
                }
                if (costA.is(Items.EMERALD) && wanted(resultId)) {
                    Stock stock = colony.warehouse().stock(level);
                    if (stock.get(costId) < costA.getCount() || (!costB.isEmpty() && stock.get(ItemIds.id(costB)) < costB.getCount())) {
                        continue;
                    }
                    if (execute(level, villager, offer, costA, costB, result, profession)) {
                        trades++;
                        colony.requests().removeIf(request -> matchesRequest(request, resultId));
                    }
                }
            }
        }
        colony.requests().clear();
        if (trades > 0) {
            colony.notify(level, Component.translatable("smartcolony.chat.market", trades));
        }
        return trades;
    }

    private boolean wanted(String resultId) {
        for (String request : colony.requests()) {
            if (matchesRequest(request, resultId)) {
                return true;
            }
        }
        return false;
    }

    private boolean execute(ServerLevel level, Villager villager, MerchantOffer offer, ItemStack costA, ItemStack costB,
                            ItemStack result, String profession) {
        String costAId = ItemIds.id(costA);
        Stock stock = colony.warehouse().stock(level);
        if (stock.get(costAId) < costA.getCount()) {
            return false;
        }
        if (!costB.isEmpty() && stock.get(ItemIds.id(costB)) < costB.getCount()) {
            return false;
        }
        colony.warehouse().take(level, costAId, costA.getCount());
        if (!costB.isEmpty()) {
            colony.warehouse().take(level, ItemIds.id(costB), costB.getCount());
        }
        ItemStack goods = result.copy();
        ItemStack rest = colony.warehouse().insert(level, goods);
        if (!rest.isEmpty()) {
            villager.spawnAtLocation(level, rest);
        }
        offer.increaseUses();
        villager.setVillagerXp(villager.getVillagerXp() + offer.getXp());
        Deal deal = new Deal(costAId, costA.getCount(), ItemIds.id(result), result.getCount(), profession);
        ledger.add(deal);
        while (ledger.size() > 40) {
            ledger.removeFirst();
        }
        colony.data().bump("market_trades", 1);
        colony.journal(level, "market: " + costA.getCount() + " " + Ids.path(costAId) + " -> " + result.getCount() + " "
                + Ids.path(ItemIds.id(result)) + " (" + profession + ")");
        return true;
    }
}
