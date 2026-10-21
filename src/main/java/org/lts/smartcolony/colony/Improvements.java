package org.lts.smartcolony.colony;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.Ids;
import org.lts.smartcolony.core.Pos;
import org.lts.smartcolony.core.Tech;
import org.lts.smartcolony.core.WallPlanner;
import org.lts.smartcolony.util.WorldUtil;

public final class Improvements {
    public static final String WALL = "wall";
    public static final String DECOR = "decor";
    public static final String REPAIR = "repair";
    public static final String SALVAGE = "salvage";
    public static final String SUPPORT = "support";
    public static final String CLEARING = "clearing";
    public static final String FURNISH = "furnish";
    private static final int SUPPORT_GAP = 4;
    private static final int WALL_SUPPORT_GAP = 3;
    private static final int SECTORS = 36;
    private static final int BORDER_GAP = 5;
    private static final int WALL_HEIGHT = 3;
    private static final int GATE_SPACING = 28;
    private static final int TOWER_SPACING = 40;
    private static final int POST_SPACING = 8;
    private static final int TOWER_HEIGHT = 5;
    private static final long WALL_AFTER = 24000L * 2;
    private static final int WALL_VERSION = 8;
    private static final int MAX_RAISE = 4;
    private static final int SLOPE_CAP = WALL_HEIGHT + MAX_RAISE + 1;
    private static final int MAX_TOP_STEP = 2;
    private static final int SEARCH_WINDOW = 10;
    private static final int GATE_TOWER_HEIGHT = 4;
    public static final int SHED_LENGTH = 4;
    public static final int SHED_WIDTH = 3;
    public static final int QUARRY_DECOR_VERSION = 3;

    private final Colony colony;
    private long blockedUntil;
    private String waitingFor = "";
    private Set<Long> wallColumns;

    private final Renovation renovation;

    Improvements(Colony colony) {
        this.colony = colony;
        this.renovation = new Renovation(colony);
    }

    private ColonyData data() {
        return colony.data();
    }

    public String waitingFor() {
        return waitingFor;
    }

    public boolean hasWork(ServerLevel level) {
        return !data().orders.isEmpty() && level.getGameTime() >= blockedUntil;
    }

    public void queueFirst(BlockPos pos, String state, String tag) {
        data().orders.addFirst(new ColonyData.Order(WorldUtil.pos(pos), state, tag));
        blockedUntil = 0;
        colony.markDirty();
    }

    public boolean isWall(Pos pos) {
        if (data().wall == null) {
            return false;
        }
        if (wallColumns == null) {
            wallColumns = new HashSet<>();
            for (Pos column : data().wall.columns) {
                wallColumns.add(BlockPos.asLong(column.x(), 0, column.z()));
            }
        }
        return wallColumns.contains(BlockPos.asLong(pos.x(), 0, pos.z()));
    }

    public void tick(ServerLevel level) {
        migrateWall(level);
        migratePen(level);
        if (!salvageSorted) {
            salvageSorted = true;
            sortSalvage();
        }
        if (data().surveyVersion < 2) {
            data().villageSurveyed = true;
            data().surveyVersion = 2;
            surveyVillage(level);
            colony.markDirty();
        }
        if (data().walkwayVersion < WALKWAY_VERSION) {
            data().walkwayVersion = WALKWAY_VERSION;
            openWalkways(level);
            colony.markDirty();
        }
        if (data().surveyVersion < 3) {
            data().surveyVersion = 3;
            for (ColonyData.Building building : data().buildings) {
                furnish(level, building);
            }
            colony.journal(level, "tidying the entrances of " + data().buildings.size() + " buildings");
            colony.markDirty();
        }
        for (ColonyData.Mine mine : colony.allMines()) {
            if (mine.quarry() && mine.decorVersion < QUARRY_DECOR_VERSION && decorateQuarry(level, mine)) {
                colony.markDirty();
            }
        }
        if (data().wall == null && level.getGameTime() - lastWallAttempt >= 200 && wallWanted(level)) {
            lastWallAttempt = level.getGameTime();
            planWall(level);
        }
        followGrowth(level);
        if (level.getGameTime() % 600 < 100) {
            inspect(level);
        }
        if (level.getGameTime() % 1200 < 100) {
            furnishingFirst();
        }
        if (level.getGameTime() % 1200 < 100 && data().orders.removeIf(order -> DECOR.equals(order.tag) && Ids.isSapling(order.state)
                && !colony.treeAllowed(level, WorldUtil.bp(order.pos)))) {
            colony.markDirty();
        }
        if (level.getGameTime() - data().founded > 24000 && level.getGameTime() - lastDecor > 48000) {
            lastDecor = level.getGameTime();
            decorate(level);
        }
        renovation.tick(level);
        ColonyData.Wall wall = data().wall;
        if (wall != null && !wall.announced && wall.total > 0 && wall.built >= wall.total) {
            wall.announced = true;
            colony.journal(level, "the wall around the village is finished");
            colony.notify(level, Component.translatable("smartcolony.chat.wall_done"));
            colony.markDirty();
        }
    }

    private long lastDecor = -1000000;
    private boolean salvageSorted;

    private void sortSalvage() {
        List<ColonyData.Order> orders = data().orders;
        List<ColonyData.Order> salvage = new ArrayList<>();
        for (ColonyData.Order order : orders) {
            if (SALVAGE.equals(order.tag)) {
                salvage.add(order);
            }
        }
        if (salvage.size() < 2) {
            return;
        }
        BlockPos center = colony.center();
        salvage.sort(java.util.Comparator.comparingDouble(order -> Math.atan2(order.pos.z() - center.getZ(), order.pos.x() - center.getX())));
        int k = 0;
        for (int i = 0; i < orders.size(); i++) {
            if (SALVAGE.equals(orders.get(i).tag)) {
                orders.set(i, salvage.get(k++));
            }
        }
    }
    private long lastWallAttempt = -1000000;
    private long lastGrowthCheck = -1000000;
    private int growthFootprint = -1;
    private final Set<Long> stubbornCorners = new HashSet<>();

    private void followGrowth(ServerLevel level) {
        ColonyData.Wall wall = data().wall;
        long now = level.getGameTime();
        if (wall == null || wall.columns.size() < 3 || data().wallVersion < WALL_VERSION || now - lastGrowthCheck < 1200) {
            return;
        }
        lastGrowthCheck = now;
        int footprint = data().buildings.size() * 31 + data().projects.size();
        if (footprint == growthFootprint) {
            return;
        }
        int foreign = 0;
        for (Pos column : wall.columns) {
            if (!colony.ownsGround(column.x(), column.z())) {
                foreign++;
            }
        }
        boolean trespass = foreign * 20 > wall.columns.size();
        Pos outside = trespass ? null : beyondWall(wall);
        if (!trespass && outside == null) {
            growthFootprint = footprint;
            return;
        }
        String material = wall.material.equals("stone") ? "stone" : "wood";
        if (replanWall(level, material, !trespass)) {
            growthFootprint = footprint;
            lastWallAttempt = now;
            if (outside != null && data().wall != null && !enclosed(data().wall, outside)) {
                stubbornCorners.add(BlockPos.asLong(outside.x(), 0, outside.z()));
            }
            colony.journal(level, trespass ? "the wall stood on " + foreign + " columns of a neighbour's land, so it was drawn back"
                    : "the village grew past its wall near " + outside.x() + " " + outside.z() + ", so the wall moves out");
        }
    }

    private boolean enclosed(ColonyData.Wall wall, Pos corner) {
        int[] xs = new int[wall.columns.size()];
        int[] zs = new int[wall.columns.size()];
        for (int i = 0; i < xs.length; i++) {
            xs[i] = wall.columns.get(i).x();
            zs[i] = wall.columns.get(i).z();
        }
        return WallPlanner.encloses(xs, zs, corner.x() + 0.5, corner.z() + 0.5);
    }

    private boolean wallCanReach(Pos corner) {
        BlockPos center = colony.center();
        double dx = corner.x() - center.getX();
        double dz = corner.z() - center.getZ();
        double distance = Math.hypot(dx, dz);
        if (distance + 5 > org.lts.smartcolony.SmartColonyConfig.get().maxColonyRadius + 24) {
            return false;
        }
        if (distance < 1) {
            return true;
        }
        double reach = distance + 5 + BORDER_GAP;
        return colony.ownsGround(center.getX() + (int) Math.round(dx / distance * reach), center.getZ() + (int) Math.round(dz / distance * reach));
    }

    private Pos beyondWall(ColonyData.Wall wall) {
        int[] xs = new int[wall.columns.size()];
        int[] zs = new int[wall.columns.size()];
        for (int i = 0; i < xs.length; i++) {
            xs[i] = wall.columns.get(i).x();
            zs[i] = wall.columns.get(i).z();
        }
        List<ColonyData.Building> footprints = new ArrayList<>(data().buildings);
        for (ColonyData.Project project : data().projects) {
            footprints.add(project.building);
        }
        for (ColonyData.Building building : footprints) {
            if (Highways.remote(building)) {
                continue;
            }
            for (Pos corner : new Pos[] {building.min, building.max, new Pos(building.min.x(), building.min.y(), building.max.z()),
                    new Pos(building.max.x(), building.min.y(), building.min.z())}) {
                if (!WallPlanner.encloses(xs, zs, corner.x() + 0.5, corner.z() + 0.5)
                        && !stubbornCorners.contains(BlockPos.asLong(corner.x(), 0, corner.z())) && wallCanReach(corner)) {
                    return corner;
                }
            }
        }
        return null;
    }

