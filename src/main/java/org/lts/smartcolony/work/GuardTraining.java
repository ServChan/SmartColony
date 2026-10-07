package org.lts.smartcolony.work;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lts.smartcolony.colony.Colony;
import org.lts.smartcolony.colony.Garrison;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.CombatMath;
import org.lts.smartcolony.core.GuardDrill;
import org.lts.smartcolony.core.Ids;
import org.lts.smartcolony.util.ItemIds;

final class GuardTraining {
    private static final int RECOVER_TICKS = 50;
    private static final int SWING_GAP = 20;
    private static final int SHOT_GAP = 40;

    private record Flying(AbstractArrow arrow, long shotAt) {
    }

    private final GuardJob job;
    private final List<Flying> flying = new ArrayList<>();
    private String mode = "";
    private int actions;
    private int clock;
    private int sessions;
    private long failUntil;

    GuardTraining(GuardJob job) {
        this.job = job;
    }

    boolean handle(ServerLevel level, Villager villager) {
        Colony colony = job.colony();
        recover(level, villager, false);
        ColonyData.Building barracks = Garrison.find(colony);
        if (barracks == null || GuardDrill.standDown(colony.defense().alarmed(level), colony.defense().threatLevel())) {
            reset();
            return false;
        }
        List<UUID> guards = Garrison.guards(colony);
        UUID self = job.colonist.uuid();
        int index = guards.indexOf(self);
        if (index < 0) {
            return false;
        }
        boolean rotating = guards.size() >= 3;
        boolean night = !colony.workTime(level);
        boolean onDuty = !rotating || night == GuardDrill.nightShift(index, guards.size());
        long now = level.getGameTime();
        if (!onDuty) {
            reset();
            return GuardDrill.hasCot(index) && rest(level, villager, colony, barracks, index);
        }
        if (night || now < failUntil || !GuardDrill.trainsNow(now, index)) {
            reset();
            return false;
        }
        return train(level, villager, colony, barracks, index);
    }

    private void reset() {
        mode = "";
        actions = 0;
        clock = 0;
    }

    private boolean rest(ServerLevel level, Villager villager, Colony colony, ColonyData.Building barracks, int index) {
        int[] cot = GuardDrill.COT_SPOTS[index];
        BlockPos spot = Garrison.spot(colony, barracks, cot[0], cot[1]);
        Mover.Result result = job.move(level, villager, spot, 1.2);
        if (result == Mover.Result.FAILED) {
            failUntil = level.getGameTime() + 600;
            return false;
        }
        job.status = "resting in the barracks";
        if (result == Mover.Result.ARRIVED && level.getGameTime() % 40 == 0 && villager.getHealth() < villager.getMaxHealth()) {
            villager.heal(0.5F);
        }
        return true;
    }

    private boolean train(ServerLevel level, Villager villager, Colony colony, ColonyData.Building barracks, int index) {
        if (mode.isEmpty() && !pickMode(villager)) {
            failUntil = level.getGameTime() + 600;
            return false;
        }
        boolean sword = mode.equals("sword");
        if (!equipFor(villager, sword)) {
            failUntil = level.getGameTime() + 600;
            reset();
            return false;
        }
        int[] stand = sword ? GuardDrill.DUMMIES[Math.floorMod(index, GuardDrill.DUMMIES.length)] : GuardDrill.LANE_STAND;
        int[] aim = sword ? GuardDrill.DUMMY_HEADS[Math.floorMod(index, GuardDrill.DUMMY_HEADS.length)] : GuardDrill.LANE_TARGET;
        BlockPos spot = Garrison.spot(colony, barracks, stand[0], stand[1]);
        BlockPos target = Garrison.spot(colony, barracks, aim[0], aim[1]);
        Mover.Result result = job.move(level, villager, spot, 1.0);
        if (result == Mover.Result.FAILED) {
            failUntil = level.getGameTime() + 600;
            reset();
            return false;
        }
        job.status = sword ? "sword practice" : "archery practice";
        if (result == Mover.Result.MOVING) {
            return true;
        }
        villager.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        clock++;
        if (sword && clock >= SWING_GAP) {
            clock = 0;
            villager.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            level.playSound(null, villager.blockPosition(), SoundEvents.PLAYER_ATTACK_WEAK, SoundSource.NEUTRAL, 0.6F, 1.0F);
            actions++;
            colony.data().bump("drill_swings", 1);
            if (actions % 4 == 0) {
                award(level, villager, colony);
            }
            if (actions >= GuardDrill.SESSION_SWINGS) {
                finish(colony);
            }
        } else if (!sword && clock >= SHOT_GAP) {
            if (!shoot(level, villager, colony, target, barracks)) {
                return true;
            }
            clock = 0;
            actions++;
            if (actions >= GuardDrill.SESSION_SHOTS) {
                finish(colony);
            }
        }
        return true;
    }

