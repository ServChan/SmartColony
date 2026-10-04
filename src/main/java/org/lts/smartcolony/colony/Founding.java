package org.lts.smartcolony.colony;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import org.lts.smartcolony.SmartColonyConfig;
import org.lts.smartcolony.core.Category;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.Pos;
import org.lts.smartcolony.util.WorldUtil;

public final class Founding {
    private static final List<String> NAMES = List.of(
            "Ashford", "Brightwater", "Cedarholm", "Dunmere", "Elmstead", "Foxhollow", "Greenvale", "Hearthwick",
            "Ironbrook", "Juniper Rise", "Kestrel Ford", "Larkspur", "Millbrook", "Northmarch", "Oakhaven", "Pinecrest",
            "Quarry Hill", "Ravenford", "Stonebridge", "Thornfield", "Upper Lea", "Willowmere", "Yarrow End", "Zephyr Cove");
    private static final Category[] ORDER = {Category.WOOD, Category.MINING, Category.FOOD, Category.ANIMAL, Category.ARSENAL};

    private Founding() {
    }

    public static Optional<BlockPos> nearbyBell(ServerLevel level, BlockPos from, int radius) {
        long radiusSqr = (long) radius * radius;
        return level.getPoiManager().getInSquare(type -> type.is(PoiTypes.MEETING), from, radius, PoiManager.Occupancy.ANY)
                .map(record -> record.getPos())
                .filter(pos -> horizontalDistSqr(pos, from) <= radiusSqr)
                .min(java.util.Comparator.comparingLong(pos -> horizontalDistSqr(pos, from)));
    }

    private static long horizontalDistSqr(BlockPos a, BlockPos b) {
        long dx = a.getX() - b.getX();
        long dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    public static int villagersNear(ServerLevel level, BlockPos pos, int radius) {
        return level.getEntitiesOfClass(Villager.class, new AABB(pos).inflate(radius, 16, radius), v -> v.isAlive() && !v.isBaby()).size();
    }

    public static void autoFound(ServerLevel level) {
        SmartColonyConfig config = SmartColonyConfig.get();
        if (!config.autoFound) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            Optional<BlockPos> bell = nearbyBell(level, player.blockPosition(), 48);
            if (bell.isEmpty() || ColonyManager.colonyNear(level, bell.get(), 96) != null || ColonyManager.recentlyFailed(bell.get())) {
                continue;
            }
            if (villagersNear(level, bell.get(), 48) < config.minVillagersToFound) {
                continue;
            }
            Colony colony = found(level, bell.get());
            if (colony == null) {
                ColonyManager.markFailed(bell.get(), level.getGameTime());
                player.sendSystemMessage(Component.translatable("smartcolony.command.no_site"));
            }
        }
    }

    public static Colony found(ServerLevel level, BlockPos center) {
        Layout layout = findLayout(level, center);
        if (layout == null) {
            return null;
        }
        ColonyData data = new ColonyData();
        data.id = "colony-" + UUID.randomUUID().toString().substring(0, 8);
        data.name = NAMES.get(Math.floorMod(center.hashCode(), NAMES.size()));
        data.dimension = level.dimension().identifier().toString();
        data.center = WorldUtil.pos(center);
        data.radius = SmartColonyConfig.get().colonyRadius;
        data.founded = level.getGameTime();
        build(level, layout, data);
        Colony colony = ColonyManager.register(data);
        colony.discoverFarms(level);
        for (Villager villager : level.getEntitiesOfClass(Villager.class, new AABB(center).inflate(data.radius, 16, data.radius), Villager::isAlive)) {
            if (ColonyManager.colonyOf(villager) == null) {
                colony.join(level, villager);
            }
        }
        colony.journal(level, "colony founded with " + colony.colonists().size() + " villagers");
        colony.planRoles(level);
        colony.notify(level, Component.translatable("smartcolony.chat.founded", data.name, colony.colonists().size()));
        return colony;
    }

    record Layout(BlockPos origin, Direction front, Direction side, int baseY) {
        BlockPos column(int i, int row) {
            return origin.relative(side, i).relative(front, row);
        }
    }

