package org.lts.smartcolony.colony;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
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
import net.minecraft.world.level.block.LeavesBlock;
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
                showPreview(player, planHere(level, player));
            }
        }
    }

    public static Colony found(ServerLevel level, BlockPos center) {
        Plan plan = findPlan(level, center);
        return plan == null ? null : found(level, center, plan);
    }

    public static Colony found(ServerLevel level, BlockPos center, Plan plan) {
        if (!plan.valid()) {
            return null;
        }
        ColonyData data = new ColonyData();
        data.id = "colony-" + UUID.randomUUID().toString().substring(0, 8);
        data.name = NAMES.get(Math.floorMod(center.hashCode(), NAMES.size()));
        data.dimension = level.dimension().identifier().toString();
        data.center = WorldUtil.pos(center);
        data.radius = SmartColonyConfig.get().colonyRadius;
        data.founded = level.getGameTime();
        build(level, plan.layout(), data);
        PREVIEWS.removeIf(preview -> preview.level() == level);
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

    public record Layout(BlockPos origin, Direction front, Direction side, int baseY) {
        BlockPos column(int i, int row) {
            return origin.relative(side, i).relative(front, row);
        }
    }

    public enum Fault {
        NONE, STEEP, WATER
    }

    public record Plan(Layout layout, int cost, List<BlockPos> blockers, Fault fault) {
        public boolean valid() {
            return fault == Fault.NONE && blockers.isEmpty();
        }

        public Component reason() {
            return switch (fault) {
                case STEEP -> Component.translatable("smartcolony.site.steep");
                case WATER -> Component.translatable("smartcolony.site.water");
                default -> Component.translatable("smartcolony.site.blocked", blockers.size());
            };
        }
    }

    private record Preview(ServerLevel level, UUID player, Plan plan, long until) {
    }

    private static final int MAX_STEP = 4;
    private static final List<Preview> PREVIEWS = new ArrayList<>();

    public static Plan planHere(ServerLevel level, ServerPlayer player) {
        Direction facing = player.getDirection();
        Direction front = facing.getOpposite();
        Direction side = front.getClockWise();
        BlockPos origin = player.blockPosition().relative(facing, 3).relative(side, -2);
        return evaluate(level, new Layout(origin, front, side, 0));
    }

    private static Plan findPlan(ServerLevel level, BlockPos center) {
        Plan best = null;
        int bestScore = Integer.MAX_VALUE;
        for (int distance = 5; distance <= 28 && distance < bestScore; distance++) {
            for (Direction toward : Direction.Plane.HORIZONTAL) {
                Direction front = toward.getOpposite();
                Direction side = front.getClockWise();
                for (int shift = -distance; shift <= distance; shift++) {
                    BlockPos origin = center.relative(toward, distance).relative(side, shift - 2);
                    Plan plan = evaluate(level, new Layout(origin, front, side, 0));
                    if (plan == null || !plan.valid()) {
                        continue;
                    }
                    int score = plan.cost() * 3 + distance;
                    if (score < bestScore) {
                        bestScore = score;
                        best = plan;
                    }
                }
            }
        }
        return best;
    }

    private static Plan evaluate(ServerLevel level, Layout layout) {
        int[] grounds = new int[21];
        List<BlockPos> blockers = new ArrayList<>();
        Fault fault = Fault.NONE;
        int k = 0;
        for (int i = -1; i <= 5; i++) {
            for (int row = 0; row <= 2; row++) {
                BlockPos column = layout.column(i, row);
                if (!WorldUtil.loaded(level, column)) {
                    return null;
                }
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ()) - 1;
                int floor = y - 8;
                while (y > floor && WorldUtil.clearable(level.getBlockState(column.atY(y)))) {
                    y--;
                }
                BlockPos ground = column.atY(y);
                if (!level.getFluidState(ground).isEmpty() || !level.getFluidState(ground.above()).isEmpty()) {
                    fault = Fault.WATER;
                } else if (!WorldUtil.naturalGround(level.getBlockState(ground))) {
                    blockers.add(ground);
                }
                grounds[k++] = y;
            }
        }
        int[] footprint = new int[14];
        int m = 0;
        for (int c = 0; c < grounds.length; c++) {
            if (c % 3 != 2) {
                footprint[m++] = grounds[c];
            }
        }
        Arrays.sort(footprint);
        int base = footprint[footprint.length / 2];
        int min = Arrays.stream(grounds).min().orElse(base);
        int max = Arrays.stream(grounds).max().orElse(base);
        if (fault == Fault.NONE && (max - base > MAX_STEP || base - min > MAX_STEP)) {
            fault = Fault.STEEP;
        }
        int cost = 0;
        k = 0;
        for (int i = -1; i <= 5; i++) {
            for (int row = 0; row <= 2; row++) {
                BlockPos column = layout.column(i, row);
                int ground = grounds[k++];
                cost += Math.abs(ground - base);
                if (level.getBlockState(column.atY(ground)).is(Blocks.DIRT_PATH)) {
                    cost += 6;
                }
                int top = row < 2 ? base + 4 : base + 2;
                for (int y = base + 1; y <= top; y++) {
                    BlockPos pos = column.atY(y);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    if (WorldUtil.clearable(state)) {
                        cost++;
                    } else if (!(y <= ground && WorldUtil.naturalGround(state)) && !blockers.contains(pos)) {
                        blockers.add(pos);
                    }
                }
            }
        }
        Layout placed = new Layout(new BlockPos(layout.origin().getX(), base, layout.origin().getZ()), layout.front(), layout.side(), base);
        return new Plan(placed, cost, blockers, fault);
    }

    public static void showPreview(ServerPlayer player, Plan plan) {
        if (plan == null) {
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        PREVIEWS.removeIf(preview -> preview.player().equals(player.getUUID()));
        PREVIEWS.add(new Preview(level, player.getUUID(), plan, level.getGameTime() + 600));
    }

    public static void tickPreviews(MinecraftServer server) {
        if (PREVIEWS.isEmpty()) {
            return;
        }
        PREVIEWS.removeIf(preview -> preview.level().getGameTime() > preview.until()
                || server.getPlayerList().getPlayer(preview.player()) == null);
        DustParticleOptions frame = new DustParticleOptions(0x40FF60, 1.4F);
        DustParticleOptions blocked = new DustParticleOptions(0xFF3030, 1.8F);
        for (Preview preview : PREVIEWS) {
            ServerLevel level = preview.level();
            if (level.getGameTime() % 10 != 0) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(preview.player());
            if (player == null || player.level() != level) {
                continue;
            }
            Layout layout = preview.plan().layout();
            int base = layout.baseY();
            for (int i = -1; i <= 5; i++) {
                for (int row = 0; row <= 1; row++) {
                    BlockPos column = layout.column(i, row);
                    int top = row == 0 && i >= 0 && i <= 4 ? base + 3 : base + 1;
                    for (int y = base + 1; y <= top; y++) {
                        level.sendParticles(player, frame, true, true, column.getX() + 0.5, y + 0.5, column.getZ() + 0.5, 2, 0.2, 0.2, 0.2, 0.0);
                    }
                }
            }
            for (BlockPos pos : preview.plan().blockers()) {
                level.sendParticles(player, blocked, true, true, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 4, 0.3, 0.3, 0.3, 0.0);
            }
        }
    }

    private static void build(ServerLevel level, Layout layout, ColonyData data) {
        int base = layout.baseY();
        for (int i = -1; i <= 5; i++) {
            for (int row = 0; row <= 2; row++) {
                BlockPos column = layout.column(i, row);
                int top = row < 2 ? base + 4 : base + 2;
                for (int y = base + 1; y <= top; y++) {
                    BlockPos pos = column.atY(y);
                    if (!level.getBlockState(pos).isAir()) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
                BlockState fill = row < 2 ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.DIRT.defaultBlockState();
                for (int y = base - 3; y < base; y++) {
                    BlockPos pos = column.atY(y);
                    if (WorldUtil.isAirLike(level.getBlockState(pos))) {
                        level.setBlock(pos, fill, Block.UPDATE_ALL);
                    }
                }
                BlockPos floor = column.atY(base);
                if (row < 2) {
                    level.setBlock(floor, Blocks.COBBLESTONE.defaultBlockState(), Block.UPDATE_ALL);
                } else if (WorldUtil.isAirLike(level.getBlockState(floor))) {
                    level.setBlock(floor, Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
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
