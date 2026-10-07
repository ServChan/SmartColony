package org.lts.smartcolony.colony;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.lts.smartcolony.core.Category;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.CraftPlanner;
import org.lts.smartcolony.core.ItemClassifier;
import org.lts.smartcolony.core.Pos;
import org.lts.smartcolony.core.Stock;
import org.lts.smartcolony.util.ItemIds;
import org.lts.smartcolony.util.WorldUtil;

public final class Warehouse {
    private final ColonyData data;
    private Stock cached;
    private long cachedAt = Long.MIN_VALUE;

    public Warehouse(ColonyData data) {
        this.data = data;
    }

    public void invalidate() {
        cached = null;
    }

    public boolean contains(BlockPos pos) {
        Pos p = WorldUtil.pos(pos);
        for (List<Pos> list : data.storage.values()) {
            if (list.contains(p)) {
                return true;
            }
        }
        return false;
    }

    public List<Container> containers(ServerLevel level, Category category) {
        List<Container> result = new ArrayList<>();
        for (Pos pos : data.storage.getOrDefault(category, List.of())) {
            Container container = container(level, WorldUtil.bp(pos));
            if (container != null) {
                result.add(container);
            }
        }
        return result;
    }

    public List<Container> all(ServerLevel level) {
        List<Container> result = new ArrayList<>();
        for (Category category : Category.values()) {
            result.addAll(containers(level, category));
        }
        return result;
    }

    public static Container container(ServerLevel level, BlockPos pos) {
        if (!WorldUtil.loaded(level, pos)) {
            return null;
        }
        BlockEntity entity = level.getBlockEntity(pos);
        return entity instanceof Container container ? container : null;
    }

    public boolean available(ServerLevel level) {
        return !all(level).isEmpty();
    }

