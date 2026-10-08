package org.lts.smartcolony.work;

import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.lts.smartcolony.colony.Ward;
import org.lts.smartcolony.core.CraftPlanner;
import org.lts.smartcolony.core.Hospital;
import org.lts.smartcolony.core.Ids;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.core.Stock;
import org.lts.smartcolony.util.WorldUtil;

public final class MedicJob extends Job {
    private enum Task {
        NONE,
        RESCUE,
        ESCORT,
        SUPPORT,
        CURE,
        PATIENT,
        PREPARE,
        FORAGE,
        DEPOSIT
    }

    private static final int PLAN_PERIOD = 20;
    private static final int TREAT_COOLDOWN = 60;
    private static final int PREPARE_TICKS = 70;
    private static final int GIVE_UP = 900;

    private Task task = Task.NONE;
    private UUID targetId;
    private long nextPlan;
    private long deadline;
    private int treatCooldown;
    private long noSuppliesUntil;
    private String craftItem;
    private int craftCount;
    private int progress;
    private boolean forageCane;
    private BlockPos forageTarget;
    private int forageMisses;
    private long forageUntil;
    private BlockPos waterSpot;
    private long waterCheckedAt = Long.MIN_VALUE / 2;
    private int harvested;

    public MedicJob(Colonist colonist) {
        super(colonist);
        depositThreshold = 4;
    }

    @Override
    public Role role() {
        return Role.MEDIC;
    }

    @Override
    protected Predicate<ItemStack> tools() {
        return stack -> false;
    }

    @Override
    protected double speed() {
        return task == Task.RESCUE || task == Task.SUPPORT ? 0.85 : 0.6;
    }

    @Override
    public boolean ignoresDanger() {
        return task == Task.RESCUE || task == Task.SUPPORT || task == Task.CURE;
    }

    @Override
    public void onRelease(ServerLevel level, Villager villager) {
        super.onRelease(level, villager);
        task = Task.NONE;
        forageTarget = null;
    }

    @Override
    public String status() {
        return task.name().toLowerCase(java.util.Locale.ROOT) + (status.isEmpty() ? "" : ": " + status);
    }

    private Ward ward() {
        return colony().ward();
    }

    @Override
    public boolean wantsControl(ServerLevel level, Villager villager) {
        if (ward().building() == null) {
            return false;
        }
        if (hasCargo(villager) || task != Task.NONE) {
            return true;
        }
        if (level.getGameTime() >= nextPlan) {
            nextPlan = level.getGameTime() + PLAN_PERIOD;
            choose(level, villager);
        }
        return task != Task.NONE || onShift(level, villager);
    }

    private boolean suppliesAvailable(ServerLevel level) {
        Stock stock = colony().warehouse().stock(level);
        return stock.get(Hospital.BANDAGE) + stock.get(Hospital.REMEDY) + stock.get(Hospital.GOLDEN)
                + stock.get(org.lts.smartcolony.core.Ids.mc("honey_bottle")) + stock.get(org.lts.smartcolony.core.Ids.mc("potion")) > 0;
    }

    private void choose(ServerLevel level, Villager villager) {
        long now = level.getGameTime();
        Villager rescue = ward().nextRescue(level, villager.getUUID(), now);
        if (rescue != null) {
            begin(Task.RESCUE, rescue.getUUID(), now + GIVE_UP);
            ward().claim(rescue.getUUID(), villager.getUUID(), now + GIVE_UP);
            return;
        }
        Villager guard = supportTarget(level);
        if (guard != null) {
            begin(Task.SUPPORT, guard.getUUID(), now + GIVE_UP);
            return;
        }
        ZombieVillager zombie = ward().cureTarget(level, villager.blockPosition());
        if (zombie != null) {
            begin(Task.CURE, zombie.getUUID(), now + GIVE_UP);
            return;
        }
        if (now >= noSuppliesUntil && suppliesAvailable(level)) {
            Villager patient = ward().nextPatient(level, now);
            if (patient != null) {
                begin(Task.PATIENT, patient.getUUID(), now + GIVE_UP);
            }
        }
    }

    private void begin(Task next, UUID target, long until) {
        task = next;
        targetId = target;
        deadline = until;
        status = "";
        treatCooldown = 0;
        colonist.mover().reset();
    }

