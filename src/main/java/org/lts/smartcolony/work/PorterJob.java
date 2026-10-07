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
        if (hasCargo(villager) || !phase.equals("start")) {
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
    }

    @Override
    public void tick(ServerLevel level, Villager villager) {
        if (wait > 0) {
            wait--;
            return;
        }
        switch (phase) {
            case "start" -> start(level, villager);
            case "toOutpost" -> toOutpost(level, villager);
            case "load" -> load(level, villager);
            case "toBuilder" -> toBuilder(level, villager);
            case "toDepot" -> toDepot(level, villager);
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
        if (!hasCargo(villager)) {
            phase = "start";
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
        phase = "start";
    }
}