    private static Layout findLayout(ServerLevel level, BlockPos center) {
        Layout best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int distance = 4; distance <= 20; distance++) {
            for (Direction toward : Direction.Plane.HORIZONTAL) {
                for (int shift = -distance; shift <= distance; shift += 2) {
                    Direction front = toward.getOpposite();
                    Direction side = front.getClockWise();
                    BlockPos anchor = center.relative(toward, distance).relative(side, shift);
                    BlockPos origin = anchor.relative(side, -2);
                    Layout layout = check(level, new Layout(origin, front, side, 0));
                    if (layout != null) {
                        double d = layout.column(2, 1).distSqr(center);
                        if (d < bestDistance) {
                            bestDistance = d;
                            best = layout;
                        }
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    private static Layout check(ServerLevel level, Layout layout) {
        List<Integer> heights = new ArrayList<>();
        for (int i = -1; i <= 5; i++) {
            for (int row = 0; row <= 1; row++) {
                BlockPos column = layout.column(i, row);
                if (!WorldUtil.loaded(level, column)) {
                    return null;
                }
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ()) - 1;
                if (level.getHeight(Heightmap.Types.MOTION_BLOCKING, column.getX(), column.getZ()) - 1 > y) {
                    return null;
                }
                BlockPos top = new BlockPos(column.getX(), y, column.getZ());
                BlockState ground = level.getBlockState(top);
                if (!level.getFluidState(top).isEmpty()) {
                    return null;
                }
                if (!(ground.is(BlockTags.DIRT) || ground.is(BlockTags.SAND) || ground.is(Blocks.GRAVEL)
                        || ground.is(BlockTags.BASE_STONE_OVERWORLD)) || ground.is(Blocks.DIRT_PATH) || ground.is(Blocks.FARMLAND)) {
                    return null;
                }
                heights.add(y);
            }
        }
        int min = heights.stream().mapToInt(Integer::intValue).min().orElse(0);
        int max = heights.stream().mapToInt(Integer::intValue).max().orElse(0);
        if (max - min > 1) {
            return null;
        }
        BlockPos depot = layout.column(2, 2);
        int depotY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, depot.getX(), depot.getZ());
        if (Math.abs(depotY - (max + 1)) > 1) {
            return null;
        }
        return new Layout(new BlockPos(layout.origin().getX(), max, layout.origin().getZ()), layout.front(), layout.side(), max);
    }

    private static void build(ServerLevel level, Layout layout, ColonyData data) {
        int base = layout.baseY();
        for (int i = -1; i <= 5; i++) {
            for (int row = 0; row <= 1; row++) {
                BlockPos column = layout.column(i, row);
                for (int y = base - 2; y <= base; y++) {
                    BlockPos pos = new BlockPos(column.getX(), y, column.getZ());
                    if (WorldUtil.isAirLike(level.getBlockState(pos))) {
                        level.setBlock(pos, Blocks.COBBLESTONE.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
                level.setBlock(new BlockPos(column.getX(), base, column.getZ()), Blocks.COBBLESTONE.defaultBlockState(), Block.UPDATE_ALL);
                for (int y = base + 1; y <= base + 4; y++) {
                    BlockPos pos = new BlockPos(column.getX(), y, column.getZ());
                    if (!level.getBlockState(pos).isAir()) {
                        level.destroyBlock(pos, true);
                    }
                }
            }
        }
        BlockState[] labels = {
                Blocks.OAK_LOG.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(), Blocks.COMPOSTER.defaultBlockState(),
                Blocks.WOOL.white().defaultBlockState(), Blocks.FLETCHING_TABLE.defaultBlockState()};
        for (int i = 0; i < ORDER.length; i++) {
            BlockPos column = layout.column(i, 0);
            List<Pos> positions = new ArrayList<>();
            for (int level2 = 1; level2 <= 2; level2++) {
                BlockPos at = new BlockPos(column.getX(), base + level2, column.getZ());
                level.setBlock(at, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, layout.front()), Block.UPDATE_ALL);
                positions.add(WorldUtil.pos(at));
            }
            data.storage.put(ORDER[i], positions);
            BlockPos labelPos = new BlockPos(column.getX(), base + 3, column.getZ());
            level.setBlock(labelPos, labels[i], Block.UPDATE_ALL);
            BlockPos signPos = labelPos.relative(layout.front());
            BlockState sign = Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, layout.front());
            level.setBlock(signPos, sign, Block.UPDATE_ALL);
            if (level.getBlockEntity(signPos) instanceof SignBlockEntity entity) {
                entity.setText(entity.getText(SignTextSlot.FRONT).asMutable()
                        .setLine(1, Component.translatable("smartcolony.storage." + ORDER[i].key()))
                        .setLine(2, Component.translatable("smartcolony.storage.sign"))
                        .asImmutable(), SignTextSlot.FRONT);
                entity.setWaxed(true);
            }
        }
        BlockPos table = layout.column(-1, 0).atY(base + 1);
        level.setBlock(table, Blocks.CRAFTING_TABLE.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos furnace = layout.column(5, 0).atY(base + 1);
        level.setBlock(furnace, Blocks.FURNACE.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, layout.front()), Block.UPDATE_ALL);
        BlockPos lantern = layout.column(5, 0).atY(base + 2);
        level.setBlock(lantern, Blocks.LANTERN.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos depot = layout.column(2, 1).atY(base + 1);
        data.depot = WorldUtil.pos(depot);
    }
}
