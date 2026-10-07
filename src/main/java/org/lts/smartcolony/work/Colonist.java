package org.lts.smartcolony.work;

import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import org.lts.smartcolony.SmartColonyConfig;
import org.lts.smartcolony.colony.Colony;
import org.lts.smartcolony.core.Role;

public final class Colonist {
    private final UUID uuid;
    private final Colony colony;
    private final Mover mover = new Mover();
    private final Digger digger = new Digger();
    private Role role = Role.IDLE;
    private Job job;
    private long releasedUntil;
    private String releaseReason = "";
    private long lastDangerCheck;

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
        releasedUntil = Math.max(releasedUntil, until);
        releaseReason = "alarm";
    }

    public Colonist(UUID uuid, Colony colony) {
        this.uuid = uuid;
        this.colony = colony;
        this.mover.guard(pos -> colony.isProtected(pos, false));
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
        if (releasedUntil > level.getGameTime()) {
            return "released: " + releaseReason;
        }
        return job.status();
    }

    public void tick(ServerLevel level, Villager villager) {
        if (job == null) {
            job = Jobs.create(role, this);
        }
        long now = level.getGameTime();
        if (villager.isUnderWater() && villager.getAirSupply() < villager.getMaxAirSupply() / 3) {
            surface(level, villager);
        }
        if (role != Role.GUARD && !job.ignoresDanger() && now - lastDangerCheck >= 10) {
            lastDangerCheck = now;
            if (inDanger(level, villager) && !trappedUnderground(level, villager)) {
                releasedUntil = now + 120;
                releaseReason = "danger";
            }
        }
        boolean want = role != Role.IDLE
                && !villager.isBaby()
                && villager.isAlive()
                && !villager.isTrading()
                && releasedUntil <= now
                && !job.idling(level)
                && job.wantsControl(level, villager);
        if (villager.isSleeping() && role != Role.GUARD && role != Role.MEDIC) {
            want = false;
        }
        if (!want && role != Role.GUARD && !villager.isBaby() && villager.isAlive() && !villager.isSleeping()
                && releasedUntil <= now && !colony.workTime(level) && bedless(villager)) {
            if (!isControlled(villager)) {
                take(level, villager);
            }
            Mover.keepAfloat(villager);
            mover.tick(level, villager, colony.depot(), 3.0, 0.6, false);
            return;
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
            digger.bonus(1.0F + 0.12F * colony.skill(uuid, role));
            job.tick(level, villager);
        }
    }

    public static final java.util.Set<Role> NIGHT_ROLES = java.util.EnumSet.of(Role.MINER, Role.SMITH, Role.FLETCHER, Role.COOK);

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
        net.minecraft.core.BlockPos bell = colony.center();
        double angle = (uuid.getLeastSignificantBits() & 0xFFFF) / 65536.0 * Math.PI * 2.0;
        double radius = 3.0 + (uuid.getMostSignificantBits() & 3);
        net.minecraft.core.BlockPos spot = org.lts.smartcolony.util.WorldUtil.surface(level,
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

    private static boolean trappedUnderground(ServerLevel level, Villager villager) {
        net.minecraft.core.BlockPos feet = villager.blockPosition();
        return !level.canSeeSky(feet.above()) && feet.getY() < org.lts.smartcolony.util.WorldUtil.surfaceY(level, feet.getX(), feet.getZ()) - 4;
    }

    private boolean inDanger(ServerLevel level, Villager villager) {
        LivingEntity attacker = villager.getLastHurtByMob();
        if (attacker instanceof Enemy && attacker.isAlive() && villager.tickCount - villager.getLastHurtByMobTimestamp() < 100) {
            return true;
        }
        return !level.getEntitiesOfClass(LivingEntity.class, villager.getBoundingBox().inflate(6.0, 3.0, 6.0),
                entity -> entity instanceof Enemy && entity.isAlive() && villager.hasLineOfSight(entity)).isEmpty();
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
