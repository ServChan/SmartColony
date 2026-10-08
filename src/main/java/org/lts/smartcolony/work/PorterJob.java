package org.lts.smartcolony.work;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import org.lts.smartcolony.colony.Outposts;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.util.WorldUtil;

public final class PorterJob extends Job {
    private static final double ROAD_SPEED = 0.85;
    private static final double TRAIL_SPEED = 0.65;
    private static final double MEET_REACH = 3.0;
    private static final int CHASE_TICKS = 900;
    private static final int LOOK_PERIOD = 60;
    private static final double RETARGET = 4.0;

    private Outposts.Task task;
    private long nextLook;
    private boolean looked;
    private int chase;
    private BlockPos meet;
    private net.minecraft.world.entity.animal.equine.AbstractHorse pack;

    public PorterJob(Colonist colonist) {
        super(colonist);
    }

    @Override
    public Role role() {
        return Role.PORTER;
    }

    @Override
    protected java.util.function.Predicate<ItemStack> tools() {
        return stack -> false;
    }

    @Override
    protected boolean keep(ItemStack stack) {
        return false;
    }

    @Override
    protected Mover.Result move(ServerLevel level, Villager villager, BlockPos pos, double reach) {
        double speed = colony().sites().onRoad(villager.blockPosition().below()) ? ROAD_SPEED : TRAIL_SPEED;
        return colonist.mover().tick(level, villager, pos, reach, speed);
    }

    @Override
    public boolean wantsControl(ServerLevel level, Villager villager) {
        if (hasCargo(villager) || !phase.equals("start") || pack != null) {
            return true;
        }
        if (!colony().workTime(level)) {
            return false;
        }
        long now = level.getGameTime();
        if (now >= nextLook) {
            nextLook = now + LOOK_PERIOD;
            looked = colony().outposts().claim(level, colonist.uuid(), villager.blockPosition()) != null;
        }
        return looked;
    }

    @Override
    public void onRelease(ServerLevel level, Villager villager) {
        super.onRelease(level, villager);
        colony().outposts().release(colonist.uuid());
        task = null;
        meet = null;
        dropPack();
    }

    private void dropPack() {
        if (pack != null && pack.isAlive() && pack.getLeashHolder() instanceof Villager) {
            pack.removeLeash();
        }
        pack = null;
        colony().stable().giveUp(colonist.uuid());
    }

    private boolean packEmpty() {
        return pack == null || org.lts.smartcolony.colony.Stable.pack(pack).isEmpty();
    }

    @Override
    public void tick(ServerLevel level, Villager villager) {
        if (wait > 0) {
            wait--;
            return;
        }
        if (pack != null && pack.isAlive() && pack.getLeashHolder() == villager && !phase.equals("fetchPack")
                && org.lts.smartcolony.colony.Stable.keepClose(pack, villager)) {
            status = "waiting for the pack animal";
            return;
        }
        switch (phase) {
            case "start" -> start(level, villager);
            case "toOutpost" -> toOutpost(level, villager);
            case "load" -> load(level, villager);
            case "toBuilder" -> toBuilder(level, villager);
            case "toDepot" -> toDepot(level, villager);
            case "fetchPack" -> fetchPack(level, villager);
            case "returnPack" -> returnPack(level, villager);
            default -> phase = "start";
        }
    }

    private void abandon() {
        colony().outposts().release(colonist.uuid());
        task = null;
        meet = null;
        phase = "start";
        wait = 40;
    }

    private void start(ServerLevel level, Villager villager) {
        if (hasCargo(villager)) {
            phase = "toDepot";
            return;
        }
        task = colony().outposts().claim(level, colonist.uuid(), villager.blockPosition());
        if (task == null) {
            status = "nothing to carry";
            looked = false;
            nextLook = level.getGameTime() + LOOK_PERIOD;
            idle(level, 100);
            return;
        }
        if (task.type() == Outposts.Type.HAUL) {
            status = "walking to the outpost at " + task.outpost().door;
            phase = "toOutpost";
            if (pack == null && colony().stable().animals() > 0) {
                pack = colony().stable().borrow(level, colonist.uuid(), villager.blockPosition());
                if (pack != null) {
                    phase = "fetchPack";
                }
            }
        } else {
            status = "going to a builder with a full load";
            chase = 0;
            meet = null;
            phase = "toBuilder";
        }
    }

    private void toOutpost(ServerLevel level, Villager villager) {
        ColonyData.Outpost outpost = task == null ? null : task.outpost();
        if (outpost == null || !colony().outposts().all().contains(outpost)) {
            abandon();
            return;
        }
        Mover.Result result = move(level, villager, WorldUtil.bp(outpost.door), 2.5);
        if (result == Mover.Result.FAILED) {
            colony().outposts().unreachable(level, outpost);
            abandon();
        } else if (result == Mover.Result.ARRIVED) {
            colony().outposts().reached(outpost);
            phase = "load";
        }
    }

