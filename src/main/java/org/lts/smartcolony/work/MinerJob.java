package org.lts.smartcolony.work;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.Ids;
import org.lts.smartcolony.core.DigPlan;
import org.lts.smartcolony.core.MinePlan;
import org.lts.smartcolony.core.Pos;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.util.ItemIds;
import org.lts.smartcolony.util.WorldUtil;

public final class MinerJob extends Job {
    private static final int CELLS_PER_TRIP = 48;
    private List<Pos> route = List.of();
    private int waypoint;
    private int cellsThisTrip;
    private List<BlockPos> vein = new ArrayList<>();
    private int veinIndex;
    private int stuck;
    private boolean returning;
    private int fluidFights;
    private int cellIndex = -1;
    private int cellTicks;
    private int skippedInRow;

    public MinerJob(Colonist colonist) {
        super(colonist);
        depositThreshold = 7;
    }

    @Override
    public Role role() {
        return Role.MINER;
    }

    @Override
    protected java.util.function.Predicate<ItemStack> tools() {
        return Tools.PICKAXE;
    }

    @Override
    protected boolean keep(ItemStack stack) {
        return tools().test(stack) || stack.is(Items.TORCH);
    }

    @Override
    protected boolean hasCargo(Villager villager) {
        return Inv.count(villager, stack -> !Tools.ANY_TOOL.test(stack) && !stack.is(Items.TORCH) && !stack.is(Items.COBBLESTONE) && !ration(stack)) > 0;
    }

    public boolean underground(Villager villager) {
        ColonyData.Mine mine = colony().data().mine;
        return mine != null && villager.getY() < mine.entrance.y() - 2;
    }

    @Override
    public boolean wantsControl(ServerLevel level, Villager villager) {
        return colony().workTime(level) || hasCargo(villager) || underground(villager);
    }

    public void danger() {
        if (!returning) {
            beginReturn();
        }
    }

    @Override
    public void tick(ServerLevel level, Villager villager) {
        if (wait > 0) {
            wait--;
            return;
        }
        switch (phase) {
            case "start" -> start(level, villager);
            case "prepare" -> prepare(level, villager);
            case "descend" -> travel(level, villager, false);
            case "dig" -> dig(level, villager);
            case "vein" -> vein(level, villager);
            case "return" -> travel(level, villager, true);
            case "deposit" -> deposit(level, villager);
            default -> phase = "start";
        }
    }

    private void start(ServerLevel level, Villager villager) {
        returning = false;
        if (underground(villager)) {
            beginReturn();
            return;
        }
        if (hasCargo(villager) && (cargoFull(villager) || !colony().workTime(level))) {
            phase = "deposit";
            return;
        }
        if (!colony().workTime(level)) {
            status = "shift over";
            wait = 100;
            return;
        }
        ColonyData.Mine mine = colony().ensureMine(level);
        if (mine == null) {
            status = "no place for a mine";
            wait = 600;
            return;
        }
        phase = "prepare";
    }

    private void prepare(ServerLevel level, Villager villager) {
        Equip equip = equip(level, villager, Tools.PICKAXE, Tools.PICKAXES);
        if (equip == Equip.BUSY) {
            return;
        }
        if (equip == Equip.MISSING) {
            status = "no pickaxe in storage";
            idle(level, 600);
            phase = "start";
            return;
        }
        if (Inv.count(villager, Ids.mc("torch")) < 4 || Inv.count(villager, Ids.mc("cobblestone")) < 8) {
            if (!atDepot(level, villager)) {
                if (goDepot(level, villager) == Mover.Result.FAILED) {
                    wait = 200;
                    phase = "start";
                }
                return;
            }
            supply(level, villager, Ids.mc("torch"), 4, 16);
            supply(level, villager, Ids.mc("cobblestone"), 8, 24);
        }
        if (cargoFull(villager)) {
            phase = "deposit";
            return;
        }
        ColonyData.Mine mine = colony().data().mine;
        route = mine.plan().routeTo(Math.min(mine.progress, mine.plan().size() - 1));
        waypoint = 0;
        cellsThisTrip = 0;
        stuck = 0;
        phase = "descend";
    }

    private void beginReturn() {
        ColonyData.Mine mine = colony().data().mine;
        returning = true;
        if (mine == null) {
            phase = "deposit";
            return;
        }
        DigPlan plan = mine.plan();
        List<Pos> forward = plan.routeTo(Math.min(mine.progress, plan.size() - 1));
        List<Pos> back = new ArrayList<>(forward);
        java.util.Collections.reverse(back);
        route = back;
        waypoint = 0;
        stuck = 0;
        phase = "return";
    }