    private boolean pickMode(Villager villager) {
        boolean sword = Tools.SWORD.test(villager.getMainHandItem()) || !Inv.find(villager, Tools.SWORD).isEmpty();
        boolean bow = (Tools.BOW.test(villager.getMainHandItem()) || !Inv.find(villager, Tools.BOW).isEmpty())
                && Inv.count(villager, Tools.ARROW) >= GuardDrill.MIN_ARROWS;
        if (!sword && !bow) {
            return false;
        }
        boolean preferSword = sessions % 2 == 0;
        mode = sword && (preferSword || !bow) ? "sword" : "bow";
        actions = 0;
        clock = 0;
        return true;
    }

    private boolean equipFor(Villager villager, boolean sword) {
        java.util.function.Predicate<ItemStack> wanted = sword ? Tools.SWORD : Tools.BOW;
        if (wanted.test(villager.getMainHandItem())) {
            return sword || Inv.count(villager, Tools.ARROW) > 0;
        }
        ItemStack stack = Inv.takeFirst(villager, wanted);
        if (stack.isEmpty()) {
            return false;
        }
        job.holdInHand(villager, stack);
        return true;
    }

    private boolean shoot(ServerLevel level, Villager villager, Colony colony, BlockPos target, ColonyData.Building barracks) {
        ItemStack ammo = Inv.find(villager, Tools.ARROW);
        if (ammo.isEmpty()) {
            finish(colony);
            return false;
        }
        BlockPos stand = Garrison.spot(colony, barracks, GuardDrill.LANE_STAND[0], GuardDrill.LANE_STAND[1]);
        AABB lane = new AABB(Vec3.atCenterOf(stand), Vec3.atCenterOf(target)).inflate(1.0, 2.0, 1.0);
        if (!level.getEntitiesOfClass(LivingEntity.class, lane, entity -> entity != villager && entity.isAlive()).isEmpty()) {
            job.status = "waiting for a clear lane";
            return false;
        }
        ItemStack bow = villager.getMainHandItem();
        ItemStack projectile = ammo.copyWithCount(1);
        AbstractArrow arrow = ProjectileUtil.getMobArrow(villager, projectile, 1.0F, bow);
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        Vec3 aim = Vec3.atCenterOf(target);
        double dx = aim.x - villager.getX();
        double dy = aim.y - arrow.getY();
        double dz = aim.z - villager.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        Projectile.spawnProjectileUsingShoot(arrow, level, projectile, dx, dy + horizontal * 0.2, dz, 1.6F,
                CombatMath.shotSpread(job.skill()));
        level.playSound(null, villager.blockPosition(), SoundEvents.SKELETON_SHOOT, SoundSource.NEUTRAL, 0.8F, 1.2F);
        ammo.shrink(1);
        if (colony.data().stat("drill_arrows") % 3 == 2) {
            bow.hurtAndBreak(1, villager, EquipmentSlot.MAINHAND);
        }
        flying.add(new Flying(arrow, level.getGameTime()));
        colony.data().bump("drill_arrows", 1);
        award(level, villager, colony);
        return true;
    }

    private void award(ServerLevel level, Villager villager, Colony colony) {
        int xp = GuardDrill.trainingXp(job.skill());
        if (xp > 0) {
            job.gainXp(level, villager, xp);
            colony.data().bump("drill_xp", xp);
        }
    }

    private void finish(Colony colony) {
        colony.data().bump("drill_sessions", 1);
        colony.markDirty();
        sessions++;
        reset();
    }

    private void recover(ServerLevel level, Villager villager, boolean force) {
        if (flying.isEmpty()) {
            return;
        }
        Iterator<Flying> it = flying.iterator();
        while (it.hasNext()) {
            Flying entry = it.next();
            if (!force && level.getGameTime() - entry.shotAt() < RECOVER_TICKS) {
                continue;
            }
            it.remove();
            if (entry.arrow().isRemoved()) {
                continue;
            }
            entry.arrow().discard();
            ItemStack rest = Inv.add(villager, ItemIds.stack(Ids.mc("arrow"), 1));
            if (!rest.isEmpty()) {
                job.colony().warehouse().insert(level, rest);
            }
        }
    }

    void release(ServerLevel level, Villager villager) {
        recover(level, villager, true);
        reset();
    }
}
