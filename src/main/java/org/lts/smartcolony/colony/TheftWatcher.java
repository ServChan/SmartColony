package org.lts.smartcolony.colony;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.lts.smartcolony.core.Category;
import org.lts.smartcolony.core.Pos;
import org.lts.smartcolony.core.Reputation;
import org.lts.smartcolony.util.WorldUtil;

public final class TheftWatcher {
    private record Session(BlockPos pos, Container container, Map<String, Integer> before, AbstractContainerMenu menu) {
    }

    private final Colony colony;
    private final Map<UUID, Session> sessions = new HashMap<>();

    public TheftWatcher(Colony colony) {
        this.colony = colony;
    }

    public void tick(ServerLevel level) {
        Iterator<Map.Entry<UUID, Session>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Session> entry = it.next();
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null || player.containerMenu != entry.getValue().menu() || !player.isAlive() || player.level() != level) {
                Session session = entry.getValue();
                it.remove();
                if (player != null) {
                    settle(level, player, session.before(), colony.warehouse().contents(session.container()));
                }
            }
        }
        for (ServerPlayer player : level.players()) {
            if (sessions.containsKey(player.getUUID()) || player.containerMenu == player.inventoryMenu) {
                continue;
            }
            if (player.isCreative() || player.isSpectator()) {
                continue;
            }
            if (!(player.containerMenu instanceof ChestMenu chest)) {
                continue;
            }
            Container container = chest.getContainer();
            if (container instanceof BlockEntity blockEntity && colony.warehouse().contains(blockEntity.getBlockPos())) {
                sessions.put(player.getUUID(), new Session(blockEntity.getBlockPos().immutable(), container,
                        colony.warehouse().contents(container), player.containerMenu));
            }
        }
    }

    private void settle(ServerLevel level, ServerPlayer player, Map<String, Integer> before, Map<String, Integer> after) {
        Reputation.Exchange exchange = Reputation.exchange(before, after);
        if (exchange.isEmpty()) {
            return;
        }
        colony.warehouse().invalidate();
        if (exchange.isTheft()) {
            int penalty = Reputation.theftPenalty(exchange.net());
            colony.data().bump("thefts", 1);
            colony.journal(level, player.getName().getString() + " took " + exchange.taken() + " without paying (-" + penalty + ")");
            colony.adjustReputation(level, player, -penalty, "smartcolony.chat.theft");
            colony.defense().enemies();
        } else if (exchange.givenValue() > 0) {
            int bonus = Reputation.donationBonus(exchange.givenValue() - exchange.takenValue());
            if (!exchange.taken().isEmpty()) {
                colony.data().bump("purchases", 1);
                colony.journal(level, player.getName().getString() + " paid for " + exchange.taken());
                if (bonus == 0) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("smartcolony.chat.prefix", colony.data().name)
                            .append(" ").append(net.minecraft.network.chat.Component.translatable("smartcolony.chat.purchase")));
                }
            } else {
                colony.data().bump("donations", 1);
                colony.journal(level, player.getName().getString() + " donated " + exchange.given());
            }
            if (bonus > 0) {
                colony.adjustReputation(level, player, bonus, "smartcolony.chat.donation");
            }
        }
    }

    public void onBreak(ServerLevel level, ServerPlayer player, BlockPos pos) {
        Container container = Warehouse.container(level, pos);
        Map<String, Integer> before = container == null ? Map.of() : colony.warehouse().contents(container);
        Pos p = WorldUtil.pos(pos);
        for (Category category : Category.values()) {
            List<Pos> list = colony.data().storage.get(category);
            if (list != null) {
                list.remove(p);
            }
        }
        colony.data().treasury.remove(p);
        colony.warehouse().invalidate();
        colony.markDirty();
        colony.journal(level, player.getName().getString() + " broke a warehouse container at " + pos.toShortString());
        if (player.isCreative()) {
            return;
        }
        Reputation.Exchange exchange = Reputation.exchange(before, Map.of());
        int penalty = Math.max(10, Reputation.theftPenalty(exchange.takenValue()));
        colony.data().bump("thefts", 1);
        colony.adjustReputation(level, player, -penalty, "smartcolony.chat.vandal");
    }
}
