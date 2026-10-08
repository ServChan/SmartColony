package org.lts.smartcolony.work;

import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import org.lts.smartcolony.core.Brewing;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.CraftPlanner;
import org.lts.smartcolony.core.Hospital;
import org.lts.smartcolony.core.Ids;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.core.Specialists;
import org.lts.smartcolony.core.Stock;
import org.lts.smartcolony.util.ItemIds;

public final class AlchemistJob extends Job {
    private static final int PREPARE_TICKS = 160;

    private BlockPos stand;
    private BlockPos water;
    private String goal;
    private String stage;
    private int progress;
    private String craftItem;
    private int craftCount;
    private long waitUntil;
    private long nextRequest;

    public AlchemistJob(Colonist colonist) {
        super(colonist);
        depositThreshold = 6;
    }

    @Override
    public Role role() {
        return Role.ALCHEMIST;
    }

    @Override
    protected Predicate<ItemStack> tools() {
        return stack -> false;
    }

    @Override
    protected boolean keep(ItemStack stack) {
        return stack.is(Items.GLASS_BOTTLE) || stack.is(Items.POTION) && !finished(stack);
    }

    @Override
    public void onRelease(ServerLevel level, Villager villager) {
        super.onRelease(level, villager);
        goal = null;
        stage = null;
        craftItem = null;
    }

    @Override
    public void tick(ServerLevel level, Villager villager) {
        if (wait > 0) {
            wait--;
            return;
        }
        switch (phase) {
            case "start" -> start(level, villager);
            case "water" -> water(level, villager);
            case "load" -> load(level, villager);
            case "brewing" -> brewing(level, villager);
            case "prepare" -> prepare(level, villager);
            case "deposit" -> deposit(level, villager);
            default -> phase = "start";
        }
    }

    static String potionOf(ItemStack stack) {
        return ItemIds.potion(stack);
    }

    private static boolean finished(ItemStack stack) {
        String potion = potionOf(stack);
        return !potion.isEmpty() && !potion.equals(Brewing.WATER) && !potion.equals(Brewing.AWKWARD);
    }

    private static ItemStack potion(String key) {
        Holder<Potion> holder = BuiltInRegistries.POTION.get(Identifier.withDefaultNamespace(key)).map(h -> (Holder<Potion>) h).orElse(null);
        return holder == null ? ItemStack.EMPTY : PotionContents.createItemStack(Items.POTION, holder);
    }

