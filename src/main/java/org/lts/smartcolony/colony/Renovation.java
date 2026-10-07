package org.lts.smartcolony.colony;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.Ids;
import org.lts.smartcolony.core.Pos;
import org.lts.smartcolony.util.WorldUtil;

public final class Renovation {
    public static final String TAG = "upgrade";
    private static final int MAX_ROOM = 80;
    private static final int MAX_REACH = 9;

    private final Colony colony;
    private long lastPlan = -100000;

    Renovation(Colony colony) {
        this.colony = colony;
    }

    private ColonyData data() {
        return colony.data();
    }

    public record Room(List<BlockPos> cells, List<BlockPos> doors, int beds) {
        public String key() {
            long min = Long.MAX_VALUE;
            for (BlockPos door : doors) {
                min = Math.min(min, door.asLong());
            }
            BlockPos first = BlockPos.of(min);
            return first.getX() + "," + first.getY() + "," + first.getZ();
        }
    }

    public void tick(ServerLevel level) {
        long now = level.getGameTime();
        if (now - lastPlan < 1200 || now - data().founded < 12000) {
            return;
        }
        lastPlan = now;
        if (data().orders.stream().anyMatch(order -> TAG.equals(order.tag))) {
            return;
        }
        int target = targetLevel(level);
        if (target == 0) {
            return;
        }
        BlockPos center = colony.center();
        List<BlockPos> beds = new ArrayList<>();
        level.getPoiManager().getInSquare(type -> type.is(PoiTypes.HOME), center, data().radius + 16, PoiManager.Occupancy.ANY)
                .forEach(record -> beds.add(record.getPos()));
        Set<String> seen = new HashSet<>();
        for (BlockPos bed : beds) {
            if (!WorldUtil.loaded(level, bed) || insideColonyBuilding(bed)) {
                continue;
            }
            Room room = room(level, bed, null);
            if (room == null || !seen.add(room.key())) {
                continue;
            }
            int done = data().renovated.getOrDefault(room.key(), 0);
            if (done >= target) {
                continue;
            }
            int next = done + 1;
            List<ColonyData.Order> orders = plan(level, room, next);
            data().renovated.put(room.key(), next);
            if (!orders.isEmpty()) {
                data().orders.addAll(0, orders);
                colony.journal(level, "renovating a village house at " + room.key() + " to level " + next + ": " + orders.size() + " jobs");
                colony.data().bump("houses_renovated", 1);
            }
            colony.markDirty();
            return;
        }
    }

    private int targetLevel(ServerLevel level) {
        int adults = colony.adultCount(level);
        int wood = colony.stockOf(level, id -> Ids.isLog(id)) * 4 + colony.stockOf(level, Ids::isPlanks);
        if (adults < 4 || wood < 24) {
            return 0;
        }
        if (adults >= 12 && wood >= 96 && level.getGameTime() - data().founded > 48000) {
            return 3;
        }
        if (adults >= 8 && wood >= 48) {
            return 2;
        }
        return 1;
    }

    private boolean insideColonyBuilding(BlockPos pos) {
        Pos p = WorldUtil.pos(pos);
        for (ColonyData.Building building : data().buildings) {
            if (building.contains(p, 0)) {
                return true;
            }
        }
        return false;
    }

