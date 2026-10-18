package org.lts.smartcolony.colony;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.Pos;
import org.lts.smartcolony.util.WorldUtil;

public final class Doors {
    private static final int SCAN_PERIOD = 20;
    private static final int CHECK_PERIOD = 10;
    private static final int REFRESH = 6000;
    private static final int GRACE = 60;
    private static final int ALARM_GRACE = 10;
    private static final double DOORWAY = 1.6;
    private static final double PLAYER_RANGE = 4.0;

    private record Scan(long at, List<BlockPos> doors) {
    }

    private final Colony colony;
    private final Map<Long, Scan> buildings = new HashMap<>();
    private final Map<Long, Long> openSince = new HashMap<>();
    private int cursor;

    Doors(Colony colony) {
        this.colony = colony;
    }

    public void tick(ServerLevel level, long ticks) {
        if (ticks % SCAN_PERIOD == 9) {
            scanNext(level);
        }
        if (ticks % CHECK_PERIOD == 4) {
            check(level);
        }
    }

    public int known() {
        int count = 0;
        for (Scan scan : buildings.values()) {
            count += scan.doors().size();
        }
        return count;
    }

    public int open(ServerLevel level) {
        int count = 0;
        for (BlockPos door : doors()) {
            if (WorldUtil.loaded(level, door) && isOpenDoor(level.getBlockState(door))) {
                count++;
            }
        }
        return count;
    }

    private List<BlockPos> doors() {
        List<BlockPos> all = new ArrayList<>();
        for (Scan scan : buildings.values()) {
            all.addAll(scan.doors());
        }
        ColonyData.Wall wall = colony.data().wall;
        if (wall != null) {
            for (Pos gate : wall.gates) {
                all.add(new BlockPos(gate.x(), gate.y() + 1, gate.z()));
            }
        }
        return all;
    }

    private void scanNext(ServerLevel level) {
        List<ColonyData.Building> list = colony.data().buildings;
        Set<Long> alive = new HashSet<>();
        for (ColonyData.Building building : list) {
            alive.add(key(building));
        }
        buildings.keySet().retainAll(alive);
        if (list.isEmpty()) {
            return;
        }
        long now = level.getGameTime();
        for (int i = 0; i < list.size(); i++) {
            ColonyData.Building building = list.get(Math.floorMod(cursor++, list.size()));
            Scan scan = buildings.get(key(building));
            if (scan != null && now - scan.at() < REFRESH) {
                continue;
            }
            if (!WorldUtil.loaded(level, WorldUtil.bp(building.min)) || !WorldUtil.loaded(level, WorldUtil.bp(building.max))) {
                continue;
            }
            buildings.put(key(building), new Scan(now, find(level, building)));
            return;
        }
    }

    private static long key(ColonyData.Building building) {
        return BlockPos.asLong(building.min.x(), building.min.y(), building.min.z());
    }

    private static List<BlockPos> find(ServerLevel level, ColonyData.Building building) {
        List<BlockPos> doors = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(building.min.x() - 1, building.min.y(), building.min.z() - 1, building.max.x() + 1,
                building.max.y(), building.max.z() + 1)) {
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof DoorBlock && state.is(BlockTags.MOB_INTERACTABLE_DOORS)
                    && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
                doors.add(pos.immutable());
            }
        }
        return doors;
    }

    private static boolean isOpenDoor(BlockState state) {
        return state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                && state.getValue(DoorBlock.OPEN) && state.is(BlockTags.MOB_INTERACTABLE_DOORS);
    }

    private void check(ServerLevel level) {
        long now = level.getGameTime();
        boolean urgent = colony.defense().alarmed(level) || !colony.workTime(level) || level.isDarkOutside();
        int grace = urgent ? ALARM_GRACE : GRACE;
        Set<Long> stillOpen = new HashSet<>();
        for (BlockPos door : doors()) {
            if (!WorldUtil.loaded(level, door)) {
                continue;
            }
            BlockState state = level.getBlockState(door);
            if (!isOpenDoor(state)) {
                continue;
            }
            long since = openSince.computeIfAbsent(door.asLong(), k -> now);
            stillOpen.add(door.asLong());
            if (now - since < grace || busy(level, door)) {
                continue;
            }
            ((DoorBlock) state.getBlock()).setOpen(null, level, state, door, false);
            openSince.remove(door.asLong());
            stillOpen.remove(door.asLong());
            colony.data().bump(urgent ? "doors_closed_night" : "doors_closed", 1);
        }
        openSince.keySet().retainAll(stillOpen);
    }

    private static boolean busy(ServerLevel level, BlockPos door) {
        AABB doorway = new AABB(door).inflate(DOORWAY, 0.5, DOORWAY);
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, doorway, LivingEntity::isAlive)) {
            if (!(entity instanceof Enemy)) {
                return true;
            }
        }
        return !level.getEntitiesOfClass(Player.class, new AABB(door).inflate(PLAYER_RANGE, 2, PLAYER_RANGE),
                player -> !player.isSpectator()).isEmpty();
    }
}
