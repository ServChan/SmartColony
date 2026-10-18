package org.lts.smartcolony.colony;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.Flight;
import org.lts.smartcolony.util.WorldUtil;

public final class SafeRooms {
    public static final int ROOM_LIMIT = 700;
    public static final int HERE_LIMIT = 400;
    private static final int REFRESH = 4800;
    private static final int WARM_PERIOD = 20;
    private static final int RETRY = 1200;
    private static final int GRID = 2;
    private static final int MAX_ROOMS = 4;
    private static final int MAX_DROP = 4;
    private static final int CELLS_PER_GUEST = 3;
    private static final int MAX_CAPACITY = 16;

    public record Room(long id, BlockPos spot, int capacity) {
    }

    private record Entry(long computedAt, List<Room> rooms) {
    }

    private final Colony colony;
    private final Map<Long, Entry> buildings = new HashMap<>();
    private final Map<UUID, Long> claims = new HashMap<>();
    private long computedTick = Long.MIN_VALUE;

    SafeRooms(Colony colony) {
        this.colony = colony;
    }

    void tick(ServerLevel level, long ticks) {
        if (ticks % WARM_PERIOD == 15) {
            spots(level, level.getGameTime(), null);
        }
    }

    public List<Flight.Spot> spots(ServerLevel level, long now, BlockPos home) {
        Map<Long, Integer> load = new HashMap<>();
        for (long room : claims.values()) {
            load.merge(room, 1, Integer::sum);
        }
        Set<Long> alive = new HashSet<>();
        List<Flight.Spot> out = new ArrayList<>();
        for (ColonyData.Building building : colony.data().buildings) {
            if (building.door == null) {
                continue;
            }
            long key = BlockPos.asLong(building.min.x(), building.min.y(), building.min.z());
            alive.add(key);
            Entry entry = buildings.get(key);
            long age = entry == null ? Long.MAX_VALUE : now - entry.computedAt();
            boolean stale = entry == null || age > REFRESH || entry.rooms().isEmpty() && age > RETRY;
            if (stale && computedTick != now && WorldUtil.loaded(level, WorldUtil.bp(building.door))) {
                computedTick = now;
                entry = new Entry(now, scan(level, building));
                buildings.put(key, entry);
            }
            if (entry == null) {
                continue;
            }
            boolean mine = home != null && building.contains(WorldUtil.pos(home), 0);
            for (Room room : entry.rooms()) {
                out.add(new Flight.Spot(room.spot().getX(), room.spot().getY(), room.spot().getZ(), room.id(), room.capacity(), mine,
                        load.getOrDefault(room.id(), 0)));
            }
        }
        buildings.keySet().retainAll(alive);
        return out;
    }

    public void claim(UUID uuid, long room) {
        claims.put(uuid, room);
    }

    public void release(UUID uuid) {
        claims.remove(uuid);
    }

    public int rooms() {
        int count = 0;
        for (Entry entry : buildings.values()) {
            count += entry.rooms().size();
        }
        return count;
    }

    public int sheltered() {
        return claims.size();
    }

    public static boolean enclosed(ServerLevel level, BlockPos feet) {
        return explore(level, feet, HERE_LIMIT, null, null) != null;
    }

    private static List<Room> scan(ServerLevel level, ColonyData.Building building) {
        List<Room> rooms = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        int[] box = {building.min.x() - 2, building.min.y() - 2, building.min.z() - 2, building.max.x() + 2, building.max.y() + 3,
                building.max.z() + 2};
        for (int x = building.min.x(); x <= building.max.x() && rooms.size() < MAX_ROOMS; x += GRID) {
            for (int z = building.min.z(); z <= building.max.z() && rooms.size() < MAX_ROOMS; z += GRID) {
                for (int y = building.min.y(); y <= building.max.y(); y++) {
                    BlockPos feet = new BlockPos(x, y, z);
                    if (seen.contains(feet.asLong()) || !WorldUtil.loaded(level, feet) || !WorldUtil.standable(level, feet)
                            || level.canSeeSky(feet)) {
                        continue;
                    }
                    Explored room = explore(level, feet, ROOM_LIMIT, box, seen);
                    if (room == null || room.doors().isEmpty()) {
                        continue;
                    }
                    BlockPos spot = farthestFromDoors(room);
                    if (spot != null) {
                        int capacity = Math.max(1, Math.min(MAX_CAPACITY, room.cells().size() / CELLS_PER_GUEST));
                        rooms.add(new Room(room.id(), spot, capacity));
                    }
                }
            }
        }
        return rooms;
    }

