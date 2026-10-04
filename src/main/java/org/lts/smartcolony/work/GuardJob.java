package org.lts.smartcolony.work;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lts.smartcolony.SmartColonyConfig;
import org.lts.smartcolony.core.CombatMath;
import org.lts.smartcolony.core.Ids;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.util.ItemIds;
import org.lts.smartcolony.util.WorldUtil;

public final class GuardJob extends Job {
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final String[] ARMOR_SUFFIX = {"_helmet", "_chestplate", "_leggings", "_boots"};

    private LivingEntity target;
    private long nextGear;
    private int shotCooldown;
    private int meleeCooldown;
    private int creeperBackOff;
    private int retargetIn;
    private int patrolIndex;
    private int pause;
    private boolean strafeLeft;
    private int eatCooldown;
    private boolean healing;
    private int engageTicks;
    private double bestGap = Double.MAX_VALUE;
    private final java.util.Map<java.util.UUID, Long> ignored = new java.util.HashMap<>();

    public GuardJob(Colonist colonist) {
        super(colonist);
        depositThreshold = 8;
    }

    @Override
    public Role role() {
        return Role.GUARD;
    }

    @Override
    protected java.util.function.Predicate<ItemStack> tools() {
        return Tools.SWORD.or(Tools.BOW);
    }

    @Override
    protected boolean keep(ItemStack stack) {
        return tools().test(stack) || Tools.ARROW.test(stack) || stack.has(DataComponents.EQUIPPABLE);
    }

    @Override
    public boolean wantsControl(ServerLevel level, Villager villager) {
        return true;
    }

    @Override
    protected double speed() {
        return target != null ? 0.75 : 0.55;
    }

