package org.lts.smartcolony.work;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
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
import org.lts.smartcolony.colony.Colony;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.Ids;
import org.lts.smartcolony.core.DigPlan;
import org.lts.smartcolony.core.MinePlan;
import org.lts.smartcolony.core.Pos;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.core.TreeFarmPlan;
import org.lts.smartcolony.util.ItemIds;
import org.lts.smartcolony.util.WorldUtil;

public final class MinerJob extends Job {
    private static final int CELLS_PER_TRIP = 48;
    private static final int FIT_PER_TRIP = 64;
    private static final int FIT_PER_STOP = 24;
    private static final int TUNNEL_LOGS = 21;
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
    private int fitBudget;
    private long fitRetry;
    private Map<String, Integer> fitNeeds;
    private final Set<String> fitItems = new HashSet<>();

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
        return tools().test(stack) || stack.is(Items.TORCH) || stack.is(Items.LADDER);
    }

    @Override
    protected boolean cargoFull(Villager villager) {
        int fitSlots = 0;
        for (String id : fitItems) {
            if (Inv.count(villager, id) > 0) {
                fitSlots++;
            }
        }
        return Inv.used(villager) - fitSlots >= depositThreshold || Inv.free(villager) == 0;
    }

    @Override
    public boolean hasCargo(Villager villager) {
        return Inv.count(villager, stack -> !Tools.ANY_TOOL.test(stack) && !stack.is(Items.TORCH) && !stack.is(Items.LADDER) && !stack.is(Items.COBBLESTONE) && !ration(stack)) > 0;
    }

    public boolean underground(Villager villager) {
        for (ColonyData.Mine mine : colony().allMines()) {
            if (underground(villager, mine)) {
                return true;
            }
        }
        return false;
    }

    private static boolean underground(Villager villager, ColonyData.Mine mine) {
        if (mine == null || villager.getY() >= mine.entrance.y() - 2) {
            return false;
        }
        BlockPos feet = villager.blockPosition();
        if (mine.quarry()) {
            return feet.getX() >= mine.corner.x() - 1 && feet.getX() <= mine.corner.x() + mine.width
                    && feet.getZ() >= mine.corner.z() - 1 && feet.getZ() <= mine.corner.z() + mine.width;
        }
        double dx = feet.getX() - mine.entrance.x();
        double dz = feet.getZ() - mine.entrance.z();
        return dx * dx + dz * dz <= (double) (mine.depth + 6) * (mine.depth + 6) && !villager.level().canSeeSky(feet);
    }

    @Override
    public boolean wantsControl(ServerLevel level, Villager villager) {
        return onShift(level, villager) || hasCargo(villager) || underground(villager);
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
            case "kit" -> kit(level, villager);
            case "trek" -> trek(level, villager);
            case "assay" -> assay(level, villager);
            case "lode" -> lode(level, villager);
            case "bore" -> bore(level, villager);
            default -> phase = "start";
        }
    }

    private void start(ServerLevel level, Villager villager) {
        returning = false;
        if (underground(villager)) {
            beginReturn();
            return;
        }
        if (hasCargo(villager) && (cargoFull(villager) || !onShift(level, villager))) {
            phase = "deposit";
            return;
        }
        if (!onShift(level, villager)) {
            status = "shift over";
            wait = 100;
            return;
        }
        ColonyData.Mine main = colony().ensureMine(level);
        if (colony().workTime(level) && colony().prospects(level, colonist.uuid())) {
            ColonyData.Discovery target = colony().prospectFor(level, colonist.uuid());
            if (target != null) {
                deposit = target;
                prospectMined = 0;
                prospectFails = 0;
                bores = 0;
                phase = "kit";
                return;
            }
        }
        site = colony().mineFor(level, colonist.uuid());
        if (main == null || site == null) {
            status = "no place for a mine";
            wait = 600;
            return;
        }
        fitNeeds = null;
        phase = "prepare";
    }

    private ColonyData.Mine mine() {
        if (site == null || !colony().allMines().contains(site)) {
            site = colony().data().mine;
        }
        return site;
    }

    private static final int PROSPECT_TICKS = 6000;
    private static final int PROSPECT_RADIUS = 12;
    private static final int PROSPECT_VEIN = 96;
    private ColonyData.Mine site;
    private ColonyData.Discovery deposit;
    private long prospectStart;
    private int prospectMined;
    private int prospectFails;
    private final ArrayDeque<BlockPos> lode = new ArrayDeque<>();
    private final Set<BlockPos> skipped = new HashSet<>();
    private static final int MAX_BORES = 4;
    private List<org.lts.smartcolony.core.Bore.Step> bore = List.of();
    private int boreIndex;
    private BlockPos boreTarget;
    private int bores;

    private boolean prospectOver(ServerLevel level, Villager villager) {
        return deposit == null || level.getGameTime() - prospectStart > PROSPECT_TICKS || !colony().workTime(level)
                || cargoFull(villager) || villager.getHealth() < villager.getMaxHealth() * 0.5F;
    }

    private void endProspect(ServerLevel level, Colony.Prospect outcome) {
        if (deposit != null) {
            colony().prospectDone(level, deposit, prospectMined, outcome);
            if (outcome != Colony.Prospect.TRIP) {
                colony().releaseProspect(colonist.uuid());
            }
        }
        deposit = null;
        lode.clear();
        skipped.clear();
        phase = "deposit";
    }

    private void kit(ServerLevel level, Villager villager) {
        Equip equip = equip(level, villager, Tools.PICKAXE, Tools.PICKAXES);
        if (equip == Equip.BUSY) {
            return;
        }
        if (equip == Equip.MISSING) {
            status = "no pickaxe in storage";
            idle(level, 600);
            deposit = null;
            phase = "start";
            return;
        }
        if (Inv.count(villager, Ids.mc("torch")) < 2 || Inv.count(villager, Ids.mc("cobblestone")) < 8) {
            if (!atDepot(level, villager)) {
                if (goDepot(level, villager) == Mover.Result.FAILED) {
                    wait = 200;
                    deposit = null;
                    phase = "start";
                }
                return;
            }
            supply(level, villager, Ids.mc("torch"), 2, 8);
            supply(level, villager, Ids.mc("cobblestone"), 8, 16);
        }
        prospectStart = level.getGameTime();
        phase = "trek";
    }

    private void trek(ServerLevel level, Villager villager) {
        if (prospectOver(level, villager)) {
            endProspect(level, Colony.Prospect.TRIP);
            return;
        }
        BlockPos ore = WorldUtil.bp(deposit.pos);
        if (!level.isPositionEntityTicking(ore)) {
            status = "the ore deposit is too far from the player";
            endProspect(level, Colony.Prospect.TRIP);
            return;
        }
        BlockPos goal = WorldUtil.surface(level, ore.getX(), ore.getZ());
        status = "walking to the ore at " + ore.toShortString();
        Mover.Result result = move(level, villager, goal, 5.0);
        if (result == Mover.Result.ARRIVED || result == Mover.Result.FAILED && villager.blockPosition().distSqr(ore) < 24 * 24) {
            phase = "assay";
        } else if (result == Mover.Result.FAILED) {
            prospectFails++;
            if (prospectFails > 3) {
                colony().journal(level, "prospector found no way to the ore at " + ore.toShortString());
                endProspect(level, Colony.Prospect.UNREACHABLE);
            }
        }
    }

    private void assay(ServerLevel level, Villager villager) {
        BlockPos center = WorldUtil.bp(deposit.pos);
        int r = PROSPECT_RADIUS;
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, 8, r))) {
            if (found.size() >= PROSPECT_VEIN || !WorldUtil.loaded(level, pos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (isOre(state) && Digger.canHarvest(villager.getMainHandItem(), state) && open(level, pos) && !skipped.contains(pos)) {
                found.add(pos.immutable());
            }
        }
        BlockPos here = villager.blockPosition();
        found.sort(java.util.Comparator.comparingDouble(pos -> pos.distSqr(here)));
        lode.clear();
        lode.addAll(found);
        status = found.isEmpty() ? "no reachable ore left here" : "found " + found.size() + " ore blocks";
        if (found.isEmpty()) {
            endProspect(level, skipped.isEmpty() ? Colony.Prospect.EMPTY : prospectMined > 0 ? Colony.Prospect.TRIP : Colony.Prospect.UNREACHABLE);
            return;
        }
        phase = "lode";
    }

    private static boolean open(ServerLevel level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockState next = level.getBlockState(pos.relative(direction));
            if (next.isAir() || next.canBeReplaced() && next.getFluidState().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void lode(ServerLevel level, Villager villager) {
        if (prospectOver(level, villager)) {
            endProspect(level, Colony.Prospect.TRIP);
            return;
        }
        while (!lode.isEmpty() && !isOre(level.getBlockState(lode.peek()))) {
            lode.poll();
        }
        if (lode.isEmpty()) {
            phase = "assay";
            return;
        }
        BlockPos ore = lode.peek();
        status = "mining the ore the scouts found (" + prospectMined + " so far)";
        if (villager.getEyePosition().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(ore)) > 4.2) {
            BlockPos stand = ScoutJob.standNear(level, ore);
            if (stand == null) {
                if (!boreTo(level, villager, ore)) {
                    skipped.add(lode.poll());
                }
                return;
            }
            Mover.Result result = move(level, villager, stand, 1.2);
            if (result == Mover.Result.FAILED && !boreTo(level, villager, ore)) {
                skipped.add(lode.poll());
            }
            return;
        }
        if (!seal(level, villager, ore, Set.of(ore), villager.blockPosition())) {
            skipped.add(lode.poll());
            return;
        }
        Digger.Result result = colonist.digger().tick(level, villager, ore, true);
        if (result == Digger.Result.DONE) {
            stash(level, villager, colonist.digger().takeDrops());
            lode.poll();
            prospectMined++;
            colony().data().bump("ores_mined", 1);
            gainXp(level, villager, 2);
            for (Direction direction : Direction.values()) {
                BlockPos next = ore.relative(direction);
                if (WorldUtil.loaded(level, next) && isOre(level.getBlockState(next)) && !lode.contains(next)) {
                    lode.addFirst(next.immutable());
                }
            }
        } else if (result == Digger.Result.FAILED) {
            skipped.add(lode.poll());
        }
    }

    private boolean boreTo(ServerLevel level, Villager villager, BlockPos ore) {
        if (bores >= MAX_BORES || villager.blockPosition().distSqr(ore) > 24 * 24) {
            return false;
        }
        List<org.lts.smartcolony.core.Bore.Step> route = org.lts.smartcolony.core.Bore.route(WorldUtil.pos(villager.blockPosition()),
                WorldUtil.pos(ore), org.lts.smartcolony.core.Bore.MAX_STEPS);
        if (route == null || route.isEmpty()) {
            return false;
        }
        for (org.lts.smartcolony.core.Bore.Step step : route) {
            BlockPos feet = WorldUtil.bp(step.feet());
            if (!WorldUtil.loaded(level, feet) || colony().isProtected(feet)) {
                return false;
            }
            for (int dy = 0; dy < step.height(); dy++) {
                if (!borable(level, villager, feet.above(dy))) {
                    return false;
                }
            }
        }
        bore = route;
        boreIndex = 0;
        boreTarget = ore.immutable();
        bores++;
        colony().data().bump("prospect_bores", 1);
        phase = "bore";
        return true;
    }

    private static boolean borable(ServerLevel level, Villager villager, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (WorldUtil.isAirLike(state) || !state.getFluidState().isEmpty()) {
            return true;
        }
        return level.getBlockEntity(pos) == null && state.getDestroySpeed(level, pos) >= 0 && state.getDestroySpeed(level, pos) <= 5.0F
                && Digger.canHarvest(villager.getMainHandItem(), state);
    }

    private void abortBore() {
        if (boreTarget != null) {
            lode.remove(boreTarget);
            skipped.add(boreTarget);
        }
        bore = List.of();
        boreTarget = null;
        phase = "lode";
    }

    private void bore(ServerLevel level, Villager villager) {
        if (prospectOver(level, villager)) {
            bore = List.of();
            endProspect(level, Colony.Prospect.TRIP);
            return;
        }
        if (boreIndex >= bore.size()) {
            bore = List.of();
            boreTarget = null;
            phase = "lode";
            return;
        }
        org.lts.smartcolony.core.Bore.Step step = bore.get(boreIndex);
        BlockPos feet = WorldUtil.bp(step.feet());
        status = "digging a passage to the ore " + (boreIndex + 1) + "/" + bore.size();
        Set<BlockPos> cell = new HashSet<>();
        for (int dy = 0; dy < step.height(); dy++) {
            cell.add(feet.above(dy));
        }
        for (int dy = step.height() - 1; dy >= 0; dy--) {
            BlockPos block = feet.above(dy);
            if (!seal(level, villager, block, cell, villager.blockPosition())) {
                abortBore();
                return;
            }
            BlockState state = level.getBlockState(block);
            if (!state.getFluidState().isEmpty()) {
                if (!fillWithCobble(level, villager, block)) {
                    abortBore();
                }
                return;
            }
            if (!WorldUtil.isAirLike(state) || !state.isAir() && !state.getCollisionShape(level, block).isEmpty()) {
                if (!borable(level, villager, block)) {
                    abortBore();
                    return;
                }
                boolean ore = isOre(state);
                Digger.Result result = colonist.digger().tick(level, villager, block, true);
                if (result == Digger.Result.DONE) {
                    stash(level, villager, colonist.digger().takeDrops());
                    colony().data().bump("blocks_mined", 1);
                    if (ore) {
                        prospectMined++;
                        colony().data().bump("ores_mined", 1);
                    }
                } else if (result == Digger.Result.FAILED) {
                    abortBore();
                }
                return;
            }
            if (!state.isAir() && state.canBeReplaced()) {
                colonist.digger().breakNow(level, villager, block, false);
            }
        }
        BlockPos floor = feet.below();
        if (!WorldUtil.solidFloor(level, floor) && !fillWithCobble(level, villager, floor) && !fillFromStorage(level, villager, floor)) {
            abortBore();
            return;
        }
        if (Mover.distance(villager, feet) > 0.9) {
            Mover.Result result = move(level, villager, feet, 0.5);
            if (result == Mover.Result.FAILED && !Mover.rescue(level, villager, feet)) {
                abortBore();
            }
            if (result != Mover.Result.ARRIVED) {
                return;
            }
        }
        if (boreIndex % 5 == 4 && level.getBrightness(LightLayer.BLOCK, feet) < 4 && level.getBrightness(LightLayer.SKY, feet) < 8
                && Inv.count(villager, Ids.mc("torch")) > 0 && Placer.placeTorch(level, villager, feet)) {
            Inv.remove(villager, stack -> stack.is(Items.TORCH), 1);
        }
        boreIndex++;
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
        ColonyData.Mine current = mine();
        int ladderGap = ladderGap(level, villager, current);
        boolean fetchLadders = ladderGap > 0 && level.getGameTime() >= ladderRetry;
        if (fitNeeds == null) {
            fitNeeds = planFittings(level, current);
        }
        boolean fetchFits = !fitNeeds.isEmpty() && level.getGameTime() >= fitRetry;
        if (Inv.count(villager, Ids.mc("torch")) < 4 || Inv.count(villager, Ids.mc("cobblestone")) < 8 || fetchLadders || fetchFits) {
            if (!atDepot(level, villager)) {
                if (goDepot(level, villager) == Mover.Result.FAILED) {
                    wait = 200;
                    phase = "start";
                }
                return;
            }
            supply(level, villager, Ids.mc("torch"), 4, 16);
            supply(level, villager, Ids.mc("cobblestone"), 8, 24);
            if (fetchLadders) {
                int have = supply(level, villager, Ids.mc("ladder"), Math.min(ladderGap, 6), Math.min(ladderGap, 12));
                if (have < ladderGap) {
                    ladderRetry = level.getGameTime() + 6000;
                }
            }
            if (fetchFits) {
                fetchFittings(level, villager);
            }
        }
        if (current != null && current.quarry()) {
            furnishQuarry(level, villager, current);
        }
        if (cargoFull(villager)) {
            phase = "deposit";
            return;
        }
        ColonyData.Mine mine = mine();
        route = mine.plan().routeTo(Math.min(mine.progress, mine.plan().size() - 1));
        waypoint = 0;
        cellsThisTrip = 0;
        stuck = 0;
        fitBudget = FIT_PER_TRIP;
        phase = "descend";
    }

    private Map<String, Integer> planFittings(ServerLevel level, ColonyData.Mine mine) {
        if (mine == null) {
            return Map.of();
        }
        if (mine.quarry() && !mine.headDone && colony().fitter().headFinished(level, mine)) {
            mine.headDone = true;
            colony().journal(level, "the quarry head is finished");
            colony().notify(level, Component.translatable("smartcolony.chat.mine_head_done"));
            colony().markDirty();
        }
        List<BlockPos> stops = new ArrayList<>();
        for (Pos stop : mine.plan().routeTo(Math.min(mine.progress, mine.plan().size() - 1))) {
            stops.add(WorldUtil.bp(stop));
        }
        if (!mine.quarry()) {
            Map<String, Integer> due = colony().fitter().needs(level, mine, TUNNEL_LOGS, stops);
            return due.isEmpty() ? due : Map.of(Ids.mc(colony().fitter().wood(level, mine) + "_log"), TUNNEL_LOGS);
        }
        return colony().fitter().needs(level, mine, FIT_PER_TRIP, stops);
    }

    private void fetchFittings(ServerLevel level, Villager villager) {
        for (Map.Entry<String, Integer> need : fitNeeds.entrySet()) {
            if (!need.getKey().equals(Ids.mc("torch")) && !need.getKey().equals(Ids.mc("cobblestone"))) {
                fitItems.add(need.getKey());
            }
            int have = supply(level, villager, need.getKey(), need.getValue(), need.getValue());
            if (have < need.getValue()) {
                fitRetry = level.getGameTime() + 2400;
            }
        }
        fitNeeds = Map.of();
    }

    private void fitHere(ServerLevel level, Villager villager) {
        ColonyData.Mine mine = mine();
        if (mine == null || fitBudget <= 0) {
            return;
        }
        int placed = colony().fitter().placeNear(level, villager, mine, Math.min(fitBudget, FIT_PER_STOP));
        if (placed > 0) {
            fitBudget -= placed;
            wait = 6 + placed * 2;
        }
    }

    private String lastReturn = "";

    private void beginReturn() {
        if (status != null && !status.startsWith("climbing") && !status.startsWith("going")) {
            lastReturn = status;
        }
        ColonyData.Mine mine = mine();
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
                fitHere(level, villager);
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
            if (!back) {
                fitHere(level, villager);
            }
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
        status = (back ? "climbing out " : "going down ") + waypoint + "/" + route.size() + (back && !lastReturn.isEmpty() ? " (" + lastReturn + ")" : "");
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
        ColonyData.Mine mine = mine();
        if (mine == null) {
            beginReturn();
            return;
        }
        DigPlan plan = mine.plan();
        if (mine.progress >= plan.size()) {
            colony().mineExhausted(level, mine);
            beginReturn();
            lastReturn = "mine worked out";
            return;
        }
        if (!Tools.PICKAXE.test(villager.getMainHandItem())) {
            beginReturn();
            lastReturn = "no pickaxe in hand";
            return;
        }
        if (cargoFull(villager) || cellsThisTrip >= CELLS_PER_TRIP || !onShift(level, villager)
                || villager.getHealth() < villager.getMaxHealth() * 0.5F) {
            lastReturn = cargoFull(villager) ? "cargo full" : cellsThisTrip >= CELLS_PER_TRIP ? "trip done" : !onShift(level, villager) ? "shift over" : "hurt";
            beginReturn();
            return;
        }
        int index = pickCell(level, mine, plan);
        if (index != cellIndex) {
            cellIndex = index;
            cellTicks = 0;
        }
        if (++cellTicks > 1200) {
            colony().journal(level, "miner gave up on section " + index + " (flooded or blocked)");
            colony().data().bump("mine_sections_skipped", 1);
            finishCell(mine, index);
            skippedInRow++;
            colony().markDirty();
            if (skippedInRow >= 8) {
                skippedInRow = 0;
                colony().mineExhausted(level, mine);
                beginReturn();
            }
            return;
        }
        MinePlan.Cell cell = plan.cell(index);
        BlockPos stand = WorldUtil.bp(plan.standFor(index));
        status = "section " + index + " at " + cell.feet();
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
        if (!sealStand(level, villager, stand, cellBlocks)) {
            finishCell(mine, index);
            return;
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
                    finishCell(mine, index);
                    return;
                }
                if (!fillWithCobble(level, villager, block)) {
                    level.setBlock(block, Blocks.AIR.defaultBlockState(), 3);
                }
                return;
            }
            if (!WorldUtil.isAirLike(state) || (!state.isAir() && !state.getCollisionShape(level, block).isEmpty())) {
                if (state.getDestroySpeed(level, block) < 0) {
                    finishCell(mine, index);
                    return;
                }
                Digger.Result result = colonist.digger().tick(level, villager, block, true);
                if (result == Digger.Result.DONE) {
                    stash(level, villager, colonist.digger().takeDrops());
                    colony().data().bump("blocks_mined", 1);
                    gainXp(level, villager, 1);
                }
                if (result == Digger.Result.FAILED) {
                    finishCell(mine, index);
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
            if (!fillWithCobble(level, villager, floor) && !fillFromStorage(level, villager, floor)) {
                colony().data().bump("mine_floor_gaps", 1);
            }
        }
        if (cell.kind() == MinePlan.Kind.QUARRY) {
            if (cell.index() % ((org.lts.smartcolony.core.QuarryPlan) mine.plan()).perLayer() == 0) {
                furnishQuarry(level, villager, mine);
            }
        } else if (level.getBrightness(LightLayer.BLOCK, feet) < 4 && level.getBrightness(LightLayer.SKY, feet) < 8
                && Inv.count(villager, Ids.mc("torch")) > 0 && Placer.placeTorch(level, villager, feet)) {
            Inv.remove(villager, stack -> stack.is(Items.TORCH), 1);
            colony().data().bump("torches_mine", 1);
        }
        fluidFights = 0;
        skippedInRow = 0;
        vein = findOres(level, cellBlocks, villager);
        veinIndex = 0;
        finishCell(mine, index);
        cellsThisTrip++;
        if (!mine.quarry() && cellsThisTrip % 3 == 0) {
            fitHere(level, villager);
        }
        colony().markDirty();
        if (!vein.isEmpty()) {
            phase = "vein";
        }
    }

    private int claimed = -1;

    private int pickCell(ServerLevel level, ColonyData.Mine mine, DigPlan plan) {
        java.util.Map<Integer, Long> claims = colony().mineClaims(mine);
        long now = level.getGameTime();
        claims.values().removeIf(at -> now - at > 600);
        if (claimed >= mine.progress && claimed < plan.size() && claims.containsKey(claimed)) {
            claims.put(claimed, now);
            return claimed;
        }
        int limit = Math.min(plan.size(), mine.progress + 6);
        for (int i = mine.progress; i < limit; i++) {
            if (claims.containsKey(i)) {
                continue;
            }
            if (i > mine.progress) {
                BlockPos stand = WorldUtil.bp(plan.standFor(i));
                if (!WorldUtil.isAirLike(level.getBlockState(stand)) || !WorldUtil.isAirLike(level.getBlockState(stand.above()))) {
                    continue;
                }
            }
            claims.put(i, now);
            claimed = i;
            return i;
        }
        claimed = -1;
        return mine.progress;
    }

    private void finishCell(ColonyData.Mine mine, int index) {
        colony().mineClaims(mine).remove(index);
        claimed = -1;
        if (index == mine.progress) {
            mine.progress++;
        }
        colony().markDirty();
    }

    private long ladderRetry;

    private int completedLayers(ColonyData.Mine mine, org.lts.smartcolony.core.QuarryPlan quarry) {
        return Math.min(quarry.layers(), mine.progress / quarry.perLayer());
    }

    private int ladderGap(ServerLevel level, Villager villager, ColonyData.Mine mine) {
        if (mine == null || !mine.quarry()) {
            return 0;
        }
        org.lts.smartcolony.core.QuarryPlan quarry = (org.lts.smartcolony.core.QuarryPlan) mine.plan();
        int missing = 0;
        for (org.lts.smartcolony.core.Pos cell : quarry.ladderCells(completedLayers(mine, quarry))) {
            if (ladderWanted(level, quarry, cell)) {
                missing++;
            }
        }
        return Math.max(0, missing - Inv.count(villager, Ids.mc("ladder")));
    }

    private boolean ladderWanted(ServerLevel level, org.lts.smartcolony.core.QuarryPlan quarry, org.lts.smartcolony.core.Pos cell) {
        BlockPos at = WorldUtil.bp(cell);
        return WorldUtil.loaded(level, at) && WorldUtil.isAirLike(level.getBlockState(at)) && ladderState().canSurvive(level, at);
    }

    private static BlockState ladderState() {
        return net.minecraft.world.level.block.Blocks.LADDER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LadderBlock.FACING, Direction.EAST);
    }

    private void furnishQuarry(ServerLevel level, Villager villager, ColonyData.Mine mine) {
        org.lts.smartcolony.core.QuarryPlan quarry = (org.lts.smartcolony.core.QuarryPlan) mine.plan();
        int completed = completedLayers(mine, quarry);
        for (org.lts.smartcolony.core.Pos cell : quarry.ladderCells(completed)) {
            if (Inv.count(villager, Ids.mc("ladder")) == 0) {
                break;
            }
            if (ladderWanted(level, quarry, cell)) {
                Placer.place(level, villager, WorldUtil.bp(cell), ladderState());
                Inv.remove(villager, stack -> stack.is(Items.LADDER), 1);
                colony().data().bump("ladders_mine", 1);
            }
        }
        int w = quarry.width();
        int cx = quarry.corner().x();
        int cz = quarry.corner().z();
        int mid = w / 2;
        int[][] spots = {{cx + mid, cz, 0, -1}, {cx + mid, cz + w - 1, 0, 1}, {cx, cz + mid, -1, 0}, {cx + w - 1, cz + mid, 1, 0}};
        for (int layer = 4; layer <= completed; layer += 4) {
            int y = quarry.layerY(layer) + 1;
            for (int[] spot : spots) {
                if (Inv.count(villager, Ids.mc("torch")) == 0) {
                    return;
                }
                BlockPos at = new BlockPos(spot[0], y, spot[1]);
                BlockPos wall = at.offset(spot[2], 0, spot[3]);
                Direction facing = Direction.getApproximateNearest(-spot[2], 0, -spot[3]);
                if (facing == null || !WorldUtil.loaded(level, at) || !WorldUtil.isAirLike(level.getBlockState(at))
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
    }

    private boolean sealStand(ServerLevel level, Villager villager, BlockPos stand, Set<BlockPos> cell) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            for (BlockPos at : new BlockPos[] {stand.relative(direction), stand.above().relative(direction)}) {
                if (cell.contains(at) || !level.getFluidState(at).is(net.minecraft.tags.FluidTags.LAVA)) {
                    continue;
                }
                if (!fillWithCobble(level, villager, at)) {
                    colony().data().bump("lava_avoided", 1);
                    return false;
                }
                colony().data().bump("fluids_sealed", 1);
            }
        }
        return true;
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
                boolean lava = fluid.getType() == Fluids.LAVA || fluid.getType() == Fluids.FLOWING_LAVA;
                if (lava) {
                    colony().data().bump("lava_avoided", 1);
                }
                return !lava;
            }
            colony().data().bump("fluids_sealed", 1);
        }
        return true;
    }

    private boolean fillFromStorage(ServerLevel level, Villager villager, BlockPos pos) {
        for (String id : new String[] {"cobblestone", "cobbled_deepslate", "dirt", "andesite", "diorite", "granite", "tuff"}) {
            if (colony().warehouse().take(level, Ids.mc(id), 1) == 1) {
                Placer.place(level, villager, pos, org.lts.smartcolony.colony.Construction.parse(Ids.mc(id), 0));
                return true;
            }
        }
        return false;
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
                    || path.equals("dirt") && !TreeFarmPlan.keepDirt(Inv.count(villager, id), colony().stockOf(level, found -> found.equals(id)))
                    || path.equals("calcite") || path.equals("dripstone_block") || path.equals("netherrack")
                    || path.equals("pointed_dripstone") || path.equals("smooth_basalt");
            if (junk || (stone && Inv.count(villager, ItemIds.id(drop)) >= 96)) {
                colony().data().bump("rubble_discarded", drop.getCount());
                continue;
            }
            kept.add(drop);
        }
        super.stash(level, villager, kept);
    }

    @Override
    protected boolean usesOutposts() {
        return true;
    }

    @Override
    protected String walkKind() {
        return "mine";
    }

    private void deposit(ServerLevel level, Villager villager) {
        if (!atDrop(level, villager)) {
            if (goDrop(level, villager) == Mover.Result.FAILED) {
                wait = 100;
            }
            return;
        }
        ColonyData.Outpost outpost = dropSite(level, villager);
        dropAll(level, villager);
        if (outpost != null) {
            restock(level, villager, outpost, Ids.mc("cobblestone"), 24);
        }
        fitItems.clear();
        int extraCobble = Inv.count(villager, Ids.mc("cobblestone")) - 24;
        if (extraCobble > 0) {
            Inv.remove(villager, stack -> stack.is(Items.COBBLESTONE), extraCobble);
            colony().warehouse().give(level, Ids.mc("cobblestone"), extraCobble);
        }
        phase = "start";
    }
}
