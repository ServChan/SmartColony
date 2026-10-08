package org.lts.smartcolony.colony;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.Decree;
import org.lts.smartcolony.core.Stock;
import org.lts.smartcolony.util.ItemIds;

public final class Contracts {
    private static final int HAND_OVER_RADIUS = 8;

    private final Colony colony;

    public Contracts(Colony colony) {
        this.colony = colony;
    }

    public enum Result {
        ADDED,
        FULL,
        UNKNOWN_ITEM
    }

    public Result add(ServerLevel level, ServerPlayer player, String item, int count) {
        if (!ItemIds.exists(item)) {
            return Result.UNKNOWN_ITEM;
        }
        if (colony.data().contracts.size() >= Decree.MAX_CONTRACTS) {
            return Result.FULL;
        }
        ColonyData.Contract contract = new ColonyData.Contract();
        contract.item = item;
        contract.count = Math.max(1, Math.min(Decree.MAX_CONTRACT, count));
        contract.player = player.getUUID().toString();
        contract.created = level.getGameTime();
        colony.data().contracts.add(contract);
        colony.journal(level, "order from " + player.getName().getString() + ": " + contract.count + " " + item);
        colony.markDirty();
        return Result.ADDED;
    }

    public int cancel(ServerLevel level, UUID player) {
        int removed = 0;
        Iterator<ColonyData.Contract> it = colony.data().contracts.iterator();
        while (it.hasNext()) {
            ColonyData.Contract contract = it.next();
            if (contract.player.equals(player.toString())) {
                if (contract.parcel > 0) {
                    colony.warehouse().give(level, contract.item, contract.parcel);
                }
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            colony.markDirty();
        }
        return removed;
    }

    public Map<String, Integer> missing(ServerLevel level) {
        Map<String, Integer> missing = new LinkedHashMap<>();
        if (colony.data().contracts.isEmpty()) {
            return missing;
        }
        Stock stock = colony.warehouse().stock(level);
        for (ColonyData.Contract contract : colony.data().contracts) {
            int left = contract.count - contract.delivered - contract.parcel;
            if (left > 0 && stock.get(contract.item) < left) {
                missing.merge(contract.item, left - stock.get(contract.item), Integer::sum);
            }
        }
        return missing;
    }

    public void tick(ServerLevel level, long ticks) {
        if (ticks % 200 != 61 || colony.data().contracts.isEmpty()) {
            return;
        }
        for (ColonyData.Contract contract : colony.data().contracts) {
            int left = contract.count - contract.delivered - contract.parcel;
            if (left > 0) {
                int have = colony.warehouse().stock(level).get(contract.item);
                if (have < left) {
                    colony.craft(level, contract.item, Math.min(64, left - have));
                    colony.request(contract.item);
                }
                int taken = colony.warehouse().take(level, contract.item, left);
                if (taken > 0) {
                    contract.parcel += taken;
                    colony.markDirty();
                }
            }
            if (!contract.ready && contract.parcel + contract.delivered >= contract.count) {
                contract.ready = true;
                colony.journal(level, "order ready: " + contract.count + " " + contract.item);
                ServerPlayer player = level.getServer().getPlayerList().getPlayer(UUID.fromString(contract.player));
                if (player != null) {
                    player.sendSystemMessage(Component.translatable("smartcolony.chat.prefix", colony.data().name).append(" ")
                            .append(Component.translatable("smartcolony.chat.order_ready", contract.count, ItemIds.stack(contract.item, 1).getHoverName())));
                }
            }
        }
        handOver(level);
    }

    private void handOver(ServerLevel level) {
        Iterator<ColonyData.Contract> it = colony.data().contracts.iterator();
        while (it.hasNext()) {
            ColonyData.Contract contract = it.next();
            if (contract.parcel <= 0) {
                continue;
            }
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(UUID.fromString(contract.player));
            if (player == null || player.level() != level || !near(player)) {
                continue;
            }
            int given = 0;
            while (contract.parcel > 0) {
                ItemStack stack = ItemIds.stack(contract.item, Math.min(contract.parcel, ItemIds.stack(contract.item, 1).getMaxStackSize()));
                int size = stack.getCount();
                if (!player.getInventory().add(stack) && stack.getCount() == size) {
                    break;
                }
                int moved = size - stack.getCount();
                contract.parcel -= moved;
                contract.delivered += moved;
                given += moved;
                if (!stack.isEmpty()) {
                    break;
                }
            }
            if (given > 0) {
                player.sendSystemMessage(Component.translatable("smartcolony.chat.prefix", colony.data().name).append(" ")
                        .append(Component.translatable("smartcolony.chat.order_handed", given, ItemIds.stack(contract.item, 1).getHoverName())));
                colony.data().bump("orders_items_handed", given);
                colony.markDirty();
            }
            if (contract.delivered >= contract.count) {
                colony.journal(level, "order complete: " + contract.count + " " + contract.item);
                colony.data().bump("orders_completed", 1);
                it.remove();
            }
        }
    }

    private boolean near(ServerPlayer player) {
        double radius = HAND_OVER_RADIUS;
        if (player.blockPosition().distSqr(colony.depot()) <= radius * radius
                || player.blockPosition().distSqr(colony.center()) <= radius * radius) {
            return true;
        }
        for (ColonyData.Building building : colony.data().buildings) {
            if ("townhall".equals(building.kind) && building.contains(org.lts.smartcolony.util.WorldUtil.pos(player.blockPosition()), 4)) {
                return true;
            }
        }
        return false;
    }
}