    private static boolean open(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof BedBlock || state.getBlock() instanceof CarpetBlock) {
            return true;
        }
        return state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty();
    }

    private static boolean covered(ServerLevel level, BlockPos cell) {
        for (int up = 2; up <= 6; up++) {
            if (!level.getBlockState(cell.above(up)).isAir()) {
                return true;
            }
        }
        return false;
    }

    public static Room room(ServerLevel level, BlockPos bed, StringBuilder why) {
        BlockState bedState = level.getBlockState(bed);
        if (!(bedState.getBlock() instanceof BedBlock)) {
            note(why, "not a bed");
            return null;
        }
        Set<BlockPos> cells = new LinkedHashSet<>();
        Set<BlockPos> doors = new LinkedHashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(bed.immutable());
        cells.add(bed.immutable());
        int beds = 0;
        while (!queue.isEmpty()) {
            BlockPos cell = queue.poll();
            if (!covered(level, cell)) {
                note(why, "open sky above " + cell.toShortString());
                return null;
            }
            if (level.getBlockState(cell).getBlock() instanceof BedBlock) {
                beds++;
            }
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos next = cell.relative(direction);
                if (cells.contains(next)) {
                    continue;
                }
                BlockState state = level.getBlockState(next);
                if (state.getBlock() instanceof DoorBlock) {
                    doors.add(state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? next.immutable() : next.below().immutable());
                    continue;
                }
                if (!open(level, next) || !open(level, next.above()) || !WorldUtil.solidFloor(level, next.below())) {
                    continue;
                }
                if (Math.abs(next.getX() - bed.getX()) > MAX_REACH || Math.abs(next.getZ() - bed.getZ()) > MAX_REACH) {
                    note(why, "too far " + next.toShortString());
                    return null;
                }
                cells.add(next.immutable());
                if (cells.size() > MAX_ROOM) {
                    note(why, "too big");
                    return null;
                }
                queue.add(next.immutable());
            }
        }
        if (cells.size() < 3 || doors.isEmpty()) {
            note(why, "cells " + cells.size() + " doors " + doors.size());
            return null;
        }
        return new Room(new ArrayList<>(cells), new ArrayList<>(doors), Math.max(1, beds / 2));
    }

    private static void note(StringBuilder why, String reason) {
        if (why != null) {
            why.append(reason);
        }
    }

    private List<ColonyData.Order> plan(ServerLevel level, Room room, int stage) {
        List<ColonyData.Order> orders = new ArrayList<>();
        String wood = colony.construction().preferredWood(level);
        Set<BlockPos> entries = new HashSet<>();
        for (BlockPos door : room.doors()) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                entries.add(door.relative(direction));
            }
        }
        switch (stage) {
            case 1 -> cozy(level, room, wood, entries, orders);
            case 2 -> facade(level, room, wood, orders);
            default -> spacious(level, room, entries, orders);
        }
        return orders;
    }

    private void cozy(ServerLevel level, Room room, String wood, Set<BlockPos> entries, List<ColonyData.Order> orders) {
        for (BlockPos cell : room.cells()) {
            BlockPos floor = cell.below();
            BlockState state = level.getBlockState(floor);
            if (state.is(BlockTags.DIRT) || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT_PATH) || state.is(Blocks.GRAVEL)
                    || state.is(Blocks.SAND) || state.is(Blocks.COARSE_DIRT)) {
                orders.add(order(floor, Ids.mc(wood + "_planks")));
            }
        }
        int light = 0;
        boolean storage = false;
        for (BlockPos cell : room.cells()) {
            light = Math.max(light, level.getBrightness(LightLayer.BLOCK, cell));
            for (BlockPos at : List.of(cell, cell.above())) {
                BlockState state = level.getBlockState(at);
                storage |= state.getBlock() instanceof BarrelBlock || state.getBlock() instanceof ChestBlock;
            }
        }
        if (light < 10) {
            BlockPos torch = null;
            Direction wall = null;
            double best = Double.MAX_VALUE;
            double cx = room.cells().stream().mapToInt(BlockPos::getX).average().orElse(0);
            double cz = room.cells().stream().mapToInt(BlockPos::getZ).average().orElse(0);
            for (BlockPos cell : room.cells()) {
                BlockPos head = cell.above();
                if (!level.getBlockState(head).isAir()) {
                    continue;
                }
                for (Direction direction : Direction.Plane.HORIZONTAL) {
                    BlockPos behind = head.relative(direction);
                    if (!level.getBlockState(behind).isFaceSturdy(level, behind, direction.getOpposite())) {
                        continue;
                    }
                    double distance = Math.pow(cell.getX() - cx, 2) + Math.pow(cell.getZ() - cz, 2);
                    if (distance < best) {
                        best = distance;
                        torch = head;
                        wall = direction;
                    }
                }
            }
            if (torch != null) {
                orders.add(order(torch, "minecraft:wall_torch[facing=" + wall.getOpposite().getSerializedName() + "]"));
            }
        }
        if (!storage) {
            for (BlockPos cell : room.cells()) {
                if (entries.contains(cell) || !level.getBlockState(cell).isAir() || walls(level, cell) < 2) {
                    continue;
                }
                orders.add(order(cell, "minecraft:barrel[facing=up]"));
                break;
            }
        }
    }

    private static int walls(ServerLevel level, BlockPos cell) {
        int walls = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = cell.relative(direction);
            if (!open(level, side) && !(level.getBlockState(side).getBlock() instanceof DoorBlock)) {
                walls++;
            }
        }
        return walls;
    }

    private void facade(ServerLevel level, Room room, String wood, List<ColonyData.Order> orders) {
        Set<BlockPos> planned = new HashSet<>();
        for (BlockPos cell : room.cells()) {
            for (BlockPos at : List.of(cell, cell.above())) {
                for (Direction out : Direction.Plane.HORIZONTAL) {
                    BlockPos window = at.relative(out);
                    BlockState glass = level.getBlockState(window);
                    if (!(glass.getBlock() instanceof IronBarsBlock) && !glass.is(Blocks.GLASS) && !glass.is(BlockTags.IMPERMEABLE)) {
                        continue;
                    }
                    BlockPos outside = window.relative(out);
                    if (!level.getBlockState(outside).isAir() || room.cells().contains(outside)) {
                        continue;
                    }
                    for (Direction side : new Direction[] {out.getClockWise(), out.getCounterClockWise()}) {
                        BlockPos shutter = outside.relative(side);
                        BlockPos backing = window.relative(side);
                        BlockState back = level.getBlockState(backing);
                        if (planned.contains(shutter) || !level.getBlockState(shutter).isAir()
                                || !back.isFaceSturdy(level, backing, out) || back.getBlock() instanceof IronBarsBlock) {
                            continue;
                        }
                        planned.add(shutter.immutable());
                        orders.add(order(shutter, Ids.mc(wood + "_trapdoor") + "[facing=" + out.getSerializedName() + ",half=top,open=true]"));
                    }
                }
            }
        }
        for (BlockPos door : room.doors()) {
            Direction out = null;
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                if (room.cells().contains(door.relative(direction.getOpposite())) && level.getBlockState(door.relative(direction)).isAir()) {
                    out = direction;
                }
            }
            if (out == null) {
                continue;
            }
            BlockPos lintel = door.above(2);
            if (!level.getBlockState(lintel).isFaceSturdy(level, lintel, out)) {
                continue;
            }
            BlockPos front = door.relative(out).above(2);
            for (BlockPos awning : List.of(front, front.relative(out.getClockWise()), front.relative(out.getCounterClockWise()))) {
                if (level.getBlockState(awning).isAir() && !planned.contains(awning)) {
                    planned.add(awning.immutable());
                    orders.add(hung(awning, Ids.mc(wood + "_stairs") + "[facing=" + out.getOpposite().getSerializedName() + ",half=bottom]"));
                }
            }
        }
    }

    private void spacious(ServerLevel level, Room room, Set<BlockPos> entries, List<ColonyData.Order> orders) {
        if (room.beds() >= 2 || room.cells().size() < 10) {
            return;
        }
        Set<BlockPos> cells = new HashSet<>(room.cells());
        for (BlockPos foot : room.cells()) {
            if (entries.contains(foot) || !level.getBlockState(foot).isAir() || !level.getBlockState(foot.above()).isAir()) {
                continue;
            }
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                BlockPos head = foot.relative(facing);
                BlockPos behind = head.relative(facing);
                if (!cells.contains(head) || entries.contains(head) || !level.getBlockState(head).isAir()
                        || !level.getBlockState(head.above()).isAir() || open(level, behind)) {
                    continue;
                }
                orders.add(order(foot, "minecraft:white_bed[facing=" + facing.getSerializedName() + ",part=foot]"));
                return;
            }
        }
    }

    private static ColonyData.Order hung(BlockPos pos, String state) {
        ColonyData.Order order = order(pos, state);
        order.attached = true;
        return order;
    }

    private static ColonyData.Order order(BlockPos pos, String state) {
        return new ColonyData.Order(WorldUtil.pos(pos), state, TAG);
    }
}
