package org.lts.smartcolony.work;

import java.util.Comparator;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.AABB;
import org.lts.smartcolony.SmartColonyConfig;
import org.lts.smartcolony.colony.Colony;
import org.lts.smartcolony.colony.ColonyManager;
import org.lts.smartcolony.colony.Embassy;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.Role;

final class Mission {
    private static final long OUT_LIMIT = 12000L;
    private static final long RAID_LIMIT = 9000L;
    private static final long HOME_LIMIT = 36000L;
    private static final int MAX_FAILURES = 5;
    private static final int CHUNK_PATIENCE = 600;
    private static final double FOE_RANGE = 10.0;
    private static final double GOLEM_PANIC = 7.0;

    private static final int ROAD_STEP = 12;
    private static final double ROAD_REACH = 24.0;

    private final Colonist colonist;
    private boolean active;
    private java.util.List<BlockPos> road;
    private int roadIndex = -1;
    private int chunkWait;
    private boolean roadHome;
    private String roadTarget = "";
    private BlockPos camp;
    private String status = "";
    private BlockPos wander;
    private long wanderUntil;

    Mission(Colonist colonist) {
        this.colonist = colonist;
    }

    boolean active() {
        return active;
    }

    String status() {
        return status;
    }

    boolean tick(ServerLevel level, Villager villager, long now) {
        Colony colony = colonist.colony();
        ColonyData.Mission mission = colony.embassy().missionOf(colonist.uuid());
        if (mission == null || !villager.isAlive()) {
            if (active) {
                finish(level, villager);
            }
            return false;
        }
        if (!active) {
            begin(level, villager, mission);
        }
        colonist.seize(level, villager);
        Mover.keepAfloat(villager);
        colonist.mover().screen(Mover.SCREEN_OFF);
        Colony target = ColonyManager.byId(mission.target);
        if (target == null && !"home".equals(mission.phase)) {
            colony.embassy().abandoned(level, mission, "the colony is gone");
        }
        switch (mission.phase) {
            case "out" -> out(level, villager, mission, target, now);
            case "stay" -> stay(level, villager, mission, target, now);
            case "raid" -> raid(level, villager, mission, target, now);
            default -> home(level, villager, mission, target, now);
        }
        return true;
    }

    private void begin(ServerLevel level, Villager villager, ColonyData.Mission mission) {
        active = true;
        if (Colonist.isControlled(villager)) {
            colonist.release(level, villager, "mission");
        }
        if (villager.isSleeping()) {
            villager.stopSleeping();
        }
        Colony colony = colonist.colony();
        if (!villager.hasCustomName() || colony.ownsName(colonist.uuid())) {
            villager.setCustomName(Component.translatable("smartcolony.mission.name." + mission.kind, colony.data().name));
            villager.setCustomNameVisible(Embassy.RAID.equals(mission.kind));
            colony.setOwnsName(colonist.uuid(), true);
        }
    }

    private void finish(ServerLevel level, Villager villager) {
        active = false;
        wander = null;
        road = null;
        roadIndex = -1;
        camp = null;
        status = "";
        if (villager.isAlive()) {
            villager.setCustomNameVisible(false);
            if (colonist.colony().ownsName(colonist.uuid())) {
                villager.setCustomName(null);
                colonist.colony().setOwnsName(colonist.uuid(), false);
            }
            colonist.applyName(villager);
            if (Colonist.isControlled(villager)) {
                colonist.release(level, villager, "back from a mission");
            }
        }
    }

    private double speed(ColonyData.Mission mission) {
        return Embassy.RAID.equals(mission.kind) || Embassy.AID.equals(mission.kind) ? 0.7 : 0.6;
    }

    private void out(ServerLevel level, Villager villager, ColonyData.Mission mission, Colony target, long now) {
        Colony colony = colonist.colony();
        if (now - mission.started > OUT_LIMIT) {
            colony.embassy().abandoned(level, mission, "the road took too long");
            return;
        }
        if (Embassy.RAID.equals(mission.kind) && !colony.embassy().relationAtWar(target)) {
            colony.embassy().abandoned(level, mission, "peace was made");
            return;
        }
        BlockPos goal = colony.embassy().destination(mission, target);
        status = "walking to " + target.data().name;
        if (Embassy.RAID.equals(mission.kind) && inside(villager, target)) {
            mission.phase = "raid";
            colony.embassy().raidEntered(level, target, now);
            return;
        }
        if (overnight(level, villager, mission, now) || alongRoad(level, villager, mission, target, false)) {
            return;
        }
        Mover.Result result = colonist.mover().tick(level, villager, goal, 4.0, speed(mission), true);
        if (result == Mover.Result.MOVING && "waiting for chunk".equals(colonist.mover().note())) {
            if (++chunkWait > CHUNK_PATIENCE) {
                chunkWait = 0;
                colony.embassy().abandoned(level, mission, "the road runs through land nobody walks");
                return;
            }
        } else {
            chunkWait = 0;
        }
        if (result == Mover.Result.ARRIVED) {
            if (Embassy.RAID.equals(mission.kind)) {
                mission.phase = "raid";
                colony.embassy().raidEntered(level, target, now);
            } else {
                colony.embassy().arrived(level, mission, villager, target);
            }
        } else if (result == Mover.Result.FAILED && ++mission.failures >= MAX_FAILURES) {
            colony.embassy().abandoned(level, mission, "no way through");
        }
    }

