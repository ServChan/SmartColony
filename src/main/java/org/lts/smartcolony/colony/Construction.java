package org.lts.smartcolony.colony;

import com.google.gson.JsonObject;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
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
import org.lts.smartcolony.core.Cemetery;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.CraftPlanner;
import org.lts.smartcolony.core.Ids;
import org.lts.smartcolony.core.GuardDrill;
import org.lts.smartcolony.core.Kindergarten;
import org.lts.smartcolony.core.Schooling;
import org.lts.smartcolony.core.Pier;
import org.lts.smartcolony.core.Pos;
import org.lts.smartcolony.core.PorterPlan;
import org.lts.smartcolony.core.PreBuildCheck;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.core.SiteEvaluator;
import org.lts.smartcolony.core.Stock;
import org.lts.smartcolony.core.TavernRules;
import org.lts.smartcolony.core.Tech;
import org.lts.smartcolony.core.TerrainMap;
import org.lts.smartcolony.core.TreeFarmPlan;
import org.lts.smartcolony.core.Hospital;
import org.lts.smartcolony.core.MaterialSwaps;
import org.lts.smartcolony.core.Staged;
import org.lts.smartcolony.core.TownHall;
import org.lts.smartcolony.util.WorldUtil;

public final class Construction {
    public enum Kind {
        CLEAR,
        FILL,
        PLACE,
        ROAD,
        ORDER
    }

    public record Task(Kind kind, BlockPos pos, BlockState state, BlockPos partner, BlockState partnerState, BlockPos stand,
                       ColonyData.Project project) {
        public Task(Kind kind, BlockPos pos, BlockState state, BlockPos partner, BlockState partnerState, BlockPos stand) {
            this(kind, pos, state, partner, partnerState, stand, null);
        }
    }

    private static final class Runtime {
        List<BlockPos> clearList;
        int clearIndex;
        List<Blueprint.Cell> cells;
        List<Pos> stiltSource;
        Set<Long> stiltColumns;
    }

    private static final int LOOKAHEAD = 10;
    private static final long CLAIM_TICKS = 1200;

    static final List<String> OVERWORLD_WOODS = List.of(
            "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak", "poplar");

    private final Colony colony;
    private final SiteCheck check;
    private final Paths paths;
    private final Lamps lamps;
    private Map<String, Integer> missing = new TreeMap<>();
    private String status = "";
    private final Map<String, Long> cooldown = new HashMap<>();
    private long lastPlan = -100000;
    private final Map<ColonyData.Project, Runtime> runtime = new IdentityHashMap<>();
    private final Map<Long, Long> claims = new HashMap<>();
    private BlockPos siteHint;
    private ServerLevel level;
    private final Map<UUID, ColonyData.Project> assigned = new HashMap<>();
    private final Map<UUID, Long> assignedAt = new HashMap<>();

    public Construction(Colony colony) {
        this.colony = colony;
        this.check = new SiteCheck(colony, this);
        this.paths = new Paths(colony);
        this.lamps = new Lamps(colony);
    }

    public Paths paths() {
        return paths;
    }

