package org.lts.smartcolony.colony;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.villager.Villager;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.Kindergarten;
import org.lts.smartcolony.core.Schooling;

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
        ColonyData.Building kindergarten = find(colony);
        ColonyData.Building school = Schoolhouse.find(colony);
        boolean workTime = colony.workTime(level);
        boolean day = workTime && (kindergarten != null || school != null);
        BlockPos playground = kindergarten == null ? null : colony.construction().playground(kindergarten);
        BlockPos classroom = school == null ? null : Schoolhouse.classSpot(colony, school);
        int teacher = school != null && workTime ? Schoolhouse.teacherSkill(level, colony, school) : -1;
        Schoolhouse.Context context = null;
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
            boolean pupil = school != null && (kindergarten == null || Schooling.schoolAge(villager.getAge()));
            if (day && pupil) {
                if (Schoolhouse.inClass(at, classroom)) {
                    if (context == null) {
                        context = Schoolhouse.context(level, colony);
                    }
                    Schoolhouse.study(colony, uuid, context, teacher);
                } else {
                    steer(villager, classroom);
                }
            } else if (day && kindergarten != null) {
                if (!Kindergarten.inside(at.getX(), at.getZ(), kindergarten.min.x(), kindergarten.min.z(), kindergarten.max.x(),
                        kindergarten.max.z())) {
                    steer(villager, playground);
                }
            } else if (Kindergarten.unsafeNear(at.getX(), at.getZ(), MINE_MARGIN, (x, z) -> colony.inMine(new BlockPos(x, at.getY(), z)))) {
                steer(villager, colony.depot());
            }
        }
    }

    private static void steer(Villager villager, BlockPos target) {
        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(target, SPEED, 2));
    }
}
