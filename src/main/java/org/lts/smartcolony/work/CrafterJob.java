package org.lts.smartcolony.work;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.CraftPlanner;
import org.lts.smartcolony.core.CrafterNeeds;
import org.lts.smartcolony.core.RecipeBook;
import org.lts.smartcolony.core.Role;

public final class CrafterJob extends Job {
    private static final int SCAN_RADIUS = 20;

    private final Role role;
    private CrafterNeeds.Want order;
    private int amount;
    private BlockPos station;
    private final Set<BlockPos> rejected = new HashSet<>();
    private int progress;
    private int total;
    private long scannedAt = Long.MIN_VALUE / 2;
    private boolean smeltScan;

    public CrafterJob(Colonist colonist, Role role) {
        super(colonist);
        this.role = role;
        depositThreshold = 4;
    }

    @Override
    public Role role() {
        return role;
    }

    @Override
    protected Predicate<ItemStack> tools() {
        return stack -> false;
    }

    @Override
    public void onRelease(ServerLevel level, Villager villager) {
        super.onRelease(level, villager);
        order = null;
    }

    @Override
    public void tick(ServerLevel level, Villager villager) {
        if (wait > 0) {
            wait--;
            return;
        }
        switch (phase) {
            case "start" -> start(level, villager);
            case "fetch" -> fetch(level, villager);
            case "walk" -> walk(level, villager);
            case "work" -> work(level, villager);
            case "deposit" -> deposit(level, villager);
            default -> phase = "start";
        }
    }

    private void start(ServerLevel level, Villager villager) {
        if (hasCargo(villager)) {
            phase = "deposit";
            return;
        }
        if (!colony().workTime(level)) {
            wait = 100;
            return;
        }
        var context = colony().crafterContext(level);
        for (CrafterNeeds.Want want : CrafterNeeds.wants(role, context)) {
            int count = feasible(context.stock(), want);
            if (count > 0) {
                order = want;
                amount = count;
                rejected.clear();
                phase = "fetch";
                status = "preparing " + org.lts.smartcolony.core.Ids.path(want.item());
                return;
            }
        }
        status = "nothing to make";
        idle(level, 600);
    }

    private int feasible(org.lts.smartcolony.core.Stock stock, CrafterNeeds.Want want) {
        if (CraftPlanner.plan(stock, want.item(), want.count()).isPresent()) {
            return want.count();
        }
        if (want.count() > 1 && CraftPlanner.plan(stock, want.item(), 1).isPresent()) {
            return 1;
        }
        return 0;
    }

    private void fetch(ServerLevel level, Villager villager) {
        if (order == null) {
            phase = "start";
            return;
        }
        if (!atDepot(level, villager)) {
            if (goDepot(level, villager) == Mover.Result.FAILED) {
                status = "cannot reach storage";
                phase = "start";
                wait = 100;
            }
            return;
        }
        var stock = colony().warehouse().stock(level);
        if (feasible(stock, order) < amount) {
            phase = "start";
            wait = 40;
            return;
        }
        boolean smelt = order.smelt();
        if (station == null || smelt != smeltScan || !isStation(level, station) || level.getGameTime() - scannedAt > 2400) {
            station = findStation(level);
            scannedAt = level.getGameTime();
            smeltScan = smelt;
        }
        status = "taking materials";
        int steps = Math.max(1, (amount + outputPerCraft() - 1) / outputPerCraft());
        total = Math.max(30, (110 - skill() * 14)) * Math.min(steps, 4);
        progress = total;
        phase = station == null ? "work" : "walk";
    }

    private int outputPerCraft() {
        List<org.lts.smartcolony.core.Recipe> recipes = RecipeBook.recipesFor(order.item());
        return recipes.isEmpty() ? 1 : Math.max(1, recipes.getFirst().count());
    }