    private boolean alongRoad(ServerLevel level, Villager villager, ColonyData.Mission mission, Colony target, boolean homeward) {
        if (target == null || Embassy.RAID.equals(mission.kind) && !homeward) {
            return false;
        }
        if (road == null || roadHome != homeward || !roadTarget.equals(target.id())) {
            road = colonist.colony().highways().road(target, homeward);
            roadHome = homeward;
            roadTarget = target.id();
            roadIndex = -1;
            if (road == null) {
                return false;
            }
            double best = ROAD_REACH * ROAD_REACH;
            for (int i = 0; i < road.size(); i++) {
                double distance = road.get(i).distToCenterSqr(villager.getX(), road.get(i).getY() + 0.5, villager.getZ());
                if (distance < best) {
                    best = distance;
                    roadIndex = i;
                }
            }
            if (roadIndex < 0) {
                road = null;
                return false;
            }
        }
        if (road == null || roadIndex < 0 || roadIndex >= road.size() - 1) {
            return false;
        }
        int next = Math.min(road.size() - 1, roadIndex + ROAD_STEP);
        BlockPos waypoint = road.get(next).above();
        status = (homeward ? "walking home along the road" : "walking along the road to " + target.data().name);
        if (Mover.distance(villager, waypoint) <= 3.0) {
            roadIndex = next;
            return true;
        }
        Mover.Result result = colonist.mover().tick(level, villager, waypoint, 2.5, speed(mission), true);
        if (result == Mover.Result.ARRIVED) {
            roadIndex = next;
        } else if (result == Mover.Result.FAILED) {
            roadIndex = road.size() - 1;
        }
        return true;
    }

    private boolean overnight(ServerLevel level, Villager villager, ColonyData.Mission mission, long now) {
        Colony colony = colonist.colony();
        if (colony.workTime(level) || Embassy.RAID.equals(mission.kind) || Embassy.AID.equals(mission.kind)) {
            camp = null;
            return false;
        }
        if (camp == null) {
            camp = org.lts.smartcolony.colony.Highways.campNear(level, villager.blockPosition(), 40);
            if (camp == null) {
                return false;
            }
        }
        mission.started = Math.max(mission.started, now - OUT_LIMIT / 2);
        status = "resting at a roadside camp";
        if (Mover.distance(villager, camp) > 2.5) {
            if (colonist.mover().tick(level, villager, camp, 2.0, 0.55, true) == Mover.Result.FAILED) {
                camp = null;
                return false;
            }
            return true;
        }
        villager.getNavigation().stop();
        if (Math.floorMod(now + colonist.uuid().hashCode(), 200) == 0) {
            villager.playAmbientSound();
        }
        return true;
    }

    private static boolean inside(Villager villager, Colony target) {
        double reach = target.data().radius + 8;
        return villager.blockPosition().distSqr(target.center()) <= reach * reach;
    }

    private void stay(ServerLevel level, Villager villager, ColonyData.Mission mission, Colony target, long now) {
        Colony colony = colonist.colony();
        boolean aid = Embassy.AID.equals(mission.kind);
        if (aid) {
            if (fightFor(level, villager, target)) {
                return;
            }
            if (!colony.embassy().stillPressed(level, target) && now > mission.until - 3000) {
                mission.until = Math.min(mission.until, now);
            }
        }
        if (Embassy.VISIT.equals(mission.kind) && !target.festival(level) && now > mission.until - 1800) {
            mission.until = Math.min(mission.until, now);
        }
        if (now >= mission.until) {
            mission.phase = "home";
            return;
        }
        status = aid ? "standing guard in " + target.data().name : "visiting " + target.data().name;
        BlockPos center = colony.embassy().destination(mission, target);
        if (wander == null || now >= wanderUntil) {
            int dx = level.getRandom().nextInt(11) - 5;
            int dz = level.getRandom().nextInt(11) - 5;
            wander = org.lts.smartcolony.util.WorldUtil.surface(level, center.getX() + dx, center.getZ() + dz);
            wanderUntil = now + 120 + level.getRandom().nextInt(120);
        }
        if (colonist.mover().tick(level, villager, wander, 1.5, 0.45, false) != Mover.Result.MOVING) {
            villager.getNavigation().stop();
            Villager host = nearestHost(level, villager, target);
            if (host != null) {
                villager.getLookControl().setLookAt(host);
            }
            if (Math.floorMod(now + colonist.uuid().hashCode(), 140) == 0) {
                villager.playAmbientSound();
                if (Embassy.VISIT.equals(mission.kind)) {
                    level.sendParticles(ParticleTypes.NOTE, villager.getX(), villager.getY() + 2.2, villager.getZ(), 2, 0.3, 0.2, 0.3, 0.5);
                }
            }
        }
    }

