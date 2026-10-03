package org.lts.smartcolony.colony;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.MinePlan;
import org.lts.smartcolony.core.Pos;
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
            if (!level.getBlockState(feet.below()).is(BlockTags.DIRT) || !WorldUtil.isAirLike(level.getBlockState(feet))
                    || !level.getFluidState(feet).isEmpty() || !canopyFree(level, feet) || colony.isProtected(feet)
                    || onRoad(feet.below())) {
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

    public ColonyData.Mine mineSite(ServerLevel level, int generation) {
        int targetY = SmartColonyConfig.get().mineTargetY;
        double offset = generation * Math.PI / 4.0;
        for (int distance = 12; distance <= 30; distance += 4) {
            for (int i = 0; i < 8; i++) {
                double angle = offset + i * Math.PI / 4.0;
                int x = center().getX() + (int) Math.round(Math.cos(angle) * distance);
                int z = center().getZ() + (int) Math.round(Math.sin(angle) * distance);
                if (!WorldUtil.loaded(level, new BlockPos(x, 0, z))) {
                    continue;
                }
                BlockPos feet = WorldUtil.surface(level, x, z);
                if (!WorldUtil.standable(level, feet) || !level.getFluidState(feet.below()).isEmpty() || colony.isProtected(feet)) {
                    continue;
                }
                int dx = x - center().getX();
                int dz = z - center().getZ();
                int dirX = Math.abs(dx) >= Math.abs(dz) ? Integer.signum(dx) : 0;
                int dirZ = dirX == 0 ? Integer.signum(dz) : 0;
                if (dirX == 0 && dirZ == 0) {
                    dirX = 1;
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

    public boolean expandFarms(ServerLevel level) {
        RandomSource random = level.getRandom();
        int radius = Math.max(16, colony.data().radius + 8);
        for (int attempt = 0; attempt < 160; attempt++) {
            int x = center().getX() + random.nextInt(radius * 2 + 1) - radius;
            int z = center().getZ() + random.nextInt(radius * 2 + 1) - radius;
            if (!WorldUtil.loaded(level, new BlockPos(x, 0, z))) {
                continue;
            }
            BlockPos water = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1, z);
            if (!level.getFluidState(water).isSourceOfType(Fluids.WATER) || colony.isProtected(water.above())) {
                continue;
            }
            int land = 0;
            for (BlockPos check : BlockPos.betweenClosed(water.offset(-4, 0, -4), water.offset(4, 0, 4))) {
                BlockState state = level.getBlockState(check);
                if ((state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT)) && WorldUtil.isAirLike(level.getBlockState(check.above()))) {
                    land++;
                }
            }
            if (land < 8) {
                continue;
            }
            int before = colony.data().farms.size();
            addAnchor(water);
            if (colony.data().farms.size() > before) {
                BlockPos seed = null;
                for (Direction direction : Direction.Plane.HORIZONTAL) {
                    BlockPos side = water.relative(direction);
                    BlockState state = level.getBlockState(side);
                    if ((state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT)) && WorldUtil.isAirLike(level.getBlockState(side.above()))) {
                        seed = side;
                        break;
                    }
                }
                if (seed != null) {
                    if (!level.getBlockState(seed.above()).isAir()) {
                        level.destroyBlock(seed.above(), true);
                    }
                    level.setBlock(seed, Blocks.FARMLAND.defaultBlockState(), 3);
                }
                return true;
            }
        }
        return false;
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

    public BlockPos pondSite(ServerLevel level) {
        RandomSource random = level.getRandom();
        for (int attempt = 0; attempt < 60; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = 10 + random.nextDouble() * 22;
            int x = center().getX() + (int) Math.round(Math.cos(angle) * distance);
            int z = center().getZ() + (int) Math.round(Math.sin(angle) * distance);
            if (!WorldUtil.loaded(level, new BlockPos(x - 5, 0, z - 5)) || !WorldUtil.loaded(level, new BlockPos(x + 5, 0, z + 5))) {
                continue;
            }
            BlockPos ground = WorldUtil.surface(level, x, z).below();
            boolean flat = true;
            for (int dx = -2; dx <= 2 && flat; dx++) {
                for (int dz = -2; dz <= 2 && flat; dz++) {
                    BlockPos column = WorldUtil.surface(level, x + dx, z + dz).below();
                    BlockState state = level.getBlockState(column);
                    if (column.getY() != ground.getY() || !(state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT))
                            || colony.isProtected(column.above()) || !canopyFree(level, column.above())) {
                        flat = false;
                    }
                }
            }
            if (flat) {
                return ground.immutable();
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

    public BlockPos[] fishingSpot(ServerLevel level, BlockPos from) {
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
