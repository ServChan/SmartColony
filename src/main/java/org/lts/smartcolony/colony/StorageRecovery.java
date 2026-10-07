package org.lts.smartcolony.colony;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.lts.smartcolony.core.Category;
import org.lts.smartcolony.core.ChestCraft;
import org.lts.smartcolony.core.Ids;
import org.lts.smartcolony.core.Pos;
import org.lts.smartcolony.util.ItemIds;
import org.lts.smartcolony.util.WorldUtil;
import org.lts.smartcolony.work.Inv;
import org.lts.smartcolony.work.Placer;

public final class StorageRecovery {
    private static final int PERIOD = 100;
    private static final double REACH = 8.0;
    private static final long WARN_EVERY = 6000;

    private final Colony colony;
    private long lastCheck = Long.MIN_VALUE;
    private long lastWarning = Long.MIN_VALUE;

    StorageRecovery(Colony colony) {
        this.colony = colony;
    }

    public void tick(ServerLevel level) {
        long now = level.getGameTime();
        if (lastCheck != Long.MIN_VALUE && now - lastCheck < PERIOD) {
            return;
        }
        lastCheck = now;
        if (!lost(level)) {
            expand(level);
            return;
        }
        BlockPos depot = colony.depot();
        if (!WorldUtil.loaded(level, depot)) {
            return;
        }
        Villager maker = null;
        ChestCraft.Spend spend = null;
        double best = Double.MAX_VALUE;
        for (Villager villager : colony.loadedVillagers(level)) {
            double distance = Math.hypot(villager.getX() - depot.getX(), villager.getZ() - depot.getZ());
            if (villager.isBaby() || distance > REACH || distance >= best) {
                continue;
            }
            ChestCraft.Spend candidate = ChestCraft.plan(
                    Inv.count(villager, stack -> Ids.isPlanks(ItemIds.id(stack))), Inv.count(villager, stack -> Ids.isLog(ItemIds.id(stack))));
            if (candidate != null) {
                maker = villager;
                spend = candidate;
                best = distance;
            }
        }
        BlockPos spot = maker == null ? null : spot(level, depot);
        if (maker == null || spot == null) {
            warn(level, now);
            return;
        }
        build(level, maker, spend, spot);
    }

    private void warn(ServerLevel level, long now) {
        if (lastWarning != Long.MIN_VALUE && now - lastWarning < WARN_EVERY) {
            return;
        }
        lastWarning = now;
        colony.journal(level, "no storage containers left: waiting for a villager with logs or planks at the depot");
        colony.notify(level, Component.translatable("smartcolony.chat.storage_lost"));
    }

    public boolean lost(ServerLevel level) {
        boolean changed = false;
        int remaining = 0;
        for (Category category : Category.values()) {
            List<Pos> list = colony.data().storage.get(category);
            if (list == null) {
                continue;
            }
            int before = list.size();
            list.removeIf(pos -> WorldUtil.loaded(level, WorldUtil.bp(pos)) && Warehouse.container(level, WorldUtil.bp(pos)) == null);
            changed |= list.size() != before;
            remaining += list.size();
        }
        if (changed) {
            colony.warehouse().invalidate();
            colony.journal(level, "dropped storage entries whose containers no longer exist");
            colony.markDirty();
        }
        return remaining == 0;
    }

