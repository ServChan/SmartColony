package org.lts.smartcolony.colony;

import com.google.gson.JsonObject;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import org.lts.smartcolony.SmartColonyConfig;
import org.lts.smartcolony.core.Blueprint;
import org.lts.smartcolony.core.Blueprints;
import org.lts.smartcolony.core.Category;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.CraftPlanner;
import org.lts.smartcolony.core.GridPath;
import org.lts.smartcolony.core.Ids;
import org.lts.smartcolony.core.Pos;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.core.SiteEvaluator;
import org.lts.smartcolony.core.Stock;
import org.lts.smartcolony.util.WorldUtil;

public final class Construction {
    public enum Kind {
        CLEAR,
        FILL,
        PLACE,
        ROAD,
        TORCH
    }

    public record Task(Kind kind, BlockPos pos, BlockState state, BlockPos partner, BlockState partnerState, BlockPos stand) {
    }

    private static final List<String> OVERWORLD_WOODS = List.of(
            "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak");

    private final Colony colony;
    private Map<String, Integer> missing = new TreeMap<>();
    private String status = "";
    private final Map<String, Long> cooldown = new HashMap<>();
    private long lastPlan = -100000;
    private long lastLighting = -100000;
    private List<BlockPos> clearList;
    private int clearIndex;
    private List<Blueprint.Cell> cells;

    public Construction(Colony colony) {
        this.colony = colony;
    }

    public Map<String, Integer> missing() {
        return missing;
    }

    public String status() {
        return status;
    }

    private ColonyData data() {
        return colony.data();
    }

    public boolean hasWork(ServerLevel level) {
        ColonyData.Project project = data().project;
        return (project != null && !project.phase.equals("reserve")) || !data().pendingRoad.isEmpty() || !data().pendingTorches.isEmpty();
    }

    public void tick(ServerLevel level) {
        long now = level.getGameTime();
        ColonyData.Project project = data().project;
        if (project == null) {
            if (now - lastPlan >= 400) {
                lastPlan = now;
                plan(level);
            }
        } else if (project.phase.equals("reserve")) {
            reserve(level);
        }
        if (now - lastLighting >= 1200) {
            lastLighting = now;
            planLighting(level);
        }
    }

    private List<String> decideNeeds(ServerLevel level) {
        long now = level.getGameTime();
        Map<Category, Double> fill = colony.fill(level);
        double fullest = fill.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        int adults = colony.adultCount(level);
        int population = colony.colonists().size();
        long beds = level.getPoiManager().getCountInRange(type -> type.is(PoiTypes.HOME), colony.center(), data().radius + 8,
                PoiManager.Occupancy.ANY);
        List<String> order = new ArrayList<>();
        if (fullest >= 0.9) {
            order.add("storage_shed");
        }
        if (beds < population + 1) {
            order.add(adults >= 6 ? "house_family" : "house_small");
        }
        boolean hasPen = data().pen != null || data().buildings.stream().anyMatch(b -> "pen".equals(b.kind));
        if (!hasPen && adults >= 5) {
            order.add("pen");
        }
        if (fullest >= 0.75) {
            order.add("storage_shed");
        }
        long posts = data().buildings.stream().filter(b -> "guard_post".equals(b.kind)).count();
        int guards = colony.desired().getOrDefault(Role.GUARD, 0);
        if (guards >= 2 && posts < Math.min(3, guards - 1)) {
            order.add("guard_post");
        }
        List<String> ready = new ArrayList<>();
        for (String id : order) {
            if (cooldown.getOrDefault(id, Long.MIN_VALUE) <= now && !ready.contains(id)) {
                ready.add(id);
            }
        }
        return ready;
    }

    private boolean satisfiable(ServerLevel level, String blueprintId, String wood) {
        Map<String, Integer> materials = new TreeMap<>(Blueprints.get(blueprintId).materials(wood));
        materials.remove(Ids.mc("glass_pane"));
        Stock stock = colony.warehouse().stock(level);
        for (String key : new ArrayList<>(materials.keySet())) {
            if (Ids.path(key).endsWith("_bed")) {
                int beds = materials.remove(key);
                boolean any = false;
                for (String color : Ids.COLORS) {
                    if (CraftPlanner.plan(stock, Ids.mc(color + "_bed"), beds).isPresent()) {
                        any = true;
                        break;
                    }
                }
                if (!any) {
                    return false;
                }
            }
        }
        return CraftPlanner.plan(stock, materials).isPresent();
    }

