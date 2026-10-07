package org.lts.smartcolony.work;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.lts.smartcolony.SmartColonyConfig;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.Ids;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.util.WorldUtil;

public final class HerderJob extends Job {
    public enum Species {
        COW,
        SHEEP,
        PIG,
        CHICKEN;

        public static Species of(Animal animal) {
            if (animal instanceof Cow) {
                return COW;
            }
            if (animal instanceof Sheep) {
                return SHEEP;
            }
            if (animal instanceof Pig) {
                return PIG;
            }
            if (animal instanceof Chicken) {
                return CHICKEN;
            }
            return null;
        }
    }

    private enum Action {
        COLLECT,
        SHEAR,
        CULL,
        BREED,
        CAPTURE
    }

    private Action action;
    private Animal target;
    private Animal partner;
    private boolean leading;
    private int gateStep;
    private int pullTicks;
    private long lastCullDay = -1;
    private long captureCooldownUntil;

    public HerderJob(Colonist colonist) {
        super(colonist);
        depositThreshold = 6;
    }

    @Override
    public Role role() {
        return Role.HERDER;
    }

    @Override
    protected java.util.function.Predicate<ItemStack> tools() {
        return Tools.SHEARS;
    }

    public boolean isLeading() {
        return leading;
    }

    @Override
    protected boolean keep(ItemStack stack) {
        return tools().test(stack) || isBait(stack);
    }

    private static boolean isBait(ItemStack stack) {
        return stack.is(Items.WHEAT) || stack.is(Items.CARROT) || stack.is(Items.WHEAT_SEEDS) || stack.is(Items.POTATO)
                || stack.is(Items.BEETROOT) || stack.is(Items.BEETROOT_SEEDS);
    }

    @Override
    public boolean wantsControl(ServerLevel level, Villager villager) {
        if (leading) {
            return true;
        }
        return colony().data().pen != null && super.wantsControl(level, villager);
    }

    @Override
    public void onRelease(ServerLevel level, Villager villager) {
        super.onRelease(level, villager);
        if (target != null && target.isLeashed() && target.getLeashHolder() == villager) {
            target.removeLeash();
        }
        leading = false;
        target = null;
        partner = null;
    }

    @Override
    public void tick(ServerLevel level, Villager villager) {
        if (wait > 0) {
            wait--;
            return;
        }
        ColonyData.Pen pen = colony().data().pen;
        if (pen == null) {
            status = "waiting for a pen";
            wait = 200;
            return;
        }
        switch (phase) {
            case "start" -> start(level, villager, pen);
            case "act" -> act(level, villager, pen);
            case "deposit" -> deposit(level, villager, pen);
            default -> phase = "start";
        }
    }

    private void start(ServerLevel level, Villager villager, ColonyData.Pen pen) {
        if (hasCargo(villager) && (cargoFull(villager) || !colony().workTime(level))) {
            phase = "deposit";
            return;
        }
        if (!colony().workTime(level)) {
            wait = 100;
            return;
        }
        if (Inv.count(villager, HerderJob::isBait) < 4) {
            if (!atDepot(level, villager)) {
                if (goDepot(level, villager) == Mover.Result.FAILED) {
                    wait = 100;
                }
                return;
            }
            supply(level, villager, Ids.mc("wheat"), 4, 12);
            supply(level, villager, Ids.mc("carrot"), 2, 8);
            supply(level, villager, Ids.mc("wheat_seeds"), 2, 8);
            Equip equip = equip(level, villager, Tools.SHEARS, List.of());
            if (equip == Equip.MISSING && !Tools.SHEARS.test(villager.getMainHandItem())) {
                colony().craft(level, Ids.mc("shears"), 1);
                equip(level, villager, Tools.SHEARS, List.of());
            }
        }
        choose(level, villager, pen);
        if (action == null) {
            status = "herd is fine";
            wait = 300;
            if (hasCargo(villager)) {
                phase = "deposit";
            }
            return;
        }
        gateStep = 0;
        status = action.name().toLowerCase(java.util.Locale.ROOT) + (target == null ? "" : " " + target.blockPosition().toShortString());
        phase = "act";
    }