    private void finishTask(long wait) {
        task = Task.NONE;
        targetId = null;
        this.wait = (int) wait;
        colonist.mover().reset();
    }

    private Villager supportTarget(ServerLevel level) {
        var enemies = colony().defense().enemies();
        if (enemies.isEmpty()) {
            return null;
        }
        Villager best = null;
        double bestRatio = 0.75;
        for (Villager member : colony().loadedVillagers(level)) {
            Colonist other = colony().colonists().get(member.getUUID());
            if (other == null || other.role() != Role.GUARD) {
                continue;
            }
            double ratio = member.getHealth() / member.getMaxHealth();
            if (ratio >= bestRatio) {
                continue;
            }
            boolean engaged = false;
            for (LivingEntity enemy : enemies) {
                if (enemy.distanceTo(member) < 18.0F) {
                    engaged = true;
                    break;
                }
            }
            if (engaged) {
                bestRatio = ratio;
                best = member;
            }
        }
        return best;
    }

    @Override
    public void tick(ServerLevel level, Villager villager) {
        if (treatCooldown > 0) {
            treatCooldown--;
        }
        if (wait > 0) {
            wait--;
            return;
        }
        long now = level.getGameTime();
        if (task != Task.NONE && now > deadline && task != Task.PREPARE && task != Task.FORAGE && task != Task.DEPOSIT) {
            finishTask(40);
            return;
        }
        switch (task) {
            case RESCUE -> rescue(level, villager);
            case ESCORT -> escort(level, villager);
            case SUPPORT -> support(level, villager);
            case CURE -> cure(level, villager);
            case PATIENT -> patient(level, villager);
            case PREPARE -> prepare(level, villager);
            case FORAGE -> forage(level, villager);
            case DEPOSIT -> deposit(level, villager);
            default -> idleTick(level, villager);
        }
    }

    private Villager target(ServerLevel level) {
        return targetId != null && level.getEntity(targetId) instanceof Villager villager && villager.isAlive() ? villager : null;
    }

    private void rescue(ServerLevel level, Villager villager) {
        Villager patient = target(level);
        if (patient == null) {
            finishTask(20);
            return;
        }
        status = "rescuing";
        Mover.Result result = move(level, villager, patient.blockPosition(), 2.5);
        if (result == Mover.Result.FAILED) {
            finishTask(100);
            return;
        }
        if (result != Mover.Result.ARRIVED) {
            return;
        }
        if (patient.getHealth() < patient.getMaxHealth() * Hospital.DISCHARGE) {
            ward().treat(level, villager, patient);
        }
        ward().escortHome(level, patient);
        colony().data().bump("medic_rescues", 1);
        gainXp(level, villager, 4);
        begin(Task.ESCORT, patient.getUUID(), level.getGameTime() + 600);
    }

    private void escort(ServerLevel level, Villager villager) {
        status = "escorting back";
        Mover.Result result = move(level, villager, ward().entrance(), 4.0);
        if (result != Mover.Result.MOVING) {
            finishTask(20);
        }
    }

    private void support(ServerLevel level, Villager villager) {
        Villager guard = target(level);
        var enemies = colony().defense().enemies();
        if (guard == null || enemies.isEmpty() || guard.getHealth() >= guard.getMaxHealth() * 0.9F) {
            finishTask(20);
            return;
        }
        status = "following the guards";
        double gap = villager.distanceTo(guard);
        if (gap > 7.5) {
            if (move(level, villager, guard.blockPosition(), 6.0) == Mover.Result.FAILED) {
                finishTask(60);
            }
            return;
        }
        villager.getNavigation().stop();
        villager.getLookControl().setLookAt(guard, 30.0F, 30.0F);
        if (treatCooldown == 0) {
            treatCooldown = TREAT_COOLDOWN;
            if (!ward().treat(level, villager, guard)) {
                noSuppliesUntil = level.getGameTime() + 600;
                finishTask(40);
                return;
            }
            villager.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            colony().data().bump("medic_field", 1);
            gainXp(level, villager, 2);
        }
    }

