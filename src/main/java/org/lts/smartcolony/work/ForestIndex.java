package org.lts.smartcolony.work;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.levelgen.Heightmap;
import org.lts.smartcolony.SmartColonyConfig;
import org.lts.smartcolony.colony.Colony;
import org.lts.smartcolony.util.WorldUtil;

public final class ForestIndex {
    private static final int STEP = 2;
    private static final int COLUMNS_PER_TICK = 48;

    private final Colony colony;
    private final Set<BlockPos> trees = new HashSet<>();
    private final Map<BlockPos, UUID> claims = new HashMap<>();
    private final Map<BlockPos, Long> rejected = new HashMap<>();
    private List<int[]> spiral;
    private int cursor;
    private int lastFullCount;
    private int foundThisPass;

    public ForestIndex(Colony colony) {
        this.colony = colony;
    }

    public int count() {
        return Math.max(trees.size(), lastFullCount);
    }

    public int known() {
        return trees.size();
    }

    private List<int[]> spiral() {
        if (spiral == null) {
            int radius = SmartColonyConfig.get().forestRadius;
            List<int[]> offsets = new ArrayList<>();
            for (int dx = -radius; dx <= radius; dx += STEP) {
                for (int dz = -radius; dz <= radius; dz += STEP) {
                    if (dx * dx + dz * dz <= radius * radius) {
                        offsets.add(new int[]{dx, dz});
                    }
                }
            }
            offsets.sort(Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1]));
            spiral = offsets;
        }
        return spiral;
    }

    public void scan(ServerLevel level) {
        List<int[]> offsets = spiral();
        BlockPos center = colony.center();
        for (int i = 0; i < COLUMNS_PER_TICK; i++) {
            if (cursor >= offsets.size()) {
                cursor = 0;
                lastFullCount = foundThisPass;
                foundThisPass = 0;
                trees.removeIf(base -> !WorldUtil.loaded(level, base) || !TreeScan.isLog(level.getBlockState(base)));
            }
            int[] offset = offsets.get(cursor++);
            int x = center.getX() + offset[0];
            int z = center.getZ() + offset[1];
            BlockPos column = new BlockPos(x, center.getY(), z);
            if (!WorldUtil.loaded(level, column)) {
                continue;
            }
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            BlockPos topPos = new BlockPos(x, top, z);
            if (!TreeScan.isLog(level.getBlockState(topPos))) {
                continue;
            }
            BlockPos base = TreeScan.baseOf(level, topPos);
            if (!level.getBlockState(base.below()).is(BlockTags.DIRT) || colony.isProtected(base)) {
                continue;
            }
            if (near(base)) {
                continue;
            }
            if (!TreeScan.naturalTree(level, base).isEmpty()) {
                trees.add(base.immutable());
                foundThisPass++;
            }
        }
    }

    private boolean near(BlockPos base) {
        for (BlockPos tree : trees) {
            if (Math.abs(tree.getX() - base.getX()) <= 1 && Math.abs(tree.getZ() - base.getZ()) <= 1) {
                return true;
            }
        }
        return false;
    }

    public BlockPos claim(ServerLevel level, UUID worker, BlockPos from) {
        release(worker);
        long now = level.getGameTime();
        rejected.entrySet().removeIf(entry -> entry.getValue() < now);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        Iterator<BlockPos> it = trees.iterator();
        while (it.hasNext()) {
            BlockPos tree = it.next();
            if (claims.containsKey(tree) || rejected.containsKey(tree)) {
                continue;
            }
            if (!WorldUtil.loaded(level, tree)) {
                continue;
            }
            if (!TreeScan.isLog(level.getBlockState(tree))) {
                it.remove();
                continue;
            }
            double distance = tree.distSqr(from);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = tree;
            }
        }
        if (best != null) {
            claims.put(best, worker);
        }
        return best;
    }

    public void release(UUID worker) {
        claims.values().removeIf(worker::equals);
    }

    public void reject(ServerLevel level, BlockPos tree) {
        rejected.put(tree, level.getGameTime() + 6000);
        claims.remove(tree);
    }

    public void felled(BlockPos tree) {
        trees.remove(tree);
        claims.remove(tree);
    }
}
