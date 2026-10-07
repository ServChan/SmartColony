package org.lts.smartcolony.work;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChainBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.lts.smartcolony.colony.Colony;
import org.lts.smartcolony.colony.Improvements;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.CraftPlanner;
import org.lts.smartcolony.core.Fit;
import org.lts.smartcolony.core.Ids;
import org.lts.smartcolony.core.MineFrames;
import org.lts.smartcolony.core.MinePlan;
import org.lts.smartcolony.core.QuarryFit;
import org.lts.smartcolony.core.QuarryHead;
import org.lts.smartcolony.core.QuarryPlan;
import org.lts.smartcolony.core.Stock;
import org.lts.smartcolony.core.Tech;
import org.lts.smartcolony.util.ItemIds;
import org.lts.smartcolony.util.WorldUtil;

public final class Fitter {
    public static final int WOOD_RESERVE = 32;
    public static final int MAX_KINDS = 3;
    public static final int NEAR_VERTICAL = 8;
    public static final double NEAR_QUARRY = 13;
    public static final double NEAR_TUNNEL = 9;
    private static final Set<String> BASE_ITEMS = Set.of(Ids.mc("torch"), Ids.mc("cobblestone"));

    private record Spec(String item, String state) {
    }

    private record Flags(String wood, boolean lantern, String chain, boolean bricks) {
    }

    private final Colony colony;
    private ColonyData.Mine headMine;
    private org.lts.smartcolony.core.Pos headShed;
    private List<Fit> head = List.of();
    private ColonyData.Mine pitMine;
    private int pitCompleted = -1;
    private List<Fit> pit = List.of();
    private ColonyData.Mine frameMine;
    private int frameProgress = -1;
    private List<Fit> frames = List.of();
    private static String chainId;

    public Fitter(Colony colony) {
        this.colony = colony;
    }

    public String wood(ServerLevel level, ColonyData.Mine mine) {
        if (mine.wood == null || mine.wood.isEmpty()) {
            mine.wood = colony.construction().preferredWood(level);
            colony.markDirty();
        }
        return mine.wood;
    }

    public List<Fit> wanted(ServerLevel level, ColonyData.Mine mine) {
        if (mine == null) {
            return List.of();
        }
        if (!mine.quarry()) {
            return frames(mine);
        }
        if (mine.decorVersion < Improvements.QUARRY_DECOR_VERSION) {
            return List.of();
        }
        QuarryPlan plan = (QuarryPlan) mine.plan();
        if (headMine != mine || !java.util.Objects.equals(headShed, mine.shed)) {
            headMine = mine;
            headShed = mine.shed;
            Map<Long, Integer> memo = new HashMap<>();
            head = QuarryHead.head(plan, (x, z) -> memo.computeIfAbsent(BlockPos.asLong(x, 0, z), key -> groundAt(level, x, z)), mine.shed);
        }
        int completed = Math.min(plan.layers(), mine.progress / plan.perLayer());
        if (pitMine != mine || pitCompleted != completed) {
            pitMine = mine;
            pitCompleted = completed;
            pit = QuarryFit.pit(plan, completed);
        }
        List<Fit> all = new ArrayList<>(head.size() + pit.size());
        all.addAll(head);
        all.addAll(pit);
        return all;
    }

    private List<Fit> frames(ColonyData.Mine mine) {
        if (frameMine != mine || Math.abs(frameProgress - mine.progress) >= 1) {
            frameMine = mine;
            frameProgress = mine.progress;
            frames = MineFrames.frames((MinePlan) mine.plan(), mine.progress);
        }
        return frames;
    }

    public boolean headFinished(ServerLevel level, ColonyData.Mine mine) {
        if (!mine.quarry() || mine.decorVersion < Improvements.QUARRY_DECOR_VERSION) {
            return false;
        }
        wanted(level, mine);
        Flags flags = flags(level, mine);
        for (Fit fit : head) {
            BlockPos pos = WorldUtil.bp(fit.pos());
            if (!WorldUtil.loaded(level, pos)) {
                return false;
            }
            BlockState current = level.getBlockState(pos);
            if (satisfied(current, fit, flags) || !WorldUtil.isAirLike(current) && !placeable(level, pos, current, fit, mine)) {
                continue;
            }
            return false;
        }
        return true;
    }

