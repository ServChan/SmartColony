package org.lts.smartcolony.colony;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.lts.smartcolony.SmartColonyConfig;
import org.lts.smartcolony.core.Blueprint;
import org.lts.smartcolony.core.BunkerPlan;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.Diplomacy;
import org.lts.smartcolony.core.Ids;
import org.lts.smartcolony.core.Pos;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.util.ItemIds;
import org.lts.smartcolony.util.WorldUtil;
import org.lts.smartcolony.work.Digger;
import org.lts.smartcolony.work.Placer;

public final class Bunker {
    private static final int RUN = 12;
    private static final double REACH_SQR = 30.0;
    private static final double FAR_SQR = 49.0;
    private static final int MIN_ADULTS = 10;
    private static final int BUSY_VILLAGE = 16;
    private static final long MIN_AGE = 24000L * 2;
    private static final long PLAN_EVERY = 1200L;
    private static final long RETRY_AFTER = 12000L;
    private static final double BAD_SHARE = 0.08;
    private static final int[] DEPTHS = {BunkerPlan.DEPTH, 13, 16};
    private static final int COVER = 3;
    private static final int MINE_GAP = 24;
    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final String[] FILL = {"cobblestone", "cobbled_deepslate", "stone", "andesite", "diorite", "granite", "tuff", "dirt"};
    private static final String[] STONE = {"stone_bricks", "cobblestone", "cobbled_deepslate", "stone", "andesite", "diorite", "granite", "tuff"};
    private static final int PATIENCE = 6;
    private static final long MAKESHIFT_FOR = 3000L;
    private static BunkerPlan.Templates templates;

    private enum Step {
        DONE,
        WAIT
    }

    private final Colony colony;
    private BunkerPlan.Layout cachedLayout;
    private String cachedWood;
    private List<BunkerPlan.Cell> cachedCells;
    private Set<Long> cachedDug;
    private long nextPlanAt;
    private String waitingFor = "";
    private long patientCell = Long.MIN_VALUE;
    private int patience;
    private long makeshiftUntil;

    Bunker(Colony colony) {
        this.colony = colony;
    }

    public static BunkerPlan.Templates templates() {
        if (templates == null) {
            templates = new BunkerPlan.Templates(load("bunker_hut"), load("bunker_room"));
        }
        return templates;
    }

