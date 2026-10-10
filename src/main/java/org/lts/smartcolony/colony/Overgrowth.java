package org.lts.smartcolony.colony;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.Pos;
import org.lts.smartcolony.util.WorldUtil;

final class Overgrowth {
    private static final int COLUMNS_PER_RUN = 96;
    private static final int MAX_QUEUED = 240;
    private static final int MUSHROOM_LIMIT = 600;

    private final Colony colony;
    private List<int[]> spiral = List.of();
    private int spiralRadius = -1;
    private int cursor;

    Overgrowth(Colony colony) {
        this.colony = colony;
    }

    void tick(ServerLevel level, long ticks) {
        if (ticks % 40 != 17) {
            return;
        }
        ColonyData data = colony.data();
        long queued = data.orders.stream().filter(order -> Improvements.CLEARING.equals(order.tag)).count();
        if (queued >= MAX_QUEUED) {
            return;
        }
        int radius = data.radius + 4;
        if (radius != spiralRadius) {
            spiralRadius = radius;
            spiral = spiral(radius);
            cursor = 0;
        }
        BlockPos center = colony.center();
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < COLUMNS_PER_RUN && !spiral.isEmpty(); i++) {
            int[] offset = spiral.get(cursor);
            cursor = (cursor + 1) % spiral.size();
            int x = center.getX() + offset[0];
            int z = center.getZ() + offset[1];
            if (!WorldUtil.loaded(level, new BlockPos(x, 0, z))) {
                continue;
            }
            BlockPos top = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1, z);
            if (!WorldUtil.hugeMushroom(level.getBlockState(top)) || seen.contains(top.asLong())) {
                continue;
            }
            List<BlockPos> blocks = mushroom(level, top, seen);
            if (blocks.isEmpty() || !wanted(blocks)) {
                continue;
            }
            Set<Long> pending = new HashSet<>();
            for (ColonyData.Order order : data.orders) {
                pending.add(BlockPos.asLong(order.pos.x(), order.pos.y(), order.pos.z()));
            }
            int added = 0;
            for (BlockPos pos : blocks) {
                if (pending.add(pos.asLong())) {
                    data.orders.add(new ColonyData.Order(WorldUtil.pos(pos), "minecraft:air", Improvements.CLEARING));
                    added++;
                }
            }
            if (added > 0) {
                data.bump("mushrooms_marked", 1);
                colony.journal(level, "a giant mushroom at " + top.toShortString() + " is marked for clearing (" + added + " blocks)");
                colony.markDirty();
                return;
            }
        }
    }

    private boolean wanted(List<BlockPos> blocks) {
        ColonyData data = colony.data();
        BlockPos stem = blocks.getLast();
        Pos at = WorldUtil.pos(stem);
        if (!data.zones.isEmpty() && org.lts.smartcolony.core.Decree.inAny(data.zones, at.x(), at.z(), 0)) {
            return false;
        }
        for (ColonyData.Building building : data.buildings) {
            for (BlockPos pos : blocks) {
                if (building.contains(WorldUtil.pos(pos), 0) && !building.contains(at, 0)) {
                    return false;
                }
            }
        }
        double reach = data.radius + 2;
        return at.horizontalDistSq(data.center) <= reach * reach;
    }

    private static List<BlockPos> mushroom(ServerLevel level, BlockPos start, Set<Long> seen) {
        List<BlockPos> result = new ArrayList<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start.asLong());
        while (!queue.isEmpty() && result.size() < MUSHROOM_LIMIT) {
            BlockPos pos = queue.poll();
            result.add(pos);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos next = pos.offset(dx, dy, dz);
                        if (seen.add(next.asLong()) && WorldUtil.loaded(level, next) && WorldUtil.hugeMushroom(level.getBlockState(next))) {
                            queue.add(next);
                        }
                    }
                }
            }
        }
        if (result.size() >= MUSHROOM_LIMIT) {
            return List.of();
        }
        result.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).reversed());
        return result;
    }

    private static List<int[]> spiral(int radius) {
        List<int[]> offsets = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx += 2) {
            for (int dz = -radius; dz <= radius; dz += 2) {
                if (dx * dx + dz * dz <= radius * radius) {
                    offsets.add(new int[] {dx, dz});
                }
            }
        }
        offsets.sort(Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1]));
        return offsets;
    }
}