    private void plan(ServerLevel level) {
        List<String> needs = decideNeeds(level);
        if (needs.isEmpty()) {
            status = "nothing to build";
            missing = new TreeMap<>();
            return;
        }
        String wood = chooseWood(level);
        String need = needs.getFirst();
        for (String candidate : needs) {
            if (satisfiable(level, candidate, wood)) {
                need = candidate;
                break;
            }
        }
        Blueprint blueprint = Blueprints.get(need);
        ColonyData.Project project = findSite(level, blueprint, wood);
        if (project == null) {
            status = "no flat land for " + need;
            cooldown.put(need, level.getGameTime() + 6000);
            return;
        }
        project.started = level.getGameTime();
        project.waitingSince = level.getGameTime();
        data().project = project;
        recomputeRequired(project);
        cells = null;
        clearList = null;
        colony.journal(level, "planned " + need + " at " + project.building.origin);
        colony.markDirty();
        reserve(level);
    }

    private static int woodStock(Stock stock, String wood) {
        return stock.get(Ids.mc(wood + "_log")) * 4 + stock.get(Ids.mc(wood + "_planks"));
    }

    private String chooseWood(ServerLevel level) {
        Stock stock = colony.warehouse().stock(level);
        String best = "oak";
        int bestCount = -1;
        for (String wood : OVERWORLD_WOODS) {
            int count = stock.get(Ids.mc(wood + "_log")) * 4 + stock.get(Ids.mc(wood + "_planks"));
            if (count > bestCount) {
                bestCount = count;
                best = wood;
            }
        }
        return best;
    }

    private void recomputeRequired(ColonyData.Project project) {
        Map<String, Integer> required = new TreeMap<>();
        for (Blueprint.Cell cell : cells(project)) {
            Blueprint.Material material = Blueprint.material(cell.state());
            if (material != null) {
                required.merge(material.item(), material.count(), Integer::sum);
            }
        }
        if (!project.fill.isEmpty()) {
            required.merge(Ids.mc("cobblestone"), project.fill.size(), Integer::sum);
        }
        project.required = required;
    }

    private List<Blueprint.Cell> cells(ColonyData.Project project) {
        Blueprint blueprint = Blueprints.get(project.building.blueprint);
        List<Blueprint.Cell> result = new ArrayList<>();
        for (Blueprint.Cell cell : blueprint.cells(project.building.wood)) {
            String id = Blueprint.blockId(cell.state());
            String swap = project.swaps.get(id);
            if (swap != null) {
                String props = cell.state().substring(id.length());
                String newState = Blueprint.resolve(swap, project.building.wood);
                if (Blueprint.blockId(newState).endsWith("_bed")) {
                    newState = newState + props;
                }
                result.add(new Blueprint.Cell(cell.x(), cell.y(), cell.z(), newState));
            } else {
                result.add(cell);
            }
        }
        return result;
    }

