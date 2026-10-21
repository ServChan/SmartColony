package org.lts.smartcolony.colony;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.lts.smartcolony.SmartColonyConfig;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.Pos;
import org.lts.smartcolony.util.WorldUtil;

public final class Chores {
    public enum Kind {
        SNOW,
        WEEDS,
        LOOT,
        HEADROOM,
        DOOR
    }

    public record Chore(Kind kind, BlockPos pos) {
    }

    private static final int MAX_QUEUE = 160;
    private static final int ROADS_PER_SCAN = 220;
    private static final long CLAIM_TICKS = 600;
    private static final int LOOT_MIN_AGE = 400;
    private static final int GIFT_MIN_AGE = 60;

    private final Colony colony;
    private final Deque<Chore> queue = new ArrayDeque<>();
    private final Set<Long> queued = new HashSet<>();
    private final Map<Long, Long> claims = new HashMap<>();
    private int roadCursor;
    private boolean snowy;
    private long snowCheckedAt = -100000;

    public Chores(Colony colony) {
        this.colony = colony;
    }

    public int pending() {
        return queue.size();
    }

    public boolean snowy() {
        return snowy;
    }

    public void tick(ServerLevel level, long ticks) {
        if (ticks % 100 == 41) {
            scan(level);
        }
    }

    private void scan(ServerLevel level) {
        long now = level.getGameTime();
        if (now - snowCheckedAt > 6000) {
            snowCheckedAt = now;
            BlockPos center = colony.center();
            snowy = level.getBiome(center).value().coldEnoughToSnow(center, level.getSeaLevel());
        }
        claims.values().removeIf(at -> now - at > CLAIM_TICKS);
        for (ColonyData.Building building : colony.data().buildings) {
            doorway(level, building);
        }
        List<Pos> roads = colony.data().roads;
        boolean clearSnow = snowy && SmartColonyConfig.get().snowClearing;
        if (!roads.isEmpty()) {
            for (int i = 0; i < Math.min(ROADS_PER_SCAN, roads.size()) && queue.size() < MAX_QUEUE; i++) {
                roadCursor = Math.floorMod(roadCursor + 1, roads.size());
                Pos road = roads.get(roadCursor);
                BlockPos ground = new BlockPos(road.x(), colony.construction().paths().ground(level, road.x(), road.z()), road.z());
                check(level, ground.above(), clearSnow);
                headroom(level, ground.above(2));
            }
        }
        for (ColonyData.Building building : colony.data().buildings) {
            if (queue.size() >= MAX_QUEUE) {
                break;
            }
            net.minecraft.core.Direction out = colony.construction().paths().outward(building);
            BlockPos door = WorldUtil.bp(building.door);
            for (int k = 0; k <= 3; k++) {
                for (int j = -1; j <= 1; j++) {
                    BlockPos cell = door.relative(out, k).relative(out.getClockWise(), j);
                    for (int dy = -1; dy <= 1; dy++) {
                        check(level, cell.above(dy), clearSnow);
                    }
                }
                BlockPos feet = door.relative(out, k);
                headroom(level, feet);
                headroom(level, feet.above());
            }
        }
        if (clearSnow) {
            BlockPos depot = colony.depot();
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos column = WorldUtil.surface(level, depot.getX() + dx, depot.getZ() + dz);
                    check(level, column, true);
                }
            }
        }
        for (Pos planted : colony.data().plantedTrees) {
            BlockPos pos = WorldUtil.bp(planted);
            if (queue.size() >= MAX_QUEUE) {
                break;
            }
            if (WorldUtil.loaded(level, pos) && level.getBlockState(pos).is(BlockTags.SAPLINGS) && !colony.treeAllowed(level, pos)) {
                offer(new Chore(Kind.WEEDS, pos.immutable()));
            }
        }
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(colony.center()).inflate(colony.data().radius, 16, colony.data().radius),
                item -> item.isAlive() && !item.hasPickUpDelay() && item.getAge() >= (item.getOwner() instanceof Player ? GIFT_MIN_AGE : LOOT_MIN_AGE))) {
            if (queue.size() >= MAX_QUEUE) {
                break;
            }
            boolean gift = item.getOwner() instanceof Player;
            if (!gift && level.getNearestPlayer(item, 6.0) instanceof Player || nearGrave(item.blockPosition()) || !onGround(level, item.blockPosition())) {
                continue;
            }
            offer(new Chore(Kind.LOOT, item.blockPosition()));
        }
    }

    private boolean onGround(ServerLevel level, BlockPos pos) {
        if (colony.inMine(pos) || colony.allMines().stream().anyMatch(ColonyData.Mine::quarry) && colony.isProtected(pos, false)
                && pos.getY() < WorldUtil.surfaceY(level, pos.getX(), pos.getZ()) - 1) {
            return false;
        }
        int surface = WorldUtil.surfaceY(level, pos.getX(), pos.getZ());
        return Math.abs(pos.getY() - surface) <= 1 && level.getFluidState(pos).isEmpty()
                && WorldUtil.standable(level, new BlockPos(pos.getX(), surface, pos.getZ()));
    }

    private boolean nearGrave(BlockPos pos) {
        for (ColonyData.Death death : colony.data().deaths) {
            if (!death.buried && death.pos.horizontalDistSq(WorldUtil.pos(pos)) < 64) {
                return true;
            }
        }
        return false;
    }

    private void check(ServerLevel level, BlockPos pos, boolean clearSnow) {
        if (!WorldUtil.loaded(level, pos)) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (Math.abs(pos.getY() - WorldUtil.surfaceY(level, pos.getX(), pos.getZ())) > 2) {
            return;
        }
        if (clearSnow && (state.is(Blocks.SNOW) || state.is(Blocks.POWDER_SNOW))) {
            offer(new Chore(Kind.SNOW, pos.immutable()));
        } else if (weed(state)) {
            offer(new Chore(Kind.WEEDS, pos.immutable()));
        }
    }

    private void headroom(ServerLevel level, BlockPos pos) {
        if (WorldUtil.loaded(level, pos) && obstruction(level, pos)) {
            offer(new Chore(Kind.HEADROOM, pos.immutable()));
        }
    }

    boolean obstruction(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.getCollisionShape(level, pos).isEmpty() && !state.is(Blocks.COBWEB)) {
            return false;
        }
        if (state.is(BlockTags.LEAVES) || state.is(Blocks.VINE) || state.is(Blocks.COBWEB) || state.is(Blocks.SNOW)
                || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.POWDER_SNOW) || state.is(BlockTags.SAPLINGS)) {
            return true;
        }
        if (state.is(BlockTags.LOGS)) {
            org.lts.smartcolony.core.Pos at = WorldUtil.pos(pos);
            for (ColonyData.Building building : colony.data().buildings) {
                if (building.contains(at, 0)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private void doorway(ServerLevel level, ColonyData.Building building) {
        if (building.blueprint.isEmpty() || "pen".equals(building.kind)) {
            return;
        }
        if (building.door == null) {
            return;
        }
        BlockPos front = WorldUtil.bp(building.door);
        BlockPos lower = front.relative(colony.construction().paths().outward(building).getOpposite());
        if (!WorldUtil.loaded(level, lower)) {
            return;
        }
        BlockState state = level.getBlockState(lower);
        if (!(state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) && level.getBlockState(lower.below()).getBlock()
                instanceof net.minecraft.world.level.block.DoorBlock) {
            lower = lower.below();
            state = level.getBlockState(lower);
        }
        if (!(state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock)
                || state.getValue(net.minecraft.world.level.block.DoorBlock.HALF)
                != net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER) {
            return;
        }
        BlockState upper = level.getBlockState(lower.above());
        if (upper.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) {
            return;
        }
        if (upper.isAir() || upper.is(BlockTags.LEAVES) || upper.is(Blocks.VINE) || upper.is(Blocks.SNOW) || upper.canBeReplaced()) {
            fixDoor(level, lower.above());
        } else {
            offer(new Chore(Kind.DOOR, lower.above()));
        }
    }

    public void fixDoor(ServerLevel level, BlockPos upper) {
        BlockState lower = level.getBlockState(upper.below());
        if (!(lower.getBlock() instanceof net.minecraft.world.level.block.DoorBlock)) {
            return;
        }
        BlockState top = lower.setValue(net.minecraft.world.level.block.DoorBlock.HALF,
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER);
        level.setBlock(upper, top, net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        level.setBlock(upper.below(), lower, net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        colony.data().bump("doors_repaired", 1);
    }

    static boolean weed(BlockState state) {
        if (state.isAir() || !state.getFluidState().isEmpty()) {
            return false;
        }
        return state.is(BlockTags.SAPLINGS) || state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS) || state.is(Blocks.FERN)
                || state.is(Blocks.LARGE_FERN) || state.is(Blocks.DEAD_BUSH) || state.is(Blocks.COBWEB) || state.is(Blocks.VINE)
                || state.is(Blocks.BUSH) || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.hasProperty(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)
                && !state.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT);
    }

    private void offer(Chore chore) {
        long key = chore.pos().asLong() * 31 + chore.kind().ordinal();
        boolean urgent = chore.kind() == Kind.DOOR || chore.kind() == Kind.HEADROOM;
        if ((queue.size() < MAX_QUEUE || urgent && queue.size() < MAX_QUEUE + 32) && queued.add(key)) {
            if (chore.kind() == Kind.SNOW || chore.kind() == Kind.DOOR || chore.kind() == Kind.HEADROOM) {
                queue.addFirst(chore);
            } else {
                queue.addLast(chore);
            }
        }
    }

    public Chore next(ServerLevel level, BlockPos near, UUID worker) {
        long now = level.getGameTime();
        Chore best = null;
        double bestDistance = Double.MAX_VALUE;
        int looked = 0;
        for (Chore chore : queue) {
            if (++looked > 48) {
                break;
            }
            Long claimed = claims.get(chore.pos().asLong());
            if (claimed != null && now - claimed < CLAIM_TICKS) {
                continue;
            }
            double distance = chore.pos().distSqr(near) + (chore.kind() == Kind.SNOW ? 0 : 64);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = chore;
            }
        }
        if (best != null) {
            claims.put(best.pos().asLong(), now);
        }
        return best;
    }

    public boolean snowPending() {
        for (Chore chore : queue) {
            if (chore.kind() == Kind.SNOW || chore.kind() == Kind.HEADROOM) {
                return true;
            }
        }
        return false;
    }

    public Chore nextSnow(ServerLevel level, BlockPos near) {
        long now = level.getGameTime();
        for (Chore chore : queue) {
            if (chore.kind() != Kind.SNOW && chore.kind() != Kind.HEADROOM) {
                continue;
            }
            Long claimed = claims.get(chore.pos().asLong());
            if (claimed != null && now - claimed < CLAIM_TICKS || chore.pos().distSqr(near) > 48 * 48) {
                continue;
            }
            claims.put(chore.pos().asLong(), now);
            return chore;
        }
        return null;
    }

    public void done(Chore chore) {
        queue.remove(chore);
        queued.remove(chore.pos().asLong() * 31 + chore.kind().ordinal());
        claims.remove(chore.pos().asLong());
    }

    public boolean stillNeeded(ServerLevel level, Chore chore) {
        if (!WorldUtil.loaded(level, chore.pos())) {
            return false;
        }
        BlockState state = level.getBlockState(chore.pos());
        return switch (chore.kind()) {
            case SNOW -> state.is(Blocks.SNOW) || state.is(Blocks.POWDER_SNOW);
            case WEEDS -> weed(state);
            case LOOT -> !level.getEntitiesOfClass(ItemEntity.class, new AABB(chore.pos()).inflate(1.5), ItemEntity::isAlive).isEmpty();
            case HEADROOM -> obstruction(level, chore.pos());
            case DOOR -> level.getBlockState(chore.pos().below()).getBlock() instanceof net.minecraft.world.level.block.DoorBlock
                    && !(level.getBlockState(chore.pos()).getBlock() instanceof net.minecraft.world.level.block.DoorBlock);
        };
    }

    public List<Chore> snapshot() {
        return new ArrayList<>(queue);
    }
}
