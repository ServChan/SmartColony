package org.lts.smartcolony.work;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import org.lts.smartcolony.colony.Colony;
import org.lts.smartcolony.colony.SafeRooms;
import org.lts.smartcolony.core.Flight;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.util.WorldUtil;

final class Refuge {
    private static final long NEVER = Long.MIN_VALUE / 4;
    private static final int CHECK = 10;
    private static final double SCAN_RADIUS = 20.0;
    private static final int CALM = 80;
    private static final double MAX_DISTANCE = 72.0;
    private static final int LOOKAHEAD = 14;
    private static final int MAX_BAD = 4;
    private static final int EVADE_STEP = 9;
    private static final int EVADE_TICKS = 40;
    private static final int SAFE_CHECK = 40;
    private static final int ATTACK_MEMORY = 100;
    private static final double CLOSE = 3.5;
    private static final double CALM_GAP = 8.0;
    private static final int STUCK = 40;
    private static final int BUDGET_PER_BLOCK = 4;
    private static final int BUDGET_BASE = 100;
    private static final double GUARD_REACH = 48.0;
    private static final double GUARD_CLOSE = 3.0;
    private static final int RETRY_ROOMS = 200;
    private static final double SPEED = 0.85;
    private static final double EVADE_SPEED = 0.95;

    private final Colonist colonist;
    private boolean active;
    private BlockPos spot;
    private long room;
    private boolean inPlace;
    private boolean guarded;
    private long spotSince;
    private long spotBudget;
    private int stuckTicks;
    private BlockPos evade;
    private long evadeUntil;
    private long lastCheck = NEVER;
    private long calmSince;
    private long alarmUntil;
    private boolean danger;
    private List<Flight.Threat> threats = List.of();
    private final Set<Long> badRooms = new HashSet<>();
    private long safeCheckedAt = NEVER;
    private long roomsClearedAt = NEVER;
    private boolean safeHere;
    private boolean severe;
    private boolean bunkerSpot;
    private boolean viaDoor;
    private static final int BUNKER_DESCENT = 40;

    Refuge(Colonist colonist) {
        this.colonist = colonist;
    }

    void alarm(long until) {
        alarmUntil = Math.max(alarmUntil, until);
    }

    boolean active() {
        return active;
    }

    String status() {
        if (!active) {
            return "";
        }
        if (evade != null) {
            return "fleeing";
        }
        if (guarded) {
            return "staying by a guard";
        }
        return inPlace ? "sheltering" : "running for cover";
    }

    boolean tick(ServerLevel level, Villager villager, long now) {
        Role role = colonist.role();
        if (role == Role.GUARD || !villager.isAlive()) {
            if (active) {
                end(level, villager, "guard duty");
            }
            return false;
        }
        if (now - lastCheck >= CHECK) {
            lastCheck = now;
            if (!evaluate(level, villager, now)) {
                return false;
            }
        }
        if (!active) {
            return false;
        }
        act(level, villager, now);
        return true;
    }

    private boolean evaluate(ServerLevel level, Villager villager, long now) {
        Colony colony = colonist.colony();
        List<LivingEntity> enemies = enemies(level, villager);
        threats = new ArrayList<>();
        danger = attacked(villager);
        for (LivingEntity enemy : enemies) {
            threats.add(new Flight.Threat(enemy.getX(), enemy.getY(), enemy.getZ()));
            if (!danger) {
                double distance = enemy.distanceTo(villager);
                boolean targetsMe = enemy instanceof Mob mob && mob.getTarget() == villager;
                if (distance <= Flight.RANGED_RANGE && Flight.dangerous(distance, villager.hasLineOfSight(enemy), targetsMe,
                        enemy instanceof RangedAttackMob, enemy instanceof Creeper)) {
                    danger = true;
                }
            }
        }
        boolean medicBusy = colonist.job() != null && colonist.job().ignoresDanger();
        boolean wasSevere = severe;
        severe = colony.defense().severe(level);
        boolean alarmed = alarmUntil > now && role() != Role.MEDIC && (active || !underground(level, villager));
        boolean wanted = (danger || alarmed) && !medicBusy;
        if (!active) {
            if (!wanted || villager.isSleeping() && !danger || villager.isTrading()) {
                return false;
            }
            if (villager.isSleeping()) {
                villager.stopSleeping();
            }
            active = true;
            calmSince = 0;
            spot = null;
            evade = null;
            inPlace = false;
            badRooms.clear();
            safeCheckedAt = NEVER;
            colony.data().bump(danger ? "refuge_danger" : "refuge_alarm", 1);
            return true;
        }
        if (severe && !wasSevere && inPlace && !colony.bunker().inside(villager.blockPosition()) && colony.bunker().ready(level)) {
            inPlace = false;
            spot = null;
            colony.safeRooms().release(colonist.uuid());
        }
        if (wanted) {
            calmSince = 0;
        } else if (calmSince == 0) {
            calmSince = now;
        } else if (now - calmSince > CALM) {
            end(level, villager, "all clear");
            return false;
        }
        return true;
    }

