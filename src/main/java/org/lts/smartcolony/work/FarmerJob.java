package org.lts.smartcolony.work;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import org.lts.smartcolony.core.Ids;
import org.lts.smartcolony.core.Pos;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.util.WorldUtil;

public final class FarmerJob extends Job {
    private static final List<String> SEEDS = List.of(
            Ids.mc("wheat_seeds"), Ids.mc("carrot"), Ids.mc("potato"), Ids.mc("beetroot_seeds"));

    private enum Kind {
        HARVEST,
        PLANT,
        TILL,
        GATHER_SEEDS
    }

    private record Task(Kind kind, BlockPos pos, boolean fallow) {
    }

    private Task task;
    private long noHoeUntil;
    private BlockPos pondSite;
    private BlockPos waterSource;
    private int idleScans;
    private boolean freeCells;

    public FarmerJob(Colonist colonist) {
        super(colonist);
        depositThreshold = 6;
    }

    @Override
    public Role role() {
        return Role.FARMER;
    }

    @Override
    protected java.util.function.Predicate<ItemStack> tools() {
        return Tools.HOE;
    }

    @Override
    protected boolean keep(ItemStack stack) {
        return tools().test(stack) || stack.is(Items.BUCKET) || stack.is(Items.WATER_BUCKET);
    }

    @Override
    public void tick(ServerLevel level, Villager villager) {
        if (wait > 0) {
            wait--;
            return;
        }
        switch (phase) {
            case "start" -> start(level, villager);
            case "find" -> find(level, villager);
            case "work" -> work(level, villager);
            case "pond" -> pond(level, villager);
            case "deposit" -> deposit(level, villager);
            default -> phase = "start";
        }
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
        if (level.getGameTime() >= noHoeUntil) {
            Equip equip = equip(level, villager, Tools.HOE, Tools.HOES);
            if (equip == Equip.BUSY) {
                return;
            }
            if (equip == Equip.MISSING) {
                noHoeUntil = level.getGameTime() + 600;
            }
        }
        if (seedCount(villager) < 8) {
            if (!atDepot(level, villager)) {
                if (goDepot(level, villager) == Mover.Result.FAILED) {
                    wait = 100;
                }
                return;
            }
            for (String seed : SEEDS) {
                supply(level, villager, seed, 4, 16);
            }
        }
        if (colony().data().farms.isEmpty()) {
            colony().discoverFarms(level);
        }
        phase = "find";
    }

    private int seedCount(Villager villager) {
        int total = 0;
        for (String seed : SEEDS) {
            total += Inv.count(villager, seed);
        }
        return total;
    }

    private void find(ServerLevel level, Villager villager) {
        if (cargoFull(villager) || !onShift(level, villager)) {
            phase = hasCargo(villager) ? "deposit" : "start";
            return;
        }
        task = nextTask(level, villager);
        if (task != null) {
            idleScans = 0;
            phase = "work";
            return;
        }
        idleScans++;
        if (colony().wantsMoreFood(level) && idleScans >= 2 && !freeCells && colony().fields().mayAddField(level)) {
            if (colony().expandFarms(level)) {
                status = "found new land near water";
                return;
            }
            if (Tools.HOE.test(villager.getMainHandItem())) {
                pondSite = colony().findPondSite(level);
                waterSource = pondSite == null ? null : colony().findWater(level, pondSite, 48);
                if (pondSite != null && waterSource != null && colony().craftOrTake(level, villager, Ids.mc("bucket"))) {
                    phase = "pond";
                    return;
                }
                pondSite = null;
                waterSource = null;
            }
        }
        if (hasCargo(villager) && idleScans > 2) {
            phase = "deposit";
            return;
        }
        status = "fields are tended";
        idle(level, 400);
    }