    private record Explored(long id, Set<Long> cells, List<BlockPos> floor, List<BlockPos> doors) {
    }

    private static Explored explore(ServerLevel level, BlockPos start, int limit, int[] box, Set<Long> touched) {
        if (!walkable(level, start)) {
            return null;
        }
        Set<Long> cells = new HashSet<>();
        List<BlockPos> floor = new ArrayList<>();
        List<BlockPos> doors = new ArrayList<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        cells.add(start.asLong());
        if (touched != null) {
            touched.add(start.asLong());
        }
        long id = start.asLong();
        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            if (level.canSeeSky(pos) || level.canSeeSky(pos.above())) {
                return null;
            }
            if (box != null && (pos.getX() < box[0] || pos.getY() < box[1] || pos.getZ() < box[2] || pos.getX() > box[3]
                    || pos.getY() > box[4] || pos.getZ() > box[5])) {
                return null;
            }
            floor.add(pos);
            id = Math.min(id, pos.asLong());
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos side = pos.relative(direction);
                if (!WorldUtil.loaded(level, side)) {
                    return null;
                }
                if (barrier(level, side) || barrier(level, side.above())) {
                    doors.add(side);
                    continue;
                }
                BlockPos next = step(level, pos, side);
                if (next == null || !cells.add(next.asLong())) {
                    continue;
                }
                if (touched != null) {
                    touched.add(next.asLong());
                }
                if (cells.size() > limit) {
                    return null;
                }
                queue.add(next);
            }
        }
        return new Explored(id, cells, floor, doors);
    }

    private static BlockPos step(ServerLevel level, BlockPos from, BlockPos side) {
        if (walkable(level, side)) {
            return side;
        }
        BlockPos up = side.above();
        if (WorldUtil.passable(level, from.above(2)) && walkable(level, up)) {
            return up;
        }
        if (!WorldUtil.passable(level, side) || !WorldUtil.passable(level, side.above())) {
            return null;
        }
        BlockPos down = side.below();
        for (int i = 0; i < MAX_DROP; i++) {
            if (!WorldUtil.passable(level, down)) {
                return null;
            }
            if (walkable(level, down)) {
                return down;
            }
            down = down.below();
        }
        return null;
    }

    private static boolean walkable(ServerLevel level, BlockPos feet) {
        return WorldUtil.passable(level, feet) && WorldUtil.passable(level, feet.above()) && !barrier(level, feet)
                && !barrier(level, feet.above()) && WorldUtil.solidFloor(level, feet.below());
    }

    private static boolean barrier(ServerLevel level, BlockPos pos) {
        Block block = level.getBlockState(pos).getBlock();
        return block instanceof DoorBlock || block instanceof FenceGateBlock || block instanceof TrapDoorBlock;
    }

    private static BlockPos farthestFromDoors(Explored room) {
        BlockPos best = null;
        double bestDistance = -1;
        for (BlockPos cell : room.floor()) {
            double nearest = Double.MAX_VALUE;
            for (BlockPos door : room.doors()) {
                nearest = Math.min(nearest, cell.distSqr(door));
            }
            if (nearest > bestDistance) {
                bestDistance = nearest;
                best = cell;
            }
        }
        return best;
    }
}
