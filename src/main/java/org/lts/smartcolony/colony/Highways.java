package org.lts.smartcolony.colony;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;
import org.lts.smartcolony.SmartColonyConfig;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.Diplomacy;
import org.lts.smartcolony.core.HighwayPlan;
import org.lts.smartcolony.core.Ids;
import org.lts.smartcolony.core.Pos;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.util.WorldUtil;
import org.lts.smartcolony.work.Placer;

public final class Highways {
    public static final String CAMP_ID = "camp";
    public static final String CAMP_KIND = "camp";
    private static final int WINDOW = 10;
    private static final int MIN_ADULTS = 8;
    private static final int CAMP_LEAD = 12;
    private static final long RETRY = 2400L;
    private static final int MAX_FAILURES = 4;
    private static final int SIGN_OFFSET = 2;

    private final Colony colony;
    private final Map<ColonyData.Highway, Set<Integer>> finished = new HashMap<>();

    Highways(Colony colony) {
        this.colony = colony;
    }

    private ColonyData data() {
        return colony.data();
    }

    public static boolean remote(ColonyData.Building building) {
        return building != null && (CAMP_KIND.equals(building.kind) || org.lts.smartcolony.core.PorterPlan.OUTPOST_KIND.equals(building.kind));
    }

    public ColonyData.Highway to(Colony other) {
        for (ColonyData.Highway highway : data().highways) {
            if (highway.partner.equals(other.id())) {
                return highway;
            }
        }
        return null;
    }

    public void tick(ServerLevel level, long ticks) {
        if (!SmartColonyConfig.get().highways || ticks % 100 != 41) {
            return;
        }
        long now = level.getGameTime();
        data().highways.removeIf(highway -> ColonyManager.byId(highway.partner) == null);
        for (ColonyData.Highway highway : new ArrayList<>(data().highways)) {
            if (now < highway.retryAt) {
                continue;
            }
            switch (highway.phase) {
                case "route" -> route(level, highway, now);
                case "build" -> progress(level, highway, now);
                default -> {
                }
            }
        }
        if (ticks % 400 == 41) {
            consider(level, now);
        }
    }

    private void consider(ServerLevel level, long now) {
        if (colony.adultCount(level) < MIN_ADULTS || colony.desired().getOrDefault(Role.BUILDER, 0) < 2) {
            return;
        }
        for (ColonyData.Highway highway : data().highways) {
            if ("route".equals(highway.phase)) {
                return;
            }
        }
        for (Map.Entry<String, ColonyData.Relation> entry : data().relations.entrySet()) {
            Colony other = ColonyManager.byId(entry.getKey());
            if (other == null || colony.id().compareTo(other.id()) > 0 || to(other) != null || other.highways().to(colony) != null
                    || !other.data().dimension.equals(data().dimension) || !level.isPositionEntityTicking(other.center())) {
                continue;
            }
            ColonyData.Relation relation = entry.getValue();
            ColonyData.Relation theirs = other.data().relations.get(colony.id());
            double distance = Math.sqrt(colony.center().distSqr(other.center()));
            if (!HighwayPlan.wanted(relation.treaty(), relation.standing, distance)
                    || theirs != null && !HighwayPlan.wanted(theirs.treaty(), theirs.standing, distance)) {
                continue;
            }
            begin(level, other, now);
            return;
        }
    }

    public String order(ServerLevel level, Colony other) {
        if (other == colony || !other.data().dimension.equals(data().dimension)) {
            return "unknown";
        }
        if (Embassy.atWar(colony, other)) {
            return "war";
        }
        if (Math.sqrt(colony.center().distSqr(other.center())) > HighwayPlan.MAX_LENGTH) {
            return "far";
        }
        if (to(other) != null || other.highways().to(colony) != null) {
            ColonyData.Highway lead = colony.id().compareTo(other.id()) < 0 ? to(other) : other.highways().to(colony);
            if (lead != null && "blocked".equals(lead.phase)) {
                lead.phase = "route";
                lead.failures = 0;
                lead.retryAt = 0;
                return "retry";
            }
            return "exists";
        }
        Colony lead = colony.id().compareTo(other.id()) < 0 ? colony : other;
        Colony follower = lead == colony ? other : colony;
        lead.highways().begin(level, follower, level.getGameTime());
        return "ordered";
    }