    private void decorate(ServerLevel level) {
        int added = 0;
        BlockPos bell = colony.center();
        String wood = colony.construction().preferredWood(level);
        for (Direction away : Direction.Plane.HORIZONTAL) {
            BlockPos seat = WorldUtil.surface(level, bell.getX() + away.getStepX() * 4, bell.getZ() + away.getStepZ() * 4);
            Direction along = away.getClockWise();
            for (BlockPos pos : new BlockPos[] {seat, seat.relative(along)}) {
                if (decorSpot(level, pos)) {
                    queue(pos, Ids.mc(wood + "_stairs") + "[facing=" + away.getSerializedName() + ",half=bottom]");
                    added++;
                }
            }
        }
        List<Pos> roads = data().roads;
        int trees = 0;
        for (int i = 3; i + 1 < roads.size() && trees < 12; i += 7) {
            Pos a = roads.get(i);
            Pos b = roads.get(i + 1);
            int dx = b.x() - a.x();
            int dz = b.z() - a.z();
            if (Math.abs(dx) + Math.abs(dz) != 1) {
                continue;
            }
            BlockPos spot = WorldUtil.surface(level, a.x() - dz * 4, a.z() + dx * 4);
            if (!decorSpot(level, spot) || !level.getBlockState(spot.below()).is(net.minecraft.tags.BlockTags.SUBSTRATE_OVERWORLD)
                    || !WorldUtil.isAirLike(level.getBlockState(spot.above())) || nearLog(level, spot) || !colony.treeAllowed(level, spot)) {
                continue;
            }
            String sapling = saplingInStock(level, wood);
            if (sapling == null) {
                break;
            }
            queue(spot, sapling);
            trees++;
            added++;
        }
        if (added > 0) {
            colony.journal(level, "decorating the village: " + added + " benches and avenue trees");
            colony.markDirty();
        }
    }

