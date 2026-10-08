package org.lts.smartcolony.work;

import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.lts.smartcolony.colony.Chores;
import org.lts.smartcolony.core.Role;

public final class ChoresJob extends Job {
    private Chores.Chore chore;
    private int misses;

    public ChoresJob(Colonist colonist) {
        super(colonist);
        depositThreshold = 6;
    }

    @Override
    public Role role() {
        return Role.IDLE;
    }

    @Override
    public boolean wantsControl(ServerLevel level, Villager villager) {
        if (villager.isBaby()) {
            return false;
        }
        return colony().workTime(level) && (colony().chores().pending() > 0 || chore != null) || hasCargo(villager);
    }

    @Override
    protected boolean nightShift(Villager villager) {
        return false;
    }

    @Override
    public void onRelease(ServerLevel level, Villager villager) {
        super.onRelease(level, villager);
        chore = null;
    }

    @Override
    public void tick(ServerLevel level, Villager villager) {
        if (wait > 0) {
            wait--;
            return;
        }
        if (cargoFull(villager) || chore == null && hasCargo(villager) && colony().chores().pending() == 0) {
            status = "carrying finds to storage";
            if (!atDepot(level, villager)) {
                if (goDepot(level, villager) == Mover.Result.FAILED) {
                    wait = 100;
                }
                return;
            }
            depositAll(level, villager);
            return;
        }
        if (chore == null) {
            chore = colony().chores().next(level, villager.blockPosition(), colonist.uuid());
            if (chore == null) {
                status = "nothing to tidy";
                idle(level, 400);
                return;
            }
        }
        if (!colony().chores().stillNeeded(level, chore)) {
            colony().chores().done(chore);
            chore = null;
            return;
        }
        status = switch (chore.kind()) {
            case SNOW -> "clearing snow";
            case WEEDS -> "clearing the path";
            case LOOT -> "picking up dropped items";
        };
        Mover.Result result = move(level, villager, chore.pos(), chore.kind() == Chores.Kind.LOOT ? 1.2 : 2.2);
        if (result == Mover.Result.FAILED) {
            colony().chores().done(chore);
            chore = null;
            if (++misses > 6) {
                misses = 0;
                idle(level, 200);
            }
            return;
        }
        if (result != Mover.Result.ARRIVED) {
            return;
        }
        misses = 0;
        perform(level, villager);
        colony().chores().done(chore);
        chore = null;
        wait = 8;
    }

    private void perform(ServerLevel level, Villager villager) {
        villager.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
        switch (chore.kind()) {
            case SNOW -> {
                level.destroyBlock(chore.pos(), false, villager);
                colony().data().bump("snow_cleared", 1);
            }
            case WEEDS -> {
                BlockState state = level.getBlockState(chore.pos());
                ItemStack drop = state.is(net.minecraft.tags.BlockTags.SAPLINGS) ? new ItemStack(state.getBlock().asItem()) : ItemStack.EMPTY;
                level.destroyBlock(chore.pos(), false, villager);
                if (!drop.isEmpty()) {
                    stash(level, villager, List.of(drop));
                }
                colony().data().bump("paths_weeded", 1);
            }
            case LOOT -> {
                for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(chore.pos()).inflate(2.0), ItemEntity::isAlive)) {
                    if (Inv.free(villager) == 0) {
                        break;
                    }
                    pickUp(villager, item);
                }
                colony().data().bump("items_tidied", 1);
            }
        }
    }
}
