package org.lts.smartcolony.work;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.lts.smartcolony.SmartColonyConfig;
import org.lts.smartcolony.core.Ids;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.util.ItemIds;
import org.lts.smartcolony.util.WorldUtil;

public final class LumberjackJob extends Job {
    private BlockPos tree;
    private List<BlockPos> logs = new ArrayList<>();
    private List<BlockPos> leaves = new ArrayList<>();
    private int index;
    private String woodType;
    private int collectTicks;
    private long noAxeUntil;
    private int planted;
    private long plantDay = -1;
    private long plantPauseUntil;

    public LumberjackJob(Colonist colonist) {
        super(colonist);
        depositThreshold = 6;
    }

    @Override
    public Role role() {
        return Role.LUMBERJACK;
    }

    @Override
    protected java.util.function.Predicate<ItemStack> tools() {
        return Tools.AXE;
    }

    @Override
    protected boolean keep(ItemStack stack) {
        return tools().test(stack);
    }

    @Override
    public boolean wantsControl(ServerLevel level, Villager villager) {
        if (hasCargo(villager)) {
            return true;
        }
        return colony().workTime(level) && !colony().woodSurplus(level);
    }

    @Override
    public void onRelease(ServerLevel level, Villager villager) {
        super.onRelease(level, villager);
        colony().forest().release(colonist.uuid());
        tree = null;
    }

    @Override
    public void tick(ServerLevel level, Villager villager) {
        if (wait > 0) {
            wait--;
            return;
        }
        switch (phase) {
            case "start" -> start(level, villager);
            case "walk" -> walk(level, villager);
            case "chop" -> chop(level, villager);
            case "settle" -> settle(level);
            case "leaves" -> leaves(level, villager);
            case "collect" -> collect(level, villager);
            case "replant" -> replant(level, villager);
            case "deposit" -> deposit(level, villager);
            case "plant" -> plantTrip(level, villager);
            default -> phase = "start";
        }
    }

    private void start(ServerLevel level, Villager villager) {
        int carried = Inv.count(villager, stack -> stack.is(ItemTags.LOGS));
        boolean storageHungry = colony().stockOf(level, Ids::isLog) < 16 && carried >= 8;
        if (cargoFull(villager) || carried >= 64 || storageHungry || (!colony().workTime(level) && hasCargo(villager))) {
            phase = "deposit";
            return;
        }
        if (level.getGameTime() >= noAxeUntil) {
            Equip equip = equip(level, villager, Tools.AXE, Tools.AXES);
            if (equip == Equip.BUSY) {
                return;
            }
            if (equip == Equip.MISSING) {
                noAxeUntil = level.getGameTime() + 600;
            }
        }
        long day = level.getGameTime() / 24000L;
        if (day != plantDay) {
            plantDay = day;
            planted = 0;
        }
        if (colony().forest().count() < SmartColonyConfig.get().treeTarget && planted < 8
                && level.getGameTime() >= plantPauseUntil
                && (Inv.count(villager, stack -> stack.is(ItemTags.SAPLINGS)) > 0 || colony().stockOf(level, Ids::isSapling) > 0)) {
            phase = "plant";
            return;
        }
        tree = colony().forest().claim(level, colonist.uuid(), villager.blockPosition());
        if (tree == null) {
            status = "looking for natural trees";
            if (hasCargo(villager)) {
                phase = "deposit";
                return;
            }
            wait = 100;
            return;
        }
        status = "tree at " + tree.toShortString();
        phase = "walk";
    }

    private void walk(ServerLevel level, Villager villager) {
        Mover.Result result = move(level, villager, tree, 2.6);
        if (result == Mover.Result.FAILED) {
            colony().forest().reject(level, tree);
            phase = "start";
            return;
        }
        if (result == Mover.Result.ARRIVED) {
            logs = TreeScan.naturalTree(level, tree);
            if (logs.isEmpty()) {
                colony().forest().felled(tree);
                phase = "start";
                return;
            }
            woodType = Ids.woodOf(ItemIds.id(level.getBlockState(tree).getBlock().asItem()));
            index = 0;
            phase = "chop";
        }
    }

    private void chop(ServerLevel level, Villager villager) {
        while (index < logs.size() && !TreeScan.isLog(level.getBlockState(logs.get(index)))) {
            index++;
        }
        if (index >= logs.size()) {
            colony().forest().felled(tree);
            colony().data().bump("trees_felled", 1);
            wait = 30;
            phase = "settle";
            return;
        }
        BlockPos log = logs.get(index);
        if (log.distSqr(villager.blockPosition()) > 100) {
            Mover.Result result = move(level, villager, new BlockPos(log.getX(), tree.getY(), log.getZ()), 3.0);
            if (result != Mover.Result.FAILED) {
                return;
            }
        }
        Digger.Result result = colonist.digger().tick(level, villager, log, true);
        if (result == Digger.Result.DONE) {
            stash(level, villager, colonist.digger().takeDrops());
            colony().data().bump("logs", 1);
            index++;
        } else if (result == Digger.Result.FAILED) {
            index++;
        }
    }

