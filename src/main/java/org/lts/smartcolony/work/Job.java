package org.lts.smartcolony.work;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.lts.smartcolony.colony.Colony;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.util.ItemIds;

public abstract class Job {
    public enum Equip {
        READY,
        BUSY,
        MISSING
    }

    protected final Colonist colonist;
    protected String phase = "start";
    protected int wait;
    protected String status = "";
    protected int depositThreshold = 6;

    protected Job(Colonist colonist) {
        this.colonist = colonist;
    }

    public abstract Role role();

    public abstract void tick(ServerLevel level, Villager villager);

    public boolean wantsControl(ServerLevel level, Villager villager) {
        return colony().workTime(level) || hasCargo(villager);
    }

    public void onRelease(ServerLevel level, Villager villager) {
        phase = "start";
    }

    public String status() {
        return phase + (status.isEmpty() ? "" : ": " + status);
    }

    protected Colony colony() {
        return colonist.colony();
    }

    protected boolean hasCargo(Villager villager) {
        return Inv.count(villager, stack -> !keep(stack) && !ration(stack)) > 0;
    }

    protected Predicate<ItemStack> tools() {
        return Tools.ANY_TOOL;
    }

    protected boolean keep(ItemStack stack) {
        return tools().test(stack);
    }

    protected boolean ration(ItemStack stack) {
        return stack.is(net.minecraft.world.item.Items.BREAD);
    }

    protected boolean cargoFull(Villager villager) {
        return Inv.used(villager) >= depositThreshold || Inv.free(villager) == 0;
    }

    protected Mover.Result move(ServerLevel level, Villager villager, BlockPos pos, double reach) {
        return colonist.mover().tick(level, villager, pos, reach, speed());
    }

    protected double speed() {
        return 0.6;
    }

    protected boolean atDepot(ServerLevel level, Villager villager) {
        return Mover.distance(villager, colony().depot()) <= 3.0;
    }

    protected Mover.Result goDepot(ServerLevel level, Villager villager) {
        return move(level, villager, colony().depot(), 2.5);
    }

    protected boolean depositAll(ServerLevel level, Villager villager) {
        var inventory = villager.getInventory();
        boolean everything = true;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || keep(stack) || ration(stack)) {
                continue;
            }
            int before = stack.getCount();
            ItemStack rest = colony().warehouse().insert(level, stack);
            int delivered = before - rest.getCount();
            colony().delivered(colonist, ItemIds.id(stack), delivered);
            inventory.setItem(slot, rest);
            if (!rest.isEmpty()) {
                everything = false;
            }
        }
        if (!everything) {
            colony().storageFull();
        }
        return everything;
    }

    protected Equip equip(ServerLevel level, Villager villager, Predicate<ItemStack> fits, List<String> craftOrder) {
        ItemStack hand = villager.getMainHandItem();
        if (!hand.isEmpty() && fits.test(hand)) {
            return Equip.READY;
        }
        ItemStack carried = Inv.takeFirst(villager, fits);
        if (!carried.isEmpty()) {
            holdInHand(villager, carried);
            return Equip.READY;
        }
        if (!atDepot(level, villager)) {
            Mover.Result result = goDepot(level, villager);
            if (result == Mover.Result.FAILED) {
                status = "cannot reach storage";
                return Equip.MISSING;
            }
            status = "going for a tool";
            return Equip.BUSY;
        }
        ItemStack best = takeBest(level, fits);
        if (best.isEmpty()) {
            for (String id : craftOrder) {
                if (colony().craft(level, id, 1)) {
                    best = takeBest(level, fits);
                    if (!best.isEmpty()) {
                        break;
                    }
                }
            }
        }
        if (best.isEmpty()) {
            if (!craftOrder.isEmpty()) {
                colony().request(craftOrder.stream().filter(id -> id.contains("wooden_")).findFirst().orElse(craftOrder.getLast()));
            }
            status = "no tool in storage";
            return Equip.MISSING;
        }
        holdInHand(villager, best);
        return Equip.READY;
    }

    protected ItemStack takeBest(ServerLevel level, Predicate<ItemStack> fits) {
        var stock = colony().warehouse().all(level);
        ItemStack bestStack = ItemStack.EMPTY;
        for (var container : stock) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.isEmpty() && fits.test(stack) && (bestStack.isEmpty() || Tools.tier(stack) > Tools.tier(bestStack))) {
                    bestStack = stack;
                }
            }
        }
        if (bestStack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack chosen = bestStack;
        return colony().warehouse().extractOne(level, stack -> stack == chosen);
    }

    protected void holdInHand(Villager villager, ItemStack stack) {
        ItemStack previous = villager.getMainHandItem();
        villager.setItemSlot(EquipmentSlot.MAINHAND, stack);
        villager.setGuaranteedDrop(EquipmentSlot.MAINHAND);
        if (!previous.isEmpty()) {
            ItemStack rest = Inv.add(villager, previous);
            if (!rest.isEmpty()) {
                villager.spawnAtLocation((ServerLevel) villager.level(), rest);
            }
        }
    }

    protected int supply(ServerLevel level, Villager villager, String id, int min, int want) {
        int have = Inv.count(villager, id);
        if (have >= min) {
            return have;
        }
        int need = want - have;
        int taken = colony().warehouse().take(level, id, need);
        if (taken < need && colony().craft(level, id, need - taken)) {
            taken += colony().warehouse().take(level, id, need - taken);
        }
        if (taken > 0) {
            ItemStack rest = Inv.add(villager, ItemIds.stack(id, taken));
            if (!rest.isEmpty()) {
                colony().warehouse().insert(level, rest);
            }
        }
        return have + taken;
    }

    protected int supply(ServerLevel level, Villager villager, Predicate<ItemStack> filter, int min, int want) {
        int have = Inv.count(villager, filter);
        if (have >= min) {
            return have;
        }
        int got = 0;
        for (ItemStack stack : colony().warehouse().extract(level, filter, want - have)) {
            got += stack.getCount();
            ItemStack rest = Inv.add(villager, stack);
            if (!rest.isEmpty()) {
                colony().warehouse().insert(level, rest);
            }
        }
        return have + got;
    }

    protected ItemEntity nearestLoot(ServerLevel level, Villager villager, double radius, Predicate<ItemStack> filter) {
        AABB box = villager.getBoundingBox().inflate(radius, 3, radius);
        return level.getEntitiesOfClass(ItemEntity.class, box, item -> item.isAlive() && filter.test(item.getItem()))
                .stream()
                .min(Comparator.comparingDouble(item -> item.distanceToSqr(villager)))
                .orElse(null);
    }

    protected boolean pickUp(Villager villager, ItemEntity item) {
        ItemStack stack = item.getItem().copy();
        ItemStack rest = Inv.add(villager, stack);
        villager.take(item, stack.getCount() - rest.getCount());
        if (rest.isEmpty()) {
            item.discard();
            return true;
        }
        item.setItem(rest);
        return false;
    }

    protected void stash(ServerLevel level, Villager villager, List<ItemStack> drops) {
        for (ItemStack drop : drops) {
            ItemStack rest = Inv.add(villager, drop);
            if (!rest.isEmpty()) {
                ItemEntity entity = new ItemEntity(level, villager.getX(), villager.getY() + 0.5, villager.getZ(), rest);
                entity.setPickUpDelay(10);
                level.addFreshEntity(entity);
            }
        }
    }
}