    public Map<String, Integer> needs(ServerLevel level, ColonyData.Mine mine, int cap, List<BlockPos> stops) {
        Map<String, Integer> needs = new LinkedHashMap<>();
        List<Fit> list = wanted(level, mine);
        if (list.isEmpty()) {
            return needs;
        }
        Flags flags = flags(level, mine);
        Stock stock = colony.warehouse().stock(level);
        Map<String, Boolean> available = new HashMap<>();
        int total = 0;
        for (Fit fit : list) {
            if (total >= cap) {
                break;
            }
            Spec spec = spec(fit, flags);
            if (spec == null) {
                continue;
            }
            BlockPos pos = WorldUtil.bp(fit.pos());
            if (!reachable(pos, stops, mine) || !WorldUtil.loaded(level, pos)) {
                continue;
            }
            BlockState current = level.getBlockState(pos);
            if (satisfied(current, fit, flags) || !placeable(level, pos, current, fit, mine)) {
                continue;
            }
            if (!available.computeIfAbsent(spec.item(), item -> obtainable(stock, item))) {
                continue;
            }
            if (!needs.containsKey(spec.item()) && !BASE_ITEMS.contains(spec.item()) && kinds(needs) >= MAX_KINDS) {
                continue;
            }
            needs.merge(spec.item(), 1, Integer::sum);
            total++;
        }
        return needs;
    }

    private static boolean reachable(BlockPos pos, List<BlockPos> stops, ColonyData.Mine mine) {
        double reach = mine.quarry() ? NEAR_QUARRY : NEAR_TUNNEL;
        for (BlockPos stop : stops) {
            if (Math.abs(pos.getY() - stop.getY()) <= NEAR_VERTICAL && horizontal(pos, stop) <= reach * reach) {
                return true;
            }
        }
        return false;
    }

    private static int kinds(Map<String, Integer> needs) {
        int kinds = 0;
        for (String item : needs.keySet()) {
            if (!BASE_ITEMS.contains(item)) {
                kinds++;
            }
        }
        return kinds;
    }

    private boolean obtainable(Stock stock, String item) {
        if (woodBased(item)) {
            int units = 0;
            for (String wood : Ids.WOOD_TYPES) {
                units += stock.get(Ids.mc(wood + "_log")) * 4 + stock.get(Ids.mc(wood + "_planks"));
            }
            if (units < WOOD_RESERVE) {
                return false;
            }
        }
        return stock.get(item) > 0 || CraftPlanner.plan(stock, item, 1).isPresent();
    }

    private static boolean woodBased(String item) {
        String path = Ids.path(item);
        return path.endsWith("_log") || path.endsWith("_planks") || path.endsWith("_fence") || path.endsWith("_slab")
                && !path.startsWith("cobblestone") || path.endsWith("_stairs") && !path.startsWith("cobblestone")
                || path.equals("barrel");
    }

    public int placeNear(ServerLevel level, Villager villager, ColonyData.Mine mine, int limit) {
        if (limit <= 0) {
            return 0;
        }
        List<Fit> list = wanted(level, mine);
        if (list.isEmpty()) {
            return 0;
        }
        Flags flags = flags(level, mine);
        int placed = 0;
        for (int pass = 0; pass < 2 && placed < limit; pass++) {
            placed += place(level, villager, mine, list, flags, limit - placed);
        }
        if (placed > 0) {
            colony.markDirty();
        }
        return placed;
    }