    void begin(ServerLevel level, Colony other, long now) {
        BlockPos start = mouth(level, colony, other);
        BlockPos goal = mouth(level, other, colony);
        ColonyData.Highway highway = new ColonyData.Highway();
        highway.partner = other.id();
        highway.lead = true;
        highway.since = now;
        highway.start = WorldUtil.pos(start);
        highway.goal = WorldUtil.pos(goal);
        highway.cells.add(highway.start);
        data().highways.add(highway);
        colony.journal(level, "surveyors set out to lay a road to " + other.data().name);
        Embassy.tell(level, colony, other, Component.translatable("smartcolony.highway.planned", data().name, other.data().name,
                (int) Math.round(Math.sqrt(start.distSqr(goal)))));
        colony.markDirty();
    }

    private static BlockPos mouth(ServerLevel level, Colony from, Colony toward) {
        BlockPos center = from.center();
        double angle = Math.atan2(toward.center().getZ() - center.getZ(), toward.center().getX() - center.getX());
        ColonyData.Wall wall = from.data().wall;
        if (wall != null && !wall.gates.isEmpty()) {
            Pos best = null;
            double bestDiff = Math.PI / 3;
            for (Pos gate : wall.gates) {
                double diff = Math.abs(Math.atan2(gate.z() - center.getZ(), gate.x() - center.getX()) - angle);
                diff = Math.min(diff, Math.PI * 2 - diff);
                if (diff < bestDiff) {
                    bestDiff = diff;
                    best = gate;
                }
            }
            if (best != null) {
                int[] out = HighwayPlan.edge(center.getX(), center.getZ(), toward.center().getX(), toward.center().getZ(),
                        (int) Math.round(Math.hypot(best.x() - center.getX(), best.z() - center.getZ())) + 3);
                int[] at = new int[] {best.x() + Integer.signum(out[0] - best.x()) * 3, best.z() + Integer.signum(out[1] - best.z()) * 3};
                return WorldUtil.surface(level, at[0], at[1]).below();
            }
        }
        int[] edge = HighwayPlan.edge(center.getX(), center.getZ(), toward.center().getX(), toward.center().getZ(), from.data().radius + 6);
        return WorldUtil.surface(level, edge[0], edge[1]).below();
    }

    private final class Ground implements HighwayPlan.Grid {
        final ServerLevel level;
        final Colony partner;
        final Map<Long, int[]> cache = new HashMap<>();

        Ground(ServerLevel level, Colony partner) {
            this.level = level;
            this.partner = partner;
        }

        int[] sample(int x, int z) {
            return cache.computeIfAbsent(BlockPos.asLong(x, 0, z), k -> evaluate(x, z));
        }