    private void travel(ServerLevel level, Villager villager, boolean back) {
        if (waypoint >= route.size()) {
            if (back) {
                phase = "deposit";
            } else {
                phase = "dig";
            }
            return;
        }
        if (back) {
            int nearest = nearestAhead(villager);
            if (nearest > waypoint) {
                waypoint = nearest;
            }
        }
        BlockPos target = WorldUtil.bp(route.get(waypoint));
        BlockPos blocker = tunnelBlocker(level, target);
        if (blocker != null && villager.blockPosition().distSqr(target) <= 9) {
            status = "reopening the tunnel";
            Digger.Result dug = colonist.digger().tick(level, villager, blocker, true);
            if (dug == Digger.Result.DONE) {
                stash(level, villager, colonist.digger().takeDrops());
                colony().data().bump("blocks_mined", 1);
            } else if (dug == Digger.Result.FAILED) {
                waypoint++;
            }
            return;
        }
        Mover.Result result = move(level, villager, target, 1.3);
        if (result == Mover.Result.ARRIVED) {
            waypoint++;
            stuck = 0;
        } else if (result == Mover.Result.FAILED) {
            stuck++;
            if (Mover.rescue(level, villager, target)) {
                waypoint++;
                colony().data().bump("miner_rescues", 1);
            } else if (stuck > 3) {
                status = "lost in the mine";
                colony().journal(level, "miner stuck near " + target.toShortString());
                waypoint++;
            }
        }
        status = (back ? "climbing out " : "going down ") + waypoint + "/" + route.size();
    }

    private BlockPos tunnelBlocker(ServerLevel level, BlockPos feet) {
        for (BlockPos pos : new BlockPos[] {feet, feet.above()}) {
            BlockState state = level.getBlockState(pos);
            if (!state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty()
                    && state.getDestroySpeed(level, pos) >= 0) {
                return pos;
            }
        }
        return null;
    }