    public Stock stock(ServerLevel level) {
        long now = level.getGameTime();
        if (cached != null && now - cachedAt < 20) {
            return cached;
        }
        Stock stock = new Stock();
        for (Container container : all(level)) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.isEmpty()) {
                    stock.add(ItemIds.id(stack), stack.getCount());
                }
            }
        }
        stock.grant(org.lts.smartcolony.core.Tech.capabilities(blueprints()));
        cached = stock;
        cachedAt = now;
        return stock;
    }

    private List<String> blueprints() {
        List<String> ids = new ArrayList<>();
        for (ColonyData.Building building : data.buildings) {
            ids.add(building.blueprint);
        }
        return ids;
    }

    public Map<Category, Double> fill(ServerLevel level) {
        Map<Category, Double> result = new EnumMap<>(Category.class);
        for (Category category : Category.values()) {
            int used = 0;
            int total = 0;
            for (Container container : containers(level, category)) {
                total += container.getContainerSize();
                for (int slot = 0; slot < container.getContainerSize(); slot++) {
                    if (!container.getItem(slot).isEmpty()) {
                        used++;
                    }
                }
            }
            result.put(category, total == 0 ? 1.0 : (double) used / total);
        }
        return result;
    }

    public ItemStack insert(ServerLevel level, ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack rest = stack.copy();
        Category home = ItemClassifier.classify(ItemIds.id(rest));
        rest = insertInto(containers(level, home), rest);
        if (!rest.isEmpty()) {
            for (Category category : Category.values()) {
                if (category != home) {
                    rest = insertInto(containers(level, category), rest);
                    if (rest.isEmpty()) {
                        break;
                    }
                }
            }
        }
        invalidate();
        return rest;
    }

    private static ItemStack insertInto(List<Container> containers, ItemStack stack) {
        for (Container container : containers) {
            for (int slot = 0; slot < container.getContainerSize() && !stack.isEmpty(); slot++) {
                ItemStack current = container.getItem(slot);
                if (!current.isEmpty() && ItemStack.isSameItemSameComponents(current, stack)) {
                    int room = Math.min(container.getMaxStackSize(current), current.getMaxStackSize()) - current.getCount();
                    if (room > 0) {
                        int moved = Math.min(room, stack.getCount());
                        current.grow(moved);
                        stack.shrink(moved);
                        container.setChanged();
                    }
                }
            }
        }
        for (Container container : containers) {
            for (int slot = 0; slot < container.getContainerSize() && !stack.isEmpty(); slot++) {
                if (container.getItem(slot).isEmpty() && container.canPlaceItem(slot, stack)) {
                    int moved = Math.min(stack.getCount(), Math.min(container.getMaxStackSize(stack), stack.getMaxStackSize()));
                    container.setItem(slot, stack.split(moved));
                    container.setChanged();
                }
            }
        }
        return stack;
    }

    public List<ItemStack> extract(ServerLevel level, Predicate<ItemStack> filter, int count) {
        List<ItemStack> taken = new ArrayList<>();
        int remaining = count;
        for (Container container : all(level)) {
            for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
                ItemStack current = container.getItem(slot);
                if (!current.isEmpty() && filter.test(current)) {
                    int moved = Math.min(remaining, current.getCount());
                    taken.add(current.split(moved));
                    remaining -= moved;
                    container.setChanged();
                }
            }
            if (remaining <= 0) {
                break;
            }
        }
        if (!taken.isEmpty()) {
            invalidate();
        }
        return taken;
    }

    public ItemStack extractOne(ServerLevel level, Predicate<ItemStack> filter) {
        List<ItemStack> list = extract(level, filter, 1);
        return list.isEmpty() ? ItemStack.EMPTY : list.getFirst();
    }

    public int take(ServerLevel level, String id, int count) {
        int taken = 0;
        for (ItemStack stack : extract(level, s -> ItemIds.id(s).equals(id), count)) {
            taken += stack.getCount();
        }
        return taken;
    }

    public boolean takeAll(ServerLevel level, Map<String, Integer> items) {
        if (!stock(level).containsAll(items)) {
            return false;
        }
        items.forEach((id, count) -> take(level, id, count));
        return true;
    }

    public boolean applyPlan(ServerLevel level, CraftPlanner.Plan plan) {
        Stock stock = stock(level);
        for (Map.Entry<String, Integer> entry : plan.delta().entrySet()) {
            if (entry.getValue() < 0 && stock.get(entry.getKey()) < -entry.getValue()) {
                return false;
            }
        }
        for (Map.Entry<String, Integer> entry : plan.delta().entrySet()) {
            if (entry.getValue() < 0) {
                take(level, entry.getKey(), -entry.getValue());
            }
        }
        for (Map.Entry<String, Integer> entry : plan.delta().entrySet()) {
            if (entry.getValue() > 0) {
                give(level, entry.getKey(), entry.getValue());
            }
        }
        invalidate();
        return true;
    }

    public int give(ServerLevel level, String id, int count) {
        int left = count;
        int overflow = 0;
        while (left > 0) {
            ItemStack stack = ItemIds.stack(id, left);
            if (stack.isEmpty()) {
                return count;
            }
            int batch = Math.min(left, stack.getMaxStackSize());
            stack.setCount(batch);
            left -= batch;
            ItemStack rest = insert(level, stack);
            if (!rest.isEmpty()) {
                overflow += rest.getCount();
                BlockPos at = WorldUtil.bp(data.depot).above();
                net.minecraft.world.entity.item.ItemEntity entity = new net.minecraft.world.entity.item.ItemEntity(
                        level, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, rest);
                if (all(level).isEmpty()) {
                    entity.setUnlimitedLifetime();
                }
                level.addFreshEntity(entity);
            }
        }
        return overflow;
    }

    public Map<String, Integer> contents(Container container) {
        Stock stock = new Stock();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty()) {
                stock.add(ItemIds.id(stack), stack.getCount());
            }
        }
        return new java.util.TreeMap<>(stock.view());
    }

    public int freeSlots(ServerLevel level) {
        int free = 0;
        for (Container container : all(level)) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                if (container.getItem(slot).isEmpty()) {
                    free++;
                }
            }
        }
        return free;
    }
}