    private BlockPos spot(ServerLevel level, BlockPos depot) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos pos = depot.offset(dx, dy, dz);
                    if (!WorldUtil.loaded(level, pos) || !WorldUtil.isAirLike(level.getBlockState(pos))
                            || !level.getFluidState(pos).isEmpty() || !WorldUtil.solidFloor(level, pos.below())
                            || colony.sites().onRoad(pos.below()) || level.getBlockState(pos.below()).is(Blocks.DIRT_PATH)
                            || Placer.occupied(level, pos)) {
                        continue;
                    }
                    candidates.add(pos.immutable());
                }
            }
        }
        candidates.sort(Comparator.comparingDouble((BlockPos pos) -> pos.distSqr(depot)));
        return candidates.isEmpty() ? null : candidates.getFirst();
    }

    private void build(ServerLevel level, Villager maker, ChestCraft.Spend spend, BlockPos spot) {
        String planksBack = null;
        if (spend.planks() > 0) {
            Inv.remove(maker, stack -> Ids.isPlanks(ItemIds.id(stack)), spend.planks());
        }
        if (spend.logs() > 0) {
            ItemStack sample = Inv.find(maker, stack -> Ids.isLog(ItemIds.id(stack)));
            planksBack = Ids.planksForLog(ItemIds.id(sample));
            Inv.remove(maker, stack -> Ids.isLog(ItemIds.id(stack)), spend.logs());
        }
        BlockPos depot = colony.depot();
        Direction facing = Direction.getApproximateNearest(depot.getX() - spot.getX(), 0, depot.getZ() - spot.getZ());
        if (!facing.getAxis().isHorizontal()) {
            facing = Direction.NORTH;
        }
        BlockState chest = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing);
        Placer.place(level, maker, spot, chest);
        colony.data().storage.computeIfAbsent(Category.WOOD, key -> new ArrayList<>()).add(WorldUtil.pos(spot));
        colony.warehouse().invalidate();
        if (spend.planksBack() > 0) {
            ItemStack rest = Inv.add(maker, ItemIds.stack(planksBack == null ? Ids.mc("oak_planks") : planksBack, spend.planksBack()));
            if (!rest.isEmpty()) {
                colony.warehouse().insert(level, rest);
            }
        }
        colony.data().bump("storage_rebuilt", 1);
        colony.journal(level, "all storage containers were lost: a villager crafted a chest from " + ChestCraft.PLANKS_PER_CHEST
                + " planks and placed it at " + spot.toShortString());
        colony.notify(level, Component.translatable("smartcolony.chat.storage_rebuilt"));
        colony.markDirty();
    }

    private static final double FULL = 0.95;
    private static final int MAX_EXTRA = 8;

    private void expand(ServerLevel level) {
        java.util.Map<Category, Double> fill = colony.fill(level);
        Category fullest = null;
        double worst = 0;
        for (java.util.Map.Entry<Category, Double> entry : fill.entrySet()) {
            if (entry.getValue() > worst) {
                worst = entry.getValue();
                fullest = entry.getKey();
            }
        }
        if (fullest == null || worst < FULL || colony.data().stat("storage_expanded") >= MAX_EXTRA) {
            return;
        }
        for (Category category : Category.values()) {
            List<Pos> list = colony.data().storage.get(category);
            if (list == null || list.isEmpty()) {
                fullest = category;
                break;
            }
        }
        BlockPos depot = colony.depot();
        if (!WorldUtil.loaded(level, depot)) {
            return;
        }
        BlockPos spot = spot(level, depot);
        if (spot == null) {
            return;
        }
        String item = null;
        for (String candidate : new String[] {Ids.mc("barrel"), Ids.mc("chest")}) {
            if (colony.warehouse().take(level, candidate, 1) == 1) {
                item = candidate;
                break;
            }
        }
        if (item == null && colony.craft(level, Ids.mc("chest"), 1) && colony.warehouse().take(level, Ids.mc("chest"), 1) == 1) {
            item = Ids.mc("chest");
        }
        if (item == null) {
            return;
        }
        Direction facing = Direction.getApproximateNearest(depot.getX() - spot.getX(), 0, depot.getZ() - spot.getZ());
        if (!facing.getAxis().isHorizontal()) {
            facing = Direction.NORTH;
        }
        BlockState state = item.equals(Ids.mc("barrel"))
                ? Blocks.BARREL.defaultBlockState().setValue(net.minecraft.world.level.block.BarrelBlock.FACING, Direction.UP)
                : Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing);
        level.setBlock(spot, state, net.minecraft.world.level.block.Block.UPDATE_ALL);
        colony.data().storage.computeIfAbsent(fullest, key -> new ArrayList<>()).add(WorldUtil.pos(spot));
        colony.warehouse().invalidate();
        colony.data().bump("storage_expanded", 1);
        colony.journal(level, "storage was full: added a " + Ids.path(item) + " for " + fullest.name().toLowerCase(java.util.Locale.ROOT)
                + " at " + spot.toShortString());
        colony.markDirty();
    }
}