    private int place(ServerLevel level, Villager villager, ColonyData.Mine mine, List<Fit> list, Flags flags, int limit) {
        double reach = mine.quarry() ? NEAR_QUARRY : NEAR_TUNNEL;
        BlockPos at = villager.blockPosition();
        int placed = 0;
        for (Fit fit : list) {
            if (placed >= limit) {
                break;
            }
            BlockPos pos = WorldUtil.bp(fit.pos());
            if (Math.abs(pos.getY() - at.getY()) > NEAR_VERTICAL || horizontal(pos, at) > reach * reach) {
                continue;
            }
            Spec spec = spec(fit, flags);
            if (spec == null || Inv.count(villager, spec.item()) == 0 || !WorldUtil.loaded(level, pos)) {
                continue;
            }
            BlockState current = level.getBlockState(pos);
            if (satisfied(current, fit, flags) || !placeable(level, pos, current, fit, mine)) {
                continue;
            }
            BlockState target = parse(spec.state());
            if (target == null || !target.canSurvive(level, pos) || !stub(fit) && Placer.occupied(level, pos)) {
                continue;
            }
            Placer.place(level, villager, pos, target);
            Inv.remove(villager, stack -> ItemIds.id(stack).equals(spec.item()), 1);
            colony.data().bump("fittings_placed", 1);
            placed++;
        }
        return placed;
    }