    private Map<Species, List<Animal>> census(ServerLevel level, ColonyData.Pen pen) {
        Map<Species, List<Animal>> result = new EnumMap<>(Species.class);
        for (Species species : Species.values()) {
            result.put(species, new java.util.ArrayList<>());
        }
        for (Animal animal : level.getEntitiesOfClass(Animal.class, penBox(pen), Animal::isAlive)) {
            Species species = Species.of(animal);
            if (species != null) {
                result.get(species).add(animal);
            }
        }
        return result;
    }

    private static AABB penBox(ColonyData.Pen pen) {
        return new AABB(pen.min.x(), pen.min.y() - 1, pen.min.z(), pen.max.x() + 1, pen.max.y() + 3, pen.max.z() + 1);
    }

    private static boolean inside(ColonyData.Pen pen, double x, double z) {
        return x >= pen.min.x() && x < pen.max.x() + 1 && z >= pen.min.z() && z < pen.max.z() + 1;
    }

    private void choose(ServerLevel level, Villager villager, ColonyData.Pen pen) {
        action = null;
        target = null;
        partner = null;
        if (!level.getEntitiesOfClass(ItemEntity.class, penBox(pen), ItemEntity::isAlive).isEmpty() && Inv.free(villager) > 1) {
            action = Action.COLLECT;
            return;
        }
        Map<Species, List<Animal>> herd = census(level, pen);
        int cap = SmartColonyConfig.get().animalsPerSpecies;
        if (Tools.SHEARS.test(villager.getMainHandItem()) || !Inv.find(villager, Tools.SHEARS).isEmpty()) {
            for (Animal sheep : herd.get(Species.SHEEP)) {
                if (sheep instanceof Sheep s && s.readyForShearing()) {
                    action = Action.SHEAR;
                    target = sheep;
                    return;
                }
            }
        }
        long day = level.getDefaultClockTime() / 24000L;
        boolean noShears = !Tools.SHEARS.test(villager.getMainHandItem()) && Inv.find(villager, Tools.SHEARS).isEmpty()
                && colony().stockOf(level, id -> id.equals(Ids.mc("shears"))) == 0;
        boolean needWool = colony().stockOf(level, id -> Ids.path(id).endsWith("_wool") || Ids.path(id).endsWith("_bed")) < 3;
        List<Animal> sheepAdults = herd.get(Species.SHEEP).stream().filter(a -> !a.isBaby()).toList();
        if (noShears && needWool && sheepAdults.size() > 2 && lastCullDay != level.getDefaultClockTime() / 24000L) {
            action = Action.CULL;
            target = sheepAdults.stream().filter(a -> !a.isInLove()).findFirst().orElse(sheepAdults.getFirst());
            return;
        }
        for (Species species : Species.values()) {
            List<Animal> adults = herd.get(species).stream().filter(a -> !a.isBaby()).toList();
            boolean over = herd.get(species).size() > cap;
            boolean hungry = colony().wantsMoreFood(level) && adults.size() > 3 && lastCullDay != day;
            if ((over || hungry) && adults.size() > 2) {
                action = Action.CULL;
                target = adults.stream().filter(a -> !a.isInLove()).findFirst().orElse(adults.getFirst());
                return;
            }
        }
        for (Species species : Species.values()) {
            List<Animal> ready = herd.get(species).stream()
                    .filter(a -> !a.isBaby() && a.canFallInLove() && a.getAge() == 0).toList();
            if (herd.get(species).size() < cap && ready.size() >= 2 && hasBaitFor(villager, ready.getFirst())) {
                action = Action.BREED;
                target = ready.get(0);
                partner = ready.get(1);
                return;
            }
        }
        if (level.getGameTime() < captureCooldownUntil) {
            return;
        }
        Species wanted = null;
        int lowest = Integer.MAX_VALUE;
        for (Species species : Species.values()) {
            int count = herd.get(species).size();
            if (count < 2 && count < lowest) {
                lowest = count;
                wanted = species;
            }
        }
        boolean woolShort = colony().stockOf(level, id -> Ids.path(id).endsWith("_wool") || Ids.path(id).endsWith("_bed")) < 6;
        if (woolShort && herd.get(Species.SHEEP).size() < cap) {
            wanted = Species.SHEEP;
        }
        if (wanted == null) {
            return;
        }
        Animal wild = findWild(level, villager, pen, wanted);
        if (wild == null) {
            for (Species species : Species.values()) {
                if (herd.get(species).size() < 2) {
                    wild = findWild(level, villager, pen, species);
                    if (wild != null) {
                        break;
                    }
                }
            }
        }
        if (wild != null) {
            action = Action.CAPTURE;
            target = wild;
        } else {
            captureCooldownUntil = level.getGameTime() + 2400;
        }
    }