    private void cure(ServerLevel level, Villager villager) {
        ZombieVillager zombie = targetId != null && level.getEntity(targetId) instanceof ZombieVillager found && found.isAlive()
                && !found.isConverting() ? found : null;
        if (zombie == null) {
            finishTask(20);
            return;
        }
        status = "curing a zombie villager";
        Mover.Result result = move(level, villager, zombie.blockPosition(), 2.2);
        if (result == Mover.Result.FAILED) {
            finishTask(200);
            return;
        }
        if (result == Mover.Result.ARRIVED) {
            boolean cured = ward().cure(level, zombie);
            villager.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            if (cured) {
                gainXp(level, villager, 8);
            }
            finishTask(cured ? 60 : 600);
        }
    }

    private void patient(ServerLevel level, Villager villager) {
        Villager patient = target(level);
        if (patient == null) {
            finishTask(20);
            return;
        }
        status = "treating a patient";
        Mover.Result result = move(level, villager, patient.blockPosition(), 2.2);
        if (result == Mover.Result.FAILED) {
            finishTask(100);
            return;
        }
        if (result != Mover.Result.ARRIVED) {
            return;
        }
        Vec3 look = patient.position();
        villager.getLookControl().setLookAt(look.x, look.y + 1.0, look.z);
        villager.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        if (ward().treat(level, villager, patient)) {
            gainXp(level, villager, 3);
            finishTask(30);
        } else {
            noSuppliesUntil = level.getGameTime() + 600;
            finishTask(30);
        }
    }

    private void idleTick(ServerLevel level, Villager villager) {
        if (hasCargo(villager)) {
            task = Task.DEPOSIT;
            return;
        }
        if (!onShift(level, villager)) {
            finishTask(100);
            return;
        }
        if (planPreparation(level, villager)) {
            return;
        }
        status = "waiting at the post";
        BlockPos post = ward().post();
        if (Mover.distance(villager, post) > 5.0) {
            move(level, villager, post, 3.5);
            return;
        }
        villager.getNavigation().stop();
        idle(level, 100);
    }

    private boolean planPreparation(ServerLevel level, Villager villager) {
        Stock stock = colony().warehouse().stock(level);
        int adults = colony().adultCount(level);
        int bandages = stock.get(Hospital.BANDAGE);
        int remedies = stock.get(Hospital.REMEDY);
        if (bandages < Hospital.bandageTarget(adults)) {
            int count = Math.min(6, Hospital.bandageTarget(adults) - bandages);
            if (CraftPlanner.plan(stock, Hospital.BANDAGE, count).isPresent()) {
                startPrepare(Hospital.BANDAGE, count);
                return true;
            }
            if (CraftPlanner.plan(stock, Hospital.BANDAGE, 3).isPresent()) {
                startPrepare(Hospital.BANDAGE, 3);
                return true;
            }
            if (startForage(level, true)) {
                return true;
            }
        }
        if (remedies < Hospital.remedyTarget(adults)) {
            int count = Math.min(3, Hospital.remedyTarget(adults) - remedies);
            if (CraftPlanner.plan(stock, Hospital.REMEDY, count).isPresent()) {
                startPrepare(Hospital.REMEDY, count);
                return true;
            }
            if (CraftPlanner.plan(stock, Hospital.REMEDY, 1).isPresent()) {
                startPrepare(Hospital.REMEDY, 1);
                return true;
            }
            if (startForage(level, false)) {
                return true;
            }
        }
        return false;
    }

    private void startPrepare(String item, int count) {
        craftItem = item;
        craftCount = count;
        progress = PREPARE_TICKS;
        task = Task.PREPARE;
        deadline = Long.MAX_VALUE;
        colonist.mover().reset();
    }

    private boolean startForage(ServerLevel level, boolean cane) {
        long now = level.getGameTime();
        if (now < forageUntil) {
            return false;
        }
        forageCane = cane;
        forageTarget = null;
        forageMisses = 0;
        harvested = 0;
        forageUntil = 0;
        task = Task.FORAGE;
        deadline = Long.MAX_VALUE;
        colonist.mover().reset();
        return true;
    }

