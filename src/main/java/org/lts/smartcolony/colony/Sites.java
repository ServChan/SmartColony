package org.lts.smartcolony.colony;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;
import org.lts.smartcolony.SmartColonyConfig;
import org.lts.smartcolony.core.Blueprint;
import org.lts.smartcolony.core.Blueprints;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.MinePlan;
import org.lts.smartcolony.core.Pos;
import org.lts.smartcolony.core.Prospecting;
import org.lts.smartcolony.util.WorldUtil;
import org.lts.smartcolony.work.TreeScan;

public final class Sites {
    private final Colony colony;
    private final List<BlockPos[]> fishingSpots = new ArrayList<>();
    private final Map<BlockPos, Long> rejectedFishing = new HashMap<>();
    private long fishingSearchedAt = -100000;

    public Sites(Colony colony) {
        this.colony = colony;
    }

    private BlockPos center() {
        return colony.center();
    }

    private static boolean canopyFree(ServerLevel level, BlockPos feet) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, feet.getX(), feet.getZ()) <= feet.getY();
    }

    public BlockPos plantingSpot(ServerLevel level, RandomSource random) {
        int radius = SmartColonyConfig.get().forestRadius;
        for (int attempt = 0; attempt < 40; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = 12 + random.nextDouble() * Math.max(1, radius - 12);
            int x = center().getX() + (int) Math.round(Math.cos(angle) * distance);
            int z = center().getZ() + (int) Math.round(Math.sin(angle) * distance);
            if (!WorldUtil.loaded(level, new BlockPos(x, 0, z))) {
                continue;
            }
            BlockPos feet = WorldUtil.surface(level, x, z);
            if (!level.getBlockState(feet.below()).is(BlockTags.SUBSTRATE_OVERWORLD) || !WorldUtil.isAirLike(level.getBlockState(feet))
                    || !level.getFluidState(feet).isEmpty() || !canopyFree(level, feet) || colony.isProtected(feet)
                    || onRoad(feet.below()) || !colony.treeAllowed(level, feet)) {
                continue;
            }
            boolean crowded = false;
            for (BlockPos check : BlockPos.betweenClosed(feet.offset(-3, 0, -3), feet.offset(3, 3, 3))) {
                BlockState state = level.getBlockState(check);
                if (TreeScan.isLog(state) || state.is(BlockTags.SAPLINGS)) {
                    crowded = true;
                    break;
                }
            }
            if (!crowded) {
                return feet.immutable();
            }
        }
        return null;
    }

    public boolean onRoad(BlockPos ground) {
        return colony.data().roads.contains(WorldUtil.pos(ground));
    }

    public ColonyData.Mine mineSite(ServerLevel level, int generation, ColonyData.Mine previous) {
        return mineSite(level, generation, previous, colony.allMines(), null);
    }

    public ColonyData.Mine mineSite(ServerLevel level, int generation, ColonyData.Mine previous, List<ColonyData.Mine> others, Pos anchor) {
        List<ColonyData.Mine> avoid = new ArrayList<>();
        for (ColonyData.Mine other : others) {
            if (other != previous) {
                avoid.add(other);
            }
        }
        if (anchor != null) {
            BlockPos origin = new BlockPos(anchor.x(), anchor.y(), anchor.z());
            ColonyData.Mine quarry = quarrySite(level, generation, previous, avoid, origin, 6, 21, origin);
            return quarry != null ? quarry : stairSite(level, generation, previous, avoid, origin, 8, 20, origin);
        }
        int reach = avoid.isEmpty() ? 0 : Math.min(Prospecting.MINE_REACH, colony.data().radius);
        ColonyData.Mine quarry = quarrySite(level, generation, previous, avoid, center(), 24, 48 + reach, null);
        return quarry != null ? quarry : stairSite(level, generation, previous, avoid, center(), 12, 30 + reach, null);
    }

    private static final int VILLAGE_GAP = 8;
    private static final int DEPOT_GAP = 18;

    private boolean awayFromVillage(int x0, int z0, int x1, int z1) {
        ColonyData data = colony.data();
        if (Math.max(Math.max(data.depot.x() - x1, x0 - data.depot.x()), Math.max(data.depot.z() - z1, z0 - data.depot.z())) < DEPOT_GAP) {
            return false;
        }
        List<ColonyData.Building> all = new ArrayList<>(data.buildings);
        for (ColonyData.Project project : data.projects) {
            all.add(project.building);
        }
        for (ColonyData.Building building : all) {
            if (building.min.x() - VILLAGE_GAP <= x1 && building.max.x() + VILLAGE_GAP >= x0
                    && building.min.z() - VILLAGE_GAP <= z1 && building.max.z() + VILLAGE_GAP >= z0
                    && !Highways.remote(building)) {
                return false;
            }
        }
        return true;
    }

    private static boolean crowded(List<ColonyData.Mine> avoid, int x, int z, int gap) {
        for (ColonyData.Mine mine : avoid) {
            Pos at = mine.quarry() ? mine.corner : mine.entrance;
            if (at != null && new Pos(x, 0, z).horizontalDistSq(at) < gap * gap) {
                return true;
            }
        }
        return false;
    }

    private static final int QUARRY_WIDTH = 7;

    private int groundY(ServerLevel level, int x, int z) {
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        int floor = y - 8;
        while (y > floor && WorldUtil.removable(level, new BlockPos(x, y, z))) {
            y--;
        }
        return y;
    }

    private ColonyData.Mine quarrySite(ServerLevel level, int generation, ColonyData.Mine previous, List<ColonyData.Mine> avoid,
                                       BlockPos origin, int minDistance, int maxDistance, BlockPos toward) {
        int targetY = SmartColonyConfig.get().mineTargetY;
        double offset = generation * Math.PI / 5.0;
        for (int distance = minDistance; distance <= maxDistance; distance += 3) {
            ColonyData.Mine best = null;
            int bestScore = Integer.MAX_VALUE;
            for (int i = 0; i < 16; i++) {
                double angle = offset + i * Math.PI / 8.0;
                int cx = origin.getX() + (int) Math.round(Math.cos(angle) * distance) - QUARRY_WIDTH / 2;
                int cz = origin.getZ() + (int) Math.round(Math.sin(angle) * distance) - QUARRY_WIDTH / 2;
                if (previous != null && previous.quarry()
                        && new org.lts.smartcolony.core.Pos(cx, 0, cz).horizontalDistSq(previous.corner) < 20 * 20) {
                    continue;
                }
                if (crowded(avoid, cx, cz, 20) || !awayFromVillage(cx - 2, cz - 2, cx + QUARRY_WIDTH + 1, cz + QUARRY_WIDTH + 1)
                        || toward != null && center().distSqr(new BlockPos(cx, center().getY(), cz))
                        > (double) (colony.data().radius + Prospecting.MINE_REACH) * (colony.data().radius + Prospecting.MINE_REACH)) {
                    continue;
                }
                int score = quarryScore(level, cx, cz);
                if (score < bestScore) {
                    bestScore = score;
                    int top = groundY(level, cx, cz);
                    ColonyData.Mine mine = new ColonyData.Mine();
                    mine.type = "quarry";
                    mine.width = QUARRY_WIDTH;
                    mine.corner = new org.lts.smartcolony.core.Pos(cx, top, cz);
                    mine.depth = Math.max(6, Math.min(50, org.lts.smartcolony.core.QuarryPlan.layersFor(top, targetY)));
                    mine.entrance = mine.plan().entrance();
                    best = mine;
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    private int quarryScore(ServerLevel level, int cx, int cz) {
        if (!WorldUtil.loaded(level, new BlockPos(cx - 3, 0, cz - 3)) || !WorldUtil.loaded(level, new BlockPos(cx + QUARRY_WIDTH + 2, 0, cz + QUARRY_WIDTH + 2))) {
            return Integer.MAX_VALUE;
        }
        BlockPos middle = new BlockPos(cx + QUARRY_WIDTH / 2, 0, cz + QUARRY_WIDTH / 2);
        if (level.getPoiManager().getInSquare(type -> true, middle.atY(level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, middle.getX(), middle.getZ())),
                QUARRY_WIDTH / 2 + 8, net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY).findAny().isPresent()) {
            return Integer.MAX_VALUE;
        }
        int top = groundY(level, cx, cz);
        int entrance = groundY(level, cx - 1, cz);
        if (entrance != top || !WorldUtil.naturalGround(level.getBlockState(new BlockPos(cx - 1, top, cz)))) {
            return Integer.MAX_VALUE;
        }
        int score = 0;
        for (int x = cx - 2; x < cx + QUARRY_WIDTH + 2; x++) {
            for (int z = cz - 2; z < cz + QUARRY_WIDTH + 2; z++) {
                int y = groundY(level, x, z);
                BlockPos ground = new BlockPos(x, y, z);
                if (!level.getFluidState(ground).isEmpty() || !level.getFluidState(ground.above()).isEmpty()) {
                    return Integer.MAX_VALUE;
                }
                if (colony.isProtected(ground.above()) || onRoad(ground)) {
                    return Integer.MAX_VALUE;
                }
                BlockState groundState = level.getBlockState(ground);
                if (!WorldUtil.naturalGround(groundState) && !groundState.is(BlockTags.LOGS) && !groundState.is(BlockTags.LEAVES)) {
                    return Integer.MAX_VALUE;
                }
                boolean pit = x >= cx && x < cx + QUARRY_WIDTH && z >= cz && z < cz + QUARRY_WIDTH;
                if (pit) {
                    if (y < top - 1 || y > top + 2 || !WorldUtil.naturalGround(level.getBlockState(ground))) {
                        return Integer.MAX_VALUE;
                    }
                    score += Math.abs(y - top);
                }
            }
        }
        return score;
    }

    private ColonyData.Mine stairSite(ServerLevel level, int generation, ColonyData.Mine previous, List<ColonyData.Mine> avoid,
                                      BlockPos origin, int minDistance, int maxDistance, BlockPos toward) {
        int targetY = SmartColonyConfig.get().mineTargetY;
        double offset = generation * Math.PI / 4.0;
        for (int distance = minDistance; distance <= maxDistance; distance += 4) {
            for (int i = 0; i < 8; i++) {
                double angle = offset + i * Math.PI / 4.0;
                int x = origin.getX() + (int) Math.round(Math.cos(angle) * distance);
                int z = origin.getZ() + (int) Math.round(Math.sin(angle) * distance);
                if (!WorldUtil.loaded(level, new BlockPos(x, 0, z)) || crowded(avoid, x, z, 24) || !awayFromVillage(x - 2, z - 2, x + 2, z + 2)) {
                    continue;
                }
                BlockPos feet = WorldUtil.surface(level, x, z);
                if (!WorldUtil.standable(level, feet) || !level.getFluidState(feet.below()).isEmpty() || colony.isProtected(feet)) {
                    continue;
                }
                int dx = toward != null ? toward.getX() - x : x - center().getX();
                int dz = toward != null ? toward.getZ() - z : z - center().getZ();
                int dirX = Math.abs(dx) >= Math.abs(dz) ? Integer.signum(dx) : 0;
                int dirZ = dirX == 0 ? Integer.signum(dz) : 0;
                if (dirX == 0 && dirZ == 0) {
                    dirX = 1;
                }
                if (previous != null && dirX == previous.dirX && dirZ == previous.dirZ
                        && WorldUtil.pos(feet).horizontalDistSq(previous.entrance) < 40 * 40) {
                    continue;
                }
                int depth = Math.max(4, Math.min(90, MinePlan.depthFor(feet.getY(), targetY)));
                MinePlan plan = new MinePlan(WorldUtil.pos(feet), dirX, dirZ, depth);
                boolean ok = true;
                for (int k = 1; k <= 6 && ok; k++) {
                    BlockPos step = WorldUtil.bp(plan.stair(k));
                    if (!WorldUtil.loaded(level, step) || colony.isProtected(step)) {
                        ok = false;
                        break;
                    }
                    for (int dy = 0; dy < 3; dy++) {
                        if (!level.getFluidState(step.above(dy)).isEmpty()) {
                            ok = false;
                        }
                    }
                }
                if (!ok) {
                    continue;
                }
                ColonyData.Mine mine = new ColonyData.Mine();
                mine.entrance = WorldUtil.pos(feet);
                mine.dirX = dirX;
                mine.dirZ = dirZ;
                mine.depth = depth;
                return mine;
            }
        }
        return null;
    }

    public void discoverFarms(ServerLevel level) {
        int radius = colony.data().radius;
        BlockPos center = center();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                if (!WorldUtil.loaded(level, new BlockPos(x, 0, z))) {
                    continue;
                }
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                BlockPos top = new BlockPos(x, y, z);
                BlockState state = level.getBlockState(top);
                if (!(state.getBlock() instanceof FarmlandBlock)) {
                    BlockState below = level.getBlockState(top.below());
                    if (below.getBlock() instanceof FarmlandBlock) {
                        top = top.below();
                    } else {
                        continue;
                    }
                }
                addAnchor(top);
            }
        }
    }

    private void addAnchor(BlockPos pos) {
        Pos p = WorldUtil.pos(pos);
        for (Pos farm : colony.data().farms) {
            if (farm.horizontalDistSq(p) < 49 && Math.abs(farm.y() - p.y()) <= 2) {
                return;
            }
        }
        if (colony.data().farms.size() < 24) {
            colony.data().farms.add(p);
            colony.markDirty();
        }
    }

    public BlockPos grass(ServerLevel level, BlockPos near) {
        for (int r = 1; r <= 14; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) {
                        continue;
                    }
                    int x = near.getX() + dx;
                    int z = near.getZ() + dz;
                    if (!WorldUtil.loaded(level, new BlockPos(x, 0, z))) {
                        continue;
                    }
                    BlockPos feet = WorldUtil.surface(level, x, z);
                    BlockState state = level.getBlockState(feet);
                    if (state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS) || state.is(Blocks.FERN)) {
                        return feet.below();
                    }
                }
            }
        }
        return null;
    }

    public BlockPos water(ServerLevel level, BlockPos near, int radius) {
        for (int r = 0; r <= radius; r += 2) {
            for (int dx = -r; dx <= r; dx += 2) {
                for (int dz = -r; dz <= r; dz += 2) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) {
                        continue;
                    }
                    int x = near.getX() + dx;
                    int z = near.getZ() + dz;
                    if (!WorldUtil.loaded(level, new BlockPos(x, 0, z))) {
                        continue;
                    }
                    BlockPos top = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1, z);
                    if (level.getFluidState(top).isSourceOfType(Fluids.WATER)) {
                        return top;
                    }
                }
            }
        }
        return null;
    }

    public void rejectFishing(BlockPos shore) {
        rejectedFishing.put(shore, Long.MAX_VALUE);
        fishingSpots.removeIf(spot -> spot[0].equals(shore));
    }

    private BlockPos[] dockSpot(ServerLevel level, BlockPos from) {
        BlockPos[] best = null;
        double bestDistance = Double.MAX_VALUE;
        for (ColonyData.Building building : colony.data().buildings) {
            Blueprint blueprint = Blueprints.get(building.blueprint);
            for (int[] f : blueprint.fishing()) {
                BlockPos stand = colony.construction().world(building, f[0], f[1], f[2]);
                BlockPos water = colony.construction().world(building, f[3], f[4], f[5]);
                if (rejectedFishing.containsKey(stand) || !WorldUtil.loaded(level, stand) || !WorldUtil.loaded(level, water)
                        || !level.getFluidState(water).isSourceOfType(Fluids.WATER) || !WorldUtil.standable(level, stand)) {
                    continue;
                }
                double distance = stand.distSqr(from);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = new BlockPos[] {stand, water};
                }
            }
        }
        return best;
    }

    public BlockPos[] fishingSpot(ServerLevel level, BlockPos from) {
        BlockPos[] dock = dockSpot(level, from);
        if (dock != null) {
            return dock;
        }
        long now = level.getGameTime();
        if (fishingSpots.isEmpty() && now - fishingSearchedAt > 1200) {
            fishingSearchedAt = now;
            searchFishing(level);
        }
        BlockPos[] best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos[] spot : fishingSpots) {
            double distance = spot[0].distSqr(from);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = spot;
            }
        }
        return best;
    }

    private void searchFishing(ServerLevel level) {
        int radius = 48;
        for (int r = 4; r <= radius && fishingSpots.size() < 6; r += 2) {
            for (int dx = -r; dx <= r; dx += 2) {
                for (int dz = -r; dz <= r; dz += 2) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) {
                        continue;
                    }
                    int x = center().getX() + dx;
                    int z = center().getZ() + dz;
                    if (!WorldUtil.loaded(level, new BlockPos(x, 0, z))) {
                        continue;
                    }
                    BlockPos water = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1, z);
                    if (!level.getFluidState(water).isSourceOfType(Fluids.WATER)) {
                        continue;
                    }
                    int area = 0;
                    for (BlockPos check : BlockPos.betweenClosed(water.offset(-2, 0, -2), water.offset(2, 0, 2))) {
                        if (level.getFluidState(check).is(FluidTags.WATER)) {
                            area++;
                        }
                    }
                    if (area < 14) {
                        continue;
                    }
                    BlockPos shore = shoreNear(level, water);
                    if (shore != null && !rejectedFishing.containsKey(shore)) {
                        fishingSpots.add(new BlockPos[]{shore, water.immutable()});
                        if (fishingSpots.size() >= 6) {
                            return;
                        }
                    }
                }
            }
        }
    }

    private BlockPos shoreNear(ServerLevel level, BlockPos water) {
        for (int r = 1; r <= 4; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) {
                        continue;
                    }
                    BlockPos feet = WorldUtil.surface(level, water.getX() + dx, water.getZ() + dz);
                    if (feet.getY() == water.getY() + 1 && WorldUtil.standable(level, feet) && level.getFluidState(feet.below()).isEmpty()) {
                        return feet.immutable();
                    }
                }
            }
        }
        return null;
    }
}
