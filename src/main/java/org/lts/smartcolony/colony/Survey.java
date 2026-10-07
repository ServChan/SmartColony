package org.lts.smartcolony.colony;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.lts.smartcolony.SmartColonyConfig;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.TerrainMap;
import org.lts.smartcolony.util.WorldUtil;

public final class Survey {
    private static final int FULL_PASSES = 3;
    private static final double KNOWN_ENOUGH = 0.9;

    private final Colony colony;
    private TerrainMap map;
    private boolean complete;

    Survey(Colony colony) {
        this.colony = colony;
    }

    public TerrainMap map() {
        return map;
    }

    public boolean complete() {
        return complete;
    }

    public static int groundY(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        int floor = y - 8;
        while (y > floor && WorldUtil.removable(level, new BlockPos(x, y, z))) {
            y--;
        }
        return y;
    }

    private int wantedRadius() {
        ColonyData data = colony.data();
        int base = Math.max(data.radius, SmartColonyConfig.get().colonyRadius);
        return Math.min(SmartColonyConfig.get().maxColonyRadius, base) + SmartColonyConfig.get().surveyMargin;
    }

    public void tick(ServerLevel level) {
        int wanted = wantedRadius();
        if (map == null || map.radius() < wanted) {
            TerrainMap grown = new TerrainMap(colony.center().getX(), colony.center().getZ(), wanted);
            if (map != null) {
                grown.copyFrom(map);
            }
            map = grown;
            complete = false;
        }
        long now = level.getGameTime();
        SmartColonyConfig config = SmartColonyConfig.get();
        int budget = complete ? config.surveyRefreshPerTick : config.surveyColumnsPerTick;
        for (int i = 0; i < budget; i++) {
            int index = map.next();
            int x = map.xOf(index);
            int z = map.zOf(index);
            TerrainMap.Column column = sample(level, x, z, null);
            if (column != null) {
                map.set(x, z, column, now);
            }
        }
        if (!complete && map.wraps() >= 1) {
            double known = (double) map.knownCount() / map.columns();
            if (known >= KNOWN_ENOUGH || map.wraps() >= FULL_PASSES) {
                complete = true;
                announce(level, known);
            }
        }
    }

    private void announce(ServerLevel level, double known) {
        if (colony.data().terrainSurveyed) {
            return;
        }
        colony.data().terrainSurveyed = true;
        int[] counts = map.counts();
        int total = map.columns();
        TerrainMap.Stats stats = map.region(map.minX(), map.minZ(), map.minX() + map.size() - 1, map.minZ() + map.size() - 1);
        colony.journal(level, "survey complete: " + total + " columns, " + Math.round(known * 100) + "% known, ground "
                + stats.min() + ".." + stats.max() + ", water " + percent(counts[TerrainMap.Surface.WATER.ordinal()], total)
                + "%, trees " + percent(counts[TerrainMap.Surface.TREE.ordinal()], total)
                + "%, foreign structures " + percent(counts[TerrainMap.Surface.STRUCTURE.ordinal()], total) + "%");
        colony.markDirty();
    }

    private static long percent(int part, int total) {
        return Math.round(part * 100.0 / Math.max(1, total));
    }

    public void reserve(int x0, int z0, int x1, int z1, long time) {
        if (map != null) {
            map.paint(x0, z0, x1, z1, TerrainMap.Surface.BUILDING, time);
        }
    }

    private boolean inQuarry(int x, int z) {
        ColonyData.Mine mine = colony.data().mine;
        return mine != null && mine.quarry() && x >= mine.corner.x() - 1 && x <= mine.corner.x() + mine.width
                && z >= mine.corner.z() - 1 && z <= mine.corner.z() + mine.width;
    }

    public TerrainMap.Column sample(ServerLevel level, int x, int z, ColonyData.Project ignore) {
        if (!WorldUtil.loaded(level, new BlockPos(x, 0, z))) {
            return null;
        }
        int y = groundY(level, x, z);
        BlockPos top = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(top);
        if (!state.getFluidState().isEmpty() || !level.getFluidState(top.above()).isEmpty()) {
            int bed = y;
            int depth = 0;
            while (depth < 24 && !level.getFluidState(new BlockPos(x, bed, z)).isEmpty()) {
                bed--;
                depth++;
            }
            return new TerrainMap.Column(bed, TerrainMap.Surface.WATER, Math.max(1, depth));
        }
        BlockState above = level.getBlockState(top.above());
        if (state.is(Blocks.FARMLAND) || above.getBlock() instanceof CropBlock || above.getBlock() instanceof StemBlock) {
            return new TerrainMap.Column(y, TerrainMap.Surface.FARMLAND, 0);
        }
        if (inQuarry(x, z)) {
            return new TerrainMap.Column(y, TerrainMap.Surface.QUARRY, 0);
        }
        if (colony.isProtected(top.above(), true, ignore)) {
            return new TerrainMap.Column(y, TerrainMap.Surface.BUILDING, 0);
        }
        if (colony.sites().onRoad(top)) {
            return new TerrainMap.Column(y, TerrainMap.Surface.ROAD, 0);
        }
        if (!WorldUtil.naturalGround(state)) {
            if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)) {
                int ground = y;
                int floor = y - 40;
                while (ground > floor) {
                    BlockState below = level.getBlockState(new BlockPos(x, ground, z));
                    if (!below.is(BlockTags.LOGS) && !below.is(BlockTags.LEAVES) && !WorldUtil.removable(level, new BlockPos(x, ground, z))) {
                        break;
                    }
                    ground--;
                }
                return new TerrainMap.Column(ground, TerrainMap.Surface.TREE, 0);
            }
            return new TerrainMap.Column(y, TerrainMap.Surface.STRUCTURE, 0);
        }
        return new TerrainMap.Column(y, state.is(Blocks.DIRT_PATH) ? TerrainMap.Surface.PATH : TerrainMap.Surface.GROUND, 0);
    }
}
