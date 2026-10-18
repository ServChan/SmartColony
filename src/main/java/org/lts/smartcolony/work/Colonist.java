package org.lts.smartcolony.work;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lts.smartcolony.SmartColonyConfig;
import org.lts.smartcolony.colony.Colony;
import org.lts.smartcolony.core.HallRest;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.core.Schedule;

public final class Colonist {
    private final UUID uuid;
    private final Colony colony;
    private final Mover mover = new Mover();
    private final Digger digger = new Digger();
    private Role role = Role.IDLE;
    private Job job;
    private long releasedUntil;
    private String releaseReason = "";
    private final Hazards hazards = new Hazards(this);
    private final Refuge refuge = new Refuge(this);
    private long lastTick;
    private long restBlockedUntil;
    private long nextHeal;
    private boolean resting;
    private String pause = "";
    private UUID leader;
    private ChoresJob helper;
    private long followUntil;
    private long fightStarted;

    public void hold(ServerLevel level, Villager villager, long until, String reason) {
        releasedUntil = until;
        releaseReason = reason;
        if (villager != null && isControlled(villager)) {
            release(level, villager, reason);
        }
    }

    public void hide(ServerLevel level, long until) {
        if (role == Role.GUARD || role == Role.MEDIC) {
            return;
        }
        refuge.alarm(until);
    }

    public Colonist(UUID uuid, Colony colony) {
        this.uuid = uuid;
        this.colony = colony;
        this.mover.guard(pos -> colony.isProtected(pos, false));
        this.mover.onAvoided(() -> colony.data().bump("lava_avoided", 1));
        this.digger.onAvoided(() -> colony.data().bump("lava_avoided", 1));
    }

    public UUID uuid() {
        return uuid;
    }

    public Colony colony() {
        return colony;
    }

    public Mover mover() {
        return mover;
    }

    public Digger digger() {
        return digger;
    }

    public Role role() {
        return role;
    }

    public Job job() {
        return job;
    }

    public void restartJob(ServerLevel level, Villager villager) {
        if (villager != null && isControlled(villager)) {
            release(level, villager, "led out of the mine");
        }
        job = null;
    }

    public void setRole(ServerLevel level, Villager villager, Role newRole) {
        if (newRole == role && job != null) {
            return;
        }
        if (villager != null && isControlled(villager)) {
            release(level, villager, "role change");
        }
        Role previous = role;
        role = newRole;
        job = Jobs.create(newRole, this);
        if (villager != null) {
            if (level != null && previous != Role.IDLE && previous != newRole) {
                job.handOverPreviousRole(level, villager);
            }
            applyName(villager);
        }
    }

    public void applyName(Villager villager) {
        if (!SmartColonyConfig.get().showRoleNames) {
            return;
        }
        boolean ours = colony.ownsName(uuid);
        if (villager.hasCustomName() && !ours) {
            return;
        }
        if (role == Role.IDLE || villager.isBaby()) {
            if (ours) {
                villager.setCustomName(null);
                colony.setOwnsName(uuid, false);
            }
            return;
        }
        villager.setCustomName(Component.translatable("smartcolony.role." + role.key()));
        villager.setCustomNameVisible(false);
        colony.setOwnsName(uuid, true);
    }

    public static boolean isControlled(Villager villager) {
        return ((ControlledVillager) villager).smartcolony$isControlled();
    }

    public String describe(ServerLevel level) {
        if (job == null) {
            return "idle";
        }
        if (refuge.active()) {
            return "refuge: " + refuge.status();
        }
        if (releasedUntil > level.getGameTime()) {
            return "released: " + releaseReason;
        }
        if (!pause.isEmpty()) {
            return pause + (pause.equals("chores") && helper != null ? ": " + helper.status() : "") + " | " + job.status();
        }
        return job.status();
    }