    private static Blueprint load(String id) {
        String path = "/smartcolony/bunker/" + id + ".json";
        try (InputStream in = Bunker.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing bunker template " + path);
            }
            return Blueprint.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read bunker template " + path, e);
        }
    }

    private ColonyData.Bunker data() {
        return colony.data().bunker;
    }

    private String wood() {
        ColonyData.Bunker bunker = data();
        return bunker == null || bunker.wood == null || bunker.wood.isEmpty() ? "oak" : bunker.wood;
    }

    public boolean exists() {
        return data() != null;
    }

    public boolean finished() {
        return data() != null && data().finished;
    }

    public String waitingFor() {
        return waitingFor;
    }

    public int progress() {
        return data() == null ? 0 : Math.min(data().done, cells().size());
    }

    public int total() {
        return data() == null ? 0 : cells().size();
    }

    private List<BunkerPlan.Cell> cells() {
        BunkerPlan.Layout layout = data().layout();
        if (!layout.equals(cachedLayout) || !wood().equals(cachedWood)) {
            cachedLayout = layout;
            cachedWood = wood();
            cachedCells = BunkerPlan.cells(layout, templates(), cachedWood);
            cachedDug = BunkerPlan.digSet(cachedCells);
        }
        return cachedCells;
    }

    private Set<Long> dug() {
        cells();
        return cachedDug;
    }

    void tick(ServerLevel level, long ticks) {
        if (ticks % 200 != 77) {
            return;
        }
        ColonyData.Bunker bunker = data();
        if (bunker == null) {
            long now = level.getGameTime();
            if (!SmartColonyConfig.get().bunkers || now < nextPlanAt || !wanted(level)) {
                return;
            }
            nextPlanAt = now + (plan(level) ? PLAN_EVERY : RETRY_AFTER);
            return;
        }
        if (bunker.version < BunkerPlan.VERSION) {
            bunker.version = BunkerPlan.VERSION;
            bunker.done = 0;
            bunker.finished = false;
            colony.journal(level, "the bunker grows into a two-floor shelter with a hall, a kitchen and a dormitory");
            colony.markDirty();
            return;
        }
        if (!bunker.finished && bunker.done >= cells().size()) {
            finish(level);
        }
    }

    private boolean wanted(ServerLevel level) {
        ColonyData d = colony.data();
        int adults = colony.adultCount(level);
        if (level.getGameTime() - d.founded < MIN_AGE || adults < MIN_ADULTS || colony.desired().getOrDefault(Role.BUILDER, 0) < 2) {
            return false;
        }
        boolean war = d.relations.values().stream().anyMatch(relation -> relation.treaty() == Diplomacy.Treaty.WAR);
        return war || adults >= BUSY_VILLAGE || d.stat("alarms_rung") > 0 || d.stat("raids") > 0 || d.stat("deaths") > 0;
    }

    public boolean plan(ServerLevel level) {
        if (data() != null) {
            return false;
        }
        BlockPos depot = colony.depot();
        BunkerPlan.Layout best = null;
        int bestScore = Integer.MAX_VALUE;
        int farthest = Math.max(10, Math.min(48, (int) (colony.data().radius * 0.7)));
        for (int ring = 8; ring <= farthest; ring += 6) {
            for (int a = 0; a < 16; a++) {
                double angle = 2 * Math.PI * a / 16;
                int x = depot.getX() + (int) Math.round(Math.cos(angle) * ring);
                int z = depot.getZ() + (int) Math.round(Math.sin(angle) * ring);
                if (!WorldUtil.loaded(level, new BlockPos(x, 0, z)) || !colony.ownsGround(x, z)) {
                    continue;
                }
                int y = WorldUtil.surfaceY(level, x, z);
                for (int[] direction : DIRECTIONS) {
                    for (int depth : DEPTHS) {
                        BunkerPlan.Layout layout = BunkerPlan.Layout.standard(x, y, z, direction[0], direction[1], depth);
                        int bad = evaluate(level, layout);
                        if (bad < 0) {
                            continue;
                        }
                        int score = bad * 4 + ring * 2 + depth;
                        if (score < bestScore) {
                            bestScore = score;
                            best = layout;
                        }
                        break;
                    }
                }
            }
        }
        if (best == null) {
            colony.journal(level, "no place for a bunker: the ground near the depot is too wet, hollow or built up");
            return false;
        }
        ColonyData.Bunker bunker = new ColonyData.Bunker();
        bunker.entrance = new Pos(best.x(), best.y(), best.z());
        bunker.dirX = best.dirX();
        bunker.dirZ = best.dirZ();
        bunker.depth = best.depth();
        bunker.planned = level.getGameTime();
        bunker.version = BunkerPlan.VERSION;
        bunker.wood = colony.construction().preferredWood(level);
        colony.data().bunker = bunker;
        colony.markDirty();
        int capacity = BunkerPlan.capacity(templates());
        colony.journal(level, "planned a bunker at " + bunker.entrance.x() + " " + bunker.entrance.y() + " " + bunker.entrance.z()
                + ", " + best.depth() + " blocks down, room for " + capacity);
        colony.notify(level, Component.translatable("smartcolony.chat.bunker_planned", capacity));
        return true;
    }

    private int evaluate(ServerLevel level, BunkerPlan.Layout layout) {
        BunkerPlan.Templates t = templates();
        int[] hut = BunkerPlan.hutBox(layout, t);
        for (int x = hut[0]; x <= hut[3]; x++) {
            for (int z = hut[2]; z <= hut[5]; z++) {
                if (!WorldUtil.loaded(level, new BlockPos(x, 0, z))) {
                    return -1;
                }
                int ground = WorldUtil.surfaceY(level, x, z);
                BlockPos feet = new BlockPos(x, ground, z);
                if (Math.abs(ground - layout.y()) > 1 || !level.getFluidState(feet.below()).isEmpty() || !level.getFluidState(feet).isEmpty()
                        || colony.isProtected(feet, true) || colony.sites().onRoad(feet.below())) {
                    return -1;
                }
            }
        }
        int[] spot = BunkerPlan.spot(layout, t);
        for (ColonyData.Mine mine : colony.allMines()) {
            double dx = mine.entrance.x() - spot[0];
            double dz = mine.entrance.z() - spot[2];
            if (dx * dx + dz * dz < MINE_GAP * MINE_GAP) {
                return -1;
            }
        }
        int[] room = BunkerPlan.roomBox(layout, t);
        for (int x = room[0]; x <= room[3]; x++) {
            for (int z = room[2]; z <= room[5]; z++) {
                if (!WorldUtil.loaded(level, new BlockPos(x, 0, z)) || WorldUtil.surfaceY(level, x, z) - (room[4] + 1) < COVER) {
                    return -1;
                }
            }
        }
        int bad = 0;
        int counted = 0;
        for (BunkerPlan.Cell cell : BunkerPlan.cells(layout, t, "oak")) {
            if (cell.y() >= layout.y() - 1) {
                continue;
            }
            BlockPos pos = new BlockPos(cell.x(), cell.y(), cell.z());
            BlockState state = level.getBlockState(pos);
            if (!state.getFluidState().isEmpty() || level.getBlockEntity(pos) != null) {
                return -1;
            }
            float hardness = state.getDestroySpeed(level, pos);
            if (hardness < 0 || hardness > 10) {
                return -1;
            }
            counted++;
            if (state.isAir() || state.getBlock() instanceof FallingBlock) {
                bad++;
            }
        }
        return bad > counted * BAD_SHARE ? -1 : bad;
    }

    public boolean hasWork(ServerLevel level) {
        ColonyData.Bunker bunker = data();
        if (bunker == null || bunker.finished || bunker.version < BunkerPlan.VERSION || bunker.done >= cells().size()) {
            return false;
        }
        return level.isPositionEntityTicking(pos(cells().get(bunker.done)));
    }

    public Construction.Task task(ServerLevel level) {
        if (!hasWork(level)) {
            return null;
        }
        BunkerPlan.Cell cell = cells().get(data().done);
        int[] stand = BunkerPlan.stand(data().layout(), templates(), cell);
        return new Construction.Task(Construction.Kind.BUNKER, pos(cell), null, null, null, new BlockPos(stand[0], stand[1], stand[2]));
    }

    private static BlockPos pos(BunkerPlan.Cell cell) {
        return new BlockPos(cell.x(), cell.y(), cell.z());
    }

    public boolean build(ServerLevel level, Villager villager, Digger digger, Consumer<List<ItemStack>> stash) {
        ColonyData.Bunker bunker = data();
        if (bunker == null || bunker.finished) {
            return true;
        }
        List<BunkerPlan.Cell> list = cells();
        BunkerPlan.Layout layout = bunker.layout();
        if (bunker.done < list.size()
                && pos(list.get(bunker.done)).distToCenterSqr(villager.getX(), villager.getEyeY(), villager.getZ()) > FAR_SQR) {
            colony.data().bump("bunker_far", 1);
            return false;
        }
        int worked = 0;
        while (bunker.done < list.size() && worked < RUN) {
            BunkerPlan.Cell cell = list.get(bunker.done);
            BlockPos pos = pos(cell);
            if (worked > 0 && pos.distToCenterSqr(villager.getX(), villager.getEyeY(), villager.getZ()) > REACH_SQR) {
                break;
            }
            if (work(level, villager, digger, stash, layout, cell, pos) == Step.WAIT) {
                colony.data().bump("bunker_waits", 1);
                colony.markDirty();
                return worked > 0;
            }
            bunker.done++;
            worked++;
        }
        waitingFor = "";
        colony.markDirty();
        if (bunker.done >= list.size()) {
            finish(level);
        }
        return true;
    }

    private Step work(ServerLevel level, Villager villager, Digger digger, Consumer<List<ItemStack>> stash, BunkerPlan.Layout layout,
                      BunkerPlan.Cell cell, BlockPos pos) {
        villager.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        BlockState state = level.getBlockState(pos);
        if (cell.kind() == BunkerPlan.Kind.DIG) {
            BlockPos above = pos.above();
            if (level.getBlockState(above).getBlock() instanceof FallingBlock && !dug().contains(BunkerPlan.key(above.getX(), above.getY(), above.getZ()))) {
                if (!placeFill(level, villager, above)) {
                    return Step.WAIT;
                }
                colony.data().bump("bunker_sand_held", 1);
            }
            if (!state.getFluidState().isEmpty()) {
                if (!placeFill(level, villager, pos)) {
                    return Step.WAIT;
                }
                state = level.getBlockState(pos);
            }
            if (!state.isAir()) {
                if (level.getBlockEntity(pos) != null && !own(layout, pos) || state.getDestroySpeed(level, pos) < 0) {
                    colony.data().bump("bunker_cells_skipped", 1);
                    return Step.DONE;
                }
                if (WorldUtil.isAirLike(state)) {
                    level.destroyBlock(pos, false, villager);
                } else {
                    digger.breakNow(level, villager, pos, true);
                    stash.accept(digger.takeDrops());
                    colony.data().bump("bunker_dug", 1);
                }
            }
            return pos.getY() < layout.y() && !seal(level, villager, layout, pos) ? Step.WAIT : Step.DONE;
        }
        BlockState target = rotated(cell.state(), cell.rotation());
        if (target == null || state.is(target.getBlock())) {
            return Step.DONE;
        }
        if (level.getBlockEntity(pos) != null) {
            if (!own(layout, pos)) {
                return Step.DONE;
            }
            digger.breakNow(level, villager, pos, true);
            stash.accept(digger.takeDrops());
        }
        BlockState chosen = obtain(level, cell, target, pos);
        if (chosen == null) {
            if (cell.kind() == BunkerPlan.Kind.FURNISH) {
                colony.data().bump("bunker_furniture_missing", 1);
                return Step.DONE;
            }
            return Step.WAIT;
        }
        put(level, villager, pos, chosen);
        colony.data().bump("bunker_placed", 1);
        return Step.DONE;
    }

    private BlockState obtain(ServerLevel level, BunkerPlan.Cell cell, BlockState target, BlockPos pos) {
        Blueprint.Material material = Blueprint.material(cell.state());
        String item = material == null ? BuiltInRegistries.BLOCK.getKey(target.getBlock()).toString() : material.item();
        int batch = cell.kind() == BunkerPlan.Kind.BLOCK ? 32 : 1;
        List<String> proper = new ArrayList<>(List.of(item));
        String basic = Ids.mc("stone_bricks");
        if (Ids.path(item).endsWith("stone_bricks") && !item.equals(basic)) {
            proper.add(basic);
        }
        for (String wanted : proper) {
            BlockState state = swap(cell, item, wanted);
            if (state != null && take(level, wanted)) {
                return state;
            }
        }
        for (String wanted : proper) {
            BlockState state = swap(cell, item, wanted);
            if (state != null && colony.produce(level, wanted, batch) && take(level, wanted)) {
                return state;
            }
        }
        long now = level.getGameTime();
        long here = pos.asLong();
        if (patientCell != here) {
            patientCell = here;
            patience = 0;
        }
        if (cell.kind() == BunkerPlan.Kind.BLOCK && now >= makeshiftUntil && patience < PATIENCE) {
            patience++;
            colony.request(item);
            waitingFor = item;
            return null;
        }
        if (cell.kind() == BunkerPlan.Kind.BLOCK && now >= makeshiftUntil) {
            makeshiftUntil = now + MAKESHIFT_FOR;
            colony.data().bump("bunker_makeshift", 1);
        }
        for (String alternative : alternatives(level, item)) {
            if (proper.contains(alternative) || !have(level, alternative)) {
                continue;
            }
            BlockState swapped = swap(cell, item, alternative);
            if (swapped != null && take(level, alternative)) {
                return swapped;
            }
        }
        colony.request(item);
        waitingFor = item;
        return null;
    }

    private BlockState swap(BunkerPlan.Cell cell, String item, String replacement) {
        if (replacement.equals(item)) {
            return rotated(cell.state(), cell.rotation());
        }
        return rotated(cell.state().replace(Ids.path(item), Ids.path(replacement)), cell.rotation());
    }

    private boolean own(BunkerPlan.Layout layout, BlockPos pos) {
        int[] room = BunkerPlan.roomBox(layout, templates());
        int[] hut = BunkerPlan.hutBox(layout, templates());
        return within(room, pos) || within(hut, pos);
    }

    private static boolean within(int[] box, BlockPos pos) {
        return pos.getX() >= box[0] && pos.getX() <= box[3] && pos.getY() >= box[1] && pos.getY() <= box[4] && pos.getZ() >= box[2]
                && pos.getZ() <= box[5];
    }

    private List<String> alternatives(ServerLevel level, String item) {
        String path = Ids.path(item);
        List<String> out = new ArrayList<>();
        if (path.endsWith("stone_bricks")) {
            for (String stone : STONE) {
                out.add(Ids.mc(stone));
            }
        } else if (path.equals("stone_brick_stairs")) {
            out.add(Ids.mc("cobblestone_stairs"));
            out.add(Ids.mc(wood() + "_stairs"));
        } else if (path.equals("stone_brick_slab")) {
            out.add(Ids.mc("cobblestone_slab"));
            out.add(Ids.mc(wood() + "_slab"));
        } else if (path.endsWith("_planks") || path.endsWith("_log") || path.endsWith("_door")) {
            String suffix = path.endsWith("_planks") ? "_planks" : path.endsWith("_door") ? "_door" : "_log";
            if (path.startsWith("stripped_")) {
                out.add(Ids.mc(path.substring("stripped_".length())));
            }
            for (String stock : colony.warehouse().stock(level).view().keySet()) {
                if (Ids.path(stock).endsWith(suffix) && !Ids.path(stock).startsWith("stripped_")) {
                    out.add(stock);
                }
            }
        }
        return out;
    }

    private boolean have(ServerLevel level, String item) {
        return colony.stockOf(level, id -> id.equals(item)) > 0;
    }

    private boolean take(ServerLevel level, String item) {
        return colony.warehouse().take(level, item, 1) == 1;
    }

    private void put(ServerLevel level, Villager villager, BlockPos pos, BlockState state) {
        int flags = Block.UPDATE_ALL | Block.UPDATE_KNOWN_SHAPE;
        if (state.getBlock() instanceof DoorBlock) {
            level.setBlock(pos, state, flags);
            level.setBlock(pos.above(), state.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), flags);
            level.playSound(null, pos, state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 0.8F);
            return;
        }
        if (state.getBlock() instanceof BedBlock) {
            BlockPos head = pos.relative(state.getValue(BedBlock.FACING));
            if (!WorldUtil.isAirLike(level.getBlockState(head))) {
                return;
            }
            level.setBlock(pos, state, flags);
            level.setBlock(head, state.setValue(BedBlock.PART, BedPart.HEAD), flags);
            level.playSound(null, pos, state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 0.8F);
            return;
        }
        Placer.place(level, villager, pos, state);
        if (level.getBlockEntity(pos) instanceof SignBlockEntity sign) {
            sign.setText(sign.getText(SignTextSlot.FRONT).asMutable()
                    .setLine(0, Component.translatable("smartcolony.bunker.sign.title"))
                    .setLine(1, Component.literal(colony.data().name))
                    .setLine(2, Component.translatable("smartcolony.bunker.sign.places", BunkerPlan.capacity(templates())))
                    .asImmutable(), SignTextSlot.FRONT);
            sign.setWaxed(true);
        }
    }

    private BlockState rotated(String state, int rotation) {
        BlockState parsed = parse(state.replace("$wood", wood()));
        return parsed == null ? null : parsed.rotate(Rotation.values()[Math.floorMod(rotation, 4)]);
    }

    private boolean seal(ServerLevel level, Villager villager, BunkerPlan.Layout layout, BlockPos pos) {
        Set<Long> dug = dug();
        for (Direction direction : Direction.values()) {
            BlockPos side = pos.relative(direction);
            if (side.getY() >= layout.y() - 1 || dug.contains(BunkerPlan.key(side.getX(), side.getY(), side.getZ()))) {
                continue;
            }
            BlockState state = level.getBlockState(side);
            boolean open = !state.getFluidState().isEmpty() || WorldUtil.passable(level, side)
                    || direction == Direction.DOWN && !WorldUtil.solidFloor(level, side);
            if (!open || level.getBlockEntity(side) != null) {
                continue;
            }
            if (!placeFill(level, villager, side)) {
                return false;
            }
            colony.data().bump("bunker_sealed", 1);
        }
        return true;
    }

    private boolean placeFill(ServerLevel level, Villager villager, BlockPos pos) {
        for (String name : FILL) {
            String id = Ids.mc(name);
            if (colony.warehouse().take(level, id, 1) == 1) {
                Placer.place(level, villager, pos, Block.byItem(ItemIds.item(id)).defaultBlockState());
                return true;
            }
        }
        colony.request(Ids.mc("cobblestone"));
        waitingFor = Ids.mc("cobblestone");
        return false;
    }

    private static BlockState parse(String state) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, state, false).blockState();
        } catch (Exception e) {
            return null;
        }
    }

    private void finish(ServerLevel level) {
        ColonyData.Bunker bunker = data();
        if (bunker == null || bunker.finished) {
            return;
        }
        bunker.finished = true;
        colony.data().bump("bunkers_built", 1);
        int capacity = BunkerPlan.capacity(templates());
        colony.journal(level, "the bunker is ready: room for " + capacity + " under " + bunker.depth + " blocks of ground");
        colony.notify(level, Component.translatable("smartcolony.chat.bunker_done", capacity));
        colony.markDirty();
    }

    public boolean ready(ServerLevel level) {
        ColonyData.Bunker bunker = data();
        if (bunker == null || !bunker.finished) {
            return false;
        }
        int[] spot = BunkerPlan.spot(bunker.layout(), templates());
        return WorldUtil.loaded(level, new BlockPos(spot[0], spot[1], spot[2]));
    }

    public BlockPos spot() {
        int[] spot = BunkerPlan.spot(data().layout(), templates());
        return new BlockPos(spot[0], spot[1], spot[2]);
    }

    public int capacity() {
        return BunkerPlan.capacity(templates());
    }

    public boolean inside(BlockPos pos) {
        ColonyData.Bunker bunker = data();
        return bunker != null && BunkerPlan.inRoom(bunker.layout(), templates(), pos.getX(), pos.getY(), pos.getZ());
    }

    public boolean covers(Pos pos, int margin) {
        ColonyData.Bunker bunker = data();
        if (bunker == null) {
            return false;
        }
        int[] box = BunkerPlan.bounds(cells());
        return pos.x() >= box[0] - margin && pos.x() <= box[3] + margin && pos.z() >= box[2] - margin && pos.z() <= box[5] + margin;
    }

    public BlockPos entrance() {
        ColonyData.Bunker bunker = data();
        if (bunker == null) {
            return null;
        }
        int[] outside = BunkerPlan.outside(bunker.layout(), templates());
        return new BlockPos(outside[0], outside[1], outside[2]);
    }
}