    @Override
    public String status() {
        if (healing) {
            return "healing";
        }
        if (target != null) {
            return "fighting " + net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).getPath();
        }
        return phase + (status.isEmpty() ? "" : ": " + status);
    }

    @Override
    public void tick(ServerLevel level, Villager villager) {
        if (shotCooldown > 0) {
            shotCooldown--;
        }
        if (meleeCooldown > 0) {
            meleeCooldown--;
        }
        if (eatCooldown > 0) {
            eatCooldown--;
        }
        float health = villager.getHealth() / villager.getMaxHealth();
        if (healing || health < SmartColonyConfig.get().guardRetreatHealth) {
            heal(level, villager, health);
            return;
        }
        ignored.values().removeIf(until -> until < level.getGameTime());
        boolean attacked = villager.getLastHurtByMob() != null && villager.tickCount - villager.getLastHurtByMobTimestamp() < 60;
        if (!armed(villager) && !attacked && level.getGameTime() >= nextGear - 2400 + 200) {
            target = null;
            if (gear(level, villager)) {
                nextGear = level.getGameTime() + 2400;
            }
            return;
        }
        if (--retargetIn <= 0) {
            retargetIn = 10;
            LivingEntity picked = pickTarget(level, villager);
            if (picked != target) {
                engageTicks = 0;
                bestGap = Double.MAX_VALUE;
            }
            target = picked;
        }
        if (target != null && (!target.isAlive() || target.distanceTo(villager) > 40 || !valid(target) || outOfBounds(target))) {
            target = null;
        }
        if (target != null) {
            double gap = target.distanceTo(villager);
            if (gap < bestGap - 0.5 || gap < 3.0 || (Tools.BOW.test(villager.getMainHandItem()) && hasArrows(villager) && villager.hasLineOfSight(target))) {
                bestGap = Math.min(gap, bestGap);
                engageTicks = 0;
            } else if (++engageTicks > 160) {
                ignored.put(target.getUUID(), level.getGameTime() + 600);
                status = "target out of reach";
                target = null;
                engageTicks = 0;
                bestGap = Double.MAX_VALUE;
                return;
            }
            fight(level, villager, health);
            return;
        }
        if (level.getGameTime() >= nextGear || (Tools.BOW.test(villager.getMainHandItem()) || !Inv.find(villager, Tools.BOW).isEmpty())
                && Inv.count(villager, Tools.ARROW) < 4 && level.getGameTime() >= nextGear - 1800) {
            if (gear(level, villager)) {
                nextGear = level.getGameTime() + 2400;
            }
            return;
        }
        if (Inv.used(villager) >= 4 && hasCargo(villager)) {
            if (!atDepot(level, villager)) {
                goDepot(level, villager);
                return;
            }
            depositAll(level, villager);
        }
        if (Inv.free(villager) > 1) {
            net.minecraft.world.entity.item.ItemEntity loot = nearestLoot(level, villager, 10.0, stack -> !stack.isEmpty());
            if (loot != null && colony().contains(loot.blockPosition())) {
                status = "collecting loot";
                if (loot.distanceTo(villager) < 1.6) {
                    pickUp(villager, loot);
                } else if (move(level, villager, loot.blockPosition(), 1.0) == Mover.Result.FAILED) {
                    loot.setPickUpDelay(200);
                }
                return;
            }
        }
        if (colony().workTime(level)) {
            patrol(level, villager);
        } else {
            nightWatch(level, villager);
        }
    }

    private boolean valid(LivingEntity entity) {
        if (entity instanceof ServerPlayer player) {
            return colony().defense().isEnemyPlayer(player);
        }
        return entity instanceof Enemy;
    }

    private LivingEntity pickTarget(ServerLevel level, Villager villager) {
        LivingEntity attacker = villager.getLastHurtByMob();
        if (attacker != null && attacker.isAlive() && valid(attacker) && villager.tickCount - villager.getLastHurtByMobTimestamp() < 200) {
            return attacker;
        }
        for (Villager member : colony().loadedVillagers(level)) {
            LivingEntity threat = member.getLastHurtByMob();
            if (threat != null && threat.isAlive() && valid(threat) && member.tickCount - member.getLastHurtByMobTimestamp() < 200
                    && threat.distanceTo(villager) < 32 && !ignored.containsKey(threat.getUUID()) && !outOfBounds(threat)) {
                return threat;
            }
        }
        LivingEntity nearest = colony().defense().nearestEnemy(villager, 24, ignored.keySet());
        return nearest == null || outOfBounds(nearest) ? null : nearest;
    }

    private boolean outOfBounds(LivingEntity entity) {
        double limit = colony().data().radius + 20;
        if (entity.blockPosition().distSqr(colony().center()) > limit * limit) {
            return true;
        }
        return entity.isInWater() || !entity.level().getFluidState(entity.blockPosition().below()).isEmpty();
    }

    private boolean armed(Villager villager) {
        boolean sword = Tools.SWORD.test(villager.getMainHandItem()) || !Inv.find(villager, Tools.SWORD).isEmpty();
        boolean bow = (Tools.BOW.test(villager.getMainHandItem()) || !Inv.find(villager, Tools.BOW).isEmpty()) && hasArrows(villager);
        return sword || bow;
    }

    private boolean gear(ServerLevel level, Villager villager) {
        if (!atDepot(level, villager)) {
            if (goDepot(level, villager) == Mover.Result.FAILED) {
                return true;
            }
            status = "fetching gear";
            return false;
        }
        ItemStack hand = villager.getMainHandItem();
        boolean hasBow = Tools.BOW.test(hand) || !Inv.find(villager, Tools.BOW).isEmpty();
        if (!hasBow) {
            ItemStack bow = takeBest(level, Tools.BOW);
            if (bow.isEmpty() && colony().craft(level, Ids.mc("bow"), 1)) {
                bow = takeBest(level, Tools.BOW);
            }
            if (!bow.isEmpty()) {
                Inv.add(villager, bow);
                hasBow = true;
            }
        }
        if (Inv.find(villager, Tools.SWORD).isEmpty() && !Tools.SWORD.test(hand)) {
            ItemStack sword = takeBest(level, Tools.SWORD);
            if (sword.isEmpty()) {
                for (String id : Tools.SWORDS) {
                    if (colony().craft(level, id, 1)) {
                        sword = takeBest(level, Tools.SWORD);
                        break;
                    }
                }
            }
            if (!sword.isEmpty()) {
                Inv.add(villager, sword);
            }
        }
        if (hasBow) {
            supply(level, villager, Ids.mc("arrow"), 16, 48);
            if (Inv.count(villager, Tools.ARROW) == 0) {
                colony().request(Ids.mc("arrow"));
            }
        }
        for (int i = 0; i < ARMOR.length; i++) {
            EquipmentSlot slot = ARMOR[i];
            String suffix = ARMOR_SUFFIX[i];
            ItemStack worn = villager.getItemBySlot(slot);
            ItemStack best = ItemStack.EMPTY;
            for (var container : colony().warehouse().all(level)) {
                for (int s = 0; s < container.getContainerSize(); s++) {
                    ItemStack stack = container.getItem(s);
                    if (!stack.isEmpty() && ItemIds.id(stack).endsWith(suffix) && Tools.tier(stack) > Tools.tier(worn)
                            && (best.isEmpty() || Tools.tier(stack) > Tools.tier(best))) {
                        best = stack;
                    }
                }
            }
            if (best.isEmpty() && worn.isEmpty()) {
                if (colony().craft(level, Ids.mc("leather" + suffix), 1)) {
                    final String id = Ids.mc("leather" + suffix);
                    best = colony().warehouse().extractOne(level, stack -> ItemIds.id(stack).equals(id));
                    if (!best.isEmpty()) {
                        wear(level, villager, slot, best);
                    }
                }
                continue;
            }
            if (!best.isEmpty()) {
                ItemStack chosen = best;
                ItemStack taken = colony().warehouse().extractOne(level, stack -> stack == chosen);
                if (!taken.isEmpty()) {
                    wear(level, villager, slot, taken);
                }
            }
        }
        armWeapon(villager, 10);
        status = "";
        return true;
    }

    private void wear(ServerLevel level, Villager villager, EquipmentSlot slot, ItemStack armor) {
        ItemStack old = villager.getItemBySlot(slot);
        villager.setItemSlot(slot, armor);
        villager.setGuaranteedDrop(slot);
        if (!old.isEmpty()) {
            colony().warehouse().insert(level, old);
        }
    }

    private boolean hasArrows(Villager villager) {
        return Inv.count(villager, Tools.ARROW) > 0;
    }

    private void armWeapon(Villager villager, double distance) {
        boolean ranged = hasArrows(villager) && (Tools.BOW.test(villager.getMainHandItem()) || !Inv.find(villager, Tools.BOW).isEmpty());
        boolean useBow = ranged && distance > 3.5;
        java.util.function.Predicate<ItemStack> wanted = useBow ? Tools.BOW : Tools.SWORD;
        if (wanted.test(villager.getMainHandItem())) {
            return;
        }
        ItemStack weapon = Inv.takeFirst(villager, wanted);
        if (!weapon.isEmpty()) {
            holdInHand(villager, weapon);
        }
    }

    private void fight(ServerLevel level, Villager villager, float health) {
        double distance = villager.distanceTo(target);
        armWeapon(villager, distance);
        boolean ranged = Tools.BOW.test(villager.getMainHandItem()) && hasArrows(villager);
        if (target instanceof Creeper creeper) {
            fightCreeper(level, villager, creeper, distance, ranged);
            return;
        }
        CombatMath.Stance stance = CombatMath.stance(ranged, distance, health, SmartColonyConfig.get().guardRetreatHealth);
        villager.getLookControl().setLookAt(target, 30.0F, 30.0F);
        switch (stance) {
            case RETREAT -> healing = true;
            case KITE -> {
                double[] away = CombatMath.retreatPoint(villager.getX(), villager.getZ(), target.getX(), target.getZ(), 7.0);
                BlockPos spot = WorldUtil.surface(level, (int) Math.floor(away[0]), (int) Math.floor(away[1]));
                if (Math.abs(spot.getY() - villager.getBlockY()) > 3) {
                    double[] side = CombatMath.strafePoint(villager.getX(), villager.getZ(), target.getX(), target.getZ(), 5.0, strafeLeft);
                    spot = WorldUtil.surface(level, (int) Math.floor(side[0]), (int) Math.floor(side[1]));
                    strafeLeft = !strafeLeft;
                }
                villager.getNavigation().moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 0.85);
                if (shotCooldown <= 0 && distance > 3.0 && clearShot(level, villager)) {
                    shoot(level, villager);
                }
            }
            case SHOOT -> {
                if (!villager.hasLineOfSight(target)) {
                    villager.getNavigation().moveTo(target, 0.7);
                    return;
                }
                villager.getNavigation().stop();
                if (shotCooldown <= 0) {
                    if (clearShot(level, villager)) {
                        shoot(level, villager);
                    } else {
                        double[] side = CombatMath.strafePoint(villager.getX(), villager.getZ(), target.getX(), target.getZ(), 3.0, strafeLeft);
                        villager.getNavigation().moveTo(side[0], villager.getY(), side[1], 0.7);
                        shotCooldown = 10;
                        colony().data().bump("shots_held_fire", 1);
                    }
                }
            }
            case APPROACH -> villager.getNavigation().moveTo(target, ranged ? 0.6 : 0.8);
            case MELEE -> {
                villager.getNavigation().stop();
                if (meleeCooldown <= 0) {
                    strike(level, villager);
                }
            }
        }
    }

    private void fightCreeper(ServerLevel level, Villager villager, Creeper creeper, double distance, boolean ranged) {
        villager.getLookControl().setLookAt(creeper, 30.0F, 30.0F);
        boolean fuse = creeper.getSwellDir() > 0 || creeper.isIgnited();
        if (creeperBackOff > 0) {
            creeperBackOff--;
        }
        double safe = ranged ? 8.0 : 7.5;
        if (fuse && distance < safe || creeperBackOff > 0 && distance < 6.0 || ranged && distance < 5.0) {
            status = "keeping away from a creeper";
            double[] away = CombatMath.retreatPoint(villager.getX(), villager.getZ(), creeper.getX(), creeper.getZ(), 6.0);
            BlockPos spot = WorldUtil.surface(level, (int) Math.floor(away[0]), (int) Math.floor(away[1]));
            if (Math.abs(spot.getY() - villager.getBlockY()) > 3) {
                double[] side = CombatMath.strafePoint(villager.getX(), villager.getZ(), creeper.getX(), creeper.getZ(), 6.0, strafeLeft);
                spot = WorldUtil.surface(level, (int) Math.floor(side[0]), (int) Math.floor(side[1]));
                strafeLeft = !strafeLeft;
            }
            villager.getNavigation().moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 1.0);
            if (ranged && !fuse && shotCooldown <= 0 && distance > 3.0 && clearShot(level, villager)) {
                shoot(level, villager);
            }
            return;
        }
        if (ranged) {
            villager.getNavigation().stop();
            if (shotCooldown <= 0 && villager.hasLineOfSight(creeper) && clearShot(level, villager)) {
                shoot(level, villager);
            }
            return;
        }
        if (fuse) {
            villager.getNavigation().stop();
            return;
        }
        if (distance > 2.4) {
            villager.getNavigation().moveTo(creeper, 0.9);
            return;
        }
        if (meleeCooldown <= 0) {
            strike(level, villager);
            creeperBackOff = 30;
            colony().data().bump("creeper_hits", 1);
        }
    }

    private boolean clearShot(ServerLevel level, Villager villager) {
        Vec3 from = villager.getEyePosition();
        Vec3 to = new Vec3(target.getX(), target.getY(0.5), target.getZ());
        AABB area = new AABB(from, to).inflate(1.0);
        List<Entity> friends = new ArrayList<>();
        friends.addAll(level.getEntitiesOfClass(Villager.class, area, v -> v != villager && v.isAlive()));
        friends.addAll(level.getEntitiesOfClass(IronGolem.class, area, IronGolem::isAlive));
        for (ServerPlayer player : level.players()) {
            if (area.contains(player.position()) && !colony().defense().isEnemyPlayer(player)) {
                friends.add(player);
            }
        }
        for (Entity friend : friends) {
            if (friend == target) {
                continue;
            }
            AABB box = friend.getBoundingBox().inflate(0.25);
            CombatMath.Box shape = new CombatMath.Box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
            if (CombatMath.segmentHits(from.x, from.y, from.z, to.x, to.y, to.z, shape)) {
                return false;
            }
        }
        return true;
    }

    private void shoot(ServerLevel level, Villager villager) {
        ItemStack bow = villager.getMainHandItem();
        ItemStack ammo = Inv.find(villager, Tools.ARROW);
        if (ammo.isEmpty()) {
            return;
        }
        ItemStack projectile = ammo.copyWithCount(1);
        AbstractArrow arrow = ProjectileUtil.getMobArrow(villager, projectile, 1.0F, bow);
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        double dx = target.getX() - villager.getX();
        double dy = target.getY(0.3333333333333333) - arrow.getY();
        double dz = target.getZ() - villager.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        Projectile.spawnProjectileUsingShoot(arrow, level, projectile, dx, dy + horizontal * 0.2, dz, 1.6F, 4.0F);
        level.playSound(null, villager.blockPosition(), SoundEvents.SKELETON_SHOOT, SoundSource.NEUTRAL, 1.0F, 1.2F);
        ammo.shrink(1);
        bow.hurtAndBreak(1, villager, EquipmentSlot.MAINHAND);
        shotCooldown = 25;
        colony().data().bump("arrows_shot", 1);
    }

    private void strike(ServerLevel level, Villager villager) {
        ItemStack weapon = villager.getMainHandItem();
        float damage = (float) Tools.attackDamage(weapon);
        villager.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        if (target.hurtServer(level, level.damageSources().mobAttack(villager), damage)) {
            double dx = target.getX() - villager.getX();
            double dz = target.getZ() - villager.getZ();
            target.knockback(0.4, -dx, -dz, level.damageSources().mobAttack(villager), damage);
            if (!weapon.isEmpty() && weapon.isDamageableItem()) {
                weapon.hurtAndBreak(1, villager, EquipmentSlot.MAINHAND);
            }
            colony().data().bump("melee_hits", 1);
        }
        meleeCooldown = 20;
    }

    private void heal(ServerLevel level, Villager villager, float health) {
        healing = health < 0.9F;
        if (!healing) {
            return;
        }
        status = "retreating to heal";
        if (!atDepot(level, villager)) {
            Mover.Result result = colonist.mover().tick(level, villager, colony().depot(), 2.5, 0.85);
            if (result == Mover.Result.FAILED && villager.distanceToSqr(Vec3.atCenterOf(colony().depot())) < 400) {
                eat(level, villager);
            }
            return;
        }
        eat(level, villager);
    }

    private void eat(ServerLevel level, Villager villager) {
        if (eatCooldown > 0) {
            return;
        }
        eatCooldown = 30;
        ItemStack food = colony().warehouse().extractOne(level, GuardJob::edible);
        if (food.isEmpty()) {
            villager.heal(0.5F);
            return;
        }
        FoodProperties properties = food.get(DataComponents.FOOD);
        float amount = properties == null ? 2.0F : Math.max(2.0F, properties.nutrition());
        villager.heal(amount);
        level.playSound(null, villager.blockPosition(), SoundEvents.GENERIC_EAT.value(), SoundSource.NEUTRAL, 0.8F, 1.0F);
        colony().data().bump("guard_meals", 1);
    }

    private static boolean edible(ItemStack stack) {
        if (!stack.has(DataComponents.FOOD)) {
            return false;
        }
        String path = Ids.path(ItemIds.id(stack));
        return !path.equals("rotten_flesh") && !path.equals("spider_eye") && !path.equals("poisonous_potato")
                && !path.equals("pufferfish") && !path.equals("chorus_fruit");
    }

    private void patrol(ServerLevel level, Villager villager) {
        List<BlockPos> route = colony().defense().patrolRoute(level);
        if (route.isEmpty()) {
            return;
        }
        if (pause > 0) {
            pause--;
            if (pause % 40 == 0) {
                villager.getLookControl().setLookAt(villager.getX() + level.getRandom().nextGaussian() * 8, villager.getEyeY(),
                        villager.getZ() + level.getRandom().nextGaussian() * 8);
            }
            return;
        }
        int offset = Math.floorMod(colonist.uuid().hashCode(), route.size());
        BlockPos point = route.get((patrolIndex + offset) % route.size());
        Mover.Result result = move(level, villager, point, 2.0);
        status = "patrolling";
        if (result != Mover.Result.MOVING) {
            patrolIndex++;
            pause = 60;
        }
    }

    private void nightWatch(ServerLevel level, Villager villager) {
        List<BlockPos> posts = colony().defense().nightPosts(level, 4);
        if (posts.isEmpty()) {
            patrol(level, villager);
            return;
        }
        List<java.util.UUID> guards = new ArrayList<>();
        colony().colonists().forEach((uuid, c) -> {
            if (c.role() == Role.GUARD) {
                guards.add(uuid);
            }
        });
        int index = Math.max(0, guards.indexOf(colonist.uuid()));
        BlockPos post = posts.get(index % posts.size());
        status = "night watch";
        if (move(level, villager, post, 2.0) == Mover.Result.ARRIVED && level.getGameTime() % 50 == 0) {
            villager.getLookControl().setLookAt(villager.getX() + level.getRandom().nextGaussian() * 10, villager.getEyeY(),
                    villager.getZ() + level.getRandom().nextGaussian() * 10);
        }
    }
}