        private int[] evaluate(int x, int z) {
            if (!WorldUtil.loaded(level, new BlockPos(x, 0, z))) {
                return new int[] {-1, 0};
            }
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            int floor = y - 6;
            while (y > floor) {
                BlockState state = level.getBlockState(new BlockPos(x, y, z));
                if (state.isAir() || state.is(Blocks.SNOW) || WorldUtil.clearable(state) && state.getFluidState().isEmpty()) {
                    y--;
                    continue;
                }
                break;
            }
            BlockPos ground = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(ground);
            BlockPos feet = ground.above();
            if (colony.construction().paths().reserved(feet) || partner != null && partner.construction().paths().reserved(feet)) {
                return new int[] {-1, y};
            }
            if (!state.getFluidState().isEmpty()) {
                boolean shallow = level.getFluidState(ground).isSourceOfType(Fluids.WATER) && level.getFluidState(feet).isEmpty()
                        && WorldUtil.solidFloor(level, ground.below()) || level.getFluidState(ground).isSourceOfType(Fluids.WATER)
                        && level.getFluidState(ground.below()).isSourceOfType(Fluids.WATER) && WorldUtil.solidFloor(level, ground.below(2));
                return new int[] {shallow && free(feet) && free(feet.above()) ? 50 : -1, y};
            }
            if (state.is(Blocks.FARMLAND) || state.is(Blocks.MUD) || state.is(BlockTags.ICE) || state.is(BlockTags.LEAVES)
                    || state.is(BlockTags.LOGS) || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CAMPFIRE) || state.is(Blocks.CACTUS)
                    || level.getBlockEntity(ground) != null) {
                return new int[] {-1, y};
            }
            if (!free(feet) || !free(feet.above())) {
                return new int[] {-1, y};
            }
            if (state.is(Blocks.DIRT_PATH)) {
                return new int[] {6, y};
            }
            if (WorldUtil.isSoil(state) || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.PODZOL) || state.is(Blocks.MYCELIUM)
                    || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.ROOTED_DIRT) || state.is(Blocks.SNOW_BLOCK)) {
                return new int[] {10, y};
            }
            if (state.is(BlockTags.SAND) || state.is(Blocks.GRAVEL)) {
                return new int[] {16, y};
            }
            if (state.isFaceSturdy(level, ground, net.minecraft.core.Direction.UP)) {
                return new int[] {14, y};
            }
            return new int[] {-1, y};
        }

        private boolean free(BlockPos pos) {
            BlockState state = level.getBlockState(pos);
            if (state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.CACTUS) || state.is(BlockTags.FIRE) || state.is(Blocks.COBWEB)
                    || state.is(BlockTags.LOGS) || !state.getFluidState().isEmpty()) {
                return false;
            }
            return state.is(Blocks.SNOW) || state.is(BlockTags.LEAVES) || WorldUtil.passable(level, pos);
        }

        @Override
        public double cost(int x, int z) {
            int[] cell = sample(x, z);
            return cell[0] < 0 ? -1 : cell[0] / 10.0;
        }

        @Override
        public int height(int x, int z) {
            return sample(x, z)[1];
        }
    }

    private void route(ServerLevel level, ColonyData.Highway highway, long now) {
        Colony partner = ColonyManager.byId(highway.partner);
        if (partner == null) {
            return;
        }
        List<int[]> waypoints = HighwayPlan.waypoints(highway.start.x(), highway.start.z(), highway.goal.x(), highway.goal.z(),
                HighwayPlan.SEGMENT);
        if (highway.segment >= waypoints.size()) {
            finish(level, highway, partner, now);
            return;
        }
        Pos last = highway.cells.getLast();
        int[] target = waypoints.get(highway.segment);
        boolean finalLeg = highway.segment == waypoints.size() - 1;
        if (!WorldUtil.loaded(level, new BlockPos(target[0], 0, target[1])) || !WorldUtil.loaded(level, WorldUtil.bp(last))) {
            highway.retryAt = now + 600;
            return;
        }
        Ground ground = new Ground(level, partner);
        List<int[]> path = List.of();
        for (int offset : finalLeg ? new int[] {0} : HighwayPlan.SIDESTEPS) {
            int[] goal = HighwayPlan.sidestep(highway.start.x(), highway.start.z(), highway.goal.x(), highway.goal.z(), target, offset);
            path = HighwayPlan.route(ground, last.x(), last.z(), goal[0], goal[1], HighwayPlan.CORRIDOR + Math.abs(offset), HighwayPlan.MAX_NODES);
            if (!path.isEmpty()) {
                break;
            }
        }
        if (path.isEmpty()) {
            highway.failures++;
            highway.retryAt = now + RETRY * highway.failures;
            if (highway.failures >= MAX_FAILURES) {
                highway.phase = "blocked";
                colony.journal(level, "the road to " + partner.data().name + " is blocked near " + target[0] + " " + target[1]);
                Embassy.tell(level, colony, partner, Component.translatable("smartcolony.highway.blocked", partner.data().name,
                        target[0], target[1]));
            }
            colony.markDirty();
            return;
        }
        highway.failures = 0;
        for (int[] cell : HighwayPlan.orthogonal(path)) {
            Pos previous = highway.cells.getLast();
            if (previous.x() == cell[0] && previous.z() == cell[1]) {
                continue;
            }
            highway.cells.add(new Pos(cell[0], ground.height(cell[0], cell[1]), cell[1]));
        }
        highway.segment++;
        colony.markDirty();
        if (highway.segment >= waypoints.size()) {
            finish(level, highway, partner, now);
        }
    }

    private void finish(ServerLevel level, ColonyData.Highway highway, Colony partner, long now) {
        List<Pos> all = new ArrayList<>(highway.cells);
        int length = all.size();
        int middle = HighwayPlan.middle(length);
        List<Integer> marks = HighwayPlan.camps(length);
        highway.cells = new ArrayList<>(all.subList(0, middle));
        highway.length = length;
        highway.phase = "build";
        highway.since = now;
        highway.camps = new ArrayList<>();
        ColonyData.Highway theirs = new ColonyData.Highway();
        theirs.partner = colony.id();
        theirs.phase = "build";
        theirs.since = now;
        theirs.length = length;
        theirs.start = highway.goal;
        theirs.goal = highway.start;
        List<Pos> rest = new ArrayList<>(all.subList(middle, length));
        java.util.Collections.reverse(rest);
        theirs.cells = rest;
        for (int mark : marks) {
            if (mark < middle) {
                highway.camps.add(mark);
            } else {
                theirs.camps.add(length - 1 - mark);
            }
        }
        partner.data().highways.removeIf(h -> h.partner.equals(colony.id()));
        partner.data().highways.add(theirs);
        colony.construction().planRoad(level, WorldUtil.bp(highway.start).above());
        partner.construction().planRoad(level, WorldUtil.bp(theirs.start).above());
        colony.journal(level, "road to " + partner.data().name + " surveyed: " + length + " blocks, " + marks.size() + " camps");
        partner.journal(level, "road to " + data().name + " surveyed: " + length + " blocks, " + marks.size() + " camps");
        Embassy.tell(level, colony, partner, Component.translatable("smartcolony.highway.surveyed", data().name, partner.data().name,
                length, marks.size()));
        colony.markDirty();
        partner.markDirty();
    }

    private void progress(ServerLevel level, ColonyData.Highway highway, long now) {
        while (highway.campsPlanned < highway.camps.size() && highway.built + CAMP_LEAD >= highway.camps.get(highway.campsPlanned)) {
            int index = Math.min(highway.cells.size() - 1, highway.camps.get(highway.campsPlanned));
            BlockPos anchor = WorldUtil.bp(highway.cells.get(index)).above();
            if (!WorldUtil.loaded(level, anchor) || !org.lts.smartcolony.core.Blueprints.IDS.contains(CAMP_ID)) {
                highway.retryAt = now + 600;
                return;
            }
            List<int[]> keepOut = new ArrayList<>();
            for (int i = Math.max(0, index - 20); i < Math.min(highway.cells.size(), index + 20); i++) {
                Pos cell = highway.cells.get(i);
                keepOut.add(new int[] {cell.x() - 1, cell.z() - 1, cell.x() + 1, cell.z() + 1});
            }
            if (colony.construction().planRemote(level, CAMP_ID, anchor, 4, 14, keepOut)) {
                highway.campsPlanned++;
                colony.journal(level, "a roadside camp is planned at " + anchor.toShortString());
                colony.markDirty();
            } else {
                highway.campsPlanned++;
                colony.journal(level, "no room for a roadside camp near " + anchor.toShortString());
                colony.markDirty();
            }
            return;
        }
        if (highway.built >= highway.cells.size() && !highway.signed) {
            highway.signed = true;
            Colony partner = ColonyManager.byId(highway.partner);
            signpost(level, highway, partner);
            ColonyData.Highway other = partner == null ? null : partner.highways().to(colony);
            boolean both = other != null && other.built >= other.cells.size();
            colony.journal(level, "our half of the road to " + (partner == null ? highway.partner : partner.data().name) + " is finished");
            if (partner != null) {
                Embassy.tell(level, colony, partner, Component.translatable(both ? "smartcolony.highway.done" : "smartcolony.highway.half",
                        data().name, partner.data().name));
                if (both) {
                    shift(colony, partner, 6);
                }
            }
            colony.markDirty();
        }
    }

    private static void shift(Colony a, Colony b, int delta) {
        ColonyData.Relation ab = a.data().relations.get(b.id());
        ColonyData.Relation ba = b.data().relations.get(a.id());
        if (ab != null) {
            ab.standing = Diplomacy.clamp(ab.standing + delta);
        }
        if (ba != null) {
            ba.standing = Diplomacy.clamp(ba.standing + delta);
        }
    }

    private void signpost(ServerLevel level, ColonyData.Highway highway, Colony partner) {
        if (highway.cells.size() < 2 || partner == null) {
            return;
        }
        Pos first = highway.cells.getFirst();
        Pos next = highway.cells.get(Math.min(highway.cells.size() - 1, 3));
        int dx = Integer.signum(next.x() - first.x());
        int dz = Integer.signum(next.z() - first.z());
        BlockPos base = null;
        for (int side : new int[] {SIGN_OFFSET, -SIGN_OFFSET}) {
            BlockPos column = WorldUtil.surface(level, first.x() - dz * side, first.z() + dx * side);
            if (WorldUtil.isAirLike(level.getBlockState(column)) && WorldUtil.isAirLike(level.getBlockState(column.above()))
                    && level.getBlockState(column.below()).isFaceSturdy(level, column.below(), net.minecraft.core.Direction.UP)
                    && !level.getBlockState(column.below()).is(Blocks.DIRT_PATH)) {
                base = column;
                break;
            }
        }
        if (base == null) {
            return;
        }
        level.setBlock(base, Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_ALL);
        float yaw = (float) Math.toDegrees(Math.atan2(-(next.x() - first.x()), next.z() - first.z()));
        int rotation = Math.floorMod(Math.round(yaw / 22.5F) + 8, 16);
        BlockPos at = base.above();
        level.setBlock(at, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, rotation), Block.UPDATE_ALL);
        if (level.getBlockEntity(at) instanceof SignBlockEntity entity) {
            entity.setText(entity.getText(SignTextSlot.FRONT).asMutable()
                    .setLine(0, Component.translatable("smartcolony.highway.sign.to"))
                    .setLine(1, Component.literal(partner.data().name))
                    .setLine(2, Component.translatable("smartcolony.highway.sign.blocks", highway.length))
                    .asImmutable(), SignTextSlot.FRONT);
            entity.setText(entity.getText(SignTextSlot.BACK).asMutable()
                    .setLine(0, Component.translatable("smartcolony.highway.sign.to"))
                    .setLine(1, Component.literal(data().name))
                    .asImmutable(), SignTextSlot.BACK);
            entity.setWaxed(true);
        }
        data().bump("signposts", 1);
    }

    public boolean hasWork(ServerLevel level) {
        for (ColonyData.Highway highway : data().highways) {
            if ("build".equals(highway.phase) && highway.built < highway.cells.size()
                    && level.isPositionEntityTicking(WorldUtil.bp(highway.cells.get(highway.built)))) {
                return true;
            }
        }
        return false;
    }

    public BlockPos nextCell(ServerLevel level, java.util.function.Predicate<BlockPos> claimed) {
        for (ColonyData.Highway highway : data().highways) {
            if (!"build".equals(highway.phase) || highway.built >= highway.cells.size()) {
                continue;
            }
            Set<Integer> done = finished.computeIfAbsent(highway, h -> new HashSet<>());
            int end = Math.min(highway.cells.size(), highway.built + WINDOW);
            for (int i = highway.built; i < end; i++) {
                if (done.contains(i)) {
                    continue;
                }
                Pos cell = highway.cells.get(i);
                BlockPos column = new BlockPos(cell.x(), cell.y(), cell.z());
                if (!level.isPositionEntityTicking(column)) {
                    break;
                }
                BlockPos ground = new BlockPos(cell.x(), groundY(level, cell.x(), cell.z(), cell.y()), cell.z());
                if (!claimed.test(ground)) {
                    return ground;
                }
            }
        }
        return null;
    }

    private static int groundY(ServerLevel level, int x, int z, int hint) {
        for (int y = hint + 2; y >= hint - 2; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (!state.isAir() && !WorldUtil.clearable(state) && !state.is(Blocks.SNOW) || !state.getFluidState().isEmpty()) {
                return y;
            }
        }
        return hint;
    }

    public void done(BlockPos ground) {
        for (ColonyData.Highway highway : data().highways) {
            int end = Math.min(highway.cells.size(), highway.built + WINDOW);
            for (int i = highway.built; i < end; i++) {
                Pos cell = highway.cells.get(i);
                if (cell.x() == ground.getX() && cell.z() == ground.getZ()) {
                    Set<Integer> done = finished.computeIfAbsent(highway, h -> new HashSet<>());
                    done.add(i);
                    while (done.remove(highway.built)) {
                        highway.built++;
                    }
                    data().bump("highway_blocks", 1);
                    colony.markDirty();
                    return;
                }
            }
        }
    }

    public boolean build(ServerLevel level, Villager villager, BlockPos ground) {
        BlockPos feet = ground.above();
        for (BlockPos clear : new BlockPos[] {feet, feet.above()}) {
            BlockState state = level.getBlockState(clear);
            if (!state.isAir() && state.getFluidState().isEmpty() && (WorldUtil.clearable(state) || state.is(BlockTags.LEAVES)
                    || state.is(Blocks.SNOW))) {
                level.destroyBlock(clear, false, villager);
            }
        }
        BlockState state = level.getBlockState(ground);
        if (!state.getFluidState().isEmpty()) {
            String planks = planks(level);
            if (planks == null) {
                return false;
            }
            Placer.place(level, villager, ground, Block.byItem(org.lts.smartcolony.util.ItemIds.item(planks)).defaultBlockState());
            data().bump("highway_bridges", 1);
            return true;
        }
        if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.PODZOL)
                || state.is(Blocks.MYCELIUM) || state.is(Blocks.ROOTED_DIRT) || state.is(Blocks.SNOW_BLOCK)) {
            level.setBlock(ground, Blocks.DIRT_PATH.defaultBlockState(), Block.UPDATE_ALL);
            level.playSound(null, ground, SoundEvents.SHOVEL_FLATTEN.value(), SoundSource.BLOCKS, 1.0F, 1.0F);
            villager.getLookControl().setLookAt(ground.getX() + 0.5, ground.getY() + 1.0, ground.getZ() + 0.5);
            return true;
        }
        if ((state.is(BlockTags.SAND) || state.is(Blocks.GRAVEL)) && colony.warehouse().take(level, Ids.mc("cobblestone"), 1) == 1) {
            Placer.place(level, villager, ground, Blocks.COBBLESTONE.defaultBlockState());
        }
        return true;
    }

    private String planks(ServerLevel level) {
        for (String id : colony.warehouse().stock(level).view().keySet()) {
            if (Ids.isPlanks(id) && colony.warehouse().take(level, id, 1) == 1) {
                return id;
            }
        }
        if (colony.produce(level, Ids.mc("oak_planks"), 4) && colony.warehouse().take(level, Ids.mc("oak_planks"), 1) == 1) {
            return Ids.mc("oak_planks");
        }
        return null;
    }

    public boolean crosses(int minX, int minZ, int maxX, int maxZ) {
        for (Colony side : ColonyManager.all()) {
            for (ColonyData.Highway highway : side.data().highways) {
                for (Pos cell : highway.cells) {
                    if (cell.x() >= minX && cell.x() <= maxX && cell.z() >= minZ && cell.z() <= maxZ) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public List<BlockPos> road(Colony target, boolean homeward) {
        ColonyData.Highway mine = to(target);
        ColonyData.Highway theirs = target.highways().to(colony);
        if (mine == null || theirs == null || !"build".equals(mine.phase) || !"build".equals(theirs.phase)) {
            return null;
        }
        int total = mine.cells.size() + theirs.cells.size();
        if (total == 0 || (mine.built + theirs.built) * 10 < total * 8) {
            return null;
        }
        List<BlockPos> path = new ArrayList<>();
        for (Pos cell : mine.cells) {
            path.add(WorldUtil.bp(cell));
        }
        for (int i = theirs.cells.size() - 1; i >= 0; i--) {
            path.add(WorldUtil.bp(theirs.cells.get(i)));
        }
        if (homeward) {
            java.util.Collections.reverse(path);
        }
        return path;
    }

    public static BlockPos campNear(ServerLevel level, BlockPos at, double reach) {
        BlockPos best = null;
        double bestDistance = reach * reach;
        for (Colony colony : ColonyManager.all()) {
            for (ColonyData.Building building : colony.data().buildings) {
                if (!CAMP_KIND.equals(building.kind) || building.door == null) {
                    continue;
                }
                BlockPos door = WorldUtil.bp(building.door);
                double distance = door.distSqr(at);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = new BlockPos((building.min.x() + building.max.x()) / 2, building.door.y(), (building.min.z() + building.max.z()) / 2);
                }
            }
        }
        return best;
    }

    public String summary(Colony other) {
        ColonyData.Highway mine = to(other);
        ColonyData.Highway theirs = other.highways().to(colony);
        if (mine == null) {
            return "";
        }
        if (!"build".equals(mine.phase)) {
            return mine.phase + "|" + mine.segment + "|0|0|0";
        }
        int built = mine.built + (theirs == null ? 0 : theirs.built);
        int total = mine.cells.size() + (theirs == null ? 0 : theirs.cells.size());
        int camps = mine.camps.size() + (theirs == null ? 0 : theirs.camps.size());
        long campsBuilt = 0;
        for (Colony side : List.of(colony, other)) {
            campsBuilt += side.data().buildings.stream().filter(b -> CAMP_KIND.equals(b.kind)).count();
        }
        return "build|" + built + "|" + total + "|" + Math.min(camps, campsBuilt) + "|" + camps;
    }

    public JsonObject debug() {
        JsonObject json = new JsonObject();
        JsonArray list = new JsonArray();
        for (ColonyData.Highway highway : data().highways) {
            JsonObject entry = new JsonObject();
            entry.addProperty("partner", highway.partner);
            entry.addProperty("phase", highway.phase);
            entry.addProperty("cells", highway.cells.size());
            entry.addProperty("built", highway.built);
            entry.addProperty("segment", highway.segment);
            entry.addProperty("camps", highway.camps.toString());
            list.add(entry);
        }
        json.add("highways", list);
        return json;
    }
}