    private BlockPos findStand(ServerLevel level) {
        for (String kind : new String[] {Specialists.APOTHECARY, Hospital.KIND}) {
            for (ColonyData.Building building : colony().data().buildings) {
                if (!kind.equals(building.kind)) {
                    continue;
                }
                for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(building.min.x(), building.origin.y(), building.min.z()),
                        new BlockPos(building.max.x(), building.max.y(), building.max.z()))) {
                    if (level.getBlockState(pos).is(Blocks.BREWING_STAND)) {
                        return pos.immutable();
                    }
                }
            }
        }
        return null;
    }

    private int healingInStorage(ServerLevel level) {
        int total = 0;
        for (Container container : colony().warehouse().all(level)) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.is(Items.POTION) && Brewing.healing(potionOf(stack))) {
                    total += stack.getCount();
                }
            }
        }
        return total;
    }

    private void start(ServerLevel level, Villager villager) {
        if (hasCargo(villager) && (cargoFull(villager) || !onShift(level, villager))) {
            phase = "deposit";
            return;
        }
        if (!onShift(level, villager)) {
            wait = 100;
            return;
        }
        if (stand == null || !level.getBlockState(stand).is(Blocks.BREWING_STAND)) {
            stand = findStand(level);
        }
        if (stand != null && level.getBlockEntity(stand) instanceof BrewingStandBlockEntity entity && hasOutput(entity)) {
            phase = "brewing";
            return;
        }
        Stock stock = colony().warehouse().stock(level);
        int guards = colony().desired().getOrDefault(Role.GUARD, 0);
        int target = Brewing.healingTarget(guards, colony().adultCount(level));
        boolean fuel = stock.get(Brewing.FUEL) > 0 || stand != null && fuelLeft(level);
        int healing = healingInStorage(level);
        if (healing < target && stock.get(Ids.mc("glistering_melon_slice")) == 0
                && CraftPlanner.plan(stock, Ids.mc("glistering_melon_slice"), 1).isPresent()) {
            startPrepare(Ids.mc("glistering_melon_slice"), 1);
            return;
        }
        String wanted = stand == null ? null : Brewing.wanted(stock, healing, target, fuel);
        if (wanted != null) {
            goal = wanted;
            stage = Brewing.finalStep(wanted).base().equals(Brewing.AWKWARD) ? Brewing.AWKWARD : wanted;
            phase = "water";
            return;
        }
        if (healing < target && level.getGameTime() >= nextRequest) {
            nextRequest = level.getGameTime() + 6000;
            if (Brewing.needsWart(stock)) {
                colony().request(Brewing.WART);
            }
            if (!fuel) {
                colony().request(Brewing.FUEL);
            }
            if (stock.get(Ids.mc("glistering_melon_slice")) == 0) {
                colony().request(Ids.mc("melon_slice"));
            }
        }
        int adults = colony().adultCount(level);
        if (stock.get(Hospital.BANDAGE) < Hospital.bandageTarget(adults) && CraftPlanner.plan(stock, Hospital.BANDAGE, 3).isPresent()) {
            startPrepare(Hospital.BANDAGE, 3);
            return;
        }
        if (stock.get(Hospital.REMEDY) < Hospital.remedyTarget(adults) && CraftPlanner.plan(stock, Hospital.REMEDY, 1).isPresent()) {
            startPrepare(Hospital.REMEDY, 1);
            return;
        }
        if (stock.get(Ids.mc("glass_bottle")) < 3 && CraftPlanner.plan(stock, Ids.mc("glass_bottle"), 3).isPresent()) {
            startPrepare(Ids.mc("glass_bottle"), 3);
            return;
        }
        status = "nothing to brew";
        idle(level, 600);
    }

    private boolean fuelLeft(ServerLevel level) {
        return level.getBlockEntity(stand) instanceof BrewingStandBlockEntity entity && !entity.getItem(4).isEmpty();
    }

    private static boolean hasOutput(BrewingStandBlockEntity entity) {
        for (int slot = 0; slot < 3; slot++) {
            if (!entity.getItem(slot).isEmpty() && entity.getItem(3).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void startPrepare(String item, int count) {
        craftItem = item;
        craftCount = count;
        progress = PREPARE_TICKS;
        phase = "prepare";
    }

    private void water(ServerLevel level, Villager villager) {
        int bottles = Inv.count(villager, stack -> stack.is(Items.GLASS_BOTTLE));
        int filled = Inv.count(villager, stack -> stack.is(Items.POTION) && Brewing.WATER.equals(potionOf(stack)));
        if (filled >= 3) {
            phase = "load";
            return;
        }
        if (bottles + filled < 3) {
            if (!atDepot(level, villager)) {
                status = "fetching bottles";
                if (goDepot(level, villager) == Mover.Result.FAILED) {
                    phase = "start";
                    wait = 100;
                }
                return;
            }
            supply(level, villager, Ids.mc("glass_bottle"), 3 - filled, 3 - filled);
            if (Inv.count(villager, stack -> stack.is(Items.GLASS_BOTTLE)) + filled < 3) {
                colony().request(Ids.mc("glass_bottle"));
                phase = "start";
                idle(level, 1200);
            }
            return;
        }
        if (water == null || !level.getFluidState(water).isSource()) {
            water = colony().findWater(level, stand != null ? stand : colony().center(), 32);
            if (water == null) {
                status = "no water nearby";
                phase = "start";
                idle(level, 1200);
                return;
            }
        }
        status = "filling bottles";
        if (Mover.distance(villager, water) > 3.0) {
            if (move(level, villager, water, 2.5) == Mover.Result.FAILED) {
                water = null;
                phase = "start";
                wait = 100;
            }
            return;
        }
        int convert = Math.min(3 - filled, Inv.remove(villager, stack -> stack.is(Items.GLASS_BOTTLE), 3 - filled));
        for (int i = 0; i < convert; i++) {
            Inv.add(villager, potion(Brewing.WATER));
        }
        level.playSound(null, villager.blockPosition(), SoundEvents.BOTTLE_FILL, SoundSource.NEUTRAL, 0.8F, 1.0F);
        phase = "load";
    }

    private void load(ServerLevel level, Villager villager) {
        if (stand == null || !(level.getBlockEntity(stand) instanceof BrewingStandBlockEntity entity)) {
            phase = "start";
            return;
        }
        status = "loading the brewing stand";
        if (Mover.distance(villager, stand) > 3.0) {
            if (move(level, villager, stand, 2.5) == Mover.Result.FAILED) {
                phase = "start";
                wait = 100;
            }
            return;
        }
        Brewing.Step step = stage.equals(Brewing.AWKWARD) ? Brewing.awkwardStep() : Brewing.finalStep(goal);
        String basePotion = step.base();
        int bases = Inv.count(villager, stack -> stack.is(Items.POTION) && basePotion.equals(potionOf(stack)));
        if (bases < 1) {
            phase = basePotion.equals(Brewing.WATER) ? "water" : "start";
            return;
        }
        if (colony().warehouse().take(level, step.ingredient(), 1) < 1) {
            colony().request(step.ingredient());
            phase = "start";
            idle(level, 600);
            return;
        }
        if (entity.getItem(4).isEmpty() && colony().warehouse().take(level, Brewing.FUEL, 1) == 1) {
            entity.setItem(4, new ItemStack(Items.BLAZE_POWDER));
        }
        for (int slot = 0; slot < 3; slot++) {
            if (!entity.getItem(slot).isEmpty()) {
                ItemStack old = entity.getItem(slot);
                entity.setItem(slot, ItemStack.EMPTY);
                stash(level, villager, java.util.List.of(old));
            }
        }
        int placed = 0;
        for (int slot = 0; slot < 3 && placed < 3; slot++) {
            ItemStack base = Inv.takeFirst(villager, stack -> stack.is(Items.POTION) && basePotion.equals(potionOf(stack)));
            if (base.isEmpty()) {
                break;
            }
            entity.setItem(slot, base);
            placed++;
        }
        entity.setItem(3, ItemIds.stack(step.ingredient(), 1));
        villager.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
        waitUntil = level.getGameTime() + Brewing.BREW_TICKS;
        phase = "brewing";
    }

    private void brewing(ServerLevel level, Villager villager) {
        if (stand == null || !(level.getBlockEntity(stand) instanceof BrewingStandBlockEntity entity)) {
            phase = "start";
            return;
        }
        status = "watching the brew";
        if (Mover.distance(villager, stand) > 3.5) {
            if (move(level, villager, stand, 2.5) == Mover.Result.FAILED) {
                phase = "start";
                wait = 100;
            }
            return;
        }
        villager.getNavigation().stop();
        villager.getLookControl().setLookAt(stand.getX() + 0.5, stand.getY() + 0.7, stand.getZ() + 0.5);
        if (!entity.getItem(3).isEmpty()) {
            if (level.getGameTime() > waitUntil + 200 && entity.getItem(4).isEmpty()) {
                stash(level, villager, java.util.List.of(entity.getItem(3)));
                entity.setItem(3, ItemStack.EMPTY);
                colony().request(Brewing.FUEL);
                phase = "start";
            }
            if (level.getGameTime() % 40 == 0) {
                level.sendParticles(ParticleTypes.WITCH, stand.getX() + 0.5, stand.getY() + 1.0, stand.getZ() + 0.5, 3, 0.2, 0.2, 0.2, 0.0);
            }
            return;
        }
        boolean awkward = false;
        for (int slot = 0; slot < 3; slot++) {
            ItemStack stack = entity.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (Brewing.AWKWARD.equals(potionOf(stack)) && goal != null && Brewing.AWKWARD.equals(stage)) {
                awkward = true;
            }
            entity.setItem(slot, ItemStack.EMPTY);
            stash(level, villager, java.util.List.of(stack));
            if (finished(stack)) {
                colony().data().bump("potions_brewed", 1);
            }
        }
        gainXp(level, villager, 3);
        if (awkward) {
            stage = goal;
            phase = "load";
            return;
        }
        goal = null;
        stage = null;
        phase = "deposit";
    }

    private void prepare(ServerLevel level, Villager villager) {
        BlockPos post = stand != null ? stand : colony().depot();
        status = "preparing " + Ids.path(craftItem);
        if (Mover.distance(villager, post) > 3.0) {
            if (move(level, villager, post, 2.5) == Mover.Result.FAILED) {
                post = colony().depot();
                if (Mover.distance(villager, post) > 3.0) {
                    phase = "start";
                    wait = 100;
                    return;
                }
            } else {
                return;
            }
        }
        villager.getLookControl().setLookAt(post.getX() + 0.5, post.getY() + 0.7, post.getZ() + 0.5);
        if (progress % 20 == 0) {
            villager.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
            level.playSound(null, villager.blockPosition(), SoundEvents.BREWING_STAND_BREW, SoundSource.NEUTRAL, 0.4F, 1.2F);
        }
        if (--progress > 0) {
            return;
        }
        if (colony().craft(level, craftItem, craftCount)) {
            gainXp(level, villager, 2);
            colony().data().bump("alchemist_prepared", craftCount);
        }
        craftItem = null;
        phase = "start";
        wait = 20;
    }

    private void deposit(ServerLevel level, Villager villager) {
        if (!atDepot(level, villager)) {
            if (goDepot(level, villager) == Mover.Result.FAILED) {
                wait = 100;
            }
            return;
        }
        depositAll(level, villager);
        phase = "start";
    }
}
