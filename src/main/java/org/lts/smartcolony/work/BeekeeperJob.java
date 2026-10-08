package org.lts.smartcolony.work;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.animal.bee.Bee;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.lts.smartcolony.SmartColonyConfig;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.Expedition;
import org.lts.smartcolony.core.Ids;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.core.Specialists;
import org.lts.smartcolony.util.WorldUtil;

public final class BeekeeperJob extends Job {
    private static final int BEES_PER_TRIP = 2;
    private static final int HIVE_CAPACITY = 3;

    private enum Task {
        NONE,
        HARVEST,
        RELOCATE,
        LEAD,
        BREED
    }

    private Task task = Task.NONE;
    private BlockPos target;
    private final List<Bee> led = new ArrayList<>();
    private long nextLook;
    private long relocateUntil;
    private int timer;

    public BeekeeperJob(Colonist colonist) {
        super(colonist);
        depositThreshold = 5;
    }

    @Override
    public Role role() {
        return Role.BEEKEEPER;
    }

    @Override
    protected Predicate<ItemStack> tools() {
        return Tools.SHEARS;
    }

    @Override
    protected boolean keep(ItemStack stack) {
        return Tools.SHEARS.test(stack) || stack.is(Items.GLASS_BOTTLE) || org.lts.smartcolony.core.Ids.isSmallFlower(org.lts.smartcolony.util.ItemIds.id(stack));
    }

    @Override
    public boolean wantsControl(ServerLevel level, Villager villager) {
        return !led.isEmpty() || super.wantsControl(level, villager);
    }

    @Override
    public void onRelease(ServerLevel level, Villager villager) {
        super.onRelease(level, villager);
        for (Bee bee : led) {
            if (bee.isAlive() && bee.getLeashHolder() == villager) {
                bee.removeLeash();
            }
        }
        led.clear();
        task = Task.NONE;
        target = null;
    }

    @Override
    public void tick(ServerLevel level, Villager villager) {
        if (wait > 0) {
            wait--;
            return;
        }
        switch (task) {
            case NONE -> plan(level, villager);
            case HARVEST -> harvest(level, villager);
            case RELOCATE -> relocate(level, villager);
            case LEAD -> lead(level, villager);
            case BREED -> breed(level, villager);
        }
    }

    private void plan(ServerLevel level, Villager villager) {
        if (hasCargo(villager) && (cargoFull(villager) || !onShift(level, villager))) {
            deposit(level, villager);
            return;
        }
        if (!onShift(level, villager)) {
            wait = 100;
            return;
        }
        if (!atDepot(level, villager) && Inv.count(villager, stack -> stack.is(Items.GLASS_BOTTLE)) == 0
                && level.getGameTime() >= nextLook) {
            nextLook = level.getGameTime() + 2400;
            if (goDepot(level, villager) != Mover.Result.FAILED) {
                status = "fetching bottles and shears";
                return;
            }
        }
        if (atDepot(level, villager)) {
            supply(level, villager, Ids.mc("glass_bottle"), 2, 6);
            supply(level, villager, stack -> org.lts.smartcolony.core.Ids.isSmallFlower(org.lts.smartcolony.util.ItemIds.id(stack)), 2, 4);
            if (!Tools.SHEARS.test(villager.getMainHandItem()) && Inv.find(villager, Tools.SHEARS).isEmpty()) {
                ItemStack shears = takeBest(level, Tools.SHEARS);
                if (shears.isEmpty() && colony().craft(level, Ids.mc("shears"), 1)) {
                    shears = takeBest(level, Tools.SHEARS);
                }
                if (!shears.isEmpty()) {
                    Inv.add(villager, shears);
                }
            }
        }
        BlockPos full = fullHive(level, villager);
        if (full != null) {
            task = Task.HARVEST;
            target = full;
            timer = 0;
            return;
        }
        List<BlockPos> hives = apiaryHives(level);
        if (!hives.isEmpty() && level.getGameTime() >= relocateUntil && occupants(level, hives) < hives.size() * 2) {
            BlockPos nest = wildNest(level, villager);
            if (nest != null) {
                task = Task.RELOCATE;
                target = nest;
                timer = 0;
                return;
            }
            relocateUntil = level.getGameTime() + 6000;
        }
        if (!hives.isEmpty() && occupants(level, hives) < hives.size() * HIVE_CAPACITY
                && Inv.count(villager, stack -> org.lts.smartcolony.core.Ids.isSmallFlower(org.lts.smartcolony.util.ItemIds.id(stack))) > 0) {
            task = Task.BREED;
            target = hives.getFirst();
            timer = 0;
            return;
        }
        if (garden(level, villager)) {
            return;
        }
        if (hasCargo(villager)) {
            deposit(level, villager);
            return;
        }
        status = "the hives are quiet";
        idle(level, 600);
    }

