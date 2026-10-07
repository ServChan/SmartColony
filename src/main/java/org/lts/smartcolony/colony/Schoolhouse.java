package org.lts.smartcolony.colony;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.AABB;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.core.Schooling;
import org.lts.smartcolony.work.Colonist;

public final class Schoolhouse {
    private Schoolhouse() {
    }

    public static ColonyData.Building find(Colony colony) {
        for (ColonyData.Building building : colony.data().buildings) {
            if (Schooling.ID.equals(building.kind)) {
                return building;
            }
        }
        return null;
    }

    public static BlockPos classSpot(Colony colony, ColonyData.Building school) {
        return colony.construction().world(school, Schooling.CLASS_X, 1, Schooling.CLASS_Z);
    }

    public static BlockPos teacherSpot(Colony colony, ColonyData.Building school) {
        return colony.construction().world(school, Schooling.TEACHER_X, 1, Schooling.TEACHER_Z);
    }

    public static boolean inClass(BlockPos at, BlockPos spot) {
        double dx = at.getX() - spot.getX();
        double dz = at.getZ() - spot.getZ();
        return dx * dx + dz * dz <= Schooling.CLASS_RADIUS * Schooling.CLASS_RADIUS && Math.abs(at.getY() - spot.getY()) <= 3;
    }

    public static boolean wantsTeacher(ServerLevel level, Colony colony) {
        return find(colony) != null && Nursery.childCount(level, colony) > 0;
    }

    public static int pupilsInClass(ServerLevel level, Colony colony, ColonyData.Building school) {
        BlockPos spot = classSpot(colony, school);
        return level.getEntitiesOfClass(Villager.class, new AABB(spot).inflate(Schooling.CLASS_RADIUS, 3, Schooling.CLASS_RADIUS),
                villager -> villager.isAlive() && villager.isBaby() && colony.isMember(villager)
                        && inClass(villager.blockPosition(), spot)).size();
    }

    public static int teacherSkill(ServerLevel level, Colony colony, ColonyData.Building school) {
        BlockPos spot = classSpot(colony, school);
        int best = -1;
        for (Map.Entry<UUID, Colonist> entry : colony.colonists().entrySet()) {
            if (entry.getValue().role() == Role.TEACHER && level.getEntity(entry.getKey()) instanceof Villager villager
                    && villager.isAlive() && Colonist.isControlled(villager) && inClass(villager.blockPosition(), spot)) {
                best = Math.max(best, colony.skill(entry.getKey(), Role.TEACHER));
            }
        }
        return best;
    }

    public record Context(Map<Role, Integer> current, Map<Role, Integer> enrolled) {
    }

    public static Context context(ServerLevel level, Colony colony) {
        EnumMap<Role, Integer> current = new EnumMap<>(Role.class);
        EnumMap<Role, Integer> enrolled = new EnumMap<>(Role.class);
        for (Map.Entry<UUID, Colonist> entry : colony.colonists().entrySet()) {
            if (!(level.getEntity(entry.getKey()) instanceof Villager villager)) {
                continue;
            }
            if (villager.isBaby()) {
                ColonyData.Member member = colony.data().members.get(entry.getKey().toString());
                if (member != null && !member.subject.isEmpty()) {
                    enrolled.merge(Role.byKey(member.subject), 1, Integer::sum);
                }
            } else {
                current.merge(entry.getValue().role(), 1, Integer::sum);
            }
        }
        return new Context(current, enrolled);
    }

    public static void study(Colony colony, UUID uuid, Context context, int teacherSkill) {
        ColonyData.Member member = colony.data().members.get(uuid.toString());
        if (member == null) {
            return;
        }
        Role subject = member.subject.isEmpty() ? null : Role.byKey(member.subject);
        if (!Schooling.keepSubject(subject, colony.desired())) {
            if (subject != null) {
                context.enrolled().merge(subject, -1, Integer::sum);
            }
            subject = Schooling.pickSubject(colony.desired(), context.current(), context.enrolled());
            context.enrolled().merge(subject, 1, Integer::sum);
            member.subject = subject.key();
        }
        member.study.merge(subject.key(), Schooling.points(teacherSkill >= 0, teacherSkill), Integer::sum);
        colony.data().bump("lessons_attended", 1);
        colony.markDirty();
    }

    public static void graduate(ServerLevel level, Colony colony, Villager villager, ColonyData.Member member) {
        if (villager.isBaby() || member.study.isEmpty()) {
            return;
        }
        Role role = Schooling.graduateRole(member.study);
        int points = role == null ? 0 : member.study.getOrDefault(role.key(), 0);
        member.study = new LinkedHashMap<>();
        member.subject = "";
        colony.markDirty();
        if (role == null) {
            return;
        }
        int xp = Schooling.graduateXp(points);
        int total = member.xp.merge(role.key(), xp, Integer::sum);
        member.graduate = role.key();
        colony.data().bump("graduates", 1);
        colony.data().bump("graduates_" + role.key(), 1);
        colony.journal(level, "school graduate: " + role.key() + ", starts at skill level " + Colony.levelFor(total));
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, villager.getX(), villager.getY() + 2.0, villager.getZ(), 12, 0.4, 0.4, 0.4, 0.0);
    }
}