    private void reserve(ServerLevel level) {
        ColonyData.Project project = data().project;
        Stock stock = colony.warehouse().stock(level);
        var plan = CraftPlanner.plan(stock, project.required);
        if (plan.isEmpty() && applySwaps(level, project, stock)) {
            recomputeRequired(project);
            cells = null;
            plan = CraftPlanner.plan(stock, project.required);
        }
        if (plan.isPresent()) {
            if (colony.warehouse().applyPlan(level, plan.get()) && colony.warehouse().takeAll(level, project.required)) {
                project.reserved = new LinkedHashMap<>(project.required);
                project.phase = "clear";
                project.cursor = 0;
                clearList = null;
                missing = new TreeMap<>();
                status = "building " + project.building.blueprint;
                colony.journal(level, "materials reserved for " + project.building.blueprint);
                colony.notify(level, Component.translatable("smartcolony.chat.build_start",
                        Component.translatable("smartcolony.building." + project.building.blueprint)));
                colony.markDirty();
            }
            return;
        }
        Map<String, Integer> lack = new TreeMap<>();
        for (Map.Entry<String, Integer> entry : project.required.entrySet()) {
            int have = stock.get(entry.getKey());
            if (have < entry.getValue() && CraftPlanner.plan(stock, entry.getKey(), entry.getValue()).isEmpty()) {
                lack.put(entry.getKey(), entry.getValue() - have);
                colony.request(entry.getKey());
            }
        }
        missing = lack;
        status = "waiting for materials: " + lack;
        String richer = chooseWood(level);
        if (!richer.equals(project.building.wood) && woodStock(stock, richer) > woodStock(stock, project.building.wood)) {
            colony.journal(level, project.building.blueprint + " switched from " + project.building.wood + " to " + richer);
            project.building.wood = richer;
            recomputeRequired(project);
            cells = null;
            colony.markDirty();
            return;
        }
        long waited = level.getGameTime() - project.waitingSince;
        if (waited > 3000) {
            String wood = chooseWood(level);
            for (String candidate : decideNeeds(level)) {
                if (!candidate.equals(project.building.blueprint) && satisfiable(level, candidate, wood)) {
                    colony.journal(level, "postponed " + project.building.blueprint + ", " + candidate + " can be built now");
                    cooldown.put(project.building.blueprint, level.getGameTime() + 6000);
                    data().project = null;
                    missing = new TreeMap<>();
                    lastPlan = -100000;
                    colony.markDirty();
                    return;
                }
            }
        }
        if (waited > 24000L * 3 && !project.building.kind.equals("house")) {
            colony.journal(level, "gave up waiting for " + project.building.blueprint);
            cooldown.put(project.building.blueprint, level.getGameTime() + 24000L);
            data().project = null;
            missing = new TreeMap<>();
            colony.markDirty();
        }
    }

    private boolean applySwaps(ServerLevel level, ColonyData.Project project, Stock stock) {
        boolean changed = false;
        if (!project.swaps.containsKey(Ids.mc("glass_pane")) && project.required.containsKey(Ids.mc("glass_pane"))
                && CraftPlanner.plan(stock, Ids.mc("glass_pane"), project.required.get(Ids.mc("glass_pane"))).isEmpty()) {
            project.swaps.put(Ids.mc("glass_pane"), "minecraft:$wood_fence");
            changed = true;
        }
        for (String required : new ArrayList<>(project.required.keySet())) {
            if (!Ids.path(required).endsWith("_bed") || project.swaps.containsKey(required)) {
                continue;
            }
            int need = project.required.get(required);
            if (CraftPlanner.plan(stock, required, need).isPresent()) {
                continue;
            }
            for (String color : Ids.COLORS) {
                String bed = Ids.mc(color + "_bed");
                if (!bed.equals(required) && CraftPlanner.plan(stock, bed, need).isPresent()) {
                    project.swaps.put(required, bed);
                    changed = true;
                    break;
                }
            }
        }
        return changed;
    }

    private ColonyData.Project findSite(ServerLevel level, Blueprint blueprint, String wood) {
        ColonyData.Project easy = findSite(level, blueprint, wood, SiteEvaluator.MAX_FILL, SiteEvaluator.MAX_CUT);
        if (easy != null || !"house".equals(blueprint.kind())) {
            return easy;
        }
        return findSite(level, blueprint, wood, 4, Math.min(7, blueprint.sizeY() + 1));
    }