    private boolean garden(ServerLevel level, Villager villager) {
        ColonyData.Building apiary = colony().buildingOfKind(Specialists.APIARY);
        if (apiary == null || Inv.count(villager, stack -> org.lts.smartcolony.core.Ids.isSmallFlower(org.lts.smartcolony.util.ItemIds.id(stack))) == 0) {
            return false;
        }
        int y = apiary.origin.y();
        for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(apiary.min.x() + 1, y, apiary.min.z() + 1),
                new BlockPos(apiary.max.x() - 1, y, apiary.max.z() - 1))) {
            if (!level.getBlockState(pos).is(Blocks.GRASS_BLOCK) || !level.getBlockState(pos.above()).isAir()
                    || level.getBlockState(pos.above(2)).is(Blocks.BEEHIVE)) {
                continue;
            }
            BlockPos flower = pos.above();
            status = "planting flowers for the bees";
            if (villager.getEyePosition().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(flower)) > 4.0) {
                if (move(level, villager, flower, 2.0) == Mover.Result.FAILED) {
                    return false;
                }
                return true;
            }
            ItemStack taken = Inv.takeFirst(villager, stack -> org.lts.smartcolony.core.Ids.isSmallFlower(org.lts.smartcolony.util.ItemIds.id(stack)));
            if (taken.isEmpty() || !(taken.getItem() instanceof net.minecraft.world.item.BlockItem block)) {
                return false;
            }
            BlockState state = block.getBlock().defaultBlockState();
            if (state.canSurvive(level, flower)) {
                level.setBlock(flower, state, net.minecraft.world.level.block.Block.UPDATE_ALL);
                taken.shrink(1);
                colony().data().bump("flowers_planted", 1);
            }
            if (!taken.isEmpty()) {
                Inv.add(villager, taken);
            }
            villager.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
            return true;
        }
        return false;
    }

    private void deposit(ServerLevel level, Villager villager) {
        status = "carrying honey to storage";
        if (!atDepot(level, villager)) {
            if (goDepot(level, villager) == Mover.Result.FAILED) {
                wait = 100;
            }
            return;
        }
        depositAll(level, villager);
    }

    private List<BlockPos> apiaryHives(ServerLevel level) {
        ColonyData.Building apiary = colony().buildingOfKind(Specialists.APIARY);
        List<BlockPos> hives = new ArrayList<>();
        if (apiary == null) {
            return hives;
        }
        for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(apiary.min.x(), apiary.origin.y(), apiary.min.z()),
                new BlockPos(apiary.max.x(), apiary.max.y(), apiary.max.z()))) {
            if (level.getBlockState(pos).is(Blocks.BEEHIVE)) {
                hives.add(pos.immutable());
            }
        }
        return hives;
    }

    private static int occupants(ServerLevel level, List<BlockPos> hives) {
        int total = 0;
        for (BlockPos hive : hives) {
            if (level.getBlockEntity(hive) instanceof BeehiveBlockEntity entity) {
                total += entity.getOccupantCount();
            }
        }
        return total;
    }

    private int range() {
        return colony().data().radius + SmartColonyConfig.get().forestRadius / 2;
    }

    private BlockPos fullHive(ServerLevel level, Villager villager) {
        return level.getPoiManager().getInRange(type -> type.is(PoiTypes.BEEHIVE) || type.is(PoiTypes.BEE_NEST), colony().center(), range(),
                        PoiManager.Occupancy.ANY)
                .map(record -> record.getPos())
                .filter(pos -> WorldUtil.loaded(level, pos))
                .filter(pos -> {
                    BlockState state = level.getBlockState(pos);
                    return state.hasProperty(BeehiveBlock.HONEY_LEVEL) && state.getValue(BeehiveBlock.HONEY_LEVEL) >= BeehiveBlock.MAX_HONEY_LEVELS;
                })
                .min(Comparator.comparingDouble(pos -> pos.distSqr(villager.blockPosition())))
                .orElse(null);
    }

    private BlockPos wildNest(ServerLevel level, Villager villager) {
        return level.getPoiManager().getInRange(type -> type.is(PoiTypes.BEE_NEST), colony().center(), range(), PoiManager.Occupancy.ANY)
                .map(record -> record.getPos())
                .filter(pos -> WorldUtil.loaded(level, pos))
                .filter(pos -> level.getBlockEntity(pos) instanceof BeehiveBlockEntity entity && entity.getOccupantCount() > 0)
                .min(Comparator.comparingDouble(pos -> pos.distSqr(villager.blockPosition())))
                .orElse(null);
    }

    private BlockPos below(ServerLevel level, BlockPos pos) {
        for (int dy = 0; dy <= 6; dy++) {
            BlockPos feet = pos.below(dy);
            for (net.minecraft.core.Direction side : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                BlockPos at = feet.relative(side);
                if (WorldUtil.standable(level, at)) {
                    return at;
                }
            }
        }
        return WorldUtil.surface(level, pos.getX(), pos.getZ());
    }

    private boolean reach(ServerLevel level, Villager villager, BlockPos pos) {
        if (villager.getEyePosition().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(pos)) <= 4.5) {
            villager.getNavigation().stop();
            villager.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            return true;
        }
        if (move(level, villager, below(level, pos), 1.5) == Mover.Result.FAILED || ++timer > 1200) {
            task = Task.NONE;
            target = null;
            wait = 40;
        }
        return false;
    }

    private void harvest(ServerLevel level, Villager villager) {
        BlockState state = level.getBlockState(target);
        if (!state.hasProperty(BeehiveBlock.HONEY_LEVEL) || state.getValue(BeehiveBlock.HONEY_LEVEL) < BeehiveBlock.MAX_HONEY_LEVELS) {
            task = Task.NONE;
            return;
        }
        status = "collecting honey";
        if (!reach(level, villager, target)) {
            return;
        }
        villager.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
        if (Inv.remove(villager, stack -> stack.is(Items.GLASS_BOTTLE), 1) == 1) {
            stash(level, villager, List.of(new ItemStack(Items.HONEY_BOTTLE)));
            level.playSound(null, target, SoundEvents.BOTTLE_FILL, SoundSource.NEUTRAL, 0.8F, 1.0F);
            colony().data().bump("honey_bottles", 1);
        } else {
            ItemStack shears = Tools.SHEARS.test(villager.getMainHandItem()) ? villager.getMainHandItem() : Inv.find(villager, Tools.SHEARS);
            if (shears.isEmpty()) {
                task = Task.NONE;
                colony().request(Ids.mc("shears"));
                idle(level, 600);
                return;
            }
            shears.hurtAndBreak(1, villager, EquipmentSlot.MAINHAND);
            stash(level, villager, List.of(new ItemStack(Items.HONEYCOMB, 3)));
            level.playSound(null, target, SoundEvents.BEEHIVE_SHEAR, SoundSource.NEUTRAL, 0.8F, 1.0F);
            colony().data().bump("honeycomb", 3);
        }
        level.setBlock(target, state.setValue(BeehiveBlock.HONEY_LEVEL, 0), net.minecraft.world.level.block.Block.UPDATE_ALL);
        gainXp(level, villager, 2);
        task = Task.NONE;
        wait = 20;
    }

    private void relocate(ServerLevel level, Villager villager) {
        if (!(level.getBlockEntity(target) instanceof BeehiveBlockEntity nest)) {
            task = Task.NONE;
            return;
        }
        status = "calming a wild nest";
        if (!reach(level, villager, target)) {
            return;
        }
        nest.emptyAllLivingFromHive(null, level.getBlockState(target), BeehiveBlockEntity.BeeReleaseStatus.EMERGENCY);
        led.clear();
        for (Bee bee : level.getEntitiesOfClass(Bee.class, new AABB(target).inflate(4.0), bee -> bee.isAlive() && !bee.isLeashed() && !bee.isAngry())) {
            if (led.size() >= BEES_PER_TRIP) {
                break;
            }
            bee.setLeashedTo(villager, true);
            led.add(bee);
        }
        colony().data().discoveries.stream()
                .filter(d -> Expedition.NEST.equals(d.kind) && d.pos.horizontalDistSq(WorldUtil.pos(target)) < 16)
                .forEach(d -> d.used = true);
        task = led.isEmpty() ? Task.NONE : Task.LEAD;
        timer = 0;
        relocateUntil = level.getGameTime() + 2400;
    }

    private void lead(ServerLevel level, Villager villager) {
        led.removeIf(bee -> !bee.isAlive() || bee.getLeashHolder() != villager);
        List<BlockPos> hives = apiaryHives(level);
        if (led.isEmpty() || hives.isEmpty()) {
            for (Bee bee : led) {
                bee.removeLeash();
            }
            led.clear();
            task = Task.NONE;
            return;
        }
        BlockPos hive = hives.stream()
                .filter(pos -> level.getBlockEntity(pos) instanceof BeehiveBlockEntity entity && !entity.isFull())
                .min(Comparator.comparingDouble(pos -> pos.distSqr(villager.blockPosition())))
                .orElse(null);
        if (hive == null) {
            for (Bee bee : led) {
                bee.removeLeash();
            }
            led.clear();
            task = Task.NONE;
            return;
        }
        status = "leading bees to the apiary";
        if (!reach(level, villager, hive)) {
            if (task == Task.NONE) {
                for (Bee bee : led) {
                    bee.removeLeash();
                }
                led.clear();
            }
            return;
        }
        if (level.getBlockEntity(hive) instanceof BeehiveBlockEntity entity) {
            for (Bee bee : new ArrayList<>(led)) {
                if (entity.isFull()) {
                    break;
                }
                bee.removeLeash();
                bee.setHivePos(hive);
                entity.addOccupant(bee);
                led.remove(bee);
                colony().data().bump("bees_settled", 1);
            }
        }
        for (Bee bee : led) {
            bee.removeLeash();
            bee.setHivePos(hive);
        }
        led.clear();
        colony().journal(level, "beekeeper settled wild bees in the apiary");
        gainXp(level, villager, 4);
        task = Task.NONE;
    }

    private void breed(ServerLevel level, Villager villager) {
        status = "feeding the bees";
        if (!reach(level, villager, target)) {
            return;
        }
        List<Bee> adults = level.getEntitiesOfClass(Bee.class, new AABB(target).inflate(8.0),
                bee -> bee.isAlive() && !bee.isBaby() && bee.canFallInLove() && bee.getAge() == 0);
        if (adults.size() < 2) {
            task = Task.NONE;
            idle(level, 1200);
            return;
        }
        for (int i = 0; i < 2; i++) {
            if (Inv.remove(villager, stack -> org.lts.smartcolony.core.Ids.isSmallFlower(org.lts.smartcolony.util.ItemIds.id(stack)), 1) < 1) {
                break;
            }
            adults.get(i).setInLove(null);
        }
        colony().data().bump("bees_bred", 1);
        gainXp(level, villager, 2);
        task = Task.NONE;
        idle(level, 2400);
    }
}
