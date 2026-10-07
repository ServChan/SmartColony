package org.lts.smartcolony.colony;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.lts.smartcolony.core.Blueprints;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.TreeFarmPlan;
import org.lts.smartcolony.util.WorldUtil;

public final class TreeFarm {
    private final Colony colony;
    private final Map<ColonyData.Building, List<BlockPos>> plotCache = new IdentityHashMap<>();
    private final Map<Long, UUID> claims = new HashMap<>();

    public TreeFarm(Colony colony) {
        this.colony = colony;
    }

    public static boolean soilFor(BlockState current, BlockState target) {
        return target.is(Blocks.DIRT) && current.is(BlockTags.SUBSTRATE_OVERWORLD) && !current.is(Blocks.DIRT_PATH)
                && !current.is(Blocks.FARMLAND) && !current.is(Blocks.MUD);
    }

    public List<ColonyData.Building> farms() {
        List<ColonyData.Building> farms = new ArrayList<>();
        for (ColonyData.Building building : colony.data().buildings) {
            if (TreeFarmPlan.KIND.equals(building.kind)) {
                farms.add(building);
            }
        }
        return farms;
    }

    public boolean exists() {
        for (ColonyData.Building building : colony.data().buildings) {
            if (TreeFarmPlan.KIND.equals(building.kind)) {
                return true;
            }
        }
        return false;
    }

    public List<BlockPos> plots(ColonyData.Building building) {
        List<BlockPos> cached = plotCache.get(building);
        if (cached != null) {
            return cached;
        }
        List<BlockPos> plots = new ArrayList<>();
        for (int[] cell : TreeFarmPlan.plots(Blueprints.get(building.blueprint))) {
            plots.add(colony.construction().world(building, cell[0], 1, cell[1]));
        }
        plotCache.put(building, plots);
        return plots;
    }

    public boolean contains(BlockPos pos) {
        for (ColonyData.Building building : colony.data().buildings) {
            if (TreeFarmPlan.KIND.equals(building.kind) && pos.getX() >= building.min.x() && pos.getX() <= building.max.x()
                    && pos.getZ() >= building.min.z() && pos.getZ() <= building.max.z()
                    && pos.getY() >= building.origin.y() - 1 && pos.getY() <= building.origin.y() + 3) {
                return true;
            }
        }
        return false;
    }

    public static boolean plotFree(ServerLevel level, BlockPos plot) {
        return WorldUtil.loaded(level, plot) && WorldUtil.isAirLike(level.getBlockState(plot))
                && level.getBlockState(plot.below()).is(BlockTags.SUBSTRATE_OVERWORLD);
    }

    public int freePlots(ServerLevel level, UUID worker) {
        int free = 0;
        for (ColonyData.Building building : farms()) {
            for (BlockPos plot : plots(building)) {
                UUID owner = claims.get(plot.asLong());
                if (plotFree(level, plot) && (owner == null || owner.equals(worker))) {
                    free++;
                }
            }
        }
        return free;
    }

    public BlockPos claimPlot(ServerLevel level, UUID worker, BlockPos from) {
        release(worker);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (ColonyData.Building building : farms()) {
            for (BlockPos plot : plots(building)) {
                if (claims.containsKey(plot.asLong()) || !plotFree(level, plot)) {
                    continue;
                }
                double distance = plot.distSqr(from);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = plot;
                }
            }
        }
        if (best != null) {
            claims.put(best.asLong(), worker);
        }
        return best;
    }

    public void release(UUID worker) {
        claims.values().removeIf(worker::equals);
    }
}