    private static Villager nearestHost(ServerLevel level, Villager villager, Colony target) {
        return level.getEntitiesOfClass(Villager.class, villager.getBoundingBox().inflate(8, 3, 8),
                        v -> v != villager && v.isAlive() && target.isMember(v))
                .stream().min(Comparator.comparingDouble(v -> v.distanceToSqr(villager))).orElse(null);
    }

    private boolean fightFor(ServerLevel level, Villager villager, Colony ally) {
        if (!(colonist.job() instanceof GuardJob guard)) {
            return false;
        }
        LivingEntity enemy = ally.defense().nearestEnemy(villager, 24, Set.of());
        if (enemy == null) {
            return false;
        }
        status = "fighting for " + ally.data().name;
        guard.skirmish(level, villager, enemy);
        return true;
    }

    private void raid(ServerLevel level, Villager villager, ColonyData.Mission mission, Colony target, long now) {
        Colony colony = colonist.colony();
        if (!colony.embassy().relationAtWar(target)) {
            colony.embassy().abandoned(level, mission, "peace was made");
            return;
        }
        float health = villager.getHealth() / villager.getMaxHealth();
        if (health < SmartColonyConfig.get().guardRetreatHealth || now - mission.started > OUT_LIMIT + RAID_LIMIT
                || golemNear(level, villager)) {
            colony.embassy().abandoned(level, mission, health < SmartColonyConfig.get().guardRetreatHealth ? "wounded" : "driven off");
            colony.data().bump("raiders_fled", 1);
            return;
        }
        LivingEntity foe = foe(level, villager, target);
        if (foe != null && colonist.job() instanceof GuardJob guard) {
            status = "fighting the guards of " + target.data().name;
            guard.skirmish(level, villager, foe);
            return;
        }
        BlockPos depot = target.depot();
        status = "raiding the storehouse of " + target.data().name;
        if (Mover.distance(villager, depot) <= 3.5) {
            villager.getNavigation().stop();
            colony.embassy().plunder(level, mission, villager, target);
            return;
        }
        Mover.Result result = colonist.mover().tick(level, villager, depot, 3.0, 0.7, false);
        if (result == Mover.Result.FAILED && ++mission.failures >= MAX_FAILURES) {
            colony.embassy().abandoned(level, mission, "could not reach the storehouse");
        }
    }

    private static boolean golemNear(ServerLevel level, Villager villager) {
        return !level.getEntitiesOfClass(IronGolem.class, villager.getBoundingBox().inflate(GOLEM_PANIC),
                golem -> golem.isAlive() && golem.getTarget() == villager).isEmpty();
    }

    private LivingEntity foe(ServerLevel level, Villager villager, Colony target) {
        LivingEntity attacker = villager.getLastHurtByMob();
        if (attacker instanceof Villager hitter && hitter.isAlive() && target.isMember(hitter)
                && villager.tickCount - villager.getLastHurtByMobTimestamp() < 120 && hitter.distanceTo(villager) < FOE_RANGE * 2) {
            return hitter;
        }
        Villager best = null;
        double bestDistance = FOE_RANGE;
        AABB box = villager.getBoundingBox().inflate(FOE_RANGE, 4, FOE_RANGE);
        for (Villager candidate : level.getEntitiesOfClass(Villager.class, box, Villager::isAlive)) {
            Colonist defender = target.colonists().get(candidate.getUUID());
            if (defender == null || defender.role() != Role.GUARD) {
                continue;
            }
            double distance = candidate.distanceTo(villager);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    private void home(ServerLevel level, Villager villager, ColonyData.Mission mission, Colony target, long now) {
        Colony colony = colonist.colony();
        if (Embassy.RAID.equals(mission.kind) && target != null && villager.getHealth() / villager.getMaxHealth() >= 0.5F
                && colony.embassy().relationAtWar(target)) {
            LivingEntity foe = foe(level, villager, target);
            if (foe != null && foe.distanceTo(villager) < 4.0 && colonist.job() instanceof GuardJob guard) {
                status = "fighting off pursuers";
                guard.skirmish(level, villager, foe);
                return;
            }
        }
        status = mission.goods.isEmpty() ? "returning home" : "carrying goods home";
        if (target != null && (overnight(level, villager, mission, now) || alongRoad(level, villager, mission, target, true))) {
            return;
        }
        Mover.Result result = colonist.mover().tick(level, villager, colony.depot(), 3.0, speed(mission), true);
        boolean late = now - mission.started > HOME_LIMIT;
        if (result == Mover.Result.FAILED) {
            mission.failures++;
        }
        if (late && !colony.contains(villager.blockPosition())) {
            Mover.rescue(level, villager, colony.depot());
            colony.data().bump("missions_rescued", 1);
            result = Mover.Result.ARRIVED;
        }
        if (result == Mover.Result.ARRIVED || colony.contains(villager.blockPosition()) && Mover.distance(villager, colony.depot()) < 6.0
                || result == Mover.Result.FAILED && mission.failures > MAX_FAILURES * 3 && colony.contains(villager.blockPosition())) {
            colony.embassy().returned(level, mission, villager);
            finish(level, villager);
        }
    }
}