    private void settle(ServerLevel level) {
        leaves = new ArrayList<>();
        for (BlockPos leaf : TreeScan.canopy(level, logs)) {
            BlockState state = level.getBlockState(leaf);
            if (TreeScan.isNaturalLeaf(state) && state.getValue(LeavesBlock.DISTANCE) >= LeavesBlock.DECAY_DISTANCE) {
                leaves.add(leaf);
            }
        }
        index = 0;
        phase = "leaves";
    }

    private void leaves(ServerLevel level, Villager villager) {
        int budget = 3;
        while (budget-- > 0 && index < leaves.size()) {
            BlockPos leaf = leaves.get(index++);
            BlockState state = level.getBlockState(leaf);
            if (!TreeScan.isNaturalLeaf(state)) {
                continue;
            }
            colonist.digger().breakNow(level, villager, leaf, true);
            stash(level, villager, colonist.digger().takeDrops());
        }
        if (index >= leaves.size()) {
            collectTicks = 120;
            phase = "collect";
        }
    }

    private void collect(ServerLevel level, Villager villager) {
        if (collectTicks-- <= 0 || Inv.free(villager) == 0) {
            phase = "replant";
            return;
        }
        ItemEntity loot = nearestLoot(level, villager, 7.0, stack -> stack.is(ItemTags.LOGS) || stack.is(ItemTags.SAPLINGS)
                || stack.is(net.minecraft.world.item.Items.APPLE) || stack.is(net.minecraft.world.item.Items.STICK));
        if (loot == null) {
            phase = "replant";
            return;
        }
        if (loot.distanceTo(villager) < 1.6) {
            pickUp(villager, loot);
        } else {
            move(level, villager, loot.blockPosition(), 1.2);
        }
    }

    private void replant(ServerLevel level, Villager villager) {
        phase = "start";
        if (tree == null) {
            return;
        }
        BlockPos spot = tree;
        if (!WorldUtil.isAirLike(level.getBlockState(spot)) || !level.getBlockState(spot.below()).is(BlockTags.DIRT)) {
            return;
        }
        ItemStack sapling = Inv.find(villager, stack -> stack.is(ItemTags.SAPLINGS)
                && (woodType == null || Ids.path(ItemIds.id(stack)).startsWith(woodType + "_")) && !ItemIds.id(stack).contains("dark_oak"));
        if (sapling.isEmpty()) {
            sapling = Inv.find(villager, stack -> stack.is(ItemTags.SAPLINGS) && !ItemIds.id(stack).contains("dark_oak"));
        }
        if (plantSapling(level, villager, spot, sapling)) {
            colony().data().bump("saplings_planted", 1);
        }
        colony().forest().release(colonist.uuid());
        tree = null;
    }

    private boolean plantSapling(ServerLevel level, Villager villager, BlockPos spot, ItemStack sapling) {
        if (sapling.isEmpty() || !(sapling.getItem() instanceof BlockItem blockItem)) {
            return false;
        }
        Block block = blockItem.getBlock();
        BlockState state = block.defaultBlockState();
        if (!state.canSurvive(level, spot)) {
            return false;
        }
        villager.getLookControl().setLookAt(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
        level.setBlock(spot, state, Block.UPDATE_ALL);
        level.playSound(null, spot, SoundEvents.GRASS_PLACE, SoundSource.BLOCKS, 1.0F, 1.0F);
        sapling.shrink(1);
        colony().rememberPlanted(spot);
        return true;
    }

    private BlockPos plantSpot;
    private int plantTries;

    private void plantTrip(ServerLevel level, Villager villager) {
        if (Inv.count(villager, stack -> stack.is(ItemTags.SAPLINGS)) == 0) {
            if (!atDepot(level, villager)) {
                if (goDepot(level, villager) == Mover.Result.FAILED) {
                    phase = "start";
                    plantPauseUntil = level.getGameTime() + 3000;
                }
                return;
            }
            if (supply(level, villager, stack -> stack.is(ItemTags.SAPLINGS) && !ItemIds.id(stack).contains("dark_oak"), 1, 6) == 0) {
                plantPauseUntil = level.getGameTime() + 3000;
                phase = "start";
                return;
            }
        }
        if (plantSpot == null) {
            plantSpot = colony().findPlantingSpot(level, level.getRandom());
            plantTries++;
            if (plantSpot == null) {
                if (plantTries > 10) {
                    plantPauseUntil = level.getGameTime() + 3000;
                    phase = "start";
                }
                return;
            }
        }
        Mover.Result result = move(level, villager, plantSpot, 2.2);
        if (result == Mover.Result.FAILED) {
            plantSpot = null;
            return;
        }
        if (result == Mover.Result.ARRIVED) {
            ItemStack sapling = Inv.find(villager, stack -> stack.is(ItemTags.SAPLINGS));
            if (plantSapling(level, villager, plantSpot, sapling)) {
                planted++;
                colony().data().bump("saplings_planted", 1);
            }
            plantSpot = null;
            plantTries = 0;
            if (planted >= 4 || Inv.count(villager, stack -> stack.is(ItemTags.SAPLINGS)) == 0) {
                phase = "start";
            }
        }
    }

    private void deposit(ServerLevel level, Villager villager) {
        if (!atDepot(level, villager)) {
            if (goDepot(level, villager) == Mover.Result.FAILED) {
                status = "cannot reach storage";
                wait = 100;
            }
            return;
        }
        depositAll(level, villager);
        phase = "start";
    }
}