    private Animal findWild(ServerLevel level, Villager villager, ColonyData.Pen pen, Species species) {
        int radius = SmartColonyConfig.get().forestRadius;
        BlockPos center = colony().center();
        AABB area = new AABB(center).inflate(radius, 24, radius);
        return level.getEntitiesOfClass(Animal.class, area, a -> a.isAlive() && Species.of(a) == species && !a.isLeashed()
                        && !inside(pen, a.getX(), a.getZ()) && !colony().isProtectedAnimal(a) && !enclosed(level, a, pen)
                        && !a.isInWater() && level.getFluidState(a.blockPosition().below()).isEmpty())
                .stream()
                .min(Comparator.comparingDouble(a -> a.distanceToSqr(villager)))
                .orElse(null);
    }

    private static boolean enclosed(ServerLevel level, Animal animal, ColonyData.Pen pen) {
        BlockPos feet = animal.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-5, -1, -5), feet.offset(5, 1, 5))) {
            if (pos.getX() >= pen.min.x() - 1 && pos.getX() <= pen.max.x() + 1 && pos.getZ() >= pen.min.z() - 1 && pos.getZ() <= pen.max.z() + 1) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.is(net.minecraft.tags.BlockTags.FENCES) || state.is(net.minecraft.tags.BlockTags.FENCE_GATES)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasBaitFor(Villager villager, Animal animal) {
        return !Inv.find(villager, animal::isFood).isEmpty();
    }

    private boolean exit(ServerLevel level, Villager villager, ColonyData.Pen pen) {
        if (!inside(pen, villager.getX(), villager.getZ())) {
            setGate(level, pen, false);
            return true;
        }
        setGate(level, pen, true);
        status = "leaving the pen";
        BlockPos beyond = new BlockPos(pen.outside.x() * 2 - pen.gate.x(), pen.outside.y(), pen.outside.z() * 2 - pen.gate.z());
        if (move(level, villager, beyond, 1.0) == Mover.Result.FAILED) {
            Mover.rescue(level, villager, beyond);
        }
        return false;
    }

    private void setGate(ServerLevel level, ColonyData.Pen pen, boolean open) {
        BlockPos gate = WorldUtil.bp(pen.gate);
        if (!WorldUtil.loaded(level, gate)) {
            return;
        }
        BlockState state = level.getBlockState(gate);
        if (state.getBlock() instanceof FenceGateBlock && state.getValue(FenceGateBlock.OPEN) != open) {
            level.setBlock(gate, state.setValue(FenceGateBlock.OPEN, open), Block.UPDATE_CLIENTS | Block.UPDATE_IMMEDIATE);
            level.playSound(null, gate, open ? SoundEvents.FENCE_GATE_OPEN : SoundEvents.FENCE_GATE_CLOSE, SoundSource.BLOCKS, 1.0F, 1.0F);
        }
    }

    private void act(ServerLevel level, Villager villager, ColonyData.Pen pen) {
        if (action == null) {
            phase = "start";
            return;
        }
        switch (action) {
            case COLLECT -> collect(level, villager, pen);
            case SHEAR -> shear(level, villager, pen);
            case CULL -> cull(level, villager, pen);
            case BREED -> breed(level, villager, pen);
            case CAPTURE -> capture(level, villager, pen);
        }
    }

    private void done(ServerLevel level, Villager villager, ColonyData.Pen pen) {
        status = "finishing at the gate";
        if (exit(level, villager, pen)) {
            action = null;
            target = null;
            partner = null;
            phase = "start";
            wait = 20;
        }
    }

    private void collect(ServerLevel level, Villager villager, ColonyData.Pen pen) {
        if (!exit(level, villager, pen)) {
            return;
        }
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, penBox(pen), ItemEntity::isAlive);
        if (items.isEmpty() || Inv.free(villager) == 0) {
            done(level, villager, pen);
            return;
        }
        ItemEntity item = items.stream().min(Comparator.comparingDouble(i -> i.distanceToSqr(villager))).get();
        if (item.distanceTo(villager) < 4.0) {
            pickUp(villager, item);
        } else if (move(level, villager, item.blockPosition(), 3.5) == Mover.Result.FAILED) {
            done(level, villager, pen);
        }
    }

    private boolean approach(ServerLevel level, Villager villager, Animal animal, double reach) {
        if (animal == null || !animal.isAlive()) {
            return false;
        }
        if (villager.distanceTo(animal) <= reach) {
            villager.getNavigation().stop();
            villager.getLookControl().setLookAt(animal);
            return true;
        }
        ColonyData.Pen pen = colony().data().pen;
        BlockPos goal = animal.blockPosition();
        double moveReach = reach;
        if (pen != null && inside(pen, animal.getX(), animal.getZ())) {
            goal = ringPoint(pen, animal);
            moveReach = 1.0;
        }
        if (move(level, villager, goal, moveReach) == Mover.Result.FAILED) {
            target = null;
        }
        return false;
    }

    private static BlockPos ringPoint(ColonyData.Pen pen, Animal animal) {
        int x = animal.getBlockX();
        int z = animal.getBlockZ();
        int toWest = x - pen.min.x();
        int toEast = pen.max.x() - x;
        int toNorth = z - pen.min.z();
        int toSouth = pen.max.z() - z;
        int best = Math.min(Math.min(toWest, toEast), Math.min(toNorth, toSouth));
        if (best == toWest) {
            return new BlockPos(pen.min.x() - 2, pen.min.y(), z);
        }
        if (best == toEast) {
            return new BlockPos(pen.max.x() + 2, pen.min.y(), z);
        }
        if (best == toNorth) {
            return new BlockPos(x, pen.min.y(), pen.min.z() - 2);
        }
        return new BlockPos(x, pen.min.y(), pen.max.z() + 2);
    }

    private void shear(ServerLevel level, Villager villager, ColonyData.Pen pen) {
        if (!exit(level, villager, pen)) {
            return;
        }
        if (!(target instanceof Sheep sheep) || !sheep.isAlive() || !sheep.readyForShearing()) {
            done(level, villager, pen);
            return;
        }
        if (!approach(level, villager, sheep, 4.0)) {
            if (target == null) {
                done(level, villager, pen);
            }
            return;
        }
        if (!Tools.SHEARS.test(villager.getMainHandItem())) {
            ItemStack carried = Inv.takeFirst(villager, Tools.SHEARS);
            if (carried.isEmpty()) {
                done(level, villager, pen);
                return;
            }
            holdInHand(villager, carried);
        }
        ItemStack shears = villager.getMainHandItem();
        sheep.shear(level, SoundSource.NEUTRAL, shears);
        shears.hurtAndBreak(1, villager, EquipmentSlot.MAINHAND);
        colony().data().bump("sheep_sheared", 1);
        action = Action.COLLECT;
    }

    private void cull(ServerLevel level, Villager villager, ColonyData.Pen pen) {
        if (!exit(level, villager, pen)) {
            return;
        }
        if (target == null || !target.isAlive()) {
            action = Action.COLLECT;
            return;
        }
        if (!approach(level, villager, target, 4.0)) {
            if (target == null) {
                done(level, villager, pen);
            }
            return;
        }
        target.hurtServer(level, level.damageSources().mobAttack(villager), 1000.0F);
        lastCullDay = level.getDefaultClockTime() / 24000L;
        colony().data().bump("animals_culled", 1);
        action = Action.COLLECT;
        wait = 20;
    }

    private void breed(ServerLevel level, Villager villager, ColonyData.Pen pen) {
        if (!exit(level, villager, pen)) {
            return;
        }
        Animal current = target != null && target.isAlive() && !target.isInLove() ? target : partner;
        if (current == null || !current.isAlive() || current.isInLove()) {
            done(level, villager, pen);
            return;
        }
        if (!approach(level, villager, current, 4.0)) {
            if (target == null) {
                done(level, villager, pen);
            }
            return;
        }
        ItemStack bait = Inv.find(villager, current::isFood);
        if (bait.isEmpty() || !current.canFallInLove()) {
            done(level, villager, pen);
            return;
        }
        bait.shrink(1);
        current.setInLove(null);
        level.playSound(null, current.blockPosition(), SoundEvents.GENERIC_EAT.value(), SoundSource.NEUTRAL, 0.5F, 1.0F);
        if (current == partner || partner == null) {
            colony().data().bump("animals_bred", 1);
            done(level, villager, pen);
        } else {
            target = partner;
            partner = null;
        }
    }

    private void capture(ServerLevel level, Villager villager, ColonyData.Pen pen) {
        if (gateStep == 2) {
            done(level, villager, pen);
            return;
        }
        if (target == null || !target.isAlive()) {
            leading = false;
            action = null;
            phase = "start";
            return;
        }
        if (!leading) {
            if (!approach(level, villager, target, 2.5)) {
                if (target == null) {
                    phase = "start";
                }
                return;
            }
            if (target.isLeashed()) {
                target = null;
                phase = "start";
                return;
            }
            target.setLeashedTo(villager, true);
            leading = true;
            gateStep = 0;
            return;
        }
        if (!target.isLeashed() || target.getLeashHolder() != villager) {
            for (ItemEntity lead : level.getEntitiesOfClass(ItemEntity.class, target.getBoundingBox().inflate(4.0),
                    item -> item.getItem().is(Items.LEAD) && item.getAge() < 40)) {
                lead.discard();
            }
            leading = false;
            phase = "start";
            return;
        }
        status = "leading a " + Species.of(target).name().toLowerCase(java.util.Locale.ROOT) + " to the pen, step " + gateStep
                + ", animal at " + target.blockPosition().toShortString();
        if (gateStep == 0) {
            if (inside(pen, villager.getX(), villager.getZ())) {
                setGate(level, pen, true);
            }
            BlockPos outside = WorldUtil.bp(pen.outside);
            Mover.Result result = colonist.mover().tick(level, villager, outside, 1.3, 0.55, false);
            if (result == Mover.Result.ARRIVED) {
                setGate(level, pen, true);
                gateStep = 1;
            } else if (result == Mover.Result.FAILED) {
                target.removeLeash();
                leading = false;
                phase = "start";
            }
            return;
        }
        if (gateStep == 1) {
            BlockPos far = new BlockPos(pen.inside.x() + (pen.inside.x() - pen.gate.x()) * 2, pen.inside.y(),
                    pen.inside.z() + (pen.inside.z() - pen.gate.z()) * 2);
            Mover.Result result = colonist.mover().tick(level, villager, far, 1.3, 0.55, false);
            if (result == Mover.Result.ARRIVED && !inside(pen, target.getX(), target.getZ()) && ++pullTicks > 120) {
                target.removeLeash();
                leading = false;
                pullTicks = 0;
                captureCooldownUntil = level.getGameTime() + 1200;
                gateStep = 2;
                return;
            }
            if (inside(pen, target.getX(), target.getZ()) && target.distanceToSqr(pen.gate.x() + 0.5, target.getY(), pen.gate.z() + 0.5) > 2.25) {
                pullTicks = 0;
                target.removeLeash();
                leading = false;
                colony().data().bump("animals_captured", 1);
                gainXp(level, villager, 3);
                captureCooldownUntil = level.getGameTime() + 1200;
                colony().journal(level, "herder brought a " + Species.of(target).name().toLowerCase(java.util.Locale.ROOT) + " to the pen");
                gateStep = 2;
            } else if (result == Mover.Result.FAILED) {
                target.removeLeash();
                leading = false;
                setGate(level, pen, false);
                phase = "start";
            }
            return;
        }
        done(level, villager, pen);
    }

    private void deposit(ServerLevel level, Villager villager, ColonyData.Pen pen) {
        if (inside(pen, villager.getX(), villager.getZ()) && !exit(level, villager, pen)) {
            return;
        }
        if (!atDepot(level, villager)) {
            if (goDepot(level, villager) == Mover.Result.FAILED) {
                wait = 100;
            }
            return;
        }
        depositAll(level, villager);
        colony().cook(level);
        phase = "start";
    }

    @Override
    protected boolean hasCargo(Villager villager) {
        return Inv.count(villager, stack -> !keep(stack) && !ration(stack)) > 0;
    }
}