    public void tick(ServerLevel level, Villager villager) {
        if (job == null) {
            job = Jobs.create(role, this);
        }
        long now = level.getGameTime();
        lastTick = now;
        ProfessionSync.tick(level, this, villager);
        if (villager.isUnderWater() && villager.getAirSupply() < villager.getMaxAirSupply() / 3) {
            surface(level, villager);
        }
        BlockPos fire = colony.fire().assignment(uuid);
        if (fire != null && !villager.isOnFire() && SmartColonyConfig.get().firefighting) {
            if (fightFire(level, villager, fire, now)) {
                return;
            }
        } else if (fightStarted != 0) {
            fightStarted = 0;
            endPause(level, villager, "fire");
        }
        if (now % 20 == Math.floorMod(uuid.hashCode(), 20) && villager.isInWall() && escapeWall(level, villager)) {
            colony.data().bump("walls_escaped", 1);
        }
        if (hazards.tick(level, villager, now)) {
            return;
        }
        if (refuge.tick(level, villager, now)) {
            return;
        }
        if (raidShelter(level, villager)) {
            return;
        }
        if (leader != null && follow(level, villager, now)) {
            return;
        }
        if (restsAtHall(level, villager, now)) {
            restAtHall(level, villager, now);
            return;
        }
        if (resting) {
            resting = false;
            wakeAtInn(villager);
            if (isControlled(villager)) {
                release(level, villager, "morning");
            }
        }
        if (helps(level, villager)) {
            return;
        }
        boolean want = (role != Role.IDLE || job.wantsControl(level, villager))
                && !villager.isBaby()
                && villager.isAlive()
                && !villager.isTrading()
                && releasedUntil <= now
                && !job.idling(level)
                && job.wantsControl(level, villager);
        if (villager.isSleeping() && role != Role.GUARD && role != Role.MEDIC) {
            want = false;
        }
        Schedule.Slot slot = colony.daily().slot(level);
        boolean dayOff = (slot == Schedule.Slot.OFF || slot == Schedule.Slot.REST) && colony.daily().restDay(level)
                && !Schedule.essential(role);
        if (want && dayOff && Jobs.canInterrupt(this, villager)) {
            want = false;
        }
        if (want && slot == Schedule.Slot.LUNCH && !Schedule.skipsBreaks(role) && !colony.festival(level)
                && (pause.equals("lunch") || Jobs.canInterrupt(this, villager))) {
            if (gather(level, villager, "lunch", colony.daily().lunchSpot(level, uuid), null)) {
                return;
            }
        } else if (pause.equals("lunch")) {
            endPause(level, villager, "lunch");
        }
        if (!want && role != Role.GUARD && !villager.isBaby() && villager.isAlive() && !villager.isSleeping()
                && releasedUntil <= now && colony.tavern().dinnerOpen(level, uuid)) {
            if (!isControlled(villager)) {
                take(level, villager);
            }
            Mover.keepAfloat(villager);
            if (colony.tavern().dine(level, villager, mover, uuid)) {
                return;
            }
        }
        if (!want && role != Role.GUARD && !villager.isBaby() && villager.isAlive() && !villager.isSleeping()
                && !colony.workTime(level) && farFromHome(villager)) {
            if (!isControlled(villager)) {
                take(level, villager);
            }
            Mover.keepAfloat(villager);
            if (mover.tick(level, villager, colony.depot(), 6.0, 0.6, false) != Mover.Result.MOVING) {
                release(level, villager, "home");
            }
            return;
        }
        boolean social = !want && !villager.isBaby() && villager.isAlive() && !villager.isSleeping() && role != Role.GUARD
                && releasedUntil <= now && !villager.isTrading();
        if (social && dayOff && slot == Schedule.Slot.REST) {
            if (gather(level, villager, "rest", colony.daily().restSpot(level, uuid), colony.daily().eveningLook())) {
                return;
            }
        } else if (social && slot == Schedule.Slot.EVENING && !bedless(villager)) {
            if (gather(level, villager, "evening", colony.daily().eveningSpot(level, uuid), colony.daily().eveningLook())) {
                return;
            }
        } else if (pause.equals("rest") || pause.equals("evening")) {
            endPause(level, villager, pause);
        }
        boolean controlled = isControlled(villager);
        if (want && !controlled) {
            take(level, villager);
        } else if (!want && controlled) {
            release(level, villager, villager.isTrading() ? "trading" : "off duty");
        }
        if (want && colony.festival(level) && role != Role.GUARD) {
            if (!isControlled(villager)) {
                take(level, villager);
            }
            Mover.keepAfloat(villager);
            celebrate(level, villager);
            return;
        }
        if (isControlled(villager)) {
            if (villager.isSleeping()) {
                villager.stopSleeping();
            }
            Mover.keepAfloat(villager);
            digger.bonus(1.0F + 0.12F * colony.skill(uuid, role) + colony.tavern().workBonus(level, uuid)
                    + (colony.daily().fed(level) ? Schedule.FED_BONUS : 0.0F));
            job.tick(level, villager);
        }
    }