    private Task nextTask(ServerLevel level, Villager villager) {
        Task best = null;
        double bestDistance = Double.MAX_VALUE;
        boolean hasSeeds = seedCount(villager) > 0;
        boolean hasHoe = Tools.HOE.test(villager.getMainHandItem());
        boolean expand = colony().wantsMoreFood(level);
        List<Pos> anchors = colony().data().farms;
        boolean[] fallow = colony().fields().fallow(level);
        freeCells = false;
        for (int i = 0; i < anchors.size(); i++) {
            boolean idle = fallow[i];
            BlockPos center = WorldUtil.bp(anchors.get(i));
            if (!WorldUtil.loaded(level, center)) {
                continue;
            }
            boolean farmland = false;
            List<BlockPos> seeds = new ArrayList<>();
            for (int dx = -5; dx <= 5; dx++) {
                for (int dz = -5; dz <= 5; dz++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        BlockPos pos = center.offset(dx, dy, dz);
                        BlockState state = level.getBlockState(pos);
                        if (state.getBlock() instanceof FarmlandBlock) {
                            farmland = true;
                        }
                        Kind kind = classify(level, pos, state, hasSeeds && !idle, expand && !idle);
                        if (kind == null && expand && !idle && seedCell(level, pos, state)) {
                            seeds.add(pos.immutable());
                        }
                        if (kind == Kind.TILL) {
                            freeCells = true;
                            if (!hasHoe) {
                                continue;
                            }
                        }
                        if (kind == null) {
                            continue;
                        }
                        double distance = pos.distSqr(villager.blockPosition()) + (kind == Kind.HARVEST ? 0 : 20);
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = new Task(kind, pos.immutable(), idle);
                        }
                    }
                }
            }
            if (!farmland && !seeds.isEmpty()) {
                freeCells = true;
                if (hasHoe) {
                    for (BlockPos pos : seeds) {
                        double distance = pos.distSqr(villager.blockPosition()) + 20;
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = new Task(Kind.TILL, pos, false);
                        }
                    }
                }
            }
        }
        if (best == null && !hasSeeds && colony().stockOf(level, id -> SEEDS.contains(id)) == 0) {
            BlockPos grass = colony().findGrass(level, villager.blockPosition());
            if (grass != null) {
                best = new Task(Kind.GATHER_SEEDS, grass, false);
            }
        }
        return best;
    }

    private Kind classify(ServerLevel level, BlockPos pos, BlockState state, boolean hasSeeds, boolean canTill) {
        if (state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)) {
            return Kind.HARVEST;
        }
        if (state.getBlock() instanceof FarmlandBlock) {
            if (hasSeeds && level.getBlockState(pos.above()).isAir()) {
                return Kind.PLANT;
            }
            return null;
        }
        if (canTill && tillableGround(level, pos, state) && nearWater(level, pos) && nextToFarmland(level, pos)) {
            return Kind.TILL;
        }
        return null;
    }

    private boolean tillableGround(ServerLevel level, BlockPos pos, BlockState state) {
        return (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT)) && WorldUtil.isAirLike(level.getBlockState(pos.above()))
                && !colony().isProtected(pos.above(), false) && colony().fields().tillable(level, pos);
    }

    private boolean seedCell(ServerLevel level, BlockPos pos, BlockState state) {
        if (!(state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT)) || !WorldUtil.isAirLike(level.getBlockState(pos.above()))) {
            return false;
        }
        boolean wet = false;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (level.getBlockState(pos.relative(direction)).getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
                wet = true;
                break;
            }
        }
        return wet && tillableGround(level, pos, state);
    }

    private static boolean nearWater(ServerLevel level, BlockPos pos) {
        for (BlockPos check : BlockPos.betweenClosed(pos.offset(-4, 0, -4), pos.offset(4, 1, 4))) {
            if (level.getFluidState(check).is(net.minecraft.tags.FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }

    private static boolean nextToFarmland(ServerLevel level, BlockPos pos) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (level.getBlockState(pos.relative(direction)).getBlock() instanceof FarmlandBlock) {
                return true;
            }
        }
        return false;
    }

    private void work(ServerLevel level, Villager villager) {
        Mover.Result result = move(level, villager, task.pos().above(), 2.2);
        if (result == Mover.Result.FAILED) {
            task = null;
            phase = "find";
            wait = 20;
            return;
        }
        if (result != Mover.Result.ARRIVED) {
            return;
        }
        BlockPos pos = task.pos();
        switch (task.kind()) {
            case HARVEST -> harvest(level, villager, pos, task.fallow());
            case PLANT -> plant(level, villager, pos);
            case TILL -> till(level, villager, pos);
            case GATHER_SEEDS -> {
                if (WorldUtil.isAirLike(level.getBlockState(pos.above())) && !level.getBlockState(pos.above()).isAir()) {
                    colonist.digger().breakNow(level, villager, pos.above(), true);
                }
                stash(level, villager, colonist.digger().takeDrops());
            }
        }
        task = null;
        wait = 8;
        phase = "find";
    }

    private void harvest(ServerLevel level, Villager villager, BlockPos pos, boolean fallow) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof CropBlock crop) || !crop.isMaxAge(state)) {
            return;
        }
        colonist.digger().breakNow(level, villager, pos, true);
        List<ItemStack> drops = colonist.digger().takeDrops();
        Block cropBlock = crop;
        for (ItemStack drop : drops) {
            if (!fallow && drop.getItem() instanceof BlockItem blockItem && blockItem.getBlock() == cropBlock && !drop.isEmpty()) {
                BlockState planted = cropBlock.defaultBlockState();
                if (planted.canSurvive(level, pos)) {
                    level.setBlock(pos, planted, Block.UPDATE_ALL);
                    drop.shrink(1);
                }
                break;
            }
        }
        stash(level, villager, drops);
        colony().data().bump("crops_harvested", 1);
        gainXp(level, villager, 1);
    }

    private void plant(ServerLevel level, Villager villager, BlockPos farmland) {
        BlockPos above = farmland.above();
        if (!level.getBlockState(above).isAir()) {
            return;
        }
        for (String seed : colony().preferredSeeds(level, SEEDS)) {
            ItemStack stack = Inv.find(villager, s -> org.lts.smartcolony.util.ItemIds.id(s).equals(seed));
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
                continue;
            }
            BlockState planted = blockItem.getBlock().defaultBlockState();
            if (!planted.canSurvive(level, above)) {
                continue;
            }
            Placer.place(level, villager, above, planted);
            stack.shrink(1);
            colony().data().bump("crops_planted", 1);
            return;
        }
    }

    private void till(ServerLevel level, Villager villager, BlockPos pos) {
        BlockState above = level.getBlockState(pos.above());
        if (!above.isAir()) {
            if (!WorldUtil.isAirLike(above)) {
                return;
            }
            colonist.digger().breakNow(level, villager, pos.above(), true);
            stash(level, villager, colonist.digger().takeDrops());
        }
        level.setBlock(pos, Blocks.FARMLAND.defaultBlockState(), Block.UPDATE_ALL);
        level.playSound(null, pos, SoundEvents.HOE_TILL.value(), SoundSource.BLOCKS, 1.0F, 1.0F);
        villager.getMainHandItem().hurtAndBreak(1, villager, net.minecraft.world.entity.EquipmentSlot.MAINHAND);
        colony().data().bump("land_tilled", 1);
    }

    private void pond(ServerLevel level, Villager villager) {
        if (pondSite == null || waterSource == null) {
            phase = "find";
            return;
        }
        boolean full = Inv.count(villager, stack -> stack.is(Items.WATER_BUCKET)) > 0;
        if (!full) {
            Mover.Result result = move(level, villager, waterSource, 2.5);
            if (result == Mover.Result.FAILED) {
                pondSite = null;
                phase = "find";
                return;
            }
            if (result == Mover.Result.ARRIVED) {
                if (level.getFluidState(waterSource).isSourceOfType(Fluids.WATER)
                        && Inv.remove(villager, stack -> stack.is(Items.BUCKET), 1) == 1) {
                    Inv.add(villager, new ItemStack(Items.WATER_BUCKET));
                    level.playSound(null, waterSource, SoundEvents.BUCKET_FILL, SoundSource.NEUTRAL, 1.0F, 1.0F);
                } else {
                    pondSite = null;
                    phase = "find";
                }
            }
            return;
        }
        Mover.Result result = move(level, villager, pondSite.above(), 2.5);
        if (result == Mover.Result.FAILED) {
            pondSite = null;
            phase = "find";
            return;
        }
        if (result == Mover.Result.ARRIVED) {
            if (!level.getBlockState(pondSite.above()).isAir()) {
                colonist.digger().breakNow(level, villager, pondSite.above(), false);
            }
            colonist.digger().breakNow(level, villager, pondSite, true);
            stash(level, villager, colonist.digger().takeDrops());
            level.setBlock(pondSite, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
            level.playSound(null, pondSite, SoundEvents.BUCKET_EMPTY, SoundSource.NEUTRAL, 1.0F, 1.0F);
            Inv.remove(villager, stack -> stack.is(Items.WATER_BUCKET), 1);
            Inv.add(villager, new ItemStack(Items.BUCKET));
            colony().addFarm(pondSite);
            colony().journal(level, "new irrigated field at " + pondSite.toShortString());
            pondSite = null;
            phase = "find";
        }
    }

    private void deposit(ServerLevel level, Villager villager) {
        if (!atDepot(level, villager)) {
            if (goDepot(level, villager) == Mover.Result.FAILED) {
                wait = 100;
            }
            return;
        }
        depositAll(level, villager);
        int buckets = Inv.remove(villager, stack -> stack.is(Items.BUCKET), 64);
        if (buckets > 0) {
            colony().warehouse().give(level, Ids.mc("bucket"), buckets);
        }
        colony().cook(level);
        phase = "start";
    }

    @Override
    protected boolean hasCargo(Villager villager) {
        return Inv.count(villager, stack -> !keep(stack) && !isSeed(stack) && !ration(stack)) > 0;
    }

    private static boolean isSeed(ItemStack stack) {
        return stack.is(Items.WHEAT_SEEDS) || stack.is(Items.CARROT) || stack.is(Items.POTATO) || stack.is(Items.BEETROOT_SEEDS);
    }
}
