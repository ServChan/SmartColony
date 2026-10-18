package org.lts.smartcolony.work;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.lts.smartcolony.colony.Colony;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.util.ItemIds;
import org.lts.smartcolony.util.WorldUtil;

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
        return onShift(level, villager) || hasCargo(villager);
    }

    protected boolean onShift(ServerLevel level, Villager villager) {
        return colony().workTime(level) || nightShift(villager);
    }

    protected boolean nightShift(Villager villager) {
        return Colonist.bedless(villager) && role() != org.lts.smartcolony.core.Role.GUARD && role() != org.lts.smartcolony.core.Role.IDLE
                && !colonist.hallRestApplies();
    }

    public boolean ignoresDanger() {
        return false;
    }

    public void onRelease(ServerLevel level, Villager villager) {
        phase = "start";
        endDrop();
    }

    public String status() {
        return phase + (status.isEmpty() ? "" : ": " + status);
    }

    protected Colony colony() {
        return colonist.colony();
    }

    public boolean hasCargo(Villager villager) {
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

    private long idleUntil;

    protected void idle(ServerLevel level, int ticks) {
        idleUntil = level.getGameTime() + ticks;
        status = status.isEmpty() ? "nothing to do" : status;
    }

    public boolean idling(ServerLevel level) {
        return level.getGameTime() < idleUntil;
    }

    protected void gainXp(ServerLevel level, Villager villager, int amount) {
        colony().gainXp(level, villager, role(), amount);
    }

    protected int skill() {
        return colony().skill(colonist.uuid(), role());
    }

    public void handOverPreviousRole(ServerLevel level, Villager villager) {
        depositAll(level, villager);
    }

    protected boolean depositAll(ServerLevel level, Villager villager) {
        Unloaded unloaded = unload(level, villager, stack -> colony().warehouse().insert(level, stack));
        if (!unloaded.everything()) {
            colony().storageFull();
        }
        return unloaded.everything();
    }

    private record Unloaded(int items, boolean everything) {
    }

    private Unloaded unload(ServerLevel level, Villager villager, UnaryOperator<ItemStack> sink) {
        var inventory = villager.getInventory();
        boolean everything = true;
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || keep(stack) || ration(stack)) {
                continue;
            }
            int before = stack.getCount();
            ItemStack rest = sink.apply(stack);
            int delivered = before - rest.getCount();
            colony().delivered(colonist, ItemIds.id(stack), delivered);
            total += delivered;
            inventory.setItem(slot, rest);
            if (!rest.isEmpty()) {
                everything = false;
            }
        }
        return new Unloaded(total, everything);
    }

    public int cargoSlots(Villager villager) {
        int slots = 0;
        var inventory = villager.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && !keep(stack) && !ration(stack)) {
                slots++;
            }
        }
        return slots;
    }

    public int surrenderCargo(Villager from, Villager to) {
        var inventory = from.getInventory();
        int moved = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || keep(stack) || ration(stack)) {
                continue;
            }
            int before = stack.getCount();
            ItemStack rest = Inv.add(to, stack.copy());
            inventory.setItem(slot, rest);
            moved += before - rest.getCount();
        }
        return moved;
    }

    protected boolean usesOutposts() {
        return false;
    }

    protected String walkKind() {
        return null;
    }

    private ColonyData.Outpost dropTarget;
    private BlockPos dropOrigin;
    private boolean dropChosen;

    protected ColonyData.Outpost dropSite(ServerLevel level, Villager villager) {
        if (dropChosen && dropTarget != null && !colony().outposts().all().contains(dropTarget)) {
            dropChosen = false;
        }
        if (!dropChosen) {
            dropOrigin = villager.blockPosition();
            dropTarget = usesOutposts() ? colony().outposts().dropFor(level, dropOrigin) : null;
            dropChosen = true;
        }
        return dropTarget;
    }

    protected void endDrop() {
        dropChosen = false;
        dropTarget = null;
        dropOrigin = null;
    }

    protected boolean atDrop(ServerLevel level, Villager villager) {
        ColonyData.Outpost outpost = dropSite(level, villager);
        if (outpost == null) {
            return atDepot(level, villager);
        }
        return Mover.distance(villager, WorldUtil.bp(outpost.door)) <= 3.0;
    }

    protected Mover.Result goDrop(ServerLevel level, Villager villager) {
        ColonyData.Outpost outpost = dropSite(level, villager);
        if (outpost == null) {
            Mover.Result result = goDepot(level, villager);
            if (result == Mover.Result.FAILED && walkKind() != null) {
                colony().outposts().noteTrouble(walkKind());
            }
            return result;
        }
        Mover.Result result = move(level, villager, WorldUtil.bp(outpost.door), 2.5);
        if (result == Mover.Result.FAILED) {
            colony().outposts().unreachable(level, outpost);
            endDrop();
        } else if (result == Mover.Result.ARRIVED) {
            colony().outposts().reached(outpost);
        }
        return result;
    }

    protected boolean dropAll(ServerLevel level, Villager villager) {
        ColonyData.Outpost outpost = dropSite(level, villager);
        if (outpost == null) {
            endDrop();
            return depositAll(level, villager);
        }
        BlockPos origin = dropOrigin;
        Unloaded unloaded = unload(level, villager, stack -> colony().warehouse().insertOutpost(level, outpost, stack));
        colony().outposts().deposited(level, outpost, unloaded.items(), origin);
        endDrop();
        return unloaded.everything();
    }

    protected int restock(ServerLevel level, Villager villager, ColonyData.Outpost outpost, String id, int want) {
        int need = want - Inv.count(villager, id);
        if (need <= 0) {
            return 0;
        }
        List<ItemStack> taken = new java.util.ArrayList<>();
        colony().warehouse().takeFromOutpost(level, outpost, stack -> ItemIds.id(stack).equals(id), need, taken);
        int got = 0;
        for (ItemStack stack : taken) {
            int before = stack.getCount();
            ItemStack rest = Inv.add(villager, stack);
            got += before - rest.getCount();
            if (!rest.isEmpty()) {
                colony().warehouse().insertOutpost(level, outpost, rest);
            }
        }
        return got;
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
        if (taken < need && colony().produce(level, id, need - taken)) {
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