    private void walk(ServerLevel level, Villager villager) {
        if (station == null) {
            phase = "work";
            return;
        }
        if (!isStation(level, station)) {
            rejected.add(station);
            station = null;
            phase = "work";
            return;
        }
        status = "going to the workstation";
        Mover.Result result = move(level, villager, station, 2.4);
        if (result == Mover.Result.FAILED) {
            rejected.add(station);
            station = findStation(level);
            if (station == null) {
                phase = "fetch";
            }
            return;
        }
        if (result == Mover.Result.ARRIVED) {
            phase = "work";
        }
    }

    private void work(ServerLevel level, Villager villager) {
        if (order == null || !colony().workTime(level)) {
            phase = "start";
            return;
        }
        BlockPos focus = station != null ? station : colony().depot();
        if (station != null && Mover.distance(villager, station) > 4.5) {
            phase = "walk";
            return;
        }
        status = "making " + org.lts.smartcolony.core.Ids.path(order.item());
        Vec3 look = Vec3.atCenterOf(focus);
        villager.getLookControl().setLookAt(look.x, look.y, look.z);
        if (progress % 12 == 0) {
            villager.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            level.playSound(null, villager.blockPosition(), workSound(), SoundSource.NEUTRAL, 0.5F, 1.0F);
            level.sendParticles(role == Role.COOK ? ParticleTypes.SMOKE : ParticleTypes.CRIT, look.x, look.y + 0.6, look.z, 3, 0.2, 0.1, 0.2, 0.02);
        }
        if (--progress > 0) {
            return;
        }
        boolean done = colony().craft(level, order.item(), amount);
        if (done) {
            gainXp(level, villager, 2 + amount / 4);
        } else {
            colony().request(order.item());
        }
        order = null;
        phase = "start";
        wait = 20;
    }

    private SoundEvent workSound() {
        return switch (role) {
            case FLETCHER -> SoundEvents.VILLAGER_WORK_FLETCHER;
            case SMITH -> SoundEvents.VILLAGER_WORK_TOOLSMITH;
            default -> SoundEvents.VILLAGER_WORK_BUTCHER;
        };
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

    private List<Block> kinds() {
        boolean smelt = order != null && order.smelt();
        return switch (role) {
            case FLETCHER -> List.of(Blocks.FLETCHING_TABLE, Blocks.CRAFTING_TABLE);
            case SMITH -> List.of(Blocks.SMITHING_TABLE, Blocks.ANVIL, Blocks.CRAFTING_TABLE);
            default -> smelt ? List.of(Blocks.SMOKER, Blocks.FURNACE) : List.of(Blocks.CRAFTING_TABLE);
        };
    }

    private boolean isStation(ServerLevel level, BlockPos pos) {
        return kinds().contains(level.getBlockState(pos).getBlock());
    }

    private BlockPos findStation(ServerLevel level) {
        List<Block> kinds = kinds();
        BlockPos depot = colony().depot();
        BlockPos best = null;
        int bestRank = Integer.MAX_VALUE;
        double bestDistance = Double.MAX_VALUE;
        List<BlockPos[]> regions = new java.util.ArrayList<>();
        regions.add(new BlockPos[]{depot.offset(-SCAN_RADIUS, -3, -SCAN_RADIUS), depot.offset(SCAN_RADIUS, 5, SCAN_RADIUS)});
        for (ColonyData.Building building : colony().data().buildings) {
            if (building.kind.equals("workshop")) {
                regions.add(new BlockPos[]{
                        new BlockPos(building.min.x(), building.min.y(), building.min.z()),
                        new BlockPos(building.max.x(), building.max.y(), building.max.z())});
            }
        }
        for (BlockPos[] region : regions) {
            for (BlockPos scan : BlockPos.betweenClosed(region[0], region[1])) {
                if (!level.getChunkSource().hasChunk(scan.getX() >> 4, scan.getZ() >> 4)) {
                    continue;
                }
                int rank = kinds.indexOf(level.getBlockState(scan).getBlock());
                if (rank < 0 || rank > bestRank || rejected.contains(scan)) {
                    continue;
                }
                double distance = scan.distSqr(depot);
                if (rank < bestRank || distance < bestDistance) {
                    bestRank = rank;
                    bestDistance = distance;
                    best = scan.immutable();
                }
            }
        }
        return best;
    }
}