    private void load(ServerLevel level, Villager villager) {
        ColonyData.Outpost outpost = task == null ? null : task.outpost();
        if (outpost == null) {
            abandon();
            return;
        }
        int moved = 0;
        while (Inv.free(villager) > 0) {
            ItemStack stack = colony().warehouse().takeLargest(level, outpost, 64);
            if (stack.isEmpty()) {
                break;
            }
            int before = stack.getCount();
            ItemStack rest = Inv.add(villager, stack);
            moved += before - rest.getCount();
            if (!rest.isEmpty()) {
                colony().warehouse().insertOutpost(level, outpost, rest);
                break;
            }
        }
        colony().outposts().release(colonist.uuid());
        task = null;
        if (pack != null && pack.isAlive() && pack.getLeashHolder() == villager) {
            net.minecraft.world.SimpleContainer bags = org.lts.smartcolony.colony.Stable.pack(pack);
            int packed = 0;
            while (true) {
                ItemStack stack = colony().warehouse().takeLargest(level, outpost, 64);
                if (stack.isEmpty()) {
                    break;
                }
                int before = stack.getCount();
                ItemStack rest = bags.addItem(stack);
                packed += before - rest.getCount();
                if (!rest.isEmpty()) {
                    colony().warehouse().insertOutpost(level, outpost, rest);
                    break;
                }
            }
            if (packed > 0) {
                moved += packed;
                colony().data().bump("pack_items", packed);
            }
        }
        if (moved > 0) {
            colony().outposts().hauled(level, outpost, moved);
            gainXp(level, villager, 1);
            status = "carrying " + moved + " items from the outpost";
            phase = "toDepot";
        } else {
            phase = "start";
        }
    }

    private void toBuilder(ServerLevel level, Villager villager) {
        if (task == null || task.builder() == null || !(level.getEntity(task.builder()) instanceof Villager builder) || !builder.isAlive()
                || chase++ > CHASE_TICKS) {
            abandon();
            return;
        }
        if (Mover.distance(villager, builder.blockPosition()) <= MEET_REACH) {
            Colonist other = colony().colonists().get(task.builder());
            int moved = other == null || other.job() == null ? 0 : other.job().surrenderCargo(builder, villager);
            colony().outposts().release(colonist.uuid());
            task = null;
            meet = null;
            if (moved > 0) {
                colony().outposts().pickedUp(moved);
                gainXp(level, villager, 1);
                phase = "toDepot";
            } else {
                phase = "start";
            }
            return;
        }
        if (meet == null || meet.distSqr(builder.blockPosition()) > RETARGET * RETARGET) {
            meet = builder.blockPosition();
        }
        if (move(level, villager, meet, 2.0) == Mover.Result.FAILED) {
            abandon();
        }
    }

    private void toDepot(ServerLevel level, Villager villager) {
        if (!hasCargo(villager) && packEmpty()) {
            phase = pack != null ? "returnPack" : "start";
            return;
        }
        if (!atDepot(level, villager)) {
            if (goDepot(level, villager) == Mover.Result.FAILED) {
                status = "cannot reach storage";
                wait = 100;
            }
            return;
        }
        depositAll(level, villager);
        if (pack != null) {
            net.minecraft.world.SimpleContainer bags = org.lts.smartcolony.colony.Stable.pack(pack);
            for (int slot = 0; slot < bags.getContainerSize(); slot++) {
                ItemStack stack = bags.getItem(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                ItemStack rest = colony().warehouse().insert(level, stack);
                bags.setItem(slot, rest);
            }
        }
        phase = pack != null && packEmpty() ? "returnPack" : "start";
    }

    private void fetchPack(ServerLevel level, Villager villager) {
        if (pack == null || !pack.isAlive() || pack.getLeashHolder() instanceof Villager holder && holder != villager) {
            dropPack();
            phase = task != null ? "toOutpost" : "start";
            return;
        }
        status = "fetching a pack animal";
        if (villager.distanceTo(pack) > 2.5) {
            if (move(level, villager, pack.blockPosition(), 2.0) == Mover.Result.FAILED) {
                dropPack();
                phase = task != null ? "toOutpost" : "start";
            }
            return;
        }
        pack.setLeashedTo(villager, true);
        colony().data().bump("pack_trips", 1);
        phase = !packEmpty() ? "toDepot" : task != null ? "toOutpost" : "start";
    }

    private void returnPack(ServerLevel level, Villager villager) {
        if (pack == null || !pack.isAlive() || pack.getLeashHolder() != villager) {
            dropPack();
            phase = "start";
            return;
        }
        BlockPos post = colony().stable().freePost(level);
        if (post == null) {
            dropPack();
            phase = "start";
            return;
        }
        status = "returning the pack animal";
        Mover.Result result = move(level, villager, post, 2.2);
        if (result == Mover.Result.FAILED) {
            dropPack();
            phase = "start";
            return;
        }
        if ((result == Mover.Result.ARRIVED || Mover.distance(villager, post) <= 4.0)
                && pack.distanceToSqr(post.getX() + 0.5, post.getY(), post.getZ() + 0.5) < 81) {
            colony().stable().tie(level, pack, post);
            pack = null;
            phase = "start";
        }
    }
}