    private boolean gather(ServerLevel level, Villager villager, String kind, BlockPos spot, BlockPos look) {
        if (spot == null) {
            return false;
        }
        if (!pause.equals(kind)) {
            if (isControlled(villager)) {
                release(level, villager, kind);
            }
            take(level, villager);
            pause = kind;
        } else if (!isControlled(villager)) {
            take(level, villager);
        }
        Mover.keepAfloat(villager);
        Mover.Result result = mover.tick(level, villager, spot, 1.6, 0.5, false);
        if (result == Mover.Result.FAILED && look != null && Mover.distance(villager, look) <= 40.0) {
            result = Mover.Result.ARRIVED;
        }
        if (result == Mover.Result.FAILED) {
            endPause(level, villager, kind);
            releasedUntil = level.getGameTime() + 400;
            releaseReason = kind + " spot unreachable";
            return false;
        }
        if (result == Mover.Result.ARRIVED) {
            villager.getNavigation().stop();
            long time = level.getGameTime() + (uuid.hashCode() & 63);
            if (look != null) {
                villager.getLookControl().setLookAt(look.getX() + 0.5, look.getY() + 1.5, look.getZ() + 0.5);
            }
            if (time % 160 == 0) {
                villager.playAmbientSound();
            }
            if (kind.equals("lunch") && time % 60 == 0) {
                level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, Items.BREAD),
                        villager.getX(), villager.getY() + 1.6, villager.getZ(), 3, 0.1, 0.1, 0.1, 0.02);
            }
        }
        return true;
    }

    private boolean helps(ServerLevel level, Villager villager) {
        boolean wanted = role != Role.IDLE && role != Role.GUARD && role != Role.MEDIC && !villager.isBaby() && !villager.isSleeping()
                && job.idling(level) && colony.workTime(level) && releasedUntil <= level.getGameTime()
                && colony.daily().slot(level) == Schedule.Slot.WORK
                && (pause.equals("chores") || colony.chores().pending() > 0) && !villager.isTrading();
        if (!wanted) {
            if (pause.equals("chores")) {
                if (helper != null && helper.hasCargo(villager)) {
                    helper.tick(level, villager);
                    return true;
                }
                endPause(level, villager, "chores");
            }
            return false;
        }
        if (helper == null) {
            helper = new ChoresJob(this);
        }
        if (!pause.equals("chores")) {
            if (isControlled(villager)) {
                release(level, villager, "chores");
            }
            take(level, villager);
            pause = "chores";
        } else if (!isControlled(villager)) {
            take(level, villager);
        }
        Mover.keepAfloat(villager);
        if (!helper.wantsControl(level, villager)) {
            endPause(level, villager, "chores");
            return false;
        }
        helper.tick(level, villager);
        return true;
    }

    private void endPause(ServerLevel level, Villager villager, String kind) {
        if (pause.equals(kind) || kind.equals("fire")) {
            pause = "";
            if (isControlled(villager)) {
                release(level, villager, "back to work");
            }
        }
    }

    public String pause() {
        return pause;
    }

    public void followLeader(UUID who, long until) {
        leader = who;
        followUntil = until;
    }

    public boolean following() {
        return leader != null;
    }

    private boolean follow(ServerLevel level, Villager villager, long now) {
        boolean home = villager.blockPosition().distSqr(colony.center()) <= (double) colony.data().radius * colony.data().radius;
        if (now > followUntil || home || !(level.getEntity(leader) instanceof Villager guide) || !guide.isAlive()) {
            leader = null;
            if (pause.equals("follow")) {
                pause = "";
                if (isControlled(villager)) {
                    release(level, villager, home ? "arrived" : "lost the guide");
                }
            }
            if (home) {
                colony.journal(level, "a new settler arrived with the scout");
                colony.data().bump("settlers_recruited", 1);
            }
            return false;
        }
        if (!pause.equals("follow")) {
            if (isControlled(villager)) {
                release(level, villager, "follow");
            }
            take(level, villager);
            pause = "follow";
        } else if (!isControlled(villager)) {
            take(level, villager);
        }
        Mover.keepAfloat(villager);
        if (villager.distanceTo(guide) > 3.0) {
            Mover.Result result = mover.tick(level, villager, guide.blockPosition(), 2.0, 0.6, false);
            if (result == Mover.Result.FAILED && villager.distanceTo(guide) > 24) {
                Mover.rescue(level, villager, guide.blockPosition());
            }
        } else {
            villager.getNavigation().stop();
            villager.getLookControl().setLookAt(guide);
        }
        return true;
    }

    private boolean raidShelter(ServerLevel level, Villager villager) {
        boolean shelters = colony.defense().raidActive() && role != Role.GUARD && role != Role.MEDIC && colony.hall().restOpen()
                && (bedless(villager) || villager.isBaby());
        if (!shelters) {
            if (pause.equals("raid")) {
                endPause(level, villager, "raid");
            }
            return false;
        }
        BlockPos spot = colony.hall().restSpot(uuid);
        if (spot == null) {
            spot = colony.daily().lunchSpot(level, uuid);
        }
        if (spot == null) {
            return false;
        }
        if (!pause.equals("raid")) {
            if (isControlled(villager)) {
                release(level, villager, "raid");
            }
            take(level, villager);
            pause = "raid";
            colony.data().bump("raid_sheltered", 1);
        } else if (!isControlled(villager)) {
            take(level, villager);
        }
        Mover.keepAfloat(villager);
        mover.screen(Mover.SCREEN_OFF);
        if (mover.tick(level, villager, spot, 1.2, 0.75, false) == Mover.Result.ARRIVED) {
            villager.getNavigation().stop();
        }
        return true;
    }

    private boolean fightFire(ServerLevel level, Villager villager, BlockPos fire, long now) {
        if (fightStarted == 0) {
            fightStarted = now;
            wakeAtInn(villager);
            if (isControlled(villager)) {
                release(level, villager, "fire");
            }
            take(level, villager);
            pause = "fire";
            colony.data().bump("firefighter_calls", 1);
        } else if (!isControlled(villager)) {
            take(level, villager);
        }
        if (!level.getBlockState(fire).is(BlockTags.FIRE)) {
            BlockPos next = colony.fire().nextFire(fire);
            if (next == null) {
                colony.fire().release(uuid);
                fightStarted = 0;
                endPause(level, villager, "fire");
                return false;
            }
            colony.fire().retarget(uuid, next);
            fire = next;
        }
        if (now - fightStarted > 900) {
            colony.fire().release(uuid);
            fightStarted = 0;
            endPause(level, villager, "fire");
            return false;
        }
        Mover.keepAfloat(villager);
        mover.screen(Mover.SCREEN_OFF);
        double distance = Mover.distance(villager, fire);
        if (distance > 3.2) {
            if (mover.tick(level, villager, fire, 2.6, 0.8, false) == Mover.Result.FAILED && distance > 6.0) {
                colony.fire().release(uuid);
                fightStarted = 0;
                endPause(level, villager, "fire");
                return false;
            }
            return true;
        }
        villager.getNavigation().stop();
        villager.getLookControl().setLookAt(fire.getX() + 0.5, fire.getY() + 0.5, fire.getZ() + 0.5);
        if (Math.floorMod(now + uuid.hashCode(), 12) == 0) {
            villager.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
            colony.fire().extinguish(level, fire, colony.fire().bucketAvailable(level));
            colony.gainXp(level, villager, role, 1);
        }
        return true;
    }

    public boolean hallRestApplies() {
        return restBlockedUntil <= lastTick && colony.hall().restOpen();
    }

    private boolean restsAtHall(ServerLevel level, Villager villager, long now) {
        return HallRest.restsHere(hallRestApplies(), bedless(villager), role == Role.GUARD, villager.isBaby(), colony.workTime(level))
                && villager.isAlive()
                && !villager.isTrading()
                && (!villager.isSleeping() || innBed != null && resting)
                && releasedUntil <= now
                && !colony.festival(level)
                && !colony.tavern().dinnerOpen(level, uuid);
    }

    private void restAtHall(ServerLevel level, Villager villager, long now) {
        if (!resting) {
            if (isControlled(villager)) {
                release(level, villager, "night rest");
            }
            take(level, villager);
            resting = true;
        } else if (!isControlled(villager)) {
            take(level, villager);
        }
        if (villager.isSleeping()) {
            if (now >= nextHeal) {
                nextHeal = now + HallRest.HEAL_PERIOD;
                if (villager.getHealth() < villager.getMaxHealth()) {
                    villager.heal(HallRest.HEAL_AMOUNT);
                }
            }
            return;
        }
        if (sleepAtInn(level, villager, now)) {
            return;
        }
        net.minecraft.core.BlockPos spot = colony.hall().restSpot(uuid);
        if (spot == null) {
            restBlockedUntil = now + 600;
            return;
        }
        Mover.keepAfloat(villager);
        Mover.Result result = mover.tick(level, villager, spot, 1.2, 0.5, false);
        if (result == Mover.Result.FAILED) {
            restBlockedUntil = now + 1200;
            return;
        }
        if (result == Mover.Result.ARRIVED) {
            villager.getNavigation().stop();
            if (now >= nextHeal) {
                nextHeal = now + HallRest.HEAL_PERIOD;
                if (villager.getHealth() < villager.getMaxHealth()) {
                    villager.heal(HallRest.HEAL_AMOUNT);
                }
            }
        }
    }

    private net.minecraft.core.BlockPos innBed;
    private long innBedRetry;

    private boolean sleepAtInn(ServerLevel level, Villager villager, long now) {
        net.minecraft.core.BlockPos bed = now < innBedRetry ? null : colony.hall().guestBed(uuid);
        if (bed == null) {
            return false;
        }
        net.minecraft.world.level.block.state.BlockState state = level.getBlockState(bed);
        if (!(state.getBlock() instanceof net.minecraft.world.level.block.BedBlock)
                || state.getValue(net.minecraft.world.level.block.BedBlock.OCCUPIED)) {
            innBedRetry = now + 400;
            return false;
        }
        Mover.keepAfloat(villager);
        if (villager.position().distanceTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(bed)) > 2.4) {
            net.minecraft.core.BlockPos side = bedside(level, villager, bed, state);
            Mover.Result result = mover.tick(level, villager, side == null ? bed : side, side == null ? 1.6 : 0.8, 0.5, false);
            if (result == Mover.Result.FAILED) {
                innBedRetry = now + 1200;
                colony.data().bump("inn_bed_unreachable", 1);
            }
            return true;
        }
        villager.getNavigation().stop();
        villager.startSleeping(bed);
        innBed = bed;
        colony.data().bump("inn_nights", 1);
        return true;
    }

    private static net.minecraft.core.BlockPos bedside(ServerLevel level, Villager villager, net.minecraft.core.BlockPos head,
            net.minecraft.world.level.block.state.BlockState state) {
        net.minecraft.core.Direction facing = state.getValue(net.minecraft.world.level.block.BedBlock.FACING);
        net.minecraft.core.BlockPos foot = head.relative(facing.getOpposite());
        net.minecraft.core.BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (net.minecraft.core.BlockPos part : new net.minecraft.core.BlockPos[] {foot, head}) {
            for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                net.minecraft.core.BlockPos cell = part.relative(direction);
                if (cell.equals(foot) || cell.equals(head) || !org.lts.smartcolony.util.WorldUtil.standable(level, cell)) {
                    continue;
                }
                double distance = cell.distSqr(villager.blockPosition());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = cell;
                }
            }
        }
        return best;
    }

    private void wakeAtInn(Villager villager) {
        if (innBed != null && villager.isSleeping() && villager.getSleepingPos().map(innBed::equals).orElse(false)) {
            villager.stopSleeping();
        }
        innBed = null;
    }

    private static boolean escapeWall(ServerLevel level, Villager villager) {
        BlockPos at = villager.blockPosition();
        for (int r = 0; r <= 3; r++) {
            for (int dy = 0; dy <= 3; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                            continue;
                        }
                        BlockPos cell = at.offset(dx, dy, dz);
                        if (org.lts.smartcolony.util.WorldUtil.standable(level, cell)) {
                            villager.getNavigation().stop();
                            villager.teleportTo(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5);
                            villager.resetFallDistance();
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    public static boolean bedless(Villager villager) {
        return villager.getBrain().getMemory(MemoryModuleType.HOME).isEmpty();
    }

    private boolean farFromHome(Villager villager) {
        double limit = colony.data().radius + 28;
        if (villager.blockPosition().distSqr(colony.center()) <= limit * limit) {
            return false;
        }
        org.lts.smartcolony.core.Pos here = org.lts.smartcolony.util.WorldUtil.pos(villager.blockPosition());
        for (org.lts.smartcolony.core.ColonyData.Building building : colony.data().buildings) {
            if (building.contains(here, 8)) {
                return false;
            }
        }
        return true;
    }

    private void celebrate(ServerLevel level, Villager villager) {
        net.minecraft.core.BlockPos bell = colony.tavern().built() ? colony.tavern().plaza() : colony.center();
        double angle = (uuid.getLeastSignificantBits() & 0xFFFF) / 65536.0 * Math.PI * 2.0;
        double radius = 3.0 + (uuid.getMostSignificantBits() & 3);
        net.minecraft.core.BlockPos spot = colony.tavern().built() ? colony.tavern().festivalSpot(level, uuid)
                : org.lts.smartcolony.util.WorldUtil.surface(level,
                bell.getX() + (int) Math.round(Math.cos(angle) * radius), bell.getZ() + (int) Math.round(Math.sin(angle) * radius));
        if (mover.tick(level, villager, spot, 1.5, 0.55, false) == Mover.Result.MOVING) {
            return;
        }
        villager.getNavigation().stop();
        villager.getLookControl().setLookAt(bell.getX() + 0.5, bell.getY() + 1.0, bell.getZ() + 0.5);
        long time = level.getGameTime() + (uuid.hashCode() & 31);
        if (time % 40 == 0) {
            villager.getJumpControl().jump();
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.NOTE, villager.getX(), villager.getY() + 2.2, villager.getZ(),
                    1, 0.3, 0.2, 0.3, 1.0);
        }
        if (time % 100 == 0) {
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER, villager.getX(), villager.getY() + 1.5,
                    villager.getZ(), 4, 0.4, 0.4, 0.4, 0.0);
        }
    }

    private void surface(ServerLevel level, Villager villager) {
        net.minecraft.core.BlockPos at = villager.blockPosition();
        for (int r = 0; r <= 8; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) {
                        continue;
                    }
                    net.minecraft.core.BlockPos column = org.lts.smartcolony.util.WorldUtil.surface(level, at.getX() + dx, at.getZ() + dz);
                    if (org.lts.smartcolony.util.WorldUtil.standable(level, column) && level.getFluidState(column.below()).isEmpty()) {
                        villager.getNavigation().stop();
                        villager.teleportTo(column.getX() + 0.5, column.getY(), column.getZ() + 0.5);
                        colony.data().bump("drowning_rescues", 1);
                        return;
                    }
                }
            }
        }
    }

    void seize(ServerLevel level, Villager villager) {
        if (!isControlled(villager)) {
            take(level, villager);
        }
    }

    private void take(ServerLevel level, Villager villager) {
        villager.getBrain().stopAll(level, villager);
        villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        villager.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
        villager.getBrain().eraseMemory(MemoryModuleType.PATH);
        villager.getNavigation().stop();
        ((ControlledVillager) villager).smartcolony$setControlled(true);
        mover.reset();
    }

    public void release(ServerLevel level, Villager villager, String reason) {
        ((ControlledVillager) villager).smartcolony$setControlled(false);
        villager.getNavigation().stop();
        digger.abort(level, villager);
        mover.closeAll(level, villager);
        mover.reset();
        if (job != null) {
            job.onRelease(level, villager);
        }
        stowHand(level, villager);
        releaseReason = reason;
    }

    public void stowHand(ServerLevel level, Villager villager) {
        ItemStack hand = villager.getMainHandItem();
        if (hand.isEmpty() || !Tools.ANY_TOOL.test(hand)) {
            return;
        }
        ItemStack rest = Inv.add(villager, hand.copy());
        villager.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        if (!rest.isEmpty()) {
            colony.warehouse().insert(level, rest);
        }
    }
}
