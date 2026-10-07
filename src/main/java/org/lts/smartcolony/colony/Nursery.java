package org.lts.smartcolony.colony;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.villager.Villager;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.Kindergarten;

public final class Nursery {
    private static final int PERIOD = 40;
    private static final int MINE_MARGIN = 4;
    private static final float SPEED = 0.6F;

    private Nursery() {
    }

    public static ColonyData.Building find(Colony colony) {
        for (ColonyData.Building building : colony.data().buildings) {
            if (Kindergarten.ID.equals(building.kind)) {
                return building;
            }
        }
        return null;
    }

    public static int childCount(ServerLevel level, Colony colony) {
        int children = 0;
        for (UUID uuid : colony.colonists().keySet()) {
            if (level.getEntity(uuid) instanceof Villager villager && villager.isAlive() && villager.isBaby()) {
                children++;
            }
        }
        return children;
    }

    public static void tick(ServerLevel level, Colony colony) {
        if (colony.ticks() % PERIOD != 17) {
            return;
        }
        ColonyData.Building school = find(colony);
        boolean day = school != null && colony.workTime(level);
        BlockPos spot = school == null ? null : colony.construction().playground(school);
        double limit = colony.data().radius + 24;
        for (UUID uuid : colony.colonists().keySet()) {
            if (!(level.getEntity(uuid) instanceof Villager villager) || !villager.isAlive() || !villager.isBaby()
                    || villager.isSleeping() || villager.isPassenger() || villager.isTrading()) {
                continue;
            }
            BlockPos at = villager.blockPosition();
            if (at.distSqr(colony.center()) > limit * limit) {
                continue;
            }
            if (day && !Kindergarten.inside(at.getX(), at.getZ(), school.min.x(), school.min.z(), school.max.x(), school.max.z())) {
                steer(villager, spot);
            } else if (Kindergarten.unsafeNear(at.getX(), at.getZ(), MINE_MARGIN, (x, z) -> colony.inMine(new BlockPos(x, at.getY(), z)))) {
                steer(villager, colony.depot());
            }
        }
    }

    private static void steer(Villager villager, BlockPos target) {
        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(target, SPEED, 2));
    }
}