    private ColonyData.Project findSite(ServerLevel level, Blueprint blueprint, String wood, int fillLimit, int cutLimit) {
        BlockPos center = colony.center();
        int maxDistance = data().radius + 28;
        double bestScore = Double.MAX_VALUE;
        ColonyData.Project best = null;
        for (int distance = 7; distance <= maxDistance; distance += 3) {
            int steps = Math.max(12, distance * 2);
            for (int i = 0; i < steps; i++) {
                double angle = (i + (distance % 2) * 0.5) * Math.PI * 2.0 / steps;
                int px = center.getX() + (int) Math.round(Math.cos(angle) * distance);
                int pz = center.getZ() + (int) Math.round(Math.sin(angle) * distance);
                int rotation = Blueprint.rotationFacing(center.getX() - px, center.getZ() - pz);
                int sx = blueprint.rotatedSizeX(rotation);
                int sz = blueprint.rotatedSizeZ(rotation);
                int ox = px - sx / 2;
                int oz = pz - sz / 2;
                int[][] ground = new int[sx][sz];
                boolean[][] blocked = new boolean[sx][sz];
                boolean ok = true;
                for (int x = -1; x <= sx && ok; x++) {
                    for (int z = -1; z <= sz && ok; z++) {
                        int wx = ox + x;
                        int wz = oz + z;
                        if (!WorldUtil.loaded(level, new BlockPos(wx, 0, wz))) {
                            ok = false;
                            break;
                        }
                        boolean inside = x >= 0 && z >= 0 && x < sx && z < sz;
                        int y = groundY(level, wx, wz);
                        BlockPos top = new BlockPos(wx, y, wz);
                        boolean bad = blockedColumn(level, top);
                        if (inside) {
                            ground[x][z] = y;
                            blocked[x][z] = bad;
                        } else if (colony.isProtected(top.above())) {
                            ok = false;
                        }
                    }
                }
                if (!ok) {
                    continue;
                }
                int paths = 0;
                for (int x = 0; x < sx; x++) {
                    for (int z = 0; z < sz; z++) {
                        if (level.getBlockState(new BlockPos(ox + x, ground[x][z], oz + z)).is(Blocks.DIRT_PATH)) {
                            paths++;
                        }
                    }
                }
                SiteEvaluator.Result result = SiteEvaluator.evaluate(ground, blocked, fillLimit, cutLimit);
                if (!result.ok() || !accessible(level, blueprint, rotation, ox, oz, result.baseY())) {
                    continue;
                }
                double score = result.score() + distance * 0.6 + paths * 3;
                if (score < bestScore) {
                    bestScore = score;
                    best = project(blueprint, wood, ox, oz, rotation, result, ground);
                }
            }
            if (best != null && distance > 12) {
                break;
            }
        }
        return best;
    }

    private boolean accessible(ServerLevel level, Blueprint blueprint, int rotation, int ox, int oz, int base) {
        for (int step = 1; step <= 2; step++) {
            int[] front = Blueprint.rotate(blueprint.doorX(), blueprint.doorZ() - step, blueprint.sizeX(), blueprint.sizeZ(), rotation);
            int x = ox + front[0];
            int z = oz + front[1];
            if (!WorldUtil.loaded(level, new BlockPos(x, 0, z))) {
                return false;
            }
            int y = groundY(level, x, z);
            BlockPos top = new BlockPos(x, y, z);
            if (Math.abs(y - base) > 1 || !level.getFluidState(top).isEmpty() || colony.warehouse().contains(top.above())) {
                return false;
            }
        }
        return true;
    }

    private int groundY(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        int floor = y - 8;
        while (y > floor && WorldUtil.removable(level, new BlockPos(x, y, z))) {
            y--;
        }
        return y;
    }

    private boolean blockedColumn(ServerLevel level, BlockPos top) {
        if (!level.getFluidState(top).isEmpty() || !level.getFluidState(top.above()).isEmpty()) {
            return true;
        }
        if (colony.isProtected(top.above()) || colony.sites().onRoad(top)) {
            return true;
        }
        return !WorldUtil.naturalGround(level.getBlockState(top));
    }

