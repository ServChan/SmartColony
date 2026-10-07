package org.lts.smartcolony.work;

import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import org.lts.smartcolony.colony.Tavern;
import org.lts.smartcolony.core.Role;

public final class InnkeeperJob extends Job {
    private static final int COOK_PERIOD = 1200;

    private long nextCook;
    private long seenService;

    public InnkeeperJob(Colonist colonist) {
        super(colonist);
    }

    @Override
    public Role role() {
        return Role.INNKEEPER;
    }

    @Override
    protected Predicate<ItemStack> tools() {
        return stack -> false;
    }

    @Override
    public boolean wantsControl(ServerLevel level, Villager villager) {
        Tavern tavern = colony().tavern();
        return tavern.built() && (colony().workTime(level) || tavern.serviceHours(level));
    }

    @Override
    public void tick(ServerLevel level, Villager villager) {
        Tavern tavern = colony().tavern();
        if (!tavern.built()) {
            status = "no tavern";
            idle(level, 600);
            return;
        }
        BlockPos counter = tavern.counter();
        status = "behind the counter";
        if (Mover.distance(villager, counter) > 1.6) {
            move(level, villager, counter, 1.2);
            return;
        }
        villager.getNavigation().stop();
        BlockPos room = tavern.room();
        villager.getLookControl().setLookAt(room.getX() + 0.5, room.getY() + 1.4, room.getZ() + 0.5);
        long now = level.getGameTime();
        long served = tavern.lastServed();
        if (served != seenService) {
            seenService = served;
            villager.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            level.playSound(null, villager.blockPosition(), SoundEvents.VILLAGER_WORK_BUTCHER, SoundSource.NEUTRAL, 0.4F, 1.0F);
            gainXp(level, villager, 1);
        }
        if (now >= nextCook) {
            nextCook = now + COOK_PERIOD;
            if (!cookWorks()) {
                status = "cooking";
                colony().cook(level);
                level.sendParticles(ParticleTypes.SMOKE, villager.getX(), villager.getY() + 1.8, villager.getZ(), 3, 0.2, 0.1, 0.2, 0.02);
            }
        }
    }

    private boolean cookWorks() {
        for (Colonist other : colony().colonists().values()) {
            if (other.role() == Role.COOK) {
                return true;
            }
        }
        return false;
    }
}