    private int nearestAhead(Villager villager) {
        int best = waypoint;
        double bestDistance = Double.MAX_VALUE;
        for (int i = waypoint; i < route.size(); i++) {
            double distance = villager.blockPosition().distSqr(WorldUtil.bp(route.get(i)));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return bestDistance < 4 ? best : waypoint;
    }

    private void dig(ServerLevel level, Villager villager) {
        ColonyData.Mine mine = colony().data().mine;
        DigPlan plan = mine.plan();
        if (mine.progress >= plan.size()) {
            colony().mineExhausted(level);
            beginReturn();
            return;
        }
        if (!Tools.PICKAXE.test(villager.getMainHandItem())) {
            beginReturn();
            return;
        }
        if (cargoFull(villager) || cellsThisTrip >= CELLS_PER_TRIP || !colony().workTime(level)
                || villager.getHealth() < villager.getMaxHealth() * 0.5F) {
            beginReturn();
            return;
        }
        if (mine.progress != cellIndex) {
            cellIndex = mine.progress;
            cellTicks = 0;
        }
        if (++cellTicks > 1200) {
            colony().journal(level, "miner gave up on section " + mine.progress + " (flooded or blocked)");
            colony().data().bump("mine_sections_skipped", 1);
            mine.progress++;
            skippedInRow++;
            colony().markDirty();
            if (skippedInRow >= 8) {
                skippedInRow = 0;
                colony().mineExhausted(level);
                beginReturn();
            }
            return;
        }
        MinePlan.Cell cell = plan.cell(mine.progress);
        BlockPos stand = WorldUtil.bp(plan.standFor(mine.progress));
        status = "section " + mine.progress + " at " + cell.feet();
        if (Mover.distance(villager, stand) > 1.6) {
            Mover.Result result = move(level, villager, stand, 1.2);
            if (result == Mover.Result.FAILED && !Mover.rescue(level, villager, stand)) {
                beginReturn();
            }
            return;
        }
        BlockPos feet = WorldUtil.bp(cell.feet());
        if (!WorldUtil.loaded(level, feet)) {
            wait = 40;
            return;
        }
        Set<BlockPos> cellBlocks = new HashSet<>();
        for (int dy = 0; dy < cell.height(); dy++) {
            cellBlocks.add(feet.above(dy));
        }
        for (int dy = cell.height() - 1; dy >= 0; dy--) {
            BlockPos block = feet.above(dy);
            if (!seal(level, villager, block, cellBlocks, stand)) {
                status = "cannot seal fluid";
                beginReturn();
                return;
            }
            BlockState state = level.getBlockState(block);
            if (!state.getFluidState().isEmpty()) {
                if (++fluidFights > 30) {
                    colony().journal(level, "miner skipped a flooded section at " + block.toShortString());
                    fluidFights = 0;
                    mine.progress++;
                    colony().markDirty();
                    return;
                }
                if (!fillWithCobble(level, villager, block)) {
                    level.setBlock(block, Blocks.AIR.defaultBlockState(), 3);
                }
                return;
            }
            if (!WorldUtil.isAirLike(state) || (!state.isAir() && !state.getCollisionShape(level, block).isEmpty())) {
                if (state.getDestroySpeed(level, block) < 0) {
                    mine.progress++;
                    return;
                }
                Digger.Result result = colonist.digger().tick(level, villager, block, true);
                if (result == Digger.Result.DONE) {
                    stash(level, villager, colonist.digger().takeDrops());
                    colony().data().bump("blocks_mined", 1);
                    gainXp(level, villager, 1);
                }
                if (result == Digger.Result.FAILED) {
                    mine.progress++;
                }
                return;
            }
            if (!state.isAir() && state.canBeReplaced()) {
                colonist.digger().breakNow(level, villager, block, false);
            }
        }
        BlockPos top = feet.above(cell.height());
        if (level.getBlockState(top).getBlock() instanceof FallingBlock) {
            Digger.Result result = colonist.digger().tick(level, villager, top, true);
            if (result == Digger.Result.DONE) {
                stash(level, villager, colonist.digger().takeDrops());
            }
            return;
        }
        BlockPos floor = feet.below();
        if (!WorldUtil.solidFloor(level, floor)) {
            if (!seal(level, villager, floor, cellBlocks, stand)) {
                beginReturn();
                return;
            }
            if (!fillWithCobble(level, villager, floor)) {
                status = "no cobblestone for the floor";
                beginReturn();
                return;
            }
        }
        if (cell.kind() == MinePlan.Kind.QUARRY) {
            lightQuarry(level, villager, mine, cell);
        } else if (level.getBrightness(LightLayer.BLOCK, feet) < 4 && level.getBrightness(LightLayer.SKY, feet) < 8
                && Inv.count(villager, Ids.mc("torch")) > 0 && Placer.placeTorch(level, villager, feet)) {
            Inv.remove(villager, stack -> stack.is(Items.TORCH), 1);
            colony().data().bump("torches_mine", 1);
        }
        fluidFights = 0;
        skippedInRow = 0;
        vein = findOres(level, cellBlocks, villager);
        veinIndex = 0;
        mine.progress++;
        cellsThisTrip++;
        colony().markDirty();
        if (!vein.isEmpty()) {
            phase = "vein";
        }
    }

    private void lightQuarry(ServerLevel level, Villager villager, ColonyData.Mine mine, MinePlan.Cell cell) {
        org.lts.smartcolony.core.QuarryPlan quarry = (org.lts.smartcolony.core.QuarryPlan) mine.plan();
        int layer = quarry.layerOf(cell.index());
        if (layer < 2 || layer % 4 != 0 || cell.index() % quarry.perLayer() != 0) {
            return;
        }
        int w = quarry.width();
        int cx = quarry.corner().x();
        int cz = quarry.corner().z();
        int y = quarry.layerY(layer) + 1;
        int mid = w / 2;
        int[][] spots = {{cx + mid, cz, 0, -1}, {cx + mid, cz + w - 1, 0, 1}, {cx, cz + mid, -1, 0}, {cx + w - 1, cz + mid, 1, 0}};
        for (int[] spot : spots) {
            if (Inv.count(villager, Ids.mc("torch")) == 0) {
                return;
            }
            BlockPos at = new BlockPos(spot[0], y, spot[1]);
            BlockPos wall = at.offset(spot[2], 0, spot[3]);
            Direction facing = Direction.getApproximateNearest(-spot[2], 0, -spot[3]);
            if (facing == null || !WorldUtil.isAirLike(level.getBlockState(at))
                    || !level.getBlockState(wall).isFaceSturdy(level, wall, facing)) {
                continue;
            }
            BlockState torch = net.minecraft.world.level.block.Blocks.WALL_TORCH.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.WallTorchBlock.FACING, facing);
            if (torch.canSurvive(level, at)) {
                Placer.place(level, villager, at, torch);
                Inv.remove(villager, stack -> stack.is(Items.TORCH), 1);
                colony().data().bump("torches_mine", 1);
            }
        }
    }

    private boolean seal(ServerLevel level, Villager villager, BlockPos block, Set<BlockPos> cell, BlockPos stand) {
        for (Direction direction : Direction.values()) {
            BlockPos side = block.relative(direction);
            if (cell.contains(side) || side.equals(stand) || side.equals(stand.above())) {
                continue;
            }
            FluidState fluid = level.getFluidState(side);
            if (fluid.isEmpty()) {
                continue;
            }
            if (!fillWithCobble(level, villager, side)) {
                return fluid.getType() != Fluids.LAVA && fluid.getType() != Fluids.FLOWING_LAVA;
            }
            colony().data().bump("fluids_sealed", 1);
        }
        return true;
    }