    private Role role() {
        return colonist.role();
    }

    private void act(ServerLevel level, Villager villager, long now) {
        Colony colony = colonist.colony();
        colonist.seize(level, villager);
        guarded = false;
        Mover.keepAfloat(villager);
        colonist.mover().screen(Mover.SCREEN_OFF);
        Flight.Threat nearest = nearest(villager);
        double closest = nearest == null ? Double.MAX_VALUE : distance(villager, nearest);
        if (inPlace) {
            if (danger && closest < CLOSE) {
                inPlace = false;
                spot = null;
                startEvade(level, villager, now);
            } else {
                villager.getNavigation().stop();
                if (nearest != null) {
                    villager.getLookControl().setLookAt(nearest.x(), nearest.y() + 1.5, nearest.z());
                }
                return;
            }
        }
        if (evade != null) {
            Mover.Result result = colonist.mover().tick(level, villager, evade, 1.5, EVADE_SPEED, false);
            if (result == Mover.Result.MOVING && now < evadeUntil) {
                return;
            }
            evade = null;
            if (closest < CLOSE) {
                startEvade(level, villager, now);
                return;
            }
            if (closest > CALM_GAP && now - roomsClearedAt > RETRY_ROOMS) {
                roomsClearedAt = now;
                badRooms.clear();
            }
        }
        if (spot == null) {
            if (shelteredHere(level, villager, now) && !(danger && closest < CLOSE)) {
                inPlace = true;
                colony.data().bump("refuge_in_place", 1);
                return;
            }
            if (!chooseSpot(level, villager, now)) {
                if (closest >= CLOSE && stayByGuard(level, villager)) {
                    return;
                }
                startEvade(level, villager, now);
                return;
            }
        }
        if (danger && closest < CLOSE && spotBehind(villager, nearest)) {
            startEvade(level, villager, now);
            return;
        }
        BlockPos goal = spot;
        if (bunkerSpot && !viaDoor) {
            BlockPos door = colony.bunker().entrance();
            if (door == null || villager.blockPosition().distSqr(door) <= 4 || colony.bunker().inside(villager.blockPosition())) {
                enterBunker(now);
            } else {
                goal = door;
            }
        }
        Mover.Result result = colonist.mover().tick(level, villager, goal, 1.2, SPEED, false);
        if (result == Mover.Result.ARRIVED && goal != spot) {
            enterBunker(now);
            return;
        }
        if (result == Mover.Result.ARRIVED) {
            villager.getNavigation().stop();
            inPlace = true;
            colony.data().bump(bunkerSpot ? "refuge_bunker_reached" : "refuge_reached", 1);
            return;
        }
        String note = colonist.mover().note();
        stuckTicks = note.startsWith("partial") || note.startsWith("no path") ? stuckTicks + 1 : 0;
        boolean unreachable = result == Mover.Result.FAILED || stuckTicks > STUCK || now - spotSince > spotBudget;
        boolean exposed = !unreachable && now % CHECK == 0 && exposed(villager);
        boolean stuck = stuckTicks > STUCK;
        if (unreachable || exposed) {
            stuckTicks = 0;
            badRooms.add(room);
            colony.safeRooms().release(colonist.uuid());
            spot = null;
            colony.data().bump(unreachable ? bunkerSpot ? viaDoor ? "refuge_bunker_descent_failed" : "refuge_bunker_door_failed"
                    : "refuge_unreachable" : "refuge_rerouted", 1);
            if (unreachable && bunkerSpot) {
                colony.data().bump("refuge_bunker_why_" + (result == Mover.Result.FAILED ? "failed" : stuck ? "stuck" : "slow"), 1);
            }
        }
    }