    private boolean decorateQuarry(ServerLevel level, ColonyData.Mine mine) {
        org.lts.smartcolony.core.QuarryPlan plan = (org.lts.smartcolony.core.QuarryPlan) mine.plan();
        int cx = mine.corner.x();
        int cz = mine.corner.z();
        int w = mine.width;
        int minX = cx - SHED_LENGTH - 3;
        int maxX = cx + w + 3;
        int minZ = cz - SHED_WIDTH - 3;
        int maxZ = cz + w + SHED_WIDTH + 2;
        for (int x : new int[] {minX, maxX}) {
            for (int z : new int[] {minZ, maxZ}) {
                if (!WorldUtil.loaded(level, new BlockPos(x, 0, z))) {
                    return false;
                }
            }
        }
        int before = data().orders.size();
        data().orders.removeIf(order -> DECOR.equals(order.tag) && order.pos.x() >= minX && order.pos.x() <= maxX
                && order.pos.z() >= minZ && order.pos.z() <= maxZ);
        int cancelled = before - data().orders.size();
        for (Pos open : new Pos[] {plan.entrance(), plan.ladderExit()}) {
            BlockPos base = new BlockPos(open.x(), ringGround(level, open.x(), open.z()) + 1, open.z());
            if (wallMaterial(level.getBlockState(base))) {
                data().orders.addFirst(new ColonyData.Order(WorldUtil.pos(base), "minecraft:air", SALVAGE));
            }
        }
        if (mine.shed == null) {
            mine.shed = findShed(level, mine);
        }
        mine.headDone = false;
        colony.journal(level, "quarry head replanned for the miners: " + cancelled + " old rim orders dropped"
                + (mine.shed != null ? ", shed site found" : ", no flat ground for a shed"));
        if (!mine.decorated) {
            tidyQuarry(level, mine);
        }
        mine.decorated = true;
        mine.decorVersion = QUARRY_DECOR_VERSION;
        return true;
    }
    private static int ringGround(ServerLevel level, int x, int z) {
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        int floor = y - 10;
        while (y > floor) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (!(WorldUtil.removable(level, pos) || wallMaterial(state) || state.is(Blocks.STONE_BRICKS) || state.is(Blocks.LADDER))) {
                break;
            }
            y--;
        }
        return y;
    }

    private Pos findShed(ServerLevel level, ColonyData.Mine mine) {
        int minX = mine.corner.x() - SHED_LENGTH - 2;
        for (int minZ : new int[] {mine.corner.z() - SHED_WIDTH - 1, mine.corner.z() + 2}) {
            int low = Integer.MAX_VALUE;
            int high = Integer.MIN_VALUE;
            boolean fits = true;
            for (int i = 0; i < SHED_LENGTH && fits; i++) {
                for (int j = 0; j < SHED_WIDTH && fits; j++) {
                    int y = ringGround(level, minX + i, minZ + j);
                    BlockPos floor = new BlockPos(minX + i, y, minZ + j);
                    fits = WorldUtil.loaded(level, floor) && level.getFluidState(floor).isEmpty() && level.getFluidState(floor.above()).isEmpty()
                            && WorldUtil.solidFloor(level, floor);
                    low = Math.min(low, y);
                    high = Math.max(high, y);
                }
            }
            if (fits && high - low <= 2) {
                return new Pos(minX, high, minZ);
            }
        }
        return null;
    }
    public boolean quarryRim(BlockPos pos) {
        for (ColonyData.Mine mine : colony.allMines()) {
            if (!mine.quarry()) {
                continue;
            }
            int cx = mine.corner.x();
            int cz = mine.corner.z();
            int w = mine.width;
            if (pos.getX() >= cx - 1 && pos.getX() <= cx + w && pos.getZ() >= cz - 1 && pos.getZ() <= cz + w
                    && (pos.getX() == cx - 1 || pos.getX() == cx + w || pos.getZ() == cz - 1 || pos.getZ() == cz + w)) {
                return true;
            }
        }
        return false;
    }

    private void tidyQuarry(ServerLevel level, ColonyData.Mine mine) {
        org.lts.smartcolony.core.QuarryPlan quarry = (org.lts.smartcolony.core.QuarryPlan) mine.plan();
        int dug = Math.min(quarry.layers(), mine.progress / quarry.perLayer());
        int cx = quarry.corner().x();
        int cz = quarry.corner().z();
        int w = quarry.width();
        int removed = 0;
        for (int y = quarry.layerY(dug) - 1; y <= quarry.corner().y() + 3; y++) {
            for (int x = cx; x < cx + w; x++) {
                for (int z = cz; z < cz + w; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH)) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
                        removed++;
                    }
                }
            }
        }
        int mid = w / 2;
        int[][] spots = {{cx + mid, cz, 0, -1}, {cx + mid, cz + w - 1, 0, 1}, {cx, cz + mid, -1, 0}, {cx + w - 1, cz + mid, 1, 0}};
        int placed = 0;
        for (int layer = 4; layer < dug; layer += 4) {
            int y = quarry.layerY(layer) + 1;
            for (int[] spot : spots) {
                BlockPos at = new BlockPos(spot[0], y, spot[1]);
                BlockPos wall = at.offset(spot[2], 0, spot[3]);
                Direction facing = Direction.getApproximateNearest(-spot[2], 0, -spot[3]);
                BlockState torch = Blocks.WALL_TORCH.defaultBlockState().setValue(net.minecraft.world.level.block.WallTorchBlock.FACING, facing);
                if (!WorldUtil.isAirLike(level.getBlockState(at)) || !level.getBlockState(wall).isFaceSturdy(level, wall, facing)
                        || !torch.canSurvive(level, at)) {
                    continue;
                }
                if (removed > placed || colony.warehouse().take(level, Ids.mc("torch"), 1) == 1) {
                    level.setBlock(at, torch, net.minecraft.world.level.block.Block.UPDATE_ALL);
                    placed++;
                }
            }
        }
        if (removed > placed) {
            colony.warehouse().give(level, Ids.mc("torch"), removed - placed);
        }
        colony.journal(level, "tidied the quarry lights: " + removed + " stray torches replaced by " + placed + " wall lamps");
    }

    private boolean decorSpot(ServerLevel level, BlockPos pos) {
        if (!WorldUtil.loaded(level, pos) || !WorldUtil.isAirLike(level.getBlockState(pos)) || !level.getFluidState(pos).isEmpty()) {
            return false;
        }
        if (!WorldUtil.solidFloor(level, pos.below()) || colony.sites().onRoad(pos.below()) || level.getBlockState(pos.below()).is(Blocks.DIRT_PATH)) {
            return false;
        }
        return !colony.isProtected(pos) && data().orders.stream().noneMatch(order -> order.pos.equals(WorldUtil.pos(pos)));
    }

    private static boolean nearLog(ServerLevel level, BlockPos pos) {
        for (BlockPos check : BlockPos.betweenClosed(pos.offset(-3, 0, -3), pos.offset(3, 3, 3))) {
            BlockState state = level.getBlockState(check);
            if (state.is(net.minecraft.tags.BlockTags.LOGS) || state.is(net.minecraft.tags.BlockTags.SAPLINGS)) {
                return true;
            }
        }
        return false;
    }

    private String saplingInStock(ServerLevel level, String wood) {
        String preferred = Ids.mc(wood + "_sapling");
        if (colony.stockOf(level, id -> id.equals(preferred)) > 0) {
            return preferred;
        }
        for (String id : colony.warehouse().stock(level).sorted().keySet()) {
            if (id.endsWith("_sapling") && !id.contains("dark_oak")) {
                return id;
            }
        }
        return null;
    }

    private void queue(BlockPos pos, String state) {
        data().orders.add(new ColonyData.Order(WorldUtil.pos(pos), state, DECOR));
    }

    public boolean queueForgeUpgrade(ServerLevel level, ColonyData.Building forge) {
        net.minecraft.world.level.block.Rotation rotation = net.minecraft.world.level.block.Rotation.values()[Math.floorMod(forge.rotation, 4)];
        String facing = rotation.rotate(Direction.EAST).getSerializedName();
        BlockPos anvil = colony.construction().world(forge, Tech.FORGE_ANVIL[0], Tech.FORGE_ANVIL[1], Tech.FORGE_ANVIL[2]);
        BlockPos table = colony.construction().world(forge, Tech.FORGE_SMITHING_TABLE[0], Tech.FORGE_SMITHING_TABLE[1], Tech.FORGE_SMITHING_TABLE[2]);
        if (!WorldUtil.loaded(level, anvil) || !WorldUtil.loaded(level, table)) {
            return false;
        }
        queueOnce(anvil, "minecraft:anvil[facing=" + facing + "]");
        queueOnce(table, "minecraft:smithing_table");
        colony.markDirty();
        return true;
    }

    private void queueAttached(BlockPos pos, String state) {
        Pos at = WorldUtil.pos(pos);
        if (data().orders.stream().noneMatch(order -> order.pos.equals(at))) {
            ColonyData.Order order = new ColonyData.Order(at, state, DECOR);
            order.attached = true;
            data().orders.add(order);
        }
    }

    private void queueOnce(BlockPos pos, String state) {
        Pos at = WorldUtil.pos(pos);
        if (data().orders.stream().noneMatch(order -> order.pos.equals(at))) {
            queue(pos, state);
        }
    }

    private int inspectBuilding;
    private int inspectWall;

    private static final int WALKWAY_VERSION = 1;
    private static final java.util.Set<String> WALKWAY_FIXED = java.util.Set.of("townhall_3", "townhall_4", "townhall_5");

    private void openWalkways(ServerLevel level) {
        int queued = 0;
        for (ColonyData.Building building : data().buildings) {
            if (!WALKWAY_FIXED.contains(building.blueprint)) {
                continue;
            }
            org.lts.smartcolony.core.Blueprint current = org.lts.smartcolony.core.Blueprints.get(building.blueprint);
            BlockPos door = colony.construction().world(building, current.doorX(), 1, current.doorZ());
            if (!WorldUtil.loaded(level, door) || !(level.getBlockState(door).getBlock() instanceof net.minecraft.world.level.block.DoorBlock)) {
                continue;
            }
            for (int[] cell : org.lts.smartcolony.core.Blueprints.get(building.blueprint).airCells()) {
                if (cell[1] < 1 || cell[1] > 2) {
                    continue;
                }
                BlockPos pos = colony.construction().world(building, cell[0], cell[1], cell[2]);
                if (!WorldUtil.loaded(level, pos)) {
                    continue;
                }
                BlockState state = level.getBlockState(pos);
                if (state.is(net.minecraft.tags.BlockTags.FENCES) || state.is(net.minecraft.tags.BlockTags.STAIRS)
                        || state.is(net.minecraft.tags.BlockTags.TRAPDOORS)) {
                    queueFirst(pos, "minecraft:air", SALVAGE);
                    queued++;
                }
            }
        }
        if (queued > 0) {
            colony.journal(level, "opening the passage to the back room of the town hall: " + queued + " blocks");
        }
    }

    public void inspectNow(ServerLevel level) {
        for (int i = 0; i < Math.max(1, data().buildings.size()); i++) {
            inspect(level);
        }
    }

    private void furnishingFirst() {
        List<ColonyData.Order> orders = data().orders;
        List<ColonyData.Order> furnish = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            if (FURNISH.equals(orders.get(i).tag)) {
                if (furnish.size() == i) {
                    furnish.add(orders.get(i));
                    continue;
                }
                furnish.clear();
                orders.stream().filter(order -> FURNISH.equals(order.tag)).forEach(furnish::add);
                orders.removeIf(order -> FURNISH.equals(order.tag));
                orders.addAll(0, furnish);
                colony.markDirty();
                return;
            }
        }
    }

    private void inspect(ServerLevel level) {
        long repairs = data().orders.stream().filter(order -> REPAIR.equals(order.tag)).count();
        if (repairs >= 64) {
            return;
        }
        List<ColonyData.Order> found = new ArrayList<>();
        List<ColonyData.Building> buildings = data().buildings;
        if (!buildings.isEmpty()) {
            ColonyData.Building building = buildings.get(Math.floorMod(inspectBuilding++, buildings.size()));
            boolean oldHall = colony.construction().innPending() && org.lts.smartcolony.core.TownHall.stageOf(building.blueprint) > 0;
            for (org.lts.smartcolony.core.Blueprint.Cell cell : colony.construction().upgrading(building) || oldHall
                    ? List.<org.lts.smartcolony.core.Blueprint.Cell>of() : colony.construction().cells(building, building.swaps)) {
                BlockPos pos = colony.construction().world(building, cell.x(), cell.y(), cell.z());
                if (!WorldUtil.loaded(level, pos) || !missing(level, pos)) {
                    continue;
                }
                BlockState expected = Construction.parse(cell.state(), building.rotation);
                if (expected.isAir() || expected.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF)
                        || expected.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.BED_PART)) {
                    continue;
                }
                found.add(new ColonyData.Order(WorldUtil.pos(pos), BlockStateParser.serialize(expected), REPAIR));
            }
        }
        if (!buildings.isEmpty()) {
            found.addAll(floorRepairs(level, buildings.get(Math.floorMod(inspectBuilding - 1, buildings.size()))));
        }
        ColonyData.Wall wall = data().wall;
        boolean wallPending = data().orders.stream().anyMatch(order -> WALL.equals(order.tag));
        if (wall != null && !wallPending && !wall.columns.isEmpty()) {
            for (int i = 0; i < 40; i++) {
                int columnIndex = Math.floorMod(inspectWall++, wall.columns.size());
                Pos column = wall.columns.get(columnIndex);
                boolean gate = wall.gates.contains(column);
                int top = gate ? 1 : height(wall, columnIndex);
                for (int h = 1; h <= top; h++) {
                    BlockPos pos = new BlockPos(column.x(), column.y() + h, column.z());
                    if (!WorldUtil.loaded(level, pos) || !missing(level, pos)) {
                        continue;
                    }
                    String state;
                    if (gate) {
                        state = doorState(wall.material.equals("stone") ? "oak" : wall.material, gateFacing(wall.columns, columnIndex));
                    } else {
                        state = wallState(wall.material, h, top, columnIndex % POST_SPACING == 0);
                    }
                    found.add(new ColonyData.Order(WorldUtil.pos(pos), state, REPAIR));
                }
            }
        }
        if (found.isEmpty()) {
            return;
        }
        int added = 0;
        for (ColonyData.Order order : found) {
            if (data().orders.stream().noneMatch(existing -> existing.pos.equals(order.pos))) {
                data().orders.addFirst(order);
                added++;
            }
        }
        if (added == 0) {
            return;
        }
        blockedUntil = 0;
        colony.journal(level, "found " + added + " damaged blocks to repair");
        colony.data().bump("repairs_planned", found.size());
        colony.markDirty();
    }

    private List<ColonyData.Order> floorRepairs(ServerLevel level, ColonyData.Building building) {
        List<ColonyData.Order> found = new ArrayList<>();
        org.lts.smartcolony.core.Blueprint blueprint = org.lts.smartcolony.core.Blueprints.get(building.blueprint);
        for (int x = 0; x < blueprint.sizeX(); x++) {
            for (int z = 0; z < blueprint.sizeZ(); z++) {
                char symbol = blueprint.at(x, 0, z);
                if (symbol == org.lts.smartcolony.core.Blueprint.AIR || symbol == org.lts.smartcolony.core.Blueprint.ANY) {
                    continue;
                }
                BlockPos floor = colony.construction().world(building, x, 0, z);
                if (!WorldUtil.loaded(level, floor) || !WorldUtil.solidFloor(level, floor)) {
                    continue;
                }
                int gap = org.lts.smartcolony.core.Support.gap(depth -> cell(level, floor.below(depth)), SUPPORT_GAP);
                for (int depth = Math.max(gap, 0); depth >= 1; depth--) {
                    found.add(new ColonyData.Order(WorldUtil.pos(floor.below(depth)), "minecraft:cobblestone", REPAIR));
                }
            }
        }
        return found;
    }

    private static boolean missing(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.isAir() || state.is(net.minecraft.tags.BlockTags.FIRE) || (!state.getFluidState().isEmpty() && state.getFluidState().isSource() == false && state.canBeReplaced());
    }

    private boolean wallWanted(ServerLevel level) {
        long age = level.getGameTime() - data().founded;
        if (age < 24000 || age < WALL_AFTER && colony.defense().threatLevel() < 0.8) {
            return false;
        }
        return colony.adultCount(level) >= 5;
    }

    private void surveyVillage(ServerLevel level) {
        BlockPos center = colony.center();
        int radius = data().radius + 8;
        int doors = 0;
        int lights = 0;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -10, -radius), center.offset(radius, 10, radius))) {
            if (doors >= 16) {
                break;
            }
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof DoorBlock) || state.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
                continue;
            }
            Direction facing = state.getValue(DoorBlock.FACING);
            BlockPos outside = null;
            for (Direction side : new Direction[] {facing, facing.getOpposite()}) {
                BlockPos candidate = pos.relative(side);
                if (WorldUtil.standable(level, candidate)
                        && (level.canSeeSky(candidate) || level.canSeeSky(candidate.relative(side)) || level.canSeeSky(candidate.relative(side, 2)))) {
                    outside = candidate.immutable();
                    break;
                }
            }
            if (outside == null) {
                continue;
            }
            doors++;
            colony.construction().planRoad(level, outside);
            BlockPos lamp = outside.relative(facing.getClockWise());
            if (WorldUtil.isAirLike(level.getBlockState(lamp)) && WorldUtil.standable(level, lamp)
                    && !data().pendingTorches.contains(WorldUtil.pos(lamp))) {
                data().pendingTorches.add(WorldUtil.pos(lamp));
                lights++;
            }
        }
        colony.journal(level, "surveyed the village: paths to " + doors + " doors, " + lights + " door lamps");
    }

    private String forcedMaterial;

    public boolean replanWall(ServerLevel level, String material) {
        return replanWall(level, material, false);
    }

    private boolean replanWall(ServerLevel level, String material, boolean keepOld) {
        List<Pos> old = data().wall == null ? List.of() : new ArrayList<>(data().wall.columns);
        List<int[]> ring = outline(level, keepOld ? old : null);
        if (!loaded(level, ring, old)) {
            return false;
        }
        previousGround = new HashMap<>();
        int towers = 0;
        for (Pos column : old) {
            previousGround.put(BlockPos.asLong(column.x(), 0, column.z()), column.y());
        }
        for (Pos column : old) {
            if (!oldTower(level, column)) {
                continue;
            }
            towers++;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int x = column.x() + dx;
                    int z = column.z() + dz;
                    previousGround.putIfAbsent(BlockPos.asLong(x, 0, z), groundBelowWall(level, x, column.y() + TOWER_HEIGHT + 2, z));
                }
            }
        }
        data().orders.removeIf(order -> WALL.equals(order.tag));
        data().wall = null;
        wallColumns = null;
        forcedMaterial = material;
        planWall(level, ring);
        forcedMaterial = null;
        Set<Long> planned = new HashSet<>();
        for (ColonyData.Order order : data().orders) {
            if (WALL.equals(order.tag)) {
                planned.add(BlockPos.asLong(order.pos.x(), order.pos.y(), order.pos.z()));
            }
        }
        data().orders.removeIf(order -> SALVAGE.equals(order.tag) && planned.contains(BlockPos.asLong(order.pos.x(), order.pos.y(), order.pos.z())));
        List<ColonyData.Order> salvageOrders = new ArrayList<>();
        for (java.util.Map.Entry<Long, Integer> entry : previousGround.entrySet()) {
            int x = BlockPos.getX(entry.getKey());
            int z = BlockPos.getZ(entry.getKey());
            int ground = entry.getValue();
            for (int y = ground + TOWER_HEIGHT + 4; y > ground; y--) {
                BlockPos pos = new BlockPos(x, y, z);
                if (!planned.contains(pos.asLong()) && wallMaterial(level.getBlockState(pos)) && !insideBuilding(pos)) {
                    salvageOrders.add(new ColonyData.Order(WorldUtil.pos(pos), "minecraft:air", SALVAGE));
                }
            }
        }
        previousGround = null;
        if (!salvageOrders.isEmpty()) {
            data().orders.addAll(0, salvageOrders);
            sortSalvage();
            colony.journal(level, "taking down " + salvageOrders.size() + " blocks of the old wall line and " + towers + " old towers");
        }
        colony.markDirty();
        return true;
    }

    private java.util.Map<Long, Integer> previousGround;

    private boolean insideBuilding(BlockPos pos) {
        Pos p = WorldUtil.pos(pos);
        for (ColonyData.Building building : data().buildings) {
            if (building.contains(p, 0)) {
                return true;
            }
        }
        return data().projects.stream().anyMatch(project -> project.building.contains(p, 0));
    }

    private boolean oldTower(ServerLevel level, Pos column) {
        int deck = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (wallMaterial(level.getBlockState(new BlockPos(column.x() + dx, column.y() + TOWER_HEIGHT, column.z() + dz)))) {
                    deck++;
                }
            }
        }
        return deck >= 5;
    }

    private static int groundBelowWall(ServerLevel level, int x, int top, int z) {
        int y = top;
        while (y > top - TOWER_HEIGHT - 8) {
            BlockState state = level.getBlockState(new BlockPos(x, y, z));
            if (!state.isAir() && !wallMaterial(state) && !WorldUtil.removable(level, new BlockPos(x, y, z))) {
                return y;
            }
            y--;
        }
        return groundY(level, x, z);
    }

    private int wallGround(ServerLevel level, int x, int z) {
        if (previousGround != null) {
            Integer y = previousGround.get(BlockPos.asLong(x, 0, z));
            if (y != null) {
                return y;
            }
        }
        return groundY(level, x, z);
    }

    public static boolean wallMaterial(BlockState state) {
        return state.is(net.minecraft.tags.BlockTags.LOGS) || state.is(net.minecraft.tags.BlockTags.PLANKS)
                || state.is(net.minecraft.tags.BlockTags.WOODEN_FENCES) || state.is(net.minecraft.tags.BlockTags.FENCE_GATES)
                || state.is(Blocks.COBBLESTONE) || state.is(Blocks.COBBLESTONE_WALL) || state.is(Blocks.TORCH) || state.is(Blocks.LANTERN)
                || state.is(Blocks.STONE_BRICK_WALL) || state.is(net.minecraft.tags.BlockTags.WOODEN_TRAPDOORS)
                || state.is(net.minecraft.tags.BlockTags.WOODEN_SLABS);
    }

    private boolean occupiedByOther(ColonyData.Building building, BlockPos cell) {
        Pos pos = WorldUtil.pos(cell);
        for (ColonyData.Building other : data().buildings) {
            if (other != building && other.contains(pos, 0)) {
                return true;
            }
        }
        return isWall(pos);
    }

    public void furnish(ServerLevel level, ColonyData.Building building) {
        BlockPos door = WorldUtil.bp(building.door);
        int midX = (building.min.x() + building.max.x()) / 2;
        int midZ = (building.min.z() + building.max.z()) / 2;
        int ox = door.getX() - midX;
        int oz = door.getZ() - midZ;
        Direction out = Math.abs(ox) >= Math.abs(oz) ? (ox >= 0 ? Direction.EAST : Direction.WEST) : (oz >= 0 ? Direction.SOUTH : Direction.NORTH);
        Direction side = out.getClockWise();
        int floor = door.getY() - 1;
        for (int k = 0; k <= 2; k++) {
            for (int j = -1; j <= 1; j++) {
                BlockPos cell = door.relative(out, k).relative(side, j);
                if (!WorldUtil.loaded(level, cell) || colony.warehouse().contains(cell) || occupiedByOther(building, cell)) {
                    continue;
                }
                for (int y = floor + 3; y >= floor + 1; y--) {
                    BlockPos pos = new BlockPos(cell.getX(), y, cell.getZ());
                    BlockState state = level.getBlockState(pos);
                    if (!state.isAir() && (WorldUtil.naturalGround(state) || WorldUtil.removable(level, pos))) {
                        queueOnce(pos, "minecraft:air");
                    }
                }
                int depth = 0;
                while (depth < 4 && WorldUtil.isAirLike(level.getBlockState(new BlockPos(cell.getX(), floor - depth, cell.getZ())))
                        && level.getFluidState(new BlockPos(cell.getX(), floor - depth, cell.getZ())).isEmpty()) {
                    depth++;
                }
                if (depth == 4) {
                    continue;
                }
                for (int d = depth - 1; d >= 0; d--) {
                    queueOnce(new BlockPos(cell.getX(), floor - d, cell.getZ()), d == 0 ? "minecraft:grass_block" : "minecraft:dirt");
                }
            }
        }
        List<String> decor = switch (building.kind) {
            case "house" -> List.of("minecraft:barrel[facing=up]", "minecraft:composter");
            case "barn" -> List.of("minecraft:hay_block", "minecraft:hay_block");
            case "workshop", "storage", "bakery" -> List.of("minecraft:barrel[facing=up]", "minecraft:barrel[facing=up]");
            case "fletchery" -> List.of("minecraft:hay_block");
            case "market" -> List.of("minecraft:barrel[facing=up]", "minecraft:hay_block");
            case "guard_post" -> List.of("minecraft:hay_block");
            case "tower" -> List.of("minecraft:barrel[facing=up]");
            case "pen" -> List.of("minecraft:hay_block", "minecraft:hay_block");
            case "chapel" -> List.of(Ids.mc(building.wood + "_stairs") + "[facing=" + out.getOpposite().getSerializedName() + "]",
                    Ids.mc(building.wood + "_stairs") + "[facing=" + out.getOpposite().getSerializedName() + "]");
            default -> List.of();
        };
        int sideIndex = 0;
        for (String state : decor) {
            int j = sideIndex++ % 2 == 0 ? -2 : 2;
            BlockPos pos = door.relative(side, j);
            BlockPos below = pos.below();
            if (!WorldUtil.loaded(level, pos) || !WorldUtil.isAirLike(level.getBlockState(pos))
                    || level.getBlockState(below).is(Blocks.DIRT_PATH) || colony.sites().onRoad(below)
                    || occupiedByOther(building, pos) || building.contains(WorldUtil.pos(pos), 0)) {
                continue;
            }
            queueOnce(pos, state);
        }
        colony.markDirty();
    }

    private List<int[]> outline(ServerLevel level) {
        return outline(level, null);
    }

    private List<int[]> outline(ServerLevel level, List<Pos> keep) {
        BlockPos center = colony.center();
        List<BlockPos> points = new ArrayList<>();
        int reach = data().radius + 32;
        level.getPoiManager().getInSquare(type -> true, center, reach, PoiManager.Occupancy.ANY)
                .forEach(record -> points.add(record.getPos()));
        int poiCount = points.size();
        for (ColonyData.Building building : data().buildings) {
            if (Highways.remote(building)) {
                continue;
            }
            points.add(WorldUtil.bp(building.min));
            points.add(WorldUtil.bp(building.max));
        }
        for (ColonyData.Project project : data().projects) {
            if (Highways.remote(project.building)) {
                continue;
            }
            points.add(WorldUtil.bp(project.building.min));
            points.add(WorldUtil.bp(project.building.max));
        }
        for (Pos farm : data().farms) {
            points.add(WorldUtil.bp(farm));
        }
        points.add(colony.depot());
        if (colony.bunker().entrance() != null) {
            points.add(colony.bunker().entrance());
        }
        for (ColonyData.Mine pit : colony.allMines()) {
            if (pit != data().mine && pit.entrance.horizontalDistSq(data().center) > (double) (data().radius + 12) * (data().radius + 12)) {
                continue;
            }
            if (pit.quarry()) {
                points.add(new BlockPos(pit.corner.x() - QUARRY_MARGIN - 1, pit.corner.y(), pit.corner.z() - QUARRY_MARGIN - 1));
                points.add(new BlockPos(pit.corner.x() + pit.width + QUARRY_MARGIN, pit.corner.y(), pit.corner.z() + pit.width + QUARRY_MARGIN));
                points.add(new BlockPos(pit.corner.x() - QUARRY_MARGIN - 1, pit.corner.y(), pit.corner.z() + pit.width + QUARRY_MARGIN));
                points.add(new BlockPos(pit.corner.x() + pit.width + QUARRY_MARGIN, pit.corner.y(), pit.corner.z() - QUARRY_MARGIN - 1));
            } else {
                points.add(WorldUtil.bp(pit.entrance));
            }
        }
        if (data().pen != null) {
            points.add(WorldUtil.bp(data().pen.min));
            points.add(WorldUtil.bp(data().pen.max));
        }
        double[] radii = new double[SECTORS];
        for (int k = 0; k < SECTORS; k++) {
            radii[k] = 14;
        }
        for (int p = 0; p < points.size(); p++) {
            BlockPos point = points.get(p);
            double dx = point.getX() - center.getX();
            double dz = point.getZ() - center.getZ();
            double distance = Math.sqrt(dx * dx + dz * dz);
            if (p < poiCount && (distance > reach || !colony.ownsGround(point.getX(), point.getZ()))) {
                continue;
            }
            double angle = Math.atan2(dz, dx);
            int sector = Math.floorMod((int) Math.round(angle / (2 * Math.PI) * SECTORS), SECTORS);
            for (int spread = -2; spread <= 2; spread++) {
                int k = Math.floorMod(sector + spread, SECTORS);
                radii[k] = Math.max(radii[k], distance + 5 - Math.abs(spread));
            }
        }
        long seed = data().id.hashCode();
        for (int pass = 0; pass < 2; pass++) {
            double[] smooth = new double[SECTORS];
            for (int k = 0; k < SECTORS; k++) {
                smooth[k] = (radii[Math.floorMod(k - 1, SECTORS)] + radii[k] * 2 + radii[(k + 1) % SECTORS]) / 4.0;
            }
            radii = smooth;
        }
        double[] need = radii.clone();
        for (int k = 0; k < SECTORS; k++) {
            long hash = (seed * 31 + k) * 0x9E3779B97F4A7C15L;
            double noise = ((hash >>> 40) % 5) - 2;
            radii[k] = Math.max(14, Math.min(org.lts.smartcolony.SmartColonyConfig.get().maxColonyRadius + 24, Math.max(radii[k], radii[k] + noise)));
        }
        radii = WallPlanner.optimize(center.getX(), center.getZ(), radii, new WallTerrain(level), SEARCH_WINDOW, 3);
        int[][] old = keep == null ? null : oldVertices(center, keep);
        int[][] vertices = new int[SECTORS][];
        for (int k = 0; k < SECTORS; k++) {
            double angle = 2 * Math.PI * k / SECTORS;
            if (old != null && old[k] != null) {
                double oldRadius = Math.hypot(old[k][0] - center.getX(), old[k][1] - center.getZ());
                if (need[k] <= oldRadius) {
                    vertices[k] = old[k];
                    continue;
                }
            }
            double radius = radii[k];
            int x = center.getX() + (int) Math.round(Math.cos(angle) * radius);
            int z = center.getZ() + (int) Math.round(Math.sin(angle) * radius);
            while (radius > 14 && !colony.ownsGround(center.getX() + (int) Math.round(Math.cos(angle) * (radius + BORDER_GAP)),
                    center.getZ() + (int) Math.round(Math.sin(angle) * (radius + BORDER_GAP)))) {
                radius -= 1;
                x = center.getX() + (int) Math.round(Math.cos(angle) * radius);
                z = center.getZ() + (int) Math.round(Math.sin(angle) * radius);
            }
            vertices[k] = new int[] {x, z};
        }
        List<int[]> ring = new ArrayList<>();
        for (int k = 0; k < SECTORS; k++) {
            int[] from = vertices[k];
            int[] to = vertices[(k + 1) % SECTORS];
            line(ring, from[0], from[1], to[0], to[1]);
        }
        return ring;
    }

    private static int[][] oldVertices(BlockPos center, List<Pos> columns) {
        int[][] vertices = new int[SECTORS][];
        double[] best = new double[SECTORS];
        java.util.Arrays.fill(best, Math.PI / SECTORS / 2);
        for (Pos column : columns) {
            double dx = column.x() - center.getX();
            double dz = column.z() - center.getZ();
            if (dx * dx + dz * dz < 1) {
                continue;
            }
            double angle = Math.atan2(dz, dx);
            int k = Math.floorMod((int) Math.round(angle / (2 * Math.PI) * SECTORS), SECTORS);
            double gap = Math.abs(angle - 2 * Math.PI * k / SECTORS);
            gap = Math.min(gap, 2 * Math.PI - gap);
            if (gap < best[k]) {
                best[k] = gap;
                vertices[k] = new int[] {column.x(), column.z()};
            }
        }
        return vertices;
    }

    private final class WallTerrain implements WallPlanner.Terrain {
        private final ServerLevel level;
        private final HashMap<Long, double[]> cache = new HashMap<>();

        WallTerrain(ServerLevel level) {
            this.level = level;
        }

        @Override
        public boolean loaded(int x, int z) {
            return WorldUtil.loaded(level, new BlockPos(x, 0, z));
        }

        @Override
        public int height(int x, int z) {
            return (int) cell(x, z)[0];
        }

        @Override
        public double penalty(int x, int z) {
            return cell(x, z)[1];
        }

        private double[] cell(int x, int z) {
            return cache.computeIfAbsent(BlockPos.asLong(x, 0, z), key -> evaluate(x, z));
        }

        private double[] evaluate(int x, int z) {
            int y = terrainY(level, x, z);
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (!state.getFluidState().isEmpty()) {
                if (!state.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
                    return new double[] {y, WallPlanner.DEEP_WATER};
                }
                return new double[] {y, WallPlanner.waterPenalty(waterDepth(level, pos))};
            }
            double penalty = 0;
            BlockState above = level.getBlockState(pos.above());
            if (state.getBlock() instanceof net.minecraft.world.level.block.FarmlandBlock || state.is(Blocks.COMPOSTER)
                    || above.getBlock() instanceof net.minecraft.world.level.block.CropBlock
                    || above.getBlock() instanceof net.minecraft.world.level.block.StemBlock || above.is(Blocks.COMPOSTER)) {
                penalty += WallPlanner.OBSTACLE;
            }
            if (structureLike(state)) {
                penalty += WallPlanner.OBSTACLE;
            }
            if (colony.warehouse().contains(pos.above()) || protectedOffWall(pos.above())) {
                penalty += WallPlanner.OBSTACLE * 1.5;
            }
            if (nearQuarry(x, z, QUARRY_MARGIN)) {
                penalty += WallPlanner.OBSTACLE * 3;
            }
            for (Pos farm : data().farms) {
                if (farm.horizontalDistSq(WorldUtil.pos(pos)) <= 9) {
                    penalty += WallPlanner.OBSTACLE;
                    break;
                }
            }
            return new double[] {y, penalty};
        }
    }

    private static final int QUARRY_MARGIN = 3;

    private boolean wallTouchesQuarry(ColonyData.Wall wall) {
        for (Pos column : wall.columns) {
            if (nearQuarry(column.x(), column.z(), 1)) {
                return true;
            }
        }
        return false;
    }

    private boolean nearQuarry(int x, int z, int margin) {
        for (ColonyData.Mine pit : colony.allMines()) {
            if (pit.quarry() && x >= pit.corner.x() - 1 - margin && x <= pit.corner.x() + pit.width + margin
                    && z >= pit.corner.z() - 1 - margin && z <= pit.corner.z() + pit.width + margin) {
                return true;
            }
        }
        return false;
    }

    private boolean protectedOffWall(BlockPos pos) {
        return colony.isProtected(pos, false) && !isWall(WorldUtil.pos(pos));
    }

    private static boolean structureLike(BlockState state) {
        return !WorldUtil.naturalGround(state) && !wallMaterial(state) && !state.is(net.minecraft.tags.BlockTags.LOGS)
                && !state.is(net.minecraft.tags.BlockTags.LEAVES) && !state.is(net.minecraft.tags.BlockTags.TERRACOTTA)
                && !state.is(net.minecraft.tags.BlockTags.ICE) && !state.is(Blocks.MOSS_BLOCK);
    }

    private static int terrainY(ServerLevel level, int x, int z) {
        int y = groundY(level, x, z);
        int floor = y - 14;
        while (y > floor && wallMaterial(level.getBlockState(new BlockPos(x, y, z)))) {
            y--;
        }
        return y;
    }

    private static int waterDepth(ServerLevel level, BlockPos surface) {
        int depth = 0;
        int y = surface.getY();
        while (depth <= WallPlanner.MAX_WATER_DEPTH + 1
                && level.getFluidState(new BlockPos(surface.getX(), y, surface.getZ())).is(net.minecraft.tags.FluidTags.WATER)) {
            depth++;
            y--;
        }
        return depth;
    }

    private static int waterBottom(ServerLevel level, BlockPos surface) {
        if (!level.getFluidState(surface).is(net.minecraft.tags.FluidTags.WATER)) {
            return -1;
        }
        int depth = waterDepth(level, surface);
        if (depth > WallPlanner.MAX_WATER_DEPTH) {
            return -1;
        }
        int bottom = surface.getY() - depth;
        BlockState floor = level.getBlockState(new BlockPos(surface.getX(), bottom, surface.getZ()));
        return floor.isAir() || !floor.getFluidState().isEmpty() ? -1 : bottom;
    }

    private static boolean loaded(ServerLevel level, List<int[]> ring, List<Pos> old) {
        for (int[] cell : ring) {
            if (!WorldUtil.loaded(level, new BlockPos(cell[0], 0, cell[1]))) {
                return false;
            }
        }
        for (Pos column : old) {
            if (!WorldUtil.loaded(level, new BlockPos(column.x(), 0, column.z()))) {
                return false;
            }
        }
        return true;
    }

    private boolean planWall(ServerLevel level) {
        List<int[]> ring = outline(level);
        if (!loaded(level, ring, List.of())) {
            return false;
        }
        planWall(level, ring);
        return true;
    }

    private void planWall(ServerLevel level, List<int[]> ring) {
        BlockPos center = colony.center();
        boolean stone = forcedMaterial != null ? forcedMaterial.equals("stone")
                : data().mine != null || colony.stockOf(level, id -> id.equals(Ids.mc("cobblestone"))) >= 64;
        String wood = colony.construction().preferredWood(level);
        ColonyData.Wall wall = new ColonyData.Wall();
        wall.material = stone ? "stone" : wood;
        wall.planned = level.getGameTime();
        List<ColonyData.Order> towerOrders = new ArrayList<>();
        List<ColonyData.Order> wallOrders = new ArrayList<>();
        Set<Long> towerCells = new HashSet<>();
        String pillar = stone ? "minecraft:cobblestone" : Ids.mc(wood + "_log");
        String cap = stone ? "minecraft:cobblestone_wall" : Ids.mc(wood + "_fence");
        List<Pos> mainGates = new ArrayList<>();
        List<Boolean> paths = new ArrayList<>();
        List<Boolean> roads = new ArrayList<>();
        List<Integer> bottoms = new ArrayList<>();
        for (int[] cell : ring) {
            BlockPos column = new BlockPos(cell[0], 0, cell[1]);
            if (!WorldUtil.loaded(level, column)) {
                continue;
            }
            int groundY = wallGround(level, cell[0], cell[1]);
            BlockPos ground = new BlockPos(cell[0], groundY, cell[1]);
            int bottom = -1;
            if (!level.getFluidState(ground).isEmpty()) {
                bottom = waterBottom(level, ground);
                if (bottom < 0) {
                    continue;
                }
            }
            if (!level.getFluidState(ground.above()).isEmpty()) {
                continue;
            }
            if (colony.isProtected(ground.above(), false) || colony.warehouse().contains(ground.above())) {
                continue;
            }
            wall.columns.add(new Pos(cell[0], groundY, cell[1]));
            bottoms.add(bottom);
            boolean road = colony.sites().onRoad(ground);
            roads.add(road);
            paths.add(road || level.getBlockState(ground).is(Blocks.DIRT_PATH));
        }
        int count = wall.columns.size();
        boolean[] gateAt = new boolean[count];
        int sinceGate = GATE_SPACING / 2;
        for (int i = 0; i < count; i++) {
            if (paths.get(i)) {
                int end = i;
                while (end + 1 < count && paths.get(end + 1) && adjacent(wall.columns.get(end), wall.columns.get(end + 1))) {
                    end++;
                }
                int middle = (i + end) / 2;
                gateAt[middle] = true;
                boolean road = false;
                for (int k = i; k <= end; k++) {
                    road |= roads.get(k);
                }
                if (road) {
                    mainGates.add(wall.columns.get(middle));
                }
                sinceGate = 0;
                i = end;
                continue;
            }
            if (++sinceGate >= GATE_SPACING) {
                gateAt[i] = true;
                sinceGate = 0;
            }
        }
        int[] ground = new int[count];
        int[] wanted = new int[count];
        boolean[] linked = new boolean[count];
        for (int i = 0; i < count; i++) {
            Pos columnPos = wall.columns.get(i);
            ground[i] = columnPos.y();
            wanted[i] = gateAt[i] ? WALL_HEIGHT : columnTop(level, center, columnPos);
            linked[i] = count > 1 && adjacent(columnPos, wall.columns.get((i + 1) % count));
        }
        int[] tops = WallPlanner.heights(ground, wanted, gateAt, linked, MAX_TOP_STEP, SLOPE_CAP);
        int sinceTower = TOWER_SPACING / 2;
        int lastGate = -100;
        for (int i = 0; i < count; i++) {
            Pos columnPos = wall.columns.get(i);
            if (bottoms.get(i) >= 0) {
                for (int y = bottoms.get(i) + 1; y <= columnPos.y(); y++) {
                    wallOrders.add(new ColonyData.Order(new Pos(columnPos.x(), y, columnPos.z()), "minecraft:cobblestone", WALL));
                }
            }
            if (gateAt[i]) {
                lastGate = i;
                wall.heights.add(WALL_HEIGHT);
                wallOrders.add(new ColonyData.Order(new Pos(columnPos.x(), columnPos.y() + 1, columnPos.z()),
                        doorState(wood, gateFacing(wall.columns, i)), WALL));
                wallOrders.add(hung(new Pos(columnPos.x(), columnPos.y() + WALL_HEIGHT, columnPos.z()), Ids.mc(wood + "_planks")));
                wall.gates.add(columnPos);
                continue;
            }
            if (++sinceTower >= (stone ? TOWER_SPACING : TOWER_SPACING * 3 / 2) && i - lastGate > 3) {
                sinceTower = 0;
                tower(level, columnPos, pillar, cap, TOWER_HEIGHT, Set.of(), towerOrders, towerCells);
            }
            boolean post = i % POST_SPACING == 0;
            int top = tops[i];
            wall.heights.add(top);
            for (int h = 1; h <= top; h++) {
                wallOrders.add(new ColonyData.Order(new Pos(columnPos.x(), columnPos.y() + h, columnPos.z()), wallState(wall.material, h, top, post), WALL));
            }
        }
        for (Pos gate : mainGates) {
            int index = wall.columns.indexOf(gate);
            Set<Long> reserved = approach(gate, gateFacing(wall.columns, index));
            for (int side : new int[] {-1, 1}) {
                int near = index + side;
                int far = index + 2 * side;
                if (far < 0 || far >= count || gateAt[near] || gateAt[far]) {
                    continue;
                }
                tower(level, wall.columns.get(far), pillar, cap, GATE_TOWER_HEIGHT, reserved, towerOrders, towerCells);
            }
        }
        wallOrders.removeIf(order -> towerCells.contains(BlockPos.asLong(order.pos.x(), 0, order.pos.z())));
        data().orders.addAll(towerOrders);
        data().orders.addAll(wallOrders);
        wall.total = towerOrders.size() + wallOrders.size();
        data().wall = wall;
        data().wallVersion = WALL_VERSION;
        wallColumns = null;
        colony.journal(level, "planned a " + wall.material + " wall: " + wall.columns.size() + " columns, " + wall.total + " blocks");
        colony.notify(level, Component.translatable("smartcolony.chat.wall_planned", wall.columns.size()));
        colony.markDirty();
    }

    private static ColonyData.Order hung(Pos pos, String state) {
        ColonyData.Order order = new ColonyData.Order(pos, state, WALL);
        order.attached = true;
        return order;
    }

    private static Direction gateFacing(List<Pos> columns, int i) {
        Pos before = columns.get(Math.max(0, i - 1));
        Pos after = columns.get(Math.min(columns.size() - 1, i + 1));
        return Math.abs(after.x() - before.x()) >= Math.abs(after.z() - before.z()) ? Direction.NORTH : Direction.EAST;
    }

    private static String doorState(String wood, Direction facing) {
        return Ids.mc(wood + "_door") + "[facing=" + facing.getSerializedName() + ",half=lower]";
    }

    private void gatesToDoors(ServerLevel level) {
        ColonyData.Wall wall = data().wall;
        String wood = wall.material.equals("stone") ? colony.construction().preferredWood(level) : wall.material;
        data().orders.removeIf(order -> WALL.equals(order.tag) && order.state.contains("_fence_gate"));
        List<ColonyData.Order> doors = new ArrayList<>();
        for (int i = 0; i < wall.columns.size(); i++) {
            Pos column = wall.columns.get(i);
            if (!wall.gates.contains(column)) {
                continue;
            }
            doors.add(new ColonyData.Order(new Pos(column.x(), column.y() + 1, column.z()), doorState(wood, gateFacing(wall.columns, i)), WALL));
            doors.add(hung(new Pos(column.x(), column.y() + WALL_HEIGHT, column.z()), Ids.mc(wood + "_planks")));
        }
        data().orders.addAll(0, doors);
        wall.total += doors.size();
        ColonyData.Mine mine = data().mine;
        if (mine != null && mine.quarry() && mine.decorated) {
            for (int y = mine.entrance.y() - 2; y <= mine.entrance.y() + 3; y++) {
                BlockPos pos = new BlockPos(mine.entrance.x(), y, mine.entrance.z());
                if (level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock) {
                    data().orders.addFirst(new ColonyData.Order(WorldUtil.pos(pos), "minecraft:air", SALVAGE));
                }
            }
        }
        colony.journal(level, "wall gates become doors so villagers can pass: " + doors.size() / 2);
        colony.markDirty();
    }

    private static boolean adjacent(Pos a, Pos b) {
        return Math.abs(a.x() - b.x()) <= 1 && Math.abs(a.z() - b.z()) <= 1;
    }

    private static String wallState(String material, int h, int top, boolean post) {
        if (material.equals("stone")) {
            return h == top ? "minecraft:cobblestone_wall" : "minecraft:cobblestone";
        }
        if (post && h > 1) {
            return Ids.mc(material + "_log");
        }
        if (h == 1) {
            return "minecraft:cobblestone";
        }
        return h == top ? Ids.mc(material + "_fence") : Ids.mc(material + "_planks");
    }

    private static int height(ColonyData.Wall wall, int index) {
        return index < wall.heights.size() ? wall.heights.get(index) : WALL_HEIGHT;
    }

    private int columnTop(ServerLevel level, BlockPos center, Pos column) {
        double dx = column.x() - center.getX();
        double dz = column.z() - center.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        int highest = column.y();
        if (length >= 1) {
            for (int d = 1; d <= 3; d++) {
                for (int side = -1; side <= 1; side++) {
                    int x = column.x() + (int) Math.round((dx * d - dz * side) / length);
                    int z = column.z() + (int) Math.round((dz * d + dx * side) / length);
                    if (!WorldUtil.loaded(level, new BlockPos(x, 0, z))) {
                        continue;
                    }
                    BlockPos ground = new BlockPos(x, wallGround(level, x, z), z);
                    if (level.getFluidState(ground).isEmpty() && !isWall(WorldUtil.pos(ground))) {
                        highest = Math.max(highest, ground.getY());
                    }
                }
            }
        }
        return WALL_HEIGHT + Math.max(0, Math.min(MAX_RAISE, highest - column.y()));
    }

    private static final int PEN_VERSION = 2;
    private static final String PEN_CLEAR = "pen_clear";

    private void migratePen(ServerLevel level) {
        ColonyData.Pen pen = data().pen;
        if (pen == null || data().penVersion >= PEN_VERSION) {
            return;
        }
        if (!WorldUtil.loaded(level, WorldUtil.bp(pen.min)) || !WorldUtil.loaded(level, WorldUtil.bp(pen.max))) {
            return;
        }
        int cleared = 0;
        int y = pen.min.y();
        for (int x = pen.min.x(); x <= pen.max.x(); x++) {
            for (int z = pen.min.z(); z <= pen.max.z(); z++) {
                boolean edge = x == pen.min.x() || x == pen.max.x() || z == pen.min.z() || z == pen.max.z();
                if (!edge) {
                    continue;
                }
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos pos = new BlockPos(x, y + dy, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir() || WorldUtil.isAirLike(state) || state.getCollisionShape(level, pos).isEmpty()) {
                        continue;
                    }
                    data().orders.addFirst(new ColonyData.Order(WorldUtil.pos(pos), "minecraft:air", PEN_CLEAR));
                    cleared++;
                }
            }
        }
        data().penVersion = PEN_VERSION;
        if (cleared > 0) {
            colony.journal(level, "cleared " + cleared + " blocks along the pen fence so animals cannot climb out");
        }
        colony.markDirty();
    }

    private void migrateWall(ServerLevel level) {
        ColonyData.Wall wall = data().wall;
        if (wall == null || data().wallVersion >= WALL_VERSION) {
            return;
        }
        if (data().wallVersion == 4) {
            data().wallVersion = 5;
            gatesToDoors(level);
            return;
        }
        if (data().wallVersion == 7 && !wallTouchesQuarry(wall)) {
            data().wallVersion = WALL_VERSION;
            colony.markDirty();
            return;
        }
        if (level.getGameTime() - lastWallAttempt < 200) {
            return;
        }
        lastWallAttempt = level.getGameTime();
        String material = wall.material.equals("stone") ? "stone" : "wood";
        if (replanWall(level, material)) {
            colony.journal(level, "the wall plan was redrawn to avoid fields, steep steps and deep water");
        }
    }

    private static Set<Long> approach(Pos gate, Direction facing) {
        Set<Long> cells = new HashSet<>();
        boolean alongX = facing == Direction.NORTH || facing == Direction.SOUTH;
        for (int step = -2; step <= 2; step++) {
            for (int spread = -1; spread <= 1; spread++) {
                int x = gate.x() + (alongX ? spread : step);
                int z = gate.z() + (alongX ? step : spread);
                cells.add(BlockPos.asLong(x, 0, z));
            }
        }
        return cells;
    }

    private void tower(ServerLevel level, Pos base, String pillar, String cap, int height, Set<Long> reserved,
                       List<ColonyData.Order> orders, Set<Long> cells) {
        int top = base.y() + height;
        boolean stoneTower = pillar.equals("minecraft:cobblestone");
        String floor = pillar.replace("_log", "_planks");
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int x = base.x() + dx;
                int z = base.z() + dz;
                if (reserved.contains(BlockPos.asLong(x, 0, z))) {
                    continue;
                }
                int ground = wallGround(level, x, z);
                BlockPos foot = new BlockPos(x, ground, z);
                if (top - ground > height + 3 || !level.getFluidState(foot).isEmpty()
                        || colony.isProtected(foot.above(), false) || colony.warehouse().contains(foot.above())) {
                    continue;
                }
                cells.add(BlockPos.asLong(x, 0, z));
                boolean corner = dx != 0 && dz != 0;
                if (stoneTower) {
                    for (int y = ground + 1; y <= top; y++) {
                        orders.add(new ColonyData.Order(new Pos(x, y, z), pillar, WALL));
                    }
                } else {
                    if (corner) {
                        for (int y = ground + 1; y < top; y++) {
                            orders.add(new ColonyData.Order(new Pos(x, y, z), pillar, WALL));
                        }
                    } else if (ground + 1 < top) {
                        orders.add(new ColonyData.Order(new Pos(x, ground + 1, z), "minecraft:cobblestone", WALL));
                    }
                    orders.add(hung(new Pos(x, top, z), floor));
                }
                if (dx != 0 || dz != 0) {
                    orders.add(hung(new Pos(x, top + 1, z), cap));
                } else {
                    orders.add(hung(new Pos(x, top + 1, z), "minecraft:torch"));
                }
            }
        }
    }

    private static void line(List<int[]> out, int x0, int z0, int x1, int z1) {
        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;
        int err = dx - dz;
        int x = x0;
        int z = z0;
        while (true) {
            if (out.isEmpty() || out.getLast()[0] != x || out.getLast()[1] != z) {
                if (!out.isEmpty()) {
                    int[] last = out.getLast();
                    if (last[0] != x && last[1] != z) {
                        out.add(new int[] {x, last[1]});
                    }
                }
                out.add(new int[] {x, z});
            }
            if (x == x1 && z == z1) {
                break;
            }
            int e2 = 2 * err;
            if (e2 > -dz) {
                err -= dz;
                x += sx;
            }
            if (e2 < dx) {
                err += dx;
                z += sz;
            }
        }
    }

    private static int groundY(ServerLevel level, int x, int z) {
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        int floor = y - 8;
        while (y > floor && WorldUtil.removable(level, new BlockPos(x, y, z))) {
            y--;
        }
        return y;
    }

    public boolean wallPending() {
        return data().wall != null && data().wall.built < data().wall.total && data().orders.stream().anyMatch(order -> WALL.equals(order.tag));
    }

    public Construction.Task nextWall(ServerLevel level) {
        return next(level, true);
    }

    public Construction.Task next(ServerLevel level) {
        return next(level, false);
    }

    private Construction.Task next(ServerLevel level, boolean wallOnly) {
        long now = level.getGameTime();
        if (now < blockedUntil) {
            return null;
        }
        List<ColonyData.Order> orders = data().orders;
        int limit = Math.min(orders.size(), 48);
        int checks = 0;
        boolean skipped = false;
        int i = 0;
        if (wallOnly) {
            i = orders.size();
            for (int k = 0; k < orders.size(); k++) {
                if (WALL.equals(orders.get(k).tag)) {
                    i = k;
                    break;
                }
            }
        }
        while (i < orders.size() && checks < limit) {
            ColonyData.Order order = orders.get(i);
            if (wallOnly && !WALL.equals(order.tag)) {
                i++;
                continue;
            }
            if (claimed(order, now)) {
                skipped = true;
                i++;
                continue;
            }
            checks++;
            BlockPos pos = WorldUtil.bp(order.pos);
            if (!WorldUtil.loaded(level, pos)) {
                orders.add(orders.remove(i));
                continue;
            }
            BlockState target = parse(order.state);
            BlockState current = level.getBlockState(pos);
            if (target != null && target.isAir()) {
                boolean salvage = SALVAGE.equals(order.tag);
                boolean penClear = PEN_CLEAR.equals(order.tag) && penClearable(level, pos, current)
                        || CLEARING.equals(order.tag) && WorldUtil.overgrowth(current) && level.getBlockEntity(pos) == null;
                if (current.isAir() || !penClear && !diggable(level, pos, current, salvage)) {
                    orders.remove(i);
                    done(order);
                    continue;
                }
                return claim(level, order, pos, target);
            }
            boolean replace = WALL.equals(order.tag) && (wallMaterial(current) || current.getFluidState().is(net.minecraft.tags.FluidTags.WATER))
                    || Renovation.TAG.equals(order.tag) && WorldUtil.naturalGround(current)
                    || DECOR.equals(order.tag) && wallMaterial(current) && quarryRim(pos);
            if (target == null || matches(current, target)
                    || (!replace && !WorldUtil.isAirLike(current) && !WorldUtil.removable(level, pos))) {
                orders.remove(i);
                done(order);
                continue;
            }
            int support = supportCheck(level, order, pos, target);
            if (support == DEFER) {
                orders.add(orders.remove(i));
                continue;
            }
            if (support == SKIP) {
                orders.remove(i);
                done(order);
                data().bump("orders_unsupported", 1);
                colony.markDirty();
                continue;
            }
            if (support == FILLED) {
                orders.add(orders.remove(orders.indexOf(order)));
                i = 0;
                continue;
            }
            String item = BuiltInRegistries.ITEM.getKey(target.getBlock().asItem()).toString();
            if (colony.stockOf(level, id -> id.equals(item)) == 0 && !colony.craft(level, item, 1)) {
                String substitute = DECOR.equals(order.tag) ? null : substitute(level, order.state, WALL.equals(order.tag));
                if (substitute != null) {
                    order.state = substitute;
                    colony.markDirty();
                    checks--;
                    continue;
                }
                colony.request(item);
                waitingFor = item;
                if (FURNISH.equals(order.tag)) {
                    i++;
                    continue;
                }
                orders.add(orders.remove(i));
                continue;
            }
            waitingFor = "";
            return claim(level, order, pos, target);
        }
        if (!orders.isEmpty() && !skipped && !wallOnly) {
            blockedUntil = now + 1200;
        }
        return null;
    }

    private static final int OK = 0;
    private static final int DEFER = -1;
    private static final int SKIP = -2;
    private static final int FILLED = 1;

    private java.util.Map<Long, Boolean> pendingCells = new HashMap<>();
    private int pendingSize = -1;
    private long pendingAt;
    private java.util.Map<Long, Integer> wallBases;
    private ColonyData.Wall wallBasesFor;

    private java.util.Map<Long, Boolean> pendingCells(long now) {
        List<ColonyData.Order> orders = data().orders;
        if (pendingSize != orders.size() || now - pendingAt > 100) {
            pendingSize = orders.size();
            pendingAt = now;
            pendingCells = new HashMap<>();
            for (ColonyData.Order order : orders) {
                pendingCells.put(BlockPos.asLong(order.pos.x(), order.pos.y(), order.pos.z()), order.state.startsWith("minecraft:air"));
            }
        }
        return pendingCells;
    }

    private Integer wallBase(Pos pos) {
        ColonyData.Wall wall = data().wall;
        if (wall == null) {
            return null;
        }
        if (wallBases == null || wallBasesFor != wall) {
            wallBases = new HashMap<>();
            wallBasesFor = wall;
            for (Pos column : wall.columns) {
                wallBases.put(column(column.x(), column.z()), column.y());
            }
        }
        return wallBases.get(column(pos.x(), pos.z()));
    }

    private static boolean hangs(BlockState state) {
        return state.getBlock() instanceof net.minecraft.world.level.block.WallTorchBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.LadderBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.TrapDoorBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.WallSignBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.ButtonBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.WallBannerBlock;
    }

    private static org.lts.smartcolony.core.Support.Cell cell(ServerLevel level, BlockPos pos) {
        if (!WorldUtil.loaded(level, pos)) {
            return org.lts.smartcolony.core.Support.Cell.SOLID;
        }
        BlockState state = level.getBlockState(pos);
        if (!state.getFluidState().isEmpty()) {
            return org.lts.smartcolony.core.Support.Cell.BLOCKED;
        }
        if (state.isFaceSturdy(level, pos, Direction.UP)) {
            return org.lts.smartcolony.core.Support.Cell.SOLID;
        }
        return WorldUtil.isAirLike(state) ? org.lts.smartcolony.core.Support.Cell.EMPTY : org.lts.smartcolony.core.Support.Cell.SOLID;
    }

    private int supportCheck(ServerLevel level, ColonyData.Order order, BlockPos pos, BlockState target) {
        if (order.attached || target.isAir() || hangs(target)) {
            return OK;
        }
        boolean wallBase = false;
        if (WALL.equals(order.tag)) {
            Integer base = wallBase(order.pos);
            if (base == null || base + 1 != order.pos.y()) {
                return OK;
            }
            wallBase = true;
        } else if (!DECOR.equals(order.tag) && !Renovation.TAG.equals(order.tag) && !SUPPORT.equals(order.tag)) {
            return OK;
        }
        BlockPos below = pos.below();
        if (cell(level, below) == org.lts.smartcolony.core.Support.Cell.SOLID) {
            return OK;
        }
        if (pendingCells(level.getGameTime()).containsKey(below.asLong())) {
            return DEFER;
        }
        int gap = org.lts.smartcolony.core.Support.gap(depth -> cell(level, pos.below(depth)), wallBase ? WALL_SUPPORT_GAP : SUPPORT_GAP);
        if (gap <= 0) {
            return wallBase ? OK : SKIP;
        }
        List<ColonyData.Order> fills = new ArrayList<>();
        for (int depth = gap; depth >= 1; depth--) {
            fills.add(new ColonyData.Order(WorldUtil.pos(pos.below(depth)), "minecraft:cobblestone", SUPPORT));
        }
        data().orders.addAll(0, fills);
        pendingSize = -1;
        data().bump("support_fills", fills.size());
        colony.markDirty();
        return FILLED;
    }

    private final java.util.Map<Long, Long> claims = new HashMap<>();

    private static long column(int x, int z) {
        return BlockPos.asLong(x, 0, z);
    }

    private boolean claimed(ColonyData.Order order, long now) {
        Long at = claims.get(column(order.pos.x(), order.pos.z()));
        return at != null && now - at < 600;
    }

    private Construction.Task claim(ServerLevel level, ColonyData.Order order, BlockPos pos, BlockState target) {
        long now = level.getGameTime();
        if (claims.size() > 32) {
            claims.values().removeIf(at -> now - at >= 600);
        }
        claims.put(column(order.pos.x(), order.pos.z()), now);
        return new Construction.Task(Construction.Kind.ORDER, pos, target, null, null, standFor(level, pos));
    }

    private static final List<String> STONES = List.of("cobbled_deepslate", "stone", "sandstone", "andesite", "diorite", "granite",
            "tuff", "mossy_cobblestone", "stone_bricks", "red_sandstone", "blackstone", "mud_bricks", "bricks", "deepslate");
    private static final List<String> WOOD_SUFFIXES = List.of("_fence_gate", "_fence", "_planks", "_log", "_stairs", "_slab");

    private static final List<String> GRAY_STONES = List.of("cobbled_deepslate", "mossy_cobblestone", "stone", "stone_bricks", "deepslate");

    private String substitute(ServerLevel level, String state, boolean wallTag) {
        int bracket = state.indexOf('[');
        String id = Ids.path(bracket < 0 ? state : state.substring(0, bracket));
        String props = bracket < 0 ? "" : state.substring(bracket);
        if (id.endsWith("_bed")) {
            for (String color : Ids.COLORS) {
                String candidate = Ids.mc(color + "_bed");
                if (!candidate.equals(Ids.mc(id))
                        && (colony.stockOf(level, found -> found.equals(candidate)) > 0 || colony.craft(level, candidate, 1))) {
                    return candidate + props;
                }
            }
            return null;
        }
        if (id.equals("cobblestone") || id.equals("cobblestone_wall")) {
            for (String stone : wallTag ? GRAY_STONES : STONES) {
                String candidate = Ids.mc(stone);
                if (colony.stockOf(level, found -> found.equals(candidate)) > 0) {
                    return candidate;
                }
            }
            if (id.equals("cobblestone_wall")) {
                String fence = Ids.mc(colony.construction().preferredWood(level) + "_fence");
                if (colony.stockOf(level, found -> found.equals(fence)) > 0 || colony.craft(level, fence, 1)) {
                    return fence;
                }
            }
            return null;
        }
        for (String suffix : WOOD_SUFFIXES) {
            if (!id.endsWith(suffix)) {
                continue;
            }
            for (String wood : Construction.OVERWORLD_WOODS) {
                String candidate = Ids.mc(wood + suffix);
                if (!candidate.equals(Ids.mc(id))
                        && (colony.stockOf(level, found -> found.equals(candidate)) > 0 || colony.craft(level, candidate, 1))) {
                    return candidate + props;
                }
            }
            return null;
        }
        return null;
    }

    private static boolean matches(BlockState current, BlockState target) {
        if (!current.is(target.getBlock())) {
            return false;
        }
        if (current.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock) {
            return current.getValue(net.minecraft.world.level.block.FenceGateBlock.FACING).getAxis()
                    == target.getValue(net.minecraft.world.level.block.FenceGateBlock.FACING).getAxis();
        }
        return true;
    }

    private BlockPos standFor(ServerLevel level, BlockPos pos) {
        BlockPos center = colony.center();
        int sx = Integer.signum(center.getX() - pos.getX());
        int sz = Integer.signum(center.getZ() - pos.getZ());
        BlockPos preferred = WorldUtil.surface(level, pos.getX() + sx * 2, pos.getZ() + sz * 2);
        if (fineStand(preferred, pos)) {
            return preferred;
        }
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (Math.abs(dx) + Math.abs(dz) < 1 || !WorldUtil.loaded(level, pos.offset(dx, 0, dz))) {
                    continue;
                }
                BlockPos stand = WorldUtil.surface(level, pos.getX() + dx, pos.getZ() + dz);
                if (!fineStand(stand, pos)) {
                    continue;
                }
                double score = dx * dx + dz * dz - 0.5 * (dx * sx + dz * sz);
                if (score < bestScore) {
                    bestScore = score;
                    best = stand;
                }
            }
        }
        return best != null ? best : preferred;
    }

    private boolean fineStand(BlockPos stand, BlockPos target) {
        return !colony.inMine(stand) && !nearQuarry(stand.getX(), stand.getZ(), 0) && Math.abs(stand.getY() - target.getY()) <= 4;
    }

    private static boolean penClearable(ServerLevel level, BlockPos pos, BlockState state) {
        if (!state.getFluidState().isEmpty() || state.getDestroySpeed(level, pos) < 0 || state.getDestroySpeed(level, pos) > 3.5F) {
            return false;
        }
        if (state.getBlock() instanceof net.minecraft.world.level.block.FenceBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock || state.is(net.minecraft.tags.BlockTags.LOGS)) {
            return false;
        }
        return !(level.getBlockEntity(pos) instanceof net.minecraft.world.Container container) || container.isEmpty();
    }

    private boolean diggable(ServerLevel level, BlockPos pos, BlockState state, boolean salvage) {
        if (level.getBlockEntity(pos) != null || !state.getFluidState().isEmpty()) {
            return false;
        }
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0 || hardness > 3.5F) {
            return false;
        }
        if (salvage) {
            return wallMaterial(state) || state.is(Blocks.STONE_BRICKS);
        }
        return WorldUtil.naturalGround(state) || WorldUtil.removable(level, pos) || state.is(net.minecraft.tags.BlockTags.LEAVES);
    }

    public boolean take(ServerLevel level, BlockState state) {
        if (state.isAir()) {
            return true;
        }
        String item = BuiltInRegistries.ITEM.getKey(state.getBlock().asItem()).toString();
        return colony.warehouse().take(level, item, 1) == 1;
    }

    private static final int WALL_RUN = 6;

    public int wallRun(ServerLevel level, net.minecraft.world.entity.npc.villager.Villager villager, BlockPos placed) {
        if (!wallMaterial(level.getBlockState(placed))) {
            return 0;
        }
        long now = level.getGameTime();
        int count = 0;
        List<ColonyData.Order> orders = data().orders;
        for (int i = 0; i < orders.size() && count < WALL_RUN; i++) {
            ColonyData.Order order = orders.get(i);
            if (!WALL.equals(order.tag) || Math.abs(order.pos.x() - placed.getX()) > 2 || Math.abs(order.pos.z() - placed.getZ()) > 2
                    || Math.abs(order.pos.y() - placed.getY()) > 3) {
                continue;
            }
            BlockPos pos = WorldUtil.bp(order.pos);
            if (pos.equals(placed) || pos.distToCenterSqr(villager.getX(), villager.getEyeY(), villager.getZ()) > 25.0) {
                continue;
            }
            boolean sameColumn = order.pos.x() == placed.getX() && order.pos.z() == placed.getZ();
            if (!sameColumn && claimed(order, now)) {
                continue;
            }
            BlockState target = parse(order.state);
            if (target == null || target.isAir() || !wallMaterial(target)) {
                continue;
            }
            BlockState current = level.getBlockState(pos);
            if (matches(current, target)) {
                orders.remove(i--);
                done(order);
                continue;
            }
            if (!WorldUtil.isAirLike(current) || !hangs(target) && cell(level, pos.below()) != org.lts.smartcolony.core.Support.Cell.SOLID) {
                continue;
            }
            if (!take(level, target)) {
                break;
            }
            org.lts.smartcolony.work.Placer.place(level, villager, pos, target);
            orders.remove(i--);
            done(order);
            count++;
        }
        if (count > 0) {
            data().bump("improvements_placed", count);
            colony.markDirty();
        }
        return count;
    }

    public void completed(Construction.Task task) {
        Pos pos = WorldUtil.pos(task.pos());
        claims.remove(column(pos.x(), pos.z()));
        List<ColonyData.Order> orders = data().orders;
        for (int i = 0; i < orders.size(); i++) {
            if (orders.get(i).pos.equals(pos)) {
                done(orders.remove(i));
                break;
            }
        }
        colony.markDirty();
    }

    private void done(ColonyData.Order order) {
        if (WALL.equals(order.tag) && data().wall != null) {
            data().wall.built++;
        }
    }

    private static BlockState parse(String state) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, state, false).blockState();
        } catch (Exception e) {
            return null;
        }
    }
}
