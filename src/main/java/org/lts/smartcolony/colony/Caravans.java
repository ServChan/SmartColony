package org.lts.smartcolony.colony;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import org.lts.smartcolony.SmartColonyConfig;
import org.lts.smartcolony.core.CaravanPlan;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.Stock;
import org.lts.smartcolony.util.ItemIds;

final class Caravans {
    private static final Map<String, Stock> SNAPSHOTS = new HashMap<>();

    private Caravans() {
    }

    static void forget() {
        SNAPSHOTS.clear();
    }

    static void tick(MinecraftServer server) {
        if (!SmartColonyConfig.get().caravans) {
            return;
        }
        for (Colony colony : ColonyManager.all()) {
            ServerLevel level = ColonyManager.levelOf(colony);
            if (level == null || !level.isPositionEntityTicking(colony.center())) {
                continue;
            }
            SNAPSHOTS.put(colony.id(), colony.warehouse().stock(level).copy());
            deliver(level, colony);
        }
        for (Colony colony : ColonyManager.all()) {
            ServerLevel level = ColonyManager.levelOf(colony);
            if (level == null || !level.isPositionEntityTicking(colony.center())) {
                continue;
            }
            dispatch(level, colony);
        }
    }

    private static boolean tradingPost(Colony colony) {
        for (ColonyData.Building building : colony.data().buildings) {
            if ("market".equals(building.kind) || "townhall".equals(building.kind) || "trading_post".equals(building.kind)) {
                return true;
            }
        }
        return false;
    }

    private static void dispatch(ServerLevel level, Colony from) {
        long now = level.getGameTime();
        if (!tradingPost(from) || !CaravanPlan.due(now, from.data().lastCaravan) || from.colonists().size() < 6) {
            return;
        }
        Stock stock = SNAPSHOTS.get(from.id());
        if (stock == null) {
            return;
        }
        for (Colony to : ColonyManager.all()) {
            if (to == from || !tradingPost(to) || !to.data().dimension.equals(from.data().dimension) || !Embassy.tradeAllowed(from, to)) {
                continue;
            }
            double distance = Math.sqrt(from.center().distSqr(to.center()));
            Stock theirs = SNAPSHOTS.get(to.id());
            if (theirs == null || distance > CaravanPlan.MAX_DISTANCE) {
                continue;
            }
            List<CaravanPlan.Load> loads = CaravanPlan.loads(stock, theirs);
            if (loads.isEmpty()) {
                continue;
            }
            ColonyData.Caravan caravan = new ColonyData.Caravan();
            caravan.from = from.id();
            caravan.to = to.id();
            caravan.departed = now;
            caravan.arrives = now + CaravanPlan.travelTicks(distance);
            for (CaravanPlan.Load load : loads) {
                int taken = from.warehouse().take(level, load.item(), load.amount());
                if (taken > 0) {
                    caravan.goods.merge(load.item(), taken, Integer::sum);
                    stock.remove(load.item(), taken);
                }
            }
            from.data().lastCaravan = now;
            if (caravan.goods.isEmpty()) {
                return;
            }
            if (from.embassy().sendCaravan(level, to, caravan.goods)) {
                from.data().bump("caravans_sent", 1);
                from.journal(level, "a carrier set out for " + to.data().name + " with " + caravan.goods);
                Embassy.tell(level, from, to, Component.translatable("smartcolony.diplomacy.caravan_walking", from.data().name, to.data().name));
                from.markDirty();
                return;
            }
            to.data().caravans.add(caravan);
            from.data().bump("caravans_sent", 1);
            from.journal(level, "caravan left for " + to.data().name + " with " + caravan.goods);
            from.notify(level, Component.translatable("smartcolony.chat.caravan_sent", to.data().name, (caravan.arrives - now) / 20));
            to.journal(level, "a caravan from " + from.data().name + " is on its way");
            from.markDirty();
            to.markDirty();
            return;
        }
        from.data().lastCaravan = now - CaravanPlan.INTERVAL / 2;
    }

    private static void deliver(ServerLevel level, Colony colony) {
        long now = level.getGameTime();
        Iterator<ColonyData.Caravan> it = colony.data().caravans.iterator();
        while (it.hasNext()) {
            ColonyData.Caravan caravan = it.next();
            if (now < caravan.arrives) {
                continue;
            }
            Iterator<Map.Entry<String, Integer>> goods = caravan.goods.entrySet().iterator();
            int delivered = 0;
            while (goods.hasNext()) {
                Map.Entry<String, Integer> entry = goods.next();
                int left = entry.getValue();
                while (left > 0) {
                    ItemStack stack = ItemIds.stack(entry.getKey(), Math.min(left, 64));
                    if (stack.isEmpty()) {
                        left = 0;
                        break;
                    }
                    int size = stack.getCount();
                    ItemStack rest = colony.warehouse().insert(level, stack);
                    int moved = size - rest.getCount();
                    left -= moved;
                    delivered += moved;
                    if (!rest.isEmpty()) {
                        break;
                    }
                }
                if (left <= 0) {
                    goods.remove();
                } else {
                    entry.setValue(left);
                }
            }
            if (delivered > 0) {
                Colony source = ColonyManager.byId(caravan.from);
                if (source != null) {
                    colony.embassy().caravanArrived(level, source, delivered);
                } else {
                    colony.journal(level, "caravan from " + caravan.from + " unloaded " + delivered + " items");
                    colony.notify(level, Component.translatable("smartcolony.chat.caravan_arrived", caravan.from, delivered));
                    colony.data().bump("caravans_received", 1);
                    colony.markDirty();
                }
            }
            if (caravan.goods.isEmpty()) {
                it.remove();
            } else if (delivered == 0) {
                caravan.arrives = now + 1200;
            }
        }
    }
}