    public Lamps lamps() {
        return lamps;
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

    private Runtime rt(ColonyData.Project project) {
        return runtime.computeIfAbsent(project, key -> new Runtime());
    }

    public List<ColonyData.Project> projects() {
        return data().projects;
    }

    public ColonyData.Project primary() {
        for (ColonyData.Project project : data().projects) {
            if (!project.phase.equals("reserve")) {
                return project;
            }
        }
        return data().projects.isEmpty() ? null : data().projects.getFirst();
    }

    private List<ColonyData.Project> active() {
        List<ColonyData.Project> active = new ArrayList<>();
        for (ColonyData.Project project : data().projects) {
            if (!project.phase.equals("reserve")) {
                active.add(project);
            }
        }
        return active;
    }

    private long lastPen = Long.MIN_VALUE / 2;

    private void unblockBeds(ServerLevel level) {
        boolean hasPen = data().pen != null || data().buildings.stream().anyMatch(b -> "pen".equals(b.kind))
                || data().projects.stream().anyMatch(p -> "pen".equals(p.building.kind));
        if (hasPen || colony.adultCount(level) < 3) {
            return;
        }
        boolean woolShort = colony.stockOf(level, id -> Ids.path(id).endsWith("_wool") || Ids.path(id).endsWith("_bed")) < 3;
        boolean bedBlocked = woolShort && data().projects.stream().anyMatch(p -> p.phase.equals("reserve")
                && p.required.keySet().stream().anyMatch(id -> Ids.path(id).endsWith("_bed")));
        if (bedBlocked && forceProject(level, "pen")) {
            colony.journal(level, "houses wait for beds and there is no wool: planned a pen first");
        }
    }

    public int maxProjects() {
        int builders = colony.desired().getOrDefault(Role.BUILDER, 0);
        return (builders >= 5 ? 3 : builders >= 3 ? 2 : 1) + TownHall.projectBonus(TownHall.stage(data()));
    }

    public boolean hasWork(ServerLevel level) {
        return !active().isEmpty() || !data().pendingRoad.isEmpty() || colony.improvements().hasWork(level);
    }

    public int backlog(ServerLevel level) {
        if (!hasWork(level)) {
            return 0;
        }
        int work = active().size() * 250;
        work += data().pendingRoad.size();
        if (colony.improvements().hasWork(level)) {
            work += data().orders.size();
        }
        return Math.max(1, work);
    }

    public void tick(ServerLevel level) {
        this.level = level;
        long now = level.getGameTime();
        colony.improvements().tick(level);
        dropDuplicates(level);
        for (ColonyData.Project project : new ArrayList<>(data().projects)) {
            if (project.phase.equals("reserve")) {
                reserve(level, project);
            }
        }
        if (now - lastPen >= 2400) {
            lastPen = now;
            unblockBeds(level);
        }
        if (now - lastPlan >= 400) {
            if (data().projects.isEmpty()) {
                lastPlan = now;
                plan(level, false);
            } else if (data().projects.size() < maxProjects()) {
                lastPlan = now;
                plan(level, true);
            } else if (data().projects.size() < maxProjects() + 1 && now - lastPlan >= 1200 && wardUrgent(level)) {
                lastPlan = now;
                plan(level, true, Hospital.blueprint(1));
            } else if (data().projects.size() < maxProjects() + 2 && now - lastPlan >= 1200
                    && data().projects.stream().allMatch(p -> p.phase.equals("reserve"))) {
                lastPlan = now;
                plan(level, true);
            }
        }
        paths.tick(level);
        lamps.tick(level);
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
        long plannedBeds = 0;
        for (ColonyData.Project project : data().projects) {
            if ("house".equals(project.building.kind)) {
                plannedBeds += Blueprints.get(project.building.blueprint).beds();
            }
        }
        boolean housingShort = beds + plannedBeds < population + 1;
        if (housingShort) {
            long houses = data().buildings.stream().filter(b -> "house".equals(b.kind)).count()
                    + data().projects.stream().filter(p -> "house".equals(p.building.kind)).count();
            order.add(adults < 6 ? "house_small" : houses % 2 == 0 ? "house_family" : "house_garden");
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
        Stock stock = colony.warehouse().stock(level);
        int rawIron = stock.get(Ids.mc("raw_iron")) + stock.get(Ids.mc("iron_ore")) + stock.get(Ids.mc("deepslate_iron_ore"));
        if (adults >= 6 && built(Tech.FORGE_BLUEPRINT) == 0 && (rawIron >= 6 || stock.get(Ids.mc("iron_ingot")) >= 12)) {
            order.add(Tech.FORGE_BLUEPRINT);
        }
        if (adults >= 7 && built(Tech.FLETCHER_BLUEPRINT) == 0 && colony.desired().getOrDefault(Role.FLETCHER, 0) > 0
                && CraftPlanner.plan(stock, Ids.mc("fletching_table"), 1).isPresent()) {
            order.add(Tech.FLETCHER_BLUEPRINT);
        }
        if (adults >= 8 && built(Tech.BAKERY_BLUEPRINT) == 0 && colony.desired().getOrDefault(Role.COOK, 0) > 0) {
            order.add(Tech.BAKERY_BLUEPRINT);
        }
        if (adults >= 10 && built("market") == 0) {
            order.add("market");
        }
        if (TavernRules.needed(adults, built(TavernRules.ID), TavernRules.foodStable(colony.foodPoints(level), adults))) {
            order.add(TavernRules.ID);
        }
        if (data().pen != null && built("barn") == 0 && stock.get(Ids.mc("wheat")) >= 80) {
            order.add("barn");
        }
        if (data().wall != null && built("watchtower") < 2 && (colony.defense().threatLevel() >= 0.4 || adults >= 12)) {
            order.add("watchtower");
        }
        int children = Nursery.childCount(level, colony);
        if (Kindergarten.needed(children, adults, built(Kindergarten.ID) > 0)) {
            order.add(Kindergarten.ID);
        }
        if (Schooling.needed(children, adults, data().buildings.stream().anyMatch(b -> Kindergarten.ID.equals(b.kind)),
                built(Schooling.ID) > 0)) {
            order.add(Schooling.ID);
        }
        if (GuardDrill.needed(guards, colony.defense().threatLevel(), adults, built(GuardDrill.ID) > 0)) {
            order.add(GuardDrill.ID);
        }
        if (adults >= 14 && built("chapel") == 0) {
            order.add("chapel");
        }
        long cemeteries = data().buildings.stream().filter(b -> Cemetery.ID.equals(b.blueprint)).count();
        long cemeteryProjects = data().projects.stream().filter(p -> Cemetery.ID.equals(p.building.blueprint)).count();
        if (colony.graveyard().needsCemetery(adults, (int) cemeteries, (int) cemeteryProjects)) {
            order.add(Cemetery.ID);
        }
        boolean waterVillage = colony.waterVillage(level);
        boolean fishers = colony.desired().getOrDefault(Role.FISHERMAN, 0) > 0;
        if (waterVillage && adults >= 5 && built(Pier.DOCK_ID) == 0 && (fishers || adults >= 10)) {
            order.add(Pier.DOCK_ID);
        }
        if (waterVillage && adults >= 9 && fishers && built(Pier.DOCK_ID) > 0 && built(Pier.FISHER_ID) == 0) {
            order.add(Pier.FISHER_ID);
        }
        boolean basics = data().buildings.stream().anyMatch(b -> "house".equals(b.kind))
                && data().buildings.stream().anyMatch(b -> "storage".equals(b.kind));
        if (TreeFarmPlan.needed(adults, (int) built(TreeFarmPlan.ID), basics, TreeFarmPlan.forestThin(colony.forest().count()))) {
            order.add(TreeFarmPlan.ID);
        }
        int hallStage = TownHall.next(TownHall.stage(data()), TownHall.inProgress(data()), adults, data().buildings.size(),
                !housingShort, Tech.ironAge(data().age), SmartColonyConfig.get().townHallAdults);
        if (hallStage > 0) {
            order.add(TownHall.blueprint(hallStage));
        }
        boolean incident = incident(level);
        int wardStage = Hospital.next(Hospital.stage(data()), Hospital.inProgress(data()), adults, data().buildings.size(),
                incident, SmartColonyConfig.get().infirmaryAdults);
        if (wardStage > 0 && satisfiable(level, Hospital.blueprint(wardStage), chooseWood(level))) {
            if (wardUrgent(level)) {
                order.addFirst(Hospital.blueprint(wardStage));
            } else {
                order.add(Hospital.blueprint(wardStage));
            }
        }
        List<String> ready = new ArrayList<>();
        for (String id : order) {
            if (cooldown.getOrDefault(id, Long.MIN_VALUE) <= now && !ready.contains(id)) {
                ready.add(id);
            }
        }
        return ready;
    }

    private boolean incident(ServerLevel level) {
        return data().stat("deaths") + data().stat("medic_wounded") + data().stat("first_injury") > 0 || woundedCount(level) > 0;
    }

    private boolean wardUrgent(ServerLevel level) {
        return Hospital.urgent(Hospital.stage(data()), Hospital.inProgress(data()), colony.adultCount(level), data().buildings.size(),
                incident(level), SmartColonyConfig.get().infirmaryAdults);
    }

    private int woundedCount(ServerLevel level) {
        int wounded = 0;
        for (UUID uuid : colony.colonists().keySet()) {
            if (level.getEntity(uuid) instanceof net.minecraft.world.entity.LivingEntity living && living.isAlive()
                    && living.getHealth() < living.getMaxHealth() * Hospital.ADMIT_AFTER_SHIFT) {
                wounded++;
            }
        }
        return wounded;
    }

    private long built(String blueprint) {
        long count = data().buildings.stream().filter(b -> blueprint.equals(b.blueprint)).count();
        count += data().projects.stream().filter(p -> blueprint.equals(p.building.blueprint)).count();
        return count;
    }

    private boolean satisfiable(ServerLevel level, String blueprintId, String wood) {
        Map<String, Integer> materials = new TreeMap<>(TownHallBuilder.materials(this, level, blueprintId, wood));
        materials.remove(Ids.mc("glass_pane"));
        Stock stock = colony.warehouse().stock(level);
        if (TreeFarmPlan.ID.equals(blueprintId)) {
            materials.remove(Ids.mc("dirt"));
        }
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
        Map<String, String> swaps = MaterialSwaps.propose(materials, Map.of(), Tech.ironAge(data().age),
                (item, count) -> CraftPlanner.plan(stock, item, count).isPresent());
        for (Map.Entry<String, String> swap : swaps.entrySet()) {
            int count = materials.remove(swap.getKey());
            Blueprint.Material replacement = Blueprint.material(MaterialSwaps.resolve(swap.getValue(), wood));
            if (replacement != null) {
                materials.merge(replacement.item(), replacement.count() * count, Integer::sum);
            }
        }
        return CraftPlanner.plan(stock, materials).isPresent();
    }

    private void plan(ServerLevel level, boolean extra) {
        plan(level, extra, null);
    }

    private void plan(ServerLevel level, boolean extra, String only) {
        List<String> needs = decideNeeds(level);
        if (only != null) {
            needs.removeIf(id -> !id.equals(only));
        }
        if (extra) {
            needs.removeIf(id -> !id.startsWith("house") && data().projects.stream().anyMatch(p -> p.building.blueprint.equals(id)));
        }
        if (needs.isEmpty()) {
            if (!extra) {
                status = "nothing to build";
                missing = new TreeMap<>();
            }
            return;
        }
        String wood = chooseWood(level);
        String need = extra ? null : needs.getFirst();
        for (String candidate : needs) {
            if (satisfiable(level, candidate, wood)) {
                need = candidate;
                break;
            }
        }
        if (need == null) {
            return;
        }
        Blueprint blueprint = Blueprints.get(need);
        ColonyData.Project project = Staged.isStaged(need) ? townHallProject(level, need, wood) : findSite(level, blueprint, wood);
        if (project == null) {
            status = "no flat land for " + need;
            cooldown.put(need, level.getGameTime() + 6000);
            return;
        }
        project.started = level.getGameTime();
        project.waitingSince = level.getGameTime();
        data().projects.add(project);
        recomputeRequired(level, project);
        markFootprint(level, project);
        colony.journal(level, "planned " + need + " at " + project.building.origin
                + (data().projects.size() > 1 ? " alongside " + (data().projects.size() - 1) + " other sites" : ""));
        colony.markDirty();
        reserve(level, project);
    }

    private static int woodStock(Stock stock, String wood) {
        return stock.get(Ids.mc(wood + "_log")) * 4 + stock.get(Ids.mc(wood + "_planks"));
    }

    public String preferredWood(ServerLevel level) {
        return chooseWood(level);
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

    void recomputeRequired(ServerLevel level, ColonyData.Project project) {
        if (project.upgrade && level != null) {
            project.required = TownHallBuilder.pending(this, level, project);
            return;
        }
        Map<String, Integer> required = new TreeMap<>();
        boolean farm = TreeFarmPlan.KIND.equals(project.building.kind);
        for (Blueprint.Cell cell : cells(project)) {
            Blueprint.Material material = Blueprint.material(cell.state());
            if (material != null && !(farm && alreadyPlaced(level, project, cell))) {
                required.merge(material.item(), material.count(), Integer::sum);
            }
        }
        for (Pos pos : project.fill) {
            required.merge(fillItem(project, pos), 1, Integer::sum);
        }
        project.required = required;
    }

    private boolean stilt(ColonyData.Project project, Pos pos) {
        if (project.stilts.isEmpty()) {
            return false;
        }
        Runtime rt = rt(project);
        if (rt.stiltSource != project.stilts || rt.stiltColumns == null) {
            rt.stiltSource = project.stilts;
            rt.stiltColumns = new HashSet<>();
            for (Pos column : project.stilts) {
                rt.stiltColumns.add(BlockPos.asLong(column.x(), 0, column.z()));
            }
        }
        return rt.stiltColumns.contains(BlockPos.asLong(pos.x(), 0, pos.z()));
    }

    private String fillItem(ColonyData.Project project, Pos pos) {
        if (stilt(project, pos)) {
            return Ids.mc(project.building.wood + "_log");
        }
        return TreeFarmPlan.KIND.equals(project.building.kind) ? Ids.mc("dirt") : Ids.mc("cobblestone");
    }

    private BlockState fillState(ColonyData.Project project, Pos pos) {
        if (stilt(project, pos)) {
            return parse(fillItem(project, pos), 0);
        }
        return TreeFarmPlan.KIND.equals(project.building.kind) ? Blocks.DIRT.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState();
    }

    private boolean alreadyPlaced(ServerLevel level, ColonyData.Project project, Blueprint.Cell cell) {
        BlockPos pos = world(project.building, cell.x(), cell.y(), cell.z());
        return WorldUtil.loaded(level, pos) && placed(level, project, cell);
    }

    List<Blueprint.Cell> cells(ColonyData.Project project) {
        return cells(project.building, project.swaps);
    }

    public List<Blueprint.Cell> cells(ColonyData.Building building, Map<String, String> swaps) {
        Blueprint blueprint = Blueprints.get(building.blueprint);
        List<Blueprint.Cell> result = new ArrayList<>();
        for (Blueprint.Cell cell : blueprint.cells(building.wood)) {
            String id = Blueprint.blockId(cell.state());
            String swap = swaps == null ? null : swaps.get(id);
            if (swap != null) {
                String props = cell.state().substring(id.length());
                String newState = Blueprint.resolve(swap, building.wood);
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

    private void reserve(ServerLevel level, ColonyData.Project project) {
        check.periodic(level, project);
        if (!data().projects.contains(project)) {
            return;
        }
        Stock stock = colony.warehouse().stock(level);
        Map<String, Integer> need = PreBuildCheck.deficit(project.required, project.reserved);
        var plan = CraftPlanner.plan(stock, need);
        if (plan.isEmpty() && applySwaps(level, project, stock)) {
            recomputeRequired(level, project);
            rt(project).cells = null;
            need = PreBuildCheck.deficit(project.required, project.reserved);
            plan = CraftPlanner.plan(stock, need);
        }
        if (plan.isPresent()) {
            if (check.enforce(level, project, false) != SiteCheck.Outcome.PROCEED) {
                return;
            }
            if (colony.warehouse().applyPlan(level, plan.get()) && colony.warehouse().takeAll(level, need)) {
                PreBuildCheck.surplus(project.required, project.reserved).forEach((id, count) -> colony.warehouse().give(level, id, count));
                project.reserved = new LinkedHashMap<>(project.required);
                project.phase = "clear";
                project.cursor = 0;
                rt(project).clearList = null;
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
        for (Map.Entry<String, Integer> entry : need.entrySet()) {
            int have = stock.get(entry.getKey());
            if (have < entry.getValue() && CraftPlanner.plan(stock, entry.getKey(), entry.getValue()).isEmpty()) {
                lack.put(entry.getKey(), entry.getValue() - have);
                colony.request(entry.getKey());
            }
        }
        missing = lack;
        status = "waiting for materials: " + lack;
        String richer = chooseWood(level);
        if (!project.upgrade && !richer.equals(project.building.wood) && woodStock(stock, richer) > woodStock(stock, project.building.wood) * 3 / 2 + 16) {
            colony.journal(level, project.building.blueprint + " switched from " + project.building.wood + " to " + richer);
            project.building.wood = richer;
            recomputeRequired(level, project);
            rt(project).cells = null;
            colony.markDirty();
            return;
        }
        long waited = level.getGameTime() - project.waitingSince;
        if (waited > 3000 && data().projects.size() >= maxProjects()) {
            String wood = chooseWood(level);
            for (String candidate : decideNeeds(level)) {
                if (!candidate.equals(project.building.blueprint) && satisfiable(level, candidate, wood)) {
                    colony.journal(level, "postponed " + project.building.blueprint + ", " + candidate + " can be built now");
                    cooldown.put(project.building.blueprint, level.getGameTime() + 6000);
                    drop(project);
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
            drop(project);
            missing = new TreeMap<>();
            colony.markDirty();
        }
    }

    private void drop(ColonyData.Project project) {
        data().projects.remove(project);
        runtime.remove(project);
        check.forget(project);
        assigned.values().removeIf(p -> p == project);
    }

    void markFootprint(ServerLevel level, ColonyData.Project project) {
        colony.survey().reserve(project.building.min.x(), project.building.min.z(), project.building.max.x(), project.building.max.z(),
                level.getGameTime());
    }

    void resetPlan(ColonyData.Project project) {
        Runtime rt = rt(project);
        rt.cells = null;
        rt.clearList = null;
        rt.clearIndex = 0;
        rt.stiltSource = null;
        rt.stiltColumns = null;
    }

    private void dropDuplicates(ServerLevel level) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ColonyData.Project project : new ArrayList<>(data().projects)) {
            String id = project.building.blueprint;
            if ("house".equals(project.building.kind) || project.upgrade) {
                continue;
            }
            if (!seen.add(id) && (project.phase.equals("reserve") || project.phase.equals("clear") && project.cursor == 0)) {
                colony.journal(level, "dropped a duplicate " + id + " site at " + project.building.origin);
                abandon(level, project);
                cooldown.remove(id);
                continue;
            }
            if (TreeFarmPlan.ID.equals(id) && project.phase.equals("reserve") && !nurseryStillWanted(level)) {
                colony.journal(level, "dropped the tree nursery site: the colony is too small for it yet");
                abandon(level, project);
            }
        }
    }

    private boolean nurseryStillWanted(ServerLevel level) {
        boolean basics = data().buildings.stream().anyMatch(b -> "house".equals(b.kind))
                && data().buildings.stream().anyMatch(b -> "storage".equals(b.kind));
        return TreeFarmPlan.needed(colony.adultCount(level), (int) built(TreeFarmPlan.ID), basics,
                TreeFarmPlan.forestThin(colony.forest().count()));
    }

    void abandon(ServerLevel level, ColonyData.Project project) {
        lowerCrane(level, project);
        project.reserved.forEach((id, count) -> colony.warehouse().give(level, id, count));
        project.reserved = new LinkedHashMap<>();
        cooldown.put(project.building.blueprint, level.getGameTime() + 6000);
        drop(project);
        missing = new TreeMap<>();
        colony.markDirty();
    }

    public com.google.gson.JsonArray siteChecks(ServerLevel level) {
        return check.describe(level);
    }

    private boolean applySwaps(ServerLevel level, ColonyData.Project project, Stock stock) {
        Map<String, String> proposed = MaterialSwaps.propose(project.required, project.swaps, Tech.ironAge(data().age),
                (item, count) -> CraftPlanner.plan(stock, item, count).isPresent());
        project.swaps.putAll(proposed);
        return !proposed.isEmpty();
    }

    ColonyData.Project findSite(ServerLevel level, Blueprint blueprint, String wood) {
        if (TreeFarmPlan.KIND.equals(blueprint.kind())) {
            return findSite(level, blueprint, wood, TreeFarmPlan.MAX_FILL, TreeFarmPlan.MAX_CUT);
        }
        ColonyData.Project easy = findSite(level, blueprint, wood, SiteEvaluator.MAX_FILL, SiteEvaluator.MAX_CUT);
        if (easy != null || !"house".equals(blueprint.kind())) {
            return easy;
        }
        return findSite(level, blueprint, wood, 4, Math.min(7, blueprint.sizeY() + 1));
    }

    private record Search(BlockPos origin, int minDistance, int maxDistance, int settle, BlockPos facing, BlockPos pull,
                          List<int[]> forbidden) {
    }

    private ColonyData.Project findSite(ServerLevel level, Blueprint blueprint, String wood, int fillLimit, int cutLimit) {
        BlockPos center = colony.center();
        return findSite(level, blueprint, wood, fillLimit, cutLimit,
                new Search(center, 7, data().radius + 28, 12, center, null, List.of()));
    }

    private ColonyData.Project findSite(ServerLevel level, Blueprint blueprint, String wood, int fillLimit, int cutLimit, Search search) {
        boolean wet = Pier.waterKind(blueprint.kind());
        BlockPos center = search.origin();
        BlockPos facing = search.facing();
        int maxDistance = search.maxDistance();
        double bestScore = Double.MAX_VALUE;
        ColonyData.Project best = null;
        for (int distance = search.minDistance(); distance <= maxDistance; distance += 3) {
            int steps = Math.max(12, distance * 2);
            for (int i = 0; i < steps; i++) {
                double angle = (i + (distance % 2) * 0.5) * Math.PI * 2.0 / steps;
                int px = center.getX() + (int) Math.round(Math.cos(angle) * distance);
                int pz = center.getZ() + (int) Math.round(Math.sin(angle) * distance);
                if (siteHint != null && Math.hypot(px - siteHint.getX(), pz - siteHint.getZ()) > 14) {
                    continue;
                }
                int rotation = Blueprint.rotationFacing(facing.getX() - px, facing.getZ() - pz);
                int sx = blueprint.rotatedSizeX(rotation);
                int sz = blueprint.rotatedSizeZ(rotation);
                int ox = px - sx / 2;
                int oz = pz - sz / 2;
                if (!search.forbidden().isEmpty() && PorterPlan.overlaps(ox, oz, ox + sx - 1, oz + sz - 1, search.forbidden())) {
                    continue;
                }
                if (sx * sz > 100 && !roughlyFlat(level, ox, oz, sx, sz, fillLimit + cutLimit)) {
                    continue;
                }
                SiteCheck.Sample sample = check.fromMap(ox, oz, sx, sz, 3, wet);
                boolean mapped = sample.status() != SiteCheck.Status.UNKNOWN;
                if (!mapped) {
                    sample = check.fromWorld(level, ox, oz, sx, sz, 3, null, wet);
                }
                if (sample.status() != SiteCheck.Status.OK) {
                    continue;
                }
                SiteEvaluator.Result result = SiteEvaluator.evaluate(sample.ground(), sample.blocked(), fillLimit, cutLimit, sample.water());
                if (!result.ok() || !Pier.enoughWater(blueprint.kind(), sample.waterColumns(), sx * sz)) {
                    continue;
                }
                double score = result.score() + distance * 0.6 + sample.paths() * 3 + pullScore(search, px, pz) + Pier.platformPenalty(sample.waterColumns());
                if (score >= bestScore) {
                    continue;
                }
                if (mapped) {
                    sample = check.fromWorld(level, ox, oz, sx, sz, 3, null, wet);
                    if (sample.status() != SiteCheck.Status.OK) {
                        continue;
                    }
                    result = SiteEvaluator.evaluate(sample.ground(), sample.blocked(), fillLimit, cutLimit, sample.water());
                    score = result.score() + distance * 0.6 + sample.paths() * 3 + pullScore(search, px, pz) + Pier.platformPenalty(sample.waterColumns());
                    if (!result.ok() || !Pier.enoughWater(blueprint.kind(), sample.waterColumns(), sx * sz) || score >= bestScore) {
                        continue;
                    }
                }
                if (!accessible(level, blueprint, rotation, ox, oz, result.baseY(), wet)) {
                    continue;
                }
                bestScore = score;
                best = project(blueprint, wood, ox, oz, rotation, result, sample.ground(), sample.columns());
            }
            if (best != null && distance > search.settle()) {
                break;
            }
        }
        return best;
    }

    private static double pullScore(Search search, int px, int pz) {
        return search.pull() == null ? 0.0 : PorterPlan.PULL_WEIGHT * Math.hypot(px - search.pull().getX(), pz - search.pull().getZ());
    }

    public boolean planOutpost(ServerLevel level, BlockPos anchor, int minRadius, int maxRadius, List<int[]> forbidden) {
        Blueprint blueprint = Blueprints.get(PorterPlan.OUTPOST_ID);
        String wood = chooseWood(level);
        if (!satisfiable(level, PorterPlan.OUTPOST_ID, wood)) {
            return false;
        }
        BlockPos depot = colony.depot();
        ColonyData.Project project = findSite(level, blueprint, wood, SiteEvaluator.MAX_FILL, SiteEvaluator.MAX_CUT,
                new Search(anchor, minRadius, maxRadius, minRadius + 4, depot, depot, forbidden));
        if (project == null) {
            return false;
        }
        project.started = level.getGameTime();
        project.waitingSince = level.getGameTime();
        data().projects.add(project);
        recomputeRequired(level, project);
        markFootprint(level, project);
        colony.journal(level, "planned an outpost at " + project.building.origin + " for the walk from " + anchor.toShortString()
                + " to the depot");
        colony.markDirty();
        reserve(level, project);
        return true;
    }

    private boolean roughlyFlat(ServerLevel level, int ox, int oz, int sx, int sz, int spread) {
        int low = Integer.MAX_VALUE;
        int high = Integer.MIN_VALUE;
        for (int x = 0; x < sx + 3; x += 4) {
            for (int z = 0; z < sz + 3; z += 4) {
                int wx = ox + Math.min(x, sx - 1);
                int wz = oz + Math.min(z, sz - 1);
                if (!WorldUtil.loaded(level, new BlockPos(wx, 0, wz))) {
                    return false;
                }
                int y = groundY(level, wx, wz);
                low = Math.min(low, y);
                high = Math.max(high, y);
                if (high - low > spread) {
                    return false;
                }
            }
        }
        return true;
    }

    boolean accessible(ServerLevel level, Blueprint blueprint, int rotation, int ox, int oz, int base) {
        return accessible(level, blueprint, rotation, ox, oz, base, Pier.waterKind(blueprint.kind()));
    }

    private boolean accessible(ServerLevel level, Blueprint blueprint, int rotation, int ox, int oz, int base, boolean wet) {
        for (int step = 1; step <= 2; step++) {
            int[] front = Blueprint.rotate(blueprint.doorX(), blueprint.doorZ() - step, blueprint.sizeX(), blueprint.sizeZ(), rotation);
            int x = ox + front[0];
            int z = oz + front[1];
            if (!WorldUtil.loaded(level, new BlockPos(x, 0, z))) {
                return false;
            }
            int y = groundY(level, x, z);
            BlockPos top = new BlockPos(x, y, z);
            if (Math.abs(y - base) > 1 || colony.warehouse().contains(top.above())) {
                return false;
            }
            if (!level.getFluidState(top).isEmpty() && !(wet && shallowWater(level, top))) {
                return false;
            }
        }
        return true;
    }

    private static boolean shallowWater(ServerLevel level, BlockPos top) {
        int depth = 0;
        BlockPos cursor = top;
        while (depth <= Pier.MAX_DEPTH + 1 && !level.getFluidState(cursor).isEmpty()) {
            cursor = cursor.below();
            depth++;
        }
        return Pier.shallow(depth) && level.getFluidState(top).isSourceOfType(net.minecraft.world.level.material.Fluids.WATER);
    }

    private int groundY(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        int floor = y - 8;
        while (y > floor && WorldUtil.removable(level, new BlockPos(x, y, z))) {
            y--;
        }
        return y;
    }

    ColonyData.Project project(Blueprint blueprint, String wood, int ox, int oz, int rotation,
                                       SiteEvaluator.Result result, int[][] ground) {
        return project(blueprint, wood, ox, oz, rotation, result, ground, null);
    }

    ColonyData.Project project(Blueprint blueprint, String wood, int ox, int oz, int rotation,
                                       SiteEvaluator.Result result, int[][] ground, TerrainMap.Column[][] columns) {
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
        boolean[][] floor = columns == null ? null : floorMask(blueprint, rotation, sx, sz);
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                if (columns != null && columns[x][z].surface() == TerrainMap.Surface.WATER) {
                    int bed = columns[x][z].ground();
                    project.stilts.add(new Pos(ox + x, bed, oz + z));
                    if (Pier.isPost(floor, x, z)) {
                        for (int y = bed + 1; y < base; y++) {
                            project.fill.add(new Pos(ox + x, y, oz + z));
                        }
                    }
                    continue;
                }
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

    private static boolean[][] floorMask(Blueprint blueprint, int rotation, int sx, int sz) {
        boolean[][] mask = new boolean[sx][sz];
        for (int x = 0; x < blueprint.sizeX(); x++) {
            for (int z = 0; z < blueprint.sizeZ(); z++) {
                int[] r = Blueprint.rotate(x, z, blueprint.sizeX(), blueprint.sizeZ(), rotation);
                mask[r[0]][r[1]] = blueprint.solidFloor(x, z);
            }
        }
        return mask;
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

    public BlockPos playground(ColonyData.Building building) {
        Blueprint blueprint = Blueprints.get(building.blueprint);
        return world(building, blueprint.doorX(), 1, Math.min(3, blueprint.sizeZ() - 1));
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
        int top = blueprint.sizeY() + 2 + (TreeFarmPlan.KIND.equals(blueprint.kind()) ? 1 : 0);
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
        if (project.upgrade) {
            list.addAll(TownHallBuilder.replaced(this, level, project));
            return list;
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

    private boolean claimed(BlockPos pos, long now) {
        Long at = claims.get(pos.asLong());
        return at != null && now - at < CLAIM_TICKS;
    }

    private Task claim(Task task, long now) {
        if (claims.size() > 64) {
            claims.values().removeIf(at -> now - at >= CLAIM_TICKS);
        }
        claims.put(task.pos().asLong(), now);
        return task;
    }

    private int load(ColonyData.Project project, long now) {
        int load = 0;
        for (Map.Entry<UUID, ColonyData.Project> entry : assigned.entrySet()) {
            Long at = assignedAt.get(entry.getKey());
            if (entry.getValue() == project && at != null && now - at < 1200) {
                load++;
            }
        }
        return load;
    }

    private Task roadTask(ServerLevel level, long now) {
        List<Pos> roads = data().pendingRoad;
        for (int i = 0; i < roads.size(); i++) {
            Pos pos = roads.get(i);
            if (!WorldUtil.loaded(level, new BlockPos(pos.x(), pos.y(), pos.z()))) {
                continue;
            }
            BlockPos ground = new BlockPos(pos.x(), paths.ground(level, pos.x(), pos.z()), pos.z());
            if (claimed(ground, now)) {
                continue;
            }
            return claim(new Task(Kind.ROAD, ground, null, null, null, ground.above()), now);
        }
        return null;
    }

    public Task next(ServerLevel level, UUID builder) {
        long now = level.getGameTime();
        if (!data().pendingRoad.isEmpty() && Math.floorMod(builder.hashCode(), 4) == 1) {
            Task road = roadTask(level, now);
            if (road != null) {
                return road;
            }
        }
        if (data().orders.size() > 40 && Math.floorMod(builder.hashCode(), 3) == 0) {
            Task order = colony.improvements().next(level);
            if (order != null) {
                return order;
            }
        }
        List<ColonyData.Project> active = active();
        if (!active.isEmpty()) {
            ColonyData.Project mine = assigned.get(builder);
            if (mine == null || !active.contains(mine)) {
                mine = active.getFirst();
                for (ColonyData.Project project : active) {
                    if (load(project, now) < load(mine, now)) {
                        mine = project;
                    }
                }
            }
            assigned.put(builder, mine);
            assignedAt.put(builder, now);
            List<ColonyData.Project> order = new ArrayList<>();
            order.add(mine);
            for (ColonyData.Project project : active) {
                if (project != mine) {
                    order.add(project);
                }
            }
            for (ColonyData.Project project : order) {
                if (!data().projects.contains(project)) {
                    continue;
                }
                Task task = projectTask(level, project, now);
                if (task != null) {
                    assigned.put(builder, project);
                    return claim(task, now);
                }
            }
        }
        Task road = roadTask(level, now);
        if (road != null) {
            return road;
        }
        return colony.improvements().next(level);
    }

    private Task projectTask(ServerLevel level, ColonyData.Project project, long now) {
        Runtime rt = rt(project);
        if (project.phase.equals("clear")) {
            if (rt.clearList == null) {
                rt.clearList = clearList(level, project);
                rt.clearIndex = 0;
            }
            List<BlockPos> list = rt.clearList;
            while (rt.clearIndex < list.size() && level.getBlockState(list.get(rt.clearIndex)).isAir()) {
                rt.clearIndex++;
            }
            for (int i = rt.clearIndex; i < list.size() && i < rt.clearIndex + LOOKAHEAD; i++) {
                BlockPos pos = list.get(i);
                if (level.getBlockState(pos).isAir() || claimed(pos, now)) {
                    continue;
                }
                return new Task(Kind.CLEAR, pos, null, null, null, groundStand(project, pos), project);
            }
            if (rt.clearIndex < list.size()) {
                return null;
            }
            if (check.enforce(level, project, true) != SiteCheck.Outcome.PROCEED) {
                return null;
            }
            rt.clearList = null;
            project.phase = "foundation";
            raiseCrane(level, project);
            project.cursor = 0;
            colony.markDirty();
        }
        if (project.phase.equals("foundation")) {
            while (project.cursor < project.fill.size() && filled(level, WorldUtil.bp(project.fill.get(project.cursor)))) {
                project.cursor++;
            }
            for (int i = project.cursor; i < project.fill.size() && i < project.cursor + LOOKAHEAD; i++) {
                BlockPos pos = WorldUtil.bp(project.fill.get(i));
                if (filled(level, pos) || claimed(pos, now)) {
                    continue;
                }
                return new Task(Kind.FILL, pos, fillState(project, project.fill.get(i)), null, null, groundStand(project, pos), project);
            }
            if (project.cursor < project.fill.size()) {
                return null;
            }
            project.phase = "build";
            project.cursor = 0;
            colony.markDirty();
        }
        if (project.phase.equals("build")) {
            if (rt.cells == null) {
                rt.cells = cells(project);
            }
            List<Blueprint.Cell> cells = rt.cells;
            while (project.cursor < cells.size() && placed(level, project, cells.get(project.cursor))) {
                project.cursor++;
            }
            for (int i = project.cursor; i < cells.size() && i < project.cursor + LOOKAHEAD; i++) {
                Blueprint.Cell cell = cells.get(i);
                BlockPos pos = world(project.building, cell.x(), cell.y(), cell.z());
                if (placed(level, project, cell) || claimed(pos, now)) {
                    continue;
                }
                BlockState target = parse(cell.state(), project.building.rotation);
                BlockPos partner = null;
                BlockState partnerState = null;
                if (target.getBlock() instanceof DoorBlock && target.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
                    partner = pos.above();
                    partnerState = target.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
                } else if (target.getBlock() instanceof BedBlock && target.getValue(BedBlock.PART) == BedPart.FOOT) {
                    partner = pos.relative(target.getValue(BedBlock.FACING));
                    partnerState = target.setValue(BedBlock.PART, BedPart.HEAD);
                }
                return new Task(Kind.PLACE, pos, target, partner, partnerState, groundStand(project, pos), project);
            }
            if (project.cursor < cells.size()) {
                return null;
            }
            finish(level, project);
            return null;
        }
        return null;
    }

    private static boolean filled(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !WorldUtil.isAirLike(state) && state.getFluidState().isEmpty();
    }

    boolean placed(ServerLevel level, ColonyData.Project project, Blueprint.Cell cell) {
        BlockPos pos = world(project.building, cell.x(), cell.y(), cell.z());
        BlockState target = parse(cell.state(), project.building.rotation);
        BlockState current = level.getBlockState(pos);
        if (TreeFarmPlan.KIND.equals(project.building.kind) && TreeFarm.soilFor(current, target)) {
            return true;
        }
        return current.getBlock() == target.getBlock() && sameShape(current, target);
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
        claims.remove(task.pos().asLong());
        ColonyData.Project project = task.project();
        switch (task.kind()) {
            case CLEAR -> {
                Runtime rt = project == null ? null : runtime.get(project);
                if (rt != null && rt.clearList != null && rt.clearIndex < rt.clearList.size() && rt.clearList.get(rt.clearIndex).equals(task.pos())) {
                    rt.clearIndex++;
                }
            }
            case FILL -> {
                if (project != null && project.cursor < project.fill.size() && WorldUtil.bp(project.fill.get(project.cursor)).equals(task.pos())) {
                    project.cursor++;
                }
            }
            case PLACE -> {
                data().bump("blocks_placed", 1);
                Runtime rt = project == null ? null : runtime.get(project);
                if (rt != null && rt.cells != null && project.cursor < rt.cells.size()) {
                    Blueprint.Cell cell = rt.cells.get(project.cursor);
                    if (world(project.building, cell.x(), cell.y(), cell.z()).equals(task.pos())) {
                        project.cursor++;
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
            case ORDER -> colony.improvements().completed(task);
        }
        colony.markDirty();
    }

    public void skip(Task task) {
        completed(task);
    }

    private void raiseCrane(ServerLevel level, ColonyData.Project project) {
        Blueprint blueprint = Blueprints.get(project.building.blueprint);
        ColonyData.Building building = project.building;
        String fence = Ids.mc(building.wood + "_fence");
        String planks = Ids.mc(building.wood + "_planks");
        int height = blueprint.sizeY() + 3;
        int arm = Math.min(7, Math.max(blueprint.sizeX(), blueprint.sizeZ()) / 2 + 3);
        int needed = height + arm + 2;
        int[][] corners = {{building.min.x() - 3, building.min.z() - 3, 1, 1}, {building.max.x() + 3, building.min.z() - 3, -1, 1},
                {building.min.x() - 3, building.max.z() + 3, 1, -1}, {building.max.x() + 3, building.max.z() + 3, -1, -1}};
        for (int[] corner : corners) {
            BlockPos ground = WorldUtil.surface(level, corner[0], corner[1]);
            if (!WorldUtil.loaded(level, ground) || !WorldUtil.solidFloor(level, ground.below()) || colony.isProtected(ground, false)) {
                continue;
            }
            List<BlockPos> mast = new ArrayList<>();
            boolean clear = true;
            for (int y = 0; y < height && clear; y++) {
                BlockPos pos = ground.above(y);
                clear = WorldUtil.isAirLike(level.getBlockState(pos));
                mast.add(pos);
            }
            BlockPos top = ground.above(height - 1);
            List<BlockPos> boom = new ArrayList<>();
            for (int i = 1; i <= arm && clear; i++) {
                BlockPos pos = top.offset(corner[2] * i, 0, 0);
                clear = WorldUtil.isAirLike(level.getBlockState(pos));
                boom.add(pos);
            }
            if (!clear) {
                continue;
            }
            Stock stock = colony.warehouse().stock(level);
            if (stock.get(fence) < needed && !colony.craft(level, fence, needed - stock.get(fence))) {
                return;
            }
            if (colony.stockOf(level, id -> id.equals(planks)) == 0 && !colony.craft(level, planks, 1)) {
                return;
            }
            if (colony.warehouse().take(level, fence, needed) < needed || colony.warehouse().take(level, planks, 1) < 1) {
                return;
            }
            BlockState fenceState = parse(fence, 0);
            List<BlockPos> all = new ArrayList<>(mast);
            all.addAll(boom);
            BlockPos end = boom.getLast();
            all.add(end.below());
            all.add(end.below(2));
            for (BlockPos pos : all) {
                org.lts.smartcolony.work.Placer.place(level, null, pos, fenceState);
                project.crane.add(WorldUtil.pos(pos));
            }
            BlockPos load = end.below(3);
            if (WorldUtil.isAirLike(level.getBlockState(load))) {
                org.lts.smartcolony.work.Placer.place(level, null, load, parse(planks, 0));
                project.crane.add(WorldUtil.pos(load));
            }
            colony.journal(level, "crane raised over the " + building.blueprint + " site");
            return;
        }
    }

    void lowerCrane(ServerLevel level, ColonyData.Project project) {
        int fences = 0;
        int planks = 0;
        for (Pos pos : project.crane) {
            BlockPos block = WorldUtil.bp(pos);
            BlockState state = level.getBlockState(block);
            if (state.is(net.minecraft.tags.BlockTags.WOODEN_FENCES)) {
                fences++;
            } else if (state.is(net.minecraft.tags.BlockTags.PLANKS)) {
                planks++;
            } else {
                continue;
            }
            level.setBlock(block, Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
        }
        if (fences > 0) {
            colony.warehouse().give(level, Ids.mc(project.building.wood + "_fence"), fences);
        }
        if (planks > 0) {
            colony.warehouse().give(level, Ids.mc(project.building.wood + "_planks"), planks);
        }
        project.crane.clear();
    }

    private void finish(ServerLevel level, ColonyData.Project project) {
        if (project.upgrade) {
            finishUpgrade(level, project);
            return;
        }
        ColonyData.Building building = project.building;
        lowerCrane(level, project);
        building.swaps = new LinkedHashMap<>(project.swaps);
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
        if (!project.stilts.isEmpty()) {
            building.onWater = true;
            data().bump("water_platforms", 1);
        }
        if (Pier.DOCK_KIND.equals(building.kind)) {
            data().bump("docks", 1);
        }
        drop(project);
        data().bump("buildings", 1);
        int reach = (int) Math.ceil(Math.sqrt(Math.max(building.min.horizontalDistSq(data().center), building.max.horizontalDistSq(data().center))));
        if (!PorterPlan.OUTPOST_KIND.equals(building.kind)) {
            data().radius = Math.min(SmartColonyConfig.get().maxColonyRadius, Math.max(data().radius, reach + 6));
        }
        if (Staged.isStaged(building.blueprint)) {
            TownHallBuilder.finished(level, colony, building);
        } else {
            colony.journal(level, "finished " + building.blueprint + " at " + building.origin);
            if (TreeFarmPlan.KIND.equals(building.kind)) {
                colony.journal(level, "tree nursery ready: " + colony.treeFarm().plots(building).size() + " sapling plots");
            }
            colony.notify(level, Component.translatable("smartcolony.chat.build_done",
                    Component.translatable("smartcolony.building." + building.blueprint)));
        }
        paths.onBuilt(level, building);
        colony.improvements().furnish(level, building);
        if (PorterPlan.OUTPOST_KIND.equals(building.kind)) {
            colony.outposts().built(level, building);
        }
        colony.markDirty();
    }

    public void planRoad(ServerLevel level, BlockPos from) {
        paths.addDoorTarget(level, from);
    }

    public JsonObject debug(ServerLevel level) {
        JsonObject json = new JsonObject();
        json.addProperty("status", status);
        JsonObject lack = new JsonObject();
        missing.forEach(lack::addProperty);
        json.add("missing", lack);
        com.google.gson.JsonArray sites = new com.google.gson.JsonArray();
        for (ColonyData.Project project : data().projects) {
            Runtime rt = runtime.get(project);
            JsonObject site = new JsonObject();
            site.addProperty("blueprint", project.building.blueprint);
            site.addProperty("phase", project.phase);
            site.addProperty("cursor", project.cursor);
            site.addProperty("cells", rt == null || rt.cells == null ? -1 : rt.cells.size());
            site.addProperty("clear", rt == null || rt.clearList == null ? -1 : rt.clearList.size() - rt.clearIndex);
            site.addProperty("origin", project.building.origin.toString());
            site.addProperty("rotation", project.building.rotation);
            site.addProperty("builders", load(project, level.getGameTime()));
            sites.add(site);
        }
        json.add("projects", sites);
        json.addProperty("maxProjects", maxProjects());
        json.add("paths", paths.debug());
        json.add("lamps", lamps.debug());
        json.addProperty("townHallStage", TownHall.stage(data()));
        return json;
    }

    public boolean forceProject(ServerLevel level, String blueprintId) {
        return forceProject(level, blueprintId, null);
    }

    public boolean forceProject(ServerLevel level, String blueprintId, BlockPos hint) {
        if (Staged.isStaged(blueprintId) || Staged.isFamily(blueprintId)) {
            return orderStaged(level, blueprintId) == Staged.Failure.NONE;
        }
        if (PorterPlan.OUTPOST_ID.equals(blueprintId)) {
            return false;
        }
        if (!blueprintId.startsWith("house") && data().projects.stream().anyMatch(p -> p.building.blueprint.equals(blueprintId))) {
            return false;
        }
        Blueprint blueprint = Blueprints.get(blueprintId);
        siteHint = hint;
        ColonyData.Project project;
        try {
            project = findSite(level, blueprint, chooseWood(level));
        } finally {
            siteHint = null;
        }
        if (project != null) {
            project.started = level.getGameTime();
            project.waitingSince = level.getGameTime();
            data().projects.add(project);
            recomputeRequired(level, project);
            markFootprint(level, project);
            colony.markDirty();
            return true;
        }
        return false;
    }

    public Staged.Failure orderStaged(ServerLevel level, String id) {
        String family = Staged.familyOf(id);
        if (family == null) {
            return Staged.Failure.NOT_STAGED;
        }
        boolean hall = TownHall.KIND.equals(family);
        Staged.Order order = Staged.order(id, hall ? TownHall.stage(data()) : Hospital.stage(data()),
                hall ? TownHall.inProgress(data()) : Hospital.inProgress(data()), Tech.ironAge(data().age));
        if (!order.ok()) {
            return order.failure();
        }
        ColonyData.Project project = townHallProject(level, order.blueprint(), chooseWood(level));
        if (project == null) {
            return Staged.Failure.NO_SITE;
        }
        project.started = level.getGameTime();
        project.waitingSince = level.getGameTime();
        data().projects.add(project);
        recomputeRequired(level, project);
        markFootprint(level, project);
        cooldown.remove(order.blueprint());
        colony.journal(level, "ordered " + order.blueprint() + " at " + project.building.origin);
        colony.markDirty();
        reserve(level, project);
        return Staged.Failure.NONE;
    }

    ColonyData colonyData() {
        return data();
    }

    public boolean upgrading(ColonyData.Building building) {
        for (ColonyData.Project project : data().projects) {
            if (project.upgrade && project.building.origin.equals(building.origin)) {
                return true;
            }
        }
        return false;
    }

    private ColonyData.Project townHallProject(ServerLevel level, String blueprintId, String wood) {
        ColonyData.Building standing = TownHallBuilder.standing(data(), blueprintId);
        if (standing != null) {
            return TownHallBuilder.upgrade(standing, blueprintId);
        }
        Blueprint blueprint = Blueprints.get(blueprintId);
        siteHint = colony.center();
        try {
            ColonyData.Project near = findSite(level, blueprint, wood);
            if (near != null) {
                return near;
            }
        } finally {
            siteHint = null;
        }
        return findSite(level, blueprint, wood);
    }

    private void finishUpgrade(ServerLevel level, ColonyData.Project project) {
        lowerCrane(level, project);
        ColonyData.Building standing = TownHallBuilder.standing(data(), project.building.blueprint);
        if (standing != null) {
            TownHallBuilder.apply(standing, project);
            data().bump(Hospital.stageOf(project.building.blueprint) > 0 ? "infirmary_upgrades" : "townhall_upgrades", 1);
            TownHallBuilder.finished(level, colony, standing);
        }
        drop(project);
        colony.markDirty();
    }
}