    private void prepare(ServerLevel level, Villager villager) {
        if (!onShift(level, villager)) {
            finishTask(20);
            return;
        }
        BlockPos post = ward().post();
        if (Mover.distance(villager, post) > 3.0) {
            status = "going to the station";
            if (move(level, villager, post, 2.5) == Mover.Result.FAILED) {
                finishTask(200);
            }
            return;
        }
        status = "preparing " + Ids.path(craftItem);
        Vec3 look = Vec3.atCenterOf(post);
        villager.getLookControl().setLookAt(look.x, look.y, look.z);
        if (progress % 14 == 0) {
            villager.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            level.playSound(null, villager.blockPosition(), SoundEvents.BREWING_STAND_BREW, SoundSource.NEUTRAL, 0.5F, 1.0F);
            level.sendParticles(ParticleTypes.COMPOSTER, look.x, look.y + 0.8, look.z, 3, 0.2, 0.1, 0.2, 0.02);
        }
        if (--progress > 0) {
            return;
        }
        if (colony().craft(level, craftItem, craftCount)) {
            gainXp(level, villager, 2);
            colony().data().bump("medic_prepared", craftCount);
        } else {
            colony().request(craftItem);
        }
        finishTask(20);
    }

    private void forage(ServerLevel level, Villager villager) {
        if (!onShift(level, villager) || cargoFull(villager) || harvested >= 6) {
            task = Task.NONE;
            forageUntil = level.getGameTime() + (harvested == 0 ? 3000 : 0);
            forageTarget = null;
            return;
        }
        if (forageTarget == null) {
            status = forageCane ? "looking for sugar cane" : "looking for herbs";
            forageTarget = probe(level, villager);
            if (forageTarget == null) {
                if (++forageMisses >= 25) {
                    task = Task.NONE;
                    forageUntil = level.getGameTime() + 3000;
                    colony().request(forageCane ? Ids.mc("sugar_cane") : Ids.mc("poppy"));
                }
                wait = 10;
                return;
            }
        }
        BlockState state = level.getBlockState(forageTarget);
        boolean valid = forageCane ? state.is(Blocks.SUGAR_CANE) && level.getBlockState(forageTarget.below()).is(Blocks.SUGAR_CANE)
                : state.is(BlockTags.SMALL_FLOWERS);
        if (!valid) {
            forageTarget = null;
            return;
        }
        status = forageCane ? "cutting sugar cane" : "picking herbs";
        Mover.Result result = move(level, villager, forageTarget, 2.2);
        if (result == Mover.Result.FAILED) {
            forageTarget = null;
            wait = 20;
            return;
        }
        if (result != Mover.Result.ARRIVED) {
            return;
        }
        ItemStack drop = new ItemStack(state.getBlock().asItem());
        villager.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        level.destroyBlock(forageTarget, false, villager);
        stash(level, villager, java.util.List.of(drop));
        harvested++;
        forageTarget = null;
        wait = 15;
    }

    private BlockPos probe(ServerLevel level, Villager villager) {
        BlockPos center = colony().center();
        int radius = colony().data().radius + 12;
        long now = level.getGameTime();
        if (forageCane && now - waterCheckedAt > 6000) {
            waterCheckedAt = now;
            waterSpot = colony().findWater(level, center, radius);
        }
        var random = villager.getRandom();
        for (int i = 0; i < 40; i++) {
            int x;
            int z;
            if (forageCane && waterSpot != null && i % 2 == 0) {
                x = waterSpot.getX() + random.nextInt(17) - 8;
                z = waterSpot.getZ() + random.nextInt(17) - 8;
            } else {
                x = center.getX() + random.nextInt(radius * 2 + 1) - radius;
                z = center.getZ() + random.nextInt(radius * 2 + 1) - radius;
            }
            BlockPos column = new BlockPos(x, 0, z);
            if (!WorldUtil.loaded(level, column)) {
                continue;
            }
            BlockPos pos = WorldUtil.surface(level, x, z);
            BlockState state = level.getBlockState(pos);
            if (forageCane) {
                if (!state.is(Blocks.SUGAR_CANE)) {
                    continue;
                }
                BlockPos top = pos;
                while (level.getBlockState(top.above()).is(Blocks.SUGAR_CANE)) {
                    top = top.above();
                }
                if (!top.equals(pos) && !colony().isProtected(top, false)) {
                    return top.immutable();
                }
            } else if (state.is(BlockTags.SMALL_FLOWERS) && !colony().isProtected(pos, false)) {
                return pos.immutable();
            }
        }
        return null;
    }

    private void deposit(ServerLevel level, Villager villager) {
        status = "delivering supplies";
        if (!atDepot(level, villager)) {
            if (goDepot(level, villager) == Mover.Result.FAILED) {
                wait = 100;
            }
            return;
        }
        depositAll(level, villager);
        task = Task.NONE;
    }
}