    private boolean stayByGuard(ServerLevel level, Villager villager) {
        Villager guard = null;
        double best = GUARD_REACH;
        for (java.util.Map.Entry<java.util.UUID, Colonist> entry : colonist.colony().colonists().entrySet()) {
            if (entry.getValue().role() == Role.GUARD && level.getEntity(entry.getKey()) instanceof Villager candidate
                    && candidate.isAlive()) {
                double distance = candidate.distanceTo(villager);
                if (distance < best) {
                    best = distance;
                    guard = candidate;
                }
            }
        }
        if (guard == null) {
            return false;
        }
        guarded = true;
        if (best > GUARD_CLOSE) {
            colonist.mover().tick(level, villager, guard.blockPosition(), GUARD_CLOSE, SPEED, false);
        } else {
            villager.getNavigation().stop();
            villager.getLookControl().setLookAt(guard);
        }
        return true;
    }

    private boolean shelteredHere(ServerLevel level, Villager villager, long now) {
        if (now - safeCheckedAt >= SAFE_CHECK) {
            safeCheckedAt = now;
            safeHere = villager.onGround() && SafeRooms.enclosed(level, villager.blockPosition());
        }
        if (safeHere && severe && colonist.colony().bunker().ready(level) && !colonist.colony().bunker().inside(villager.blockPosition())) {
            return false;
        }
        return safeHere;
    }

    private boolean chooseSpot(ServerLevel level, Villager villager, long now) {
        if (badRooms.size() >= MAX_BAD) {
            return false;
        }
        Colony colony = colonist.colony();
        BlockPos home = villager.getBrain().getMemory(MemoryModuleType.HOME)
                .filter(global -> global.dimension() == level.dimension()).map(global -> global.pos()).orElse(null);
        List<Flight.Spot> spots = colony.safeRooms().spots(level, now, home);
        int index = Flight.pick(spots, villager.getX(), villager.getY(), villager.getZ(), threats, MAX_DISTANCE, severe,
                candidate -> badRooms.contains(candidate.room()));
        if (index < 0) {
            return false;
        }
        Flight.Spot chosen = spots.get(index);
        bunkerSpot = chosen.bunker();
        if (bunkerSpot) {
            colony.data().bump("refuge_bunker_chosen", 1);
        } else if (severe) {
            colony.data().bump("refuge_severe_elsewhere", 1);
        }
        spot = new BlockPos(chosen.x(), chosen.y(), chosen.z());
        room = chosen.room();
        spotSince = now;
        viaDoor = false;
        BlockPos approach = bunkerSpot ? colony.bunker().entrance() : spot;
        spotBudget = (long) (Math.sqrt((approach == null ? spot : approach).distToCenterSqr(villager.position())) * BUDGET_PER_BLOCK)
                + BUDGET_BASE * (bunkerSpot ? 2 : 1);
        stuckTicks = 0;
        spot = colony.safeRooms().placeFor(room, colonist.uuid(), spot);
        colony.safeRooms().claim(colonist.uuid(), room);
        colonist.mover().reset();
        return true;
    }

    private void enterBunker(long now) {
        if (viaDoor) {
            return;
        }
        viaDoor = true;
        spotSince = now;
        spotBudget = (long) (Math.sqrt(spot.distSqr(colonist.colony().bunker().entrance())) + BUNKER_DESCENT) * BUDGET_PER_BLOCK + BUDGET_BASE;
        stuckTicks = 0;
        colonist.mover().reset();
    }

    private void startEvade(ServerLevel level, Villager villager, long now) {
        Colony colony = colonist.colony();
        BlockPos anchor = spot != null ? spot : colony.groundCenter();
        double[] direction = Flight.away(villager.getX(), villager.getZ(), threats, anchor.getX() + 0.5, anchor.getZ() + 0.5);
        BlockPos target = null;
        for (int turn = 0; turn <= 6 && target == null; turn++) {
            for (int sign : turn == 0 ? new int[] {1} : new int[] {1, -1}) {
                double angle = sign * turn * Math.PI / 12.0;
                double dx = direction[0] * Math.cos(angle) - direction[1] * Math.sin(angle);
                double dz = direction[0] * Math.sin(angle) + direction[1] * Math.cos(angle);
                BlockPos candidate = footing(level, villager, (int) Math.floor(villager.getX() + dx * EVADE_STEP),
                        (int) Math.floor(villager.getZ() + dz * EVADE_STEP));
                if (candidate != null && clearOf(candidate)) {
                    target = candidate;
                    break;
                }
            }
        }
        if (target == null) {
            evade = null;
            return;
        }
        evade = target;
        evadeUntil = now + EVADE_TICKS;
        colonist.mover().reset();
        colony.data().bump("refuge_evaded", 1);
    }