    private boolean fillWithCobble(ServerLevel level, Villager villager, BlockPos pos) {
        ItemStack material = Inv.find(villager, stack -> stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE)
                || stack.is(Items.DIRT) || stack.is(Items.ANDESITE) || stack.is(Items.DIORITE) || stack.is(Items.GRANITE)
                || stack.is(Items.TUFF));
        if (material.isEmpty()) {
            return false;
        }
        BlockState state = net.minecraft.world.level.block.Block.byItem(material.getItem()).defaultBlockState();
        Placer.place(level, villager, pos, state);
        material.shrink(1);
        return true;
    }

    private List<BlockPos> findOres(ServerLevel level, Set<BlockPos> cell, Villager villager) {
        List<BlockPos> result = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>(cell);
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        for (BlockPos block : cell) {
            for (Direction direction : Direction.values()) {
                BlockPos side = block.relative(direction);
                if (seen.add(side) && isOre(level.getBlockState(side))) {
                    queue.add(side);
                }
            }
        }
        while (!queue.isEmpty() && result.size() < 24) {
            BlockPos pos = queue.poll();
            if (pos.distToCenterSqr(villager.getEyePosition()) > 30) {
                continue;
            }
            result.add(pos);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos next = pos.offset(dx, dy, dz);
                        if (seen.add(next) && WorldUtil.loaded(level, next) && isOre(level.getBlockState(next))) {
                            queue.add(next);
                        }
                    }
                }
            }
        }
        return result;
    }

    private static boolean isOre(BlockState state) {
        String path = Ids.path(ItemIds.id(state.getBlock().asItem()));
        return path.endsWith("_ore") || path.equals("ancient_debris");
    }

    private void vein(ServerLevel level, Villager villager) {
        if (veinIndex >= vein.size() || cargoFull(villager)) {
            phase = "dig";
            return;
        }
        BlockPos ore = vein.get(veinIndex);
        BlockState state = level.getBlockState(ore);
        if (!isOre(state) || !Digger.canHarvest(villager.getMainHandItem(), state)) {
            veinIndex++;
            return;
        }
        Set<BlockPos> single = Set.of(ore);
        if (!seal(level, villager, ore, single, villager.blockPosition())) {
            veinIndex++;
            return;
        }
        Digger.Result result = colonist.digger().tick(level, villager, ore, true);
        if (result == Digger.Result.DONE) {
            stash(level, villager, colonist.digger().takeDrops());
            colony().data().bump("ores_mined", 1);
            gainXp(level, villager, 2);
            veinIndex++;
        } else if (result == Digger.Result.FAILED) {
            veinIndex++;
        }
    }

    @Override
    protected void stash(ServerLevel level, Villager villager, List<ItemStack> drops) {
        List<ItemStack> kept = new ArrayList<>();
        for (ItemStack drop : drops) {
            String path = Ids.path(ItemIds.id(drop));
            boolean stone = path.equals("cobblestone") || path.equals("cobbled_deepslate");
            boolean building = path.equals("diorite") || path.equals("granite") || path.equals("andesite") || path.equals("tuff");
            String id = ItemIds.id(drop);
            boolean junk = building && (Inv.count(villager, id) >= 64 || colony().stockOf(level, found -> found.equals(id)) >= 128)
                    || path.equals("dirt") || path.equals("calcite") || path.equals("dripstone_block") || path.equals("netherrack")
                    || path.equals("pointed_dripstone") || path.equals("smooth_basalt");
            if (junk || (stone && Inv.count(villager, ItemIds.id(drop)) >= 96)) {
                colony().data().bump("rubble_discarded", drop.getCount());
                continue;
            }
            kept.add(drop);
        }
        super.stash(level, villager, kept);
    }

    private void deposit(ServerLevel level, Villager villager) {
        if (!atDepot(level, villager)) {
            if (goDepot(level, villager) == Mover.Result.FAILED) {
                wait = 100;
            }
            return;
        }
        depositAll(level, villager);
        int extraCobble = Inv.count(villager, Ids.mc("cobblestone")) - 24;
        if (extraCobble > 0) {
            Inv.remove(villager, stack -> stack.is(Items.COBBLESTONE), extraCobble);
            colony().warehouse().give(level, Ids.mc("cobblestone"), extraCobble);
        }
        phase = "start";
    }
}