    private static double horizontal(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private static boolean stub(Fit fit) {
        return fit.part() == Fit.Part.STAIR || fit.part() == Fit.Part.LANDING;
    }

    private Flags flags(ServerLevel level, ColonyData.Mine mine) {
        Stock stock = colony.warehouse().stock(level);
        boolean lantern = Tech.ironAge(colony.data().age)
                && (stock.get(Ids.mc("lantern")) > 0 || CraftPlanner.plan(stock, Ids.mc("lantern"), 1).isPresent());
        String chain = chainId();
        boolean hasChain = chain != null && stock.get(chain) > 0;
        return new Flags(wood(level, mine), lantern, hasChain ? chain : null, stock.get(Ids.mc("stone_bricks")) > 0);
    }

    private static String chainId() {
        if (chainId == null) {
            chainId = "";
            for (String candidate : List.of("minecraft:iron_chain", "minecraft:chain")) {
                Identifier key = Identifier.tryParse(candidate);
                if (key != null && BuiltInRegistries.BLOCK.containsKey(key)) {
                    chainId = candidate;
                    break;
                }
            }
        }
        return chainId.isEmpty() ? null : chainId;
    }

    private Spec spec(Fit fit, Flags flags) {
        String wood = flags.wood();
        return switch (fit.part()) {
            case LOG_X -> new Spec(Ids.mc(wood + "_log"), Ids.mc(wood + "_log") + "[axis=x]");
            case LOG_Y -> new Spec(Ids.mc(wood + "_log"), Ids.mc(wood + "_log") + "[axis=y]");
            case LOG_Z -> new Spec(Ids.mc(wood + "_log"), Ids.mc(wood + "_log") + "[axis=z]");
            case FENCE -> new Spec(Ids.mc(wood + "_fence"), Ids.mc(wood + "_fence"));
            case STAIR -> new Spec(Ids.mc("cobblestone_stairs"), Ids.mc("cobblestone_stairs") + "[facing=" + fit.dir().key() + ",half=bottom]");
            case STAIR_TOP -> new Spec(Ids.mc(wood + "_stairs"), Ids.mc(wood + "_stairs") + "[facing=" + fit.dir().key() + ",half=top]");
            case LANDING, PLANK -> new Spec(Ids.mc(wood + "_planks"), Ids.mc(wood + "_planks"));
            case SLAB -> new Spec(Ids.mc(wood + "_slab"), Ids.mc(wood + "_slab") + "[type=bottom]");
            case CURB -> flags.bricks() ? new Spec(Ids.mc("stone_bricks"), Ids.mc("stone_bricks"))
                    : new Spec(Ids.mc("cobblestone"), Ids.mc("cobblestone"));
            case LAMP -> flags.lantern() ? new Spec(Ids.mc("lantern"), Ids.mc("lantern") + "[hanging=false]")
                    : new Spec(Ids.mc("torch"), Ids.mc("torch"));
            case HANG_LAMP -> flags.lantern() ? new Spec(Ids.mc("lantern"), Ids.mc("lantern") + "[hanging=true]") : null;
            case ROPE -> flags.chain() != null ? new Spec(flags.chain(), flags.chain() + "[axis=y]")
                    : new Spec(Ids.mc(wood + "_fence"), Ids.mc(wood + "_fence"));
            case BARREL -> new Spec(Ids.mc("barrel"), Ids.mc("barrel") + "[facing=up]");
            case TABLE -> new Spec(Ids.mc("crafting_table"), Ids.mc("crafting_table"));
        };
    }

    private static boolean satisfied(BlockState current, Fit fit, Flags flags) {
        return switch (fit.part()) {
            case LOG_X -> current.is(BlockTags.LOGS) && axis(current) == net.minecraft.core.Direction.Axis.X;
            case LOG_Y -> current.is(BlockTags.LOGS) && axis(current) == net.minecraft.core.Direction.Axis.Y;
            case LOG_Z -> current.is(BlockTags.LOGS) && axis(current) == net.minecraft.core.Direction.Axis.Z;
            case FENCE -> current.is(BlockTags.WOODEN_FENCES);
            case STAIR, STAIR_TOP -> current.getBlock() instanceof StairBlock;
            case LANDING, PLANK -> current.is(BlockTags.PLANKS);
            case SLAB -> current.is(BlockTags.SLABS);
            case CURB -> current.is(Blocks.COBBLESTONE) || current.is(Blocks.STONE_BRICKS);
            case LAMP -> current.is(Blocks.TORCH) || current.is(Blocks.LANTERN);
            case HANG_LAMP -> current.is(Blocks.LANTERN);
            case ROPE -> current.is(BlockTags.WOODEN_FENCES) || current.getBlock() instanceof ChainBlock;
            case BARREL -> current.is(Blocks.BARREL);
            case TABLE -> current.is(Blocks.CRAFTING_TABLE);
        };
    }

    private static net.minecraft.core.Direction.Axis axis(BlockState state) {
        return state.hasProperty(RotatedPillarBlock.AXIS) ? state.getValue(RotatedPillarBlock.AXIS) : null;
    }

    private static boolean placeable(ServerLevel level, BlockPos pos, BlockState current, Fit fit, ColonyData.Mine mine) {
        if (stub(fit)) {
            return !current.isAir() && current.getFluidState().isEmpty() && !current.hasBlockEntity()
                    && current.getDestroySpeed(level, pos) >= 0 && !isOre(current) && !(current.getBlock() instanceof FallingBlock);
        }
        if (WorldUtil.isAirLike(current) || ours(current) && !current.is(BlockTags.LOGS)) {
            return true;
        }
        return !mine.quarry() && rock(current);
    }

    private static boolean rock(BlockState state) {
        return (state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.SUBSTRATE_OVERWORLD)) && !isOre(state)
                && !(state.getBlock() instanceof FallingBlock) && state.getFluidState().isEmpty();
    }

    private static boolean isOre(BlockState state) {
        String path = Ids.path(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        return path.endsWith("_ore") || path.equals("ancient_debris");
    }

    private static boolean ours(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(BlockTags.PLANKS) || state.is(BlockTags.SLABS) || state.is(BlockTags.WOODEN_FENCES)
                || state.is(BlockTags.STAIRS) || state.is(Blocks.COBBLESTONE) || state.is(Blocks.STONE_BRICKS)
                || state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH) || state.is(Blocks.LANTERN) || state.is(Blocks.BARREL)
                || state.is(Blocks.CRAFTING_TABLE) || state.is(Blocks.COMPOSTER) || state.is(Blocks.LADDER)
                || state.getBlock() instanceof ChainBlock;
    }

    private int groundAt(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        int floor = y - 12;
        while (y > floor) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (!(WorldUtil.removable(level, pos) || ours(state))) {
                break;
            }
            y--;
        }
        return y;
    }

    private static BlockState parse(String state) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, state, false).blockState();
        } catch (CommandSyntaxException e) {
            return null;
        }
    }
}