    private static BlockPos footing(ServerLevel level, Villager villager, int x, int z) {
        BlockPos column = new BlockPos(x, villager.getBlockY(), z);
        if (!WorldUtil.loaded(level, column)) {
            return null;
        }
        for (int dy = 0; dy <= 3; dy++) {
            for (int sign : dy == 0 ? new int[] {1} : new int[] {1, -1}) {
                BlockPos feet = column.above(sign * dy);
                if (WorldUtil.standable(level, feet) && level.getFluidState(feet.below()).isEmpty()) {
                    return feet;
                }
            }
        }
        return null;
    }

    private boolean clearOf(BlockPos pos) {
        for (Flight.Threat threat : threats) {
            double dx = threat.x() - (pos.getX() + 0.5);
            double dz = threat.z() - (pos.getZ() + 0.5);
            if (dx * dx + dz * dz < Flight.SPOT_CLEAR * Flight.SPOT_CLEAR) {
                return false;
            }
        }
        return true;
    }

    private boolean spotBehind(Villager villager, Flight.Threat threat) {
        if (threat == null || spot == null) {
            return false;
        }
        double tx = threat.x() - villager.getX();
        double tz = threat.z() - villager.getZ();
        double sx = spot.getX() + 0.5 - villager.getX();
        double sz = spot.getZ() + 0.5 - villager.getZ();
        return tx * sx + tz * sz > 0;
    }

    private boolean exposed(Villager villager) {
        Path path = villager.getNavigation().getPath();
        if (path == null || path.isDone() || threats.isEmpty()) {
            return false;
        }
        List<Flight.Threat> ahead = new ArrayList<>();
        for (Flight.Threat threat : threats) {
            if (distance(villager, threat) > CLOSE) {
                ahead.add(threat);
            }
        }
        List<int[]> nodes = new ArrayList<>();
        for (int i = path.getNextNodeIndex() + 1; i < Math.min(path.getNodeCount(), path.getNextNodeIndex() + LOOKAHEAD); i++) {
            Node node = path.getNode(i);
            nodes.add(new int[] {node.x, node.y, node.z});
        }
        return Flight.exposed(nodes, ahead);
    }

    private Flight.Threat nearest(Villager villager) {
        Flight.Threat best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Flight.Threat threat : threats) {
            double distance = distance(villager, threat);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = threat;
            }
        }
        return best;
    }

    private static double distance(Villager villager, Flight.Threat threat) {
        double dx = threat.x() - villager.getX();
        double dz = threat.z() - villager.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private List<LivingEntity> enemies(ServerLevel level, Villager villager) {
        Colony colony = colonist.colony();
        AABB box = villager.getBoundingBox().inflate(SCAN_RADIUS, 6.0, SCAN_RADIUS);
        return level.getEntitiesOfClass(LivingEntity.class, box, entity -> entity.isAlive() && entity != villager
                && (entity instanceof Enemy && !colony.ward().spares(entity)
                || entity instanceof ServerPlayer player && colony.defense().isEnemyPlayer(player)
                || colony.embassy().isRaider(entity)));
    }

    private static boolean attacked(Villager villager) {
        LivingEntity attacker = villager.getLastHurtByMob();
        return attacker instanceof Enemy && attacker.isAlive() && villager.tickCount - villager.getLastHurtByMobTimestamp() < ATTACK_MEMORY;
    }

    private static boolean underground(ServerLevel level, Villager villager) {
        BlockPos feet = villager.blockPosition();
        return !level.canSeeSky(feet.above()) && feet.getY() < WorldUtil.surfaceY(level, feet.getX(), feet.getZ()) - 4;
    }

    void end(ServerLevel level, Villager villager, String reason) {
        if (!active) {
            return;
        }
        active = false;
        spot = null;
        evade = null;
        inPlace = false;
        threats = List.of();
        colonist.colony().safeRooms().release(colonist.uuid());
        colonist.hold(level, villager, level.getGameTime(), reason);
    }
}