    private ColonyData.Project project(Blueprint blueprint, String wood, int ox, int oz, int rotation,
                                       SiteEvaluator.Result result, int[][] ground) {
        ColonyData.Project project = new ColonyData.Project();
        ColonyData.Building building = project.building;
        building.blueprint = blueprint.id();
        building.kind = blueprint.kind();
        building.wood = wood;
        building.rotation = rotation;
        building.beds = blueprint.beds();
        int base = result.baseY();
        building.origin = new Pos(ox, base, oz);
        int sx = blueprint.rotatedSizeX(rotation);
        int sz = blueprint.rotatedSizeZ(rotation);
        building.min = new Pos(ox, base - result.maxFill(), oz);
        building.max = new Pos(ox + sx - 1, base + blueprint.sizeY() - 1, oz + sz - 1);
        int[] door = Blueprint.rotate(blueprint.doorX(), blueprint.doorZ() - 1, blueprint.sizeX(), blueprint.sizeZ(), rotation);
        building.door = new Pos(ox + door[0], base + 1, oz + door[1]);
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                int localTop = base - 1;
                for (int y = ground[x][z] + 1; y <= localTop; y++) {
                    project.fill.add(new Pos(ox + x, y, oz + z));
                }
                if (ground[x][z] < base && floorIsLoose(blueprint, rotation, x, z)) {
                    project.fill.add(new Pos(ox + x, base, oz + z));
                }
            }
        }
        return project;
    }

    private static boolean floorIsLoose(Blueprint blueprint, int rotation, int wx, int wz) {
        for (int x = 0; x < blueprint.sizeX(); x++) {
            for (int z = 0; z < blueprint.sizeZ(); z++) {
                int[] r = Blueprint.rotate(x, z, blueprint.sizeX(), blueprint.sizeZ(), rotation);
                if (r[0] == wx && r[1] == wz) {
                    char c = blueprint.at(x, 0, z);
                    return c == Blueprint.ANY || c == Blueprint.AIR;
                }
            }
        }
        return true;
    }

    public BlockPos world(ColonyData.Building building, int x, int y, int z) {
        Blueprint blueprint = Blueprints.get(building.blueprint);
        int[] r = Blueprint.rotate(x, z, blueprint.sizeX(), blueprint.sizeZ(), building.rotation);
        return new BlockPos(building.origin.x() + r[0], building.origin.y() + y, building.origin.z() + r[1]);
    }

    public static BlockState parse(String state, int rotation) {
        try {
            BlockState parsed = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, state, false).blockState();
            return parsed.rotate(Rotation.values()[Math.floorMod(rotation, 4)]);
        } catch (CommandSyntaxException e) {
            return Blocks.AIR.defaultBlockState();
        }
    }

    private List<BlockPos> clearList(ServerLevel level, ColonyData.Project project) {
        Blueprint blueprint = Blueprints.get(project.building.blueprint);
        List<BlockPos> list = new ArrayList<>();
        int top = blueprint.sizeY() + 2;
        for (int y = top - 1; y >= 1; y--) {
            for (int x = 0; x < blueprint.sizeX(); x++) {
                for (int z = 0; z < blueprint.sizeZ(); z++) {
                    char c = y < blueprint.sizeY() ? blueprint.at(x, y, z) : Blueprint.AIR;
                    if (c == Blueprint.ANY) {
                        continue;
                    }
                    BlockPos pos = world(project.building, x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    if (c != Blueprint.AIR) {
                        continue;
                    }
                    list.add(pos.immutable());
                }
            }
        }
        for (int y = 1; y < blueprint.sizeY(); y++) {
            for (int x = 0; x < blueprint.sizeX(); x++) {
                for (int z = 0; z < blueprint.sizeZ(); z++) {
                    char c = blueprint.at(x, y, z);
                    if (c == Blueprint.ANY || c == Blueprint.AIR) {
                        continue;
                    }
                    BlockPos pos = world(project.building, x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (!state.isAir() && !WorldUtil.isAirLike(state) || !state.getFluidState().isEmpty()) {
                        list.add(pos.immutable());
                    }
                }
            }
        }
        return list;
    }

    public Task next(ServerLevel level) {
        ColonyData.Project project = data().project;
        if (project != null && !project.phase.equals("reserve")) {
            Task task = projectTask(level, project);
            if (task != null || data().project != null) {
                return task;
            }
        }
        while (!data().pendingRoad.isEmpty()) {
            Pos pos = data().pendingRoad.getFirst();
            BlockPos column = new BlockPos(pos.x(), pos.y(), pos.z());
            if (!WorldUtil.loaded(level, column)) {
                data().pendingRoad.removeFirst();
                continue;
            }
            BlockPos ground = WorldUtil.surface(level, pos.x(), pos.z()).below();
            return new Task(Kind.ROAD, ground, null, null, null, ground.above());
        }
        while (!data().pendingTorches.isEmpty()) {
            Pos pos = data().pendingTorches.getFirst();
            BlockPos feet = WorldUtil.bp(pos);
            if (!WorldUtil.loaded(level, feet) || !WorldUtil.isAirLike(level.getBlockState(feet))) {
                data().pendingTorches.removeFirst();
                continue;
            }
            return new Task(Kind.TORCH, feet, null, null, null, feet);
        }
        return null;
    }

    private Task projectTask(ServerLevel level, ColonyData.Project project) {
        if (project.phase.equals("clear")) {
            if (clearList == null) {
                clearList = clearList(level, project);
                clearIndex = 0;
            }
            while (clearIndex < clearList.size()) {
                BlockPos pos = clearList.get(clearIndex);
                BlockState state = level.getBlockState(pos);
                if (state.isAir()) {
                    clearIndex++;
                    continue;
                }
                return new Task(Kind.CLEAR, pos, null, null, null, groundStand(project, pos));
            }
            clearList = null;
            project.phase = "foundation";
            project.cursor = 0;
            colony.markDirty();
        }
        if (project.phase.equals("foundation")) {
            while (project.cursor < project.fill.size()) {
                BlockPos pos = WorldUtil.bp(project.fill.get(project.cursor));
                BlockState state = level.getBlockState(pos);
                if (!WorldUtil.isAirLike(state) && state.getFluidState().isEmpty()) {
                    project.cursor++;
                    continue;
                }
                return new Task(Kind.FILL, pos, Blocks.COBBLESTONE.defaultBlockState(), null, null, groundStand(project, pos));
            }
            project.phase = "build";
            project.cursor = 0;
            colony.markDirty();
        }
        if (project.phase.equals("build")) {
            if (cells == null) {
                cells = cells(project);
            }
            while (project.cursor < cells.size()) {
                Blueprint.Cell cell = cells.get(project.cursor);
                BlockPos pos = world(project.building, cell.x(), cell.y(), cell.z());
                BlockState target = parse(cell.state(), project.building.rotation);
                BlockState current = level.getBlockState(pos);
                if (current.getBlock() == target.getBlock() && sameShape(current, target)) {
                    project.cursor++;
                    continue;
                }
                BlockPos partner = null;
                BlockState partnerState = null;
                if (target.getBlock() instanceof DoorBlock && target.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
                    partner = pos.above();
                    partnerState = target.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
                } else if (target.getBlock() instanceof BedBlock && target.getValue(BedBlock.PART) == BedPart.FOOT) {
                    partner = pos.relative(target.getValue(BedBlock.FACING));
                    partnerState = target.setValue(BedBlock.PART, BedPart.HEAD);
                }
                return new Task(Kind.PLACE, pos, target, partner, partnerState, groundStand(project, pos));
            }
            finish(level, project);
            return null;
        }
        return null;
    }

    private static boolean sameShape(BlockState current, BlockState target) {
        if (target.getBlock() instanceof DoorBlock) {
            return current.getValue(DoorBlock.HALF) == target.getValue(DoorBlock.HALF);
        }
        if (target.getBlock() instanceof BedBlock) {
            return current.getValue(BedBlock.PART) == target.getValue(BedBlock.PART);
        }
        return true;
    }

    private BlockPos groundStand(ColonyData.Project project, BlockPos pos) {
        return new BlockPos(pos.getX(), project.building.origin.y() + 1, pos.getZ());
    }

    public void completed(Task task) {
        ColonyData.Project project = data().project;
        switch (task.kind()) {
            case CLEAR -> {
                if (clearList != null && clearIndex < clearList.size() && clearList.get(clearIndex).equals(task.pos())) {
                    clearIndex++;
                }
            }
            case FILL -> {
                if (project != null && project.cursor < project.fill.size() && WorldUtil.bp(project.fill.get(project.cursor)).equals(task.pos())) {
                    project.cursor++;
                }
            }
            case PLACE -> {
                if (project != null && cells != null && project.cursor < cells.size()) {
                    Blueprint.Cell cell = cells.get(project.cursor);
                    if (world(project.building, cell.x(), cell.y(), cell.z()).equals(task.pos())) {
                        project.cursor++;
                        data().bump("blocks_placed", 1);
                    }
                }
            }
            case ROAD -> {
                Pos pos = WorldUtil.pos(task.pos());
                data().pendingRoad.removeIf(p -> p.x() == pos.x() && p.z() == pos.z());
                if (!data().roads.contains(pos)) {
                    data().roads.add(pos);
                }
                data().bump("road_blocks", 1);
            }
            case TORCH -> {
                Pos pos = WorldUtil.pos(task.pos());
                data().pendingTorches.remove(pos);
                data().torches.add(pos);
                data().bump("street_torches", 1);
            }
        }
        colony.markDirty();
    }

    public void skip(Task task) {
        completed(task);
    }

    private void finish(ServerLevel level, ColonyData.Project project) {
        ColonyData.Building building = project.building;
        Blueprint blueprint = Blueprints.get(building.blueprint);
        for (int[] container : blueprint.containers()) {
            building.containers.add(WorldUtil.pos(world(building, container[0], container[1], container[2])));
        }
        if (building.kind.equals("storage")) {
            Map<Category, Double> fill = colony.fill(level);
            List<Category> fullest = new ArrayList<>(fill.keySet());
            fullest.sort((a, b) -> Double.compare(fill.get(b), fill.get(a)));
            int half = (building.containers.size() + 1) / 2;
            for (int i = 0; i < building.containers.size(); i++) {
                Category category = fullest.get(i < half ? 0 : Math.min(1, fullest.size() - 1));
                data().storage.computeIfAbsent(category, key -> new ArrayList<>()).add(building.containers.get(i));
            }
            building.storageFor = fullest.getFirst();
        }
        if (building.kind.equals("pen")) {
            ColonyData.Pen pen = new ColonyData.Pen();
            BlockPos a = world(building, 1, 1, 1);
            BlockPos b = world(building, blueprint.sizeX() - 2, 1, blueprint.sizeZ() - 2);
            pen.min = new Pos(Math.min(a.getX(), b.getX()), a.getY(), Math.min(a.getZ(), b.getZ()));
            pen.max = new Pos(Math.max(a.getX(), b.getX()), a.getY(), Math.max(a.getZ(), b.getZ()));
            pen.gate = WorldUtil.pos(world(building, blueprint.doorX(), 1, blueprint.doorZ()));
            pen.outside = WorldUtil.pos(world(building, blueprint.doorX(), 1, blueprint.doorZ() - 1));
            pen.inside = WorldUtil.pos(world(building, blueprint.doorX(), 1, blueprint.doorZ() + 2));
            data().pen = pen;
        }
        data().buildings.add(building);
        data().project = null;
        cells = null;
        clearList = null;
        data().bump("buildings", 1);
        int reach = (int) Math.ceil(Math.sqrt(Math.max(building.min.horizontalDistSq(data().center), building.max.horizontalDistSq(data().center))));
        data().radius = Math.min(SmartColonyConfig.get().maxColonyRadius, Math.max(data().radius, reach + 6));
        colony.journal(level, "finished " + building.blueprint + " at " + building.origin);
        colony.notify(level, Component.translatable("smartcolony.chat.build_done",
                Component.translatable("smartcolony.building." + building.blueprint)));
        planRoad(level, WorldUtil.bp(building.door));
        colony.markDirty();
    }

    public void planRoad(ServerLevel level, BlockPos from) {
        BlockPos start = new BlockPos(from.getX(), 0, from.getZ());
        BlockPos goal = nearestRoadOrDepot(start);
        List<int[]> path = GridPath.find(new GridPath.Terrain() {
            @Override
            public int height(int x, int z) {
                if (!WorldUtil.loaded(level, new BlockPos(x, 0, z))) {
                    return Integer.MIN_VALUE / 2;
                }
                return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            }

            @Override
            public boolean blocked(int x, int z) {
                if (!WorldUtil.loaded(level, new BlockPos(x, 0, z))) {
                    return true;
                }
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                BlockPos top = new BlockPos(x, y, z);
                if (!level.getFluidState(top).isEmpty()) {
                    return true;
                }
                Pos p = new Pos(x, y + 1, z);
                for (ColonyData.Building building : data().buildings) {
                    if (building.contains(p, 0)) {
                        return true;
                    }
                }
                BlockState state = level.getBlockState(top);
                return state.is(Blocks.FARMLAND) || colony.warehouse().contains(top.above());
            }

            @Override
            public boolean preferred(int x, int z) {
                return data().roads.stream().anyMatch(p -> p.x() == x && p.z() == z);
            }
        }, from.getX(), from.getZ(), goal.getX(), goal.getZ(), 6000);
        int added = 0;
        for (int[] cell : path) {
            boolean known = false;
            for (Pos road : data().roads) {
                if (road.x() == cell[0] && road.z() == cell[1]) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                data().pendingRoad.add(new Pos(cell[0], from.getY(), cell[1]));
                added++;
            }
        }
        if (added > 0) {
            colony.journal(level, "road planned: " + added + " blocks");
        }
    }

    private BlockPos nearestRoadOrDepot(BlockPos from) {
        BlockPos best = colony.depot();
        double bestDistance = horizontal(from, best);
        for (Pos road : data().roads) {
            BlockPos pos = WorldUtil.bp(road);
            double distance = horizontal(from, pos);
            if (distance < bestDistance && distance > 4) {
                bestDistance = distance;
                best = pos;
            }
        }
        return best;
    }

    private static double horizontal(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private void planLighting(ServerLevel level) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int i = 0; i < data().roads.size(); i += 3) {
            candidates.add(WorldUtil.bp(data().roads.get(i)));
        }
        for (ColonyData.Building building : data().buildings) {
            candidates.add(WorldUtil.bp(building.door).below());
        }
        candidates.add(colony.depot().below());
        int planned = 0;
        for (BlockPos ground : candidates) {
            if (planned >= 6 || data().pendingTorches.size() > 16) {
                break;
            }
            if (!WorldUtil.loaded(level, ground)) {
                continue;
            }
            BlockPos surface = WorldUtil.surface(level, ground.getX(), ground.getZ());
            if (level.getBrightness(LightLayer.BLOCK, surface) >= 8) {
                continue;
            }
            if (nearTorch(surface, 7)) {
                continue;
            }
            BlockPos spot = torchSpot(level, surface);
            if (spot != null) {
                data().pendingTorches.add(WorldUtil.pos(spot));
                planned++;
            }
        }
        if (planned > 0) {
            colony.markDirty();
        }
    }

    private boolean nearTorch(BlockPos pos, int distance) {
        Pos p = WorldUtil.pos(pos);
        for (Pos torch : data().torches) {
            if (torch.horizontalDistSq(p) < distance * distance) {
                return true;
            }
        }
        for (Pos torch : data().pendingTorches) {
            if (torch.horizontalDistSq(p) < distance * distance) {
                return true;
            }
        }
        return false;
    }

    private BlockPos torchSpot(ServerLevel level, BlockPos feet) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = WorldUtil.surface(level, feet.getX() + direction.getStepX(), feet.getZ() + direction.getStepZ());
            if (Math.abs(side.getY() - feet.getY()) > 1) {
                continue;
            }
            if (colony.sites().onRoad(side.below()) || colony.warehouse().contains(side) || !WorldUtil.standable(level, side)) {
                continue;
            }
            boolean insideBuilding = false;
            for (ColonyData.Building building : data().buildings) {
                if (building.contains(WorldUtil.pos(side), 0)) {
                    insideBuilding = true;
                    break;
                }
            }
            if (insideBuilding || side.equals(colony.depot())) {
                continue;
            }
            return side.immutable();
        }
        return null;
    }

    public JsonObject debug(ServerLevel level) {
        JsonObject json = new JsonObject();
        json.addProperty("status", status);
        JsonObject lack = new JsonObject();
        missing.forEach(lack::addProperty);
        json.add("missing", lack);
        ColonyData.Project project = data().project;
        if (project != null) {
            json.addProperty("blueprint", project.building.blueprint);
            json.addProperty("phase", project.phase);
            json.addProperty("cursor", project.cursor);
            json.addProperty("cells", cells == null ? -1 : cells.size());
            json.addProperty("clear", clearList == null ? -1 : clearList.size() - clearIndex);
            json.addProperty("origin", project.building.origin.toString());
            json.addProperty("rotation", project.building.rotation);
        }
        json.addProperty("pendingRoad", data().pendingRoad.size());
        json.addProperty("pendingTorches", data().pendingTorches.size());
        return json;
    }

    public boolean forceProject(ServerLevel level, String blueprintId) {
        Blueprint blueprint = Blueprints.get(blueprintId);
        ColonyData.Project project = findSite(level, blueprint, chooseWood(level));
        if (project != null) {
            project.started = level.getGameTime();
            project.waitingSince = level.getGameTime();
            data().project = project;
            recomputeRequired(project);
            cells = null;
            clearList = null;
            colony.markDirty();
            return true;
        }
        return false;
    }
}
