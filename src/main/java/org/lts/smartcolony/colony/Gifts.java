package org.lts.smartcolony.colony;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.lts.smartcolony.core.Pos;
import org.lts.smartcolony.util.ItemIds;
import org.lts.smartcolony.util.WorldUtil;

final class Gifts {
    private static final int PERIOD = 20;
    private static final int MIN_AGE = 30;
    private static final double REACH = 4.0;
    private static final long REPORT_AFTER = 60;
    private static final int LISTED = 6;

    private record Received(Component name, int count) {
    }

    private final Colony colony;
    private final Map<String, Received> received = new LinkedHashMap<>();
    private long lastGift;

    Gifts(Colony colony) {
        this.colony = colony;
    }

    void tick(ServerLevel level, long ticks) {
        if (ticks % PERIOD != 3) {
            return;
        }
        long now = level.getGameTime();
        List<BlockPos> points = new ArrayList<>();
        points.add(colony.depot());
        for (List<Pos> list : colony.data().storage.values()) {
            for (Pos pos : list) {
                points.add(WorldUtil.bp(pos));
            }
        }
        Set<ItemEntity> seen = new HashSet<>();
        for (BlockPos point : points) {
            if (!WorldUtil.loaded(level, point)) {
                continue;
            }
            for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(point).inflate(REACH),
                    item -> item.isAlive() && item.getAge() >= MIN_AGE && item.getOwner() instanceof Player)) {
                if (seen.add(item)) {
                    accept(level, item, now);
                }
            }
        }
        if (!received.isEmpty() && now - lastGift >= REPORT_AFTER) {
            report(level);
        }
    }

    private void accept(ServerLevel level, ItemEntity entity, long now) {
        ItemStack stack = entity.getItem();
        if (stack.isEmpty()) {
            return;
        }
        Component name = stack.getHoverName();
        String id = ItemIds.id(stack);
        ItemStack rest = colony.warehouse().insert(level, stack.copy());
        int taken = stack.getCount() - rest.getCount();
        if (taken <= 0) {
            return;
        }
        if (rest.isEmpty()) {
            entity.discard();
        } else {
            entity.setItem(rest);
        }
        received.merge(id, new Received(name, taken), (a, b) -> new Received(a.name(), a.count() + b.count()));
        lastGift = now;
        colony.data().bump("gifts_received", taken);
        colony.markDirty();
    }

    private void report(ServerLevel level) {
        MutableComponent list = Component.empty();
        int shown = 0;
        for (Received entry : received.values()) {
            if (shown == LISTED) {
                list.append(", …");
                break;
            }
            if (shown > 0) {
                list.append(", ");
            }
            list.append(Component.literal(entry.count() + " × ")).append(entry.name());
            shown++;
        }
        colony.notify(level, Component.translatable("smartcolony.chat.gift", list));
        colony.journal(level, "took " + received.size() + " kinds of items the player left at the storage");
        received.clear();
    }
}
