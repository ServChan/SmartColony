package org.lts.smartcolony.work;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import org.lts.smartcolony.colony.Schoolhouse;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.Role;

public final class TeacherJob extends Job {
    private static final int LESSON_TICKS = 100;

    private int lessonClock;

    public TeacherJob(Colonist colonist) {
        super(colonist);
    }

    @Override
    public Role role() {
        return Role.TEACHER;
    }

    @Override
    public boolean wantsControl(ServerLevel level, Villager villager) {
        return colony().workTime(level) && Schoolhouse.find(colony()) != null;
    }

    @Override
    public void tick(ServerLevel level, Villager villager) {
        ColonyData.Building school = Schoolhouse.find(colony());
        if (school == null) {
            status = "no school";
            idle(level, 400);
            return;
        }
        BlockPos spot = Schoolhouse.teacherSpot(colony(), school);
        Mover.Result result = move(level, villager, spot, 1.5);
        if (result == Mover.Result.MOVING) {
            phase = "walk";
            status = "going to class";
            return;
        }
        if (result == Mover.Result.FAILED) {
            status = "cannot reach the classroom";
            idle(level, 300);
            return;
        }
        phase = "teach";
        BlockPos board = Schoolhouse.classSpot(colony(), school);
        villager.getLookControl().setLookAt(board.getX() + 0.5, villager.getEyeY(), board.getZ() + 0.5);
        if (++lessonClock < LESSON_TICKS) {
            return;
        }
        lessonClock = 0;
        int pupils = Schoolhouse.pupilsInClass(level, colony(), school);
        if (pupils == 0) {
            status = "waiting for pupils";
            return;
        }
        status = "teaching " + pupils;
        colony().data().bump("lessons_given", 1);
        gainXp(level, villager, 1);
        level.sendParticles(ParticleTypes.ENCHANT, villager.getX(), villager.getY() + 1.8, villager.getZ(), 6, 0.5, 0.3, 0.5, 0.2);
    }
}
