package org.lts.smartcolony.work;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import org.lts.smartcolony.core.Role;

public final class Jobs {
    private Jobs() {
    }

    public static Job create(Role role, Colonist colonist) {
        return switch (role) {
            case LUMBERJACK -> new LumberjackJob(colonist);
            case MINER -> new MinerJob(colonist);
            case FARMER -> new FarmerJob(colonist);
            case FISHERMAN -> new FishermanJob(colonist);
            case HERDER -> new HerderJob(colonist);
            case BUILDER -> new BuilderJob(colonist);
            case GUARD -> new GuardJob(colonist);
            case IDLE -> new IdleJob(colonist);
        };
    }

    public static boolean canInterrupt(Colonist colonist, Villager villager) {
        Job job = colonist.job();
        if (job instanceof MinerJob miner && miner.underground(villager)) {
            return false;
        }
        if (job instanceof HerderJob herder && herder.isLeading()) {
            return false;
        }
        return true;
    }

    public static final class IdleJob extends Job {
        public IdleJob(Colonist colonist) {
            super(colonist);
        }

        @Override
        public Role role() {
            return Role.IDLE;
        }

        @Override
        public boolean wantsControl(ServerLevel level, Villager villager) {
            return false;
        }

        @Override
        public void tick(ServerLevel level, Villager villager) {
        }
    }
}
