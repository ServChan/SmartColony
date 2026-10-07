package org.lts.smartcolony.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import org.lts.smartcolony.SmartColony;
import org.lts.smartcolony.SmartColonyConfig;
import org.lts.smartcolony.colony.Colony;
import org.lts.smartcolony.core.Blueprints;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.Ids;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.core.Tech;
import org.lts.smartcolony.work.Colonist;
import org.lts.smartcolony.work.Mover;

public final class Advisor {
    private static final int MAX_ACTIONS = 6;
    private static final Set<String> CRAFTABLE = Set.of(
            "wooden_pickaxe", "stone_pickaxe", "iron_pickaxe", "wooden_axe", "stone_axe", "iron_axe", "wooden_hoe", "stone_hoe",
            "iron_hoe", "wooden_sword", "stone_sword", "iron_sword", "wooden_shovel", "stone_shovel", "iron_shovel", "torch",
            "chest", "barrel", "furnace", "ladder", "bread", "glass_pane", "shears", "bucket", "fishing_rod", "bow", "arrow",
            "shield", "white_bed", "lantern", "hay_block", "iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots");

    private final Colony colony;
    private CompletableFuture<String> pending;
    private long lastAsk = -1000000;
    private boolean forced;
    private String state = "off";
    private String thoughts = "";
    private String error = "";
    private String lastPrompt = "";
    private String lastReply = "";
    private final List<String> feedback = new ArrayList<>();
    private final java.util.Set<String> lastStuck = new java.util.HashSet<>();
    private Map<Role, Integer> roleTargets = Map.of();
    private long rolesUntil;

    public Advisor(Colony colony) {
        this.colony = colony;
    }

    public String state() {
        return state;
    }

    public String thoughts() {
        return thoughts;
    }

    public String error() {
        return error;
    }

    public void askNow() {
        forced = true;
    }

    public boolean active() {
        return SmartColonyConfig.get().aiEnabled && !colony.data().aiOff;
    }

    public Map<Role, Integer> roleTargets(long now) {
        return now < rolesUntil ? roleTargets : Map.of();
    }

    public JsonObject debug() {
        JsonObject json = new JsonObject();
        json.addProperty("state", state);
        json.addProperty("thoughts", thoughts);
        json.addProperty("error", error);
        json.addProperty("lastAsk", lastAsk);
        JsonObject targets = new JsonObject();
        roleTargets.forEach((role, count) -> targets.addProperty(role.key(), count));
        json.add("roleTargets", targets);
        json.addProperty("rolesUntil", rolesUntil);
        JsonArray results = new JsonArray();
        feedback.forEach(results::add);
        json.add("feedback", results);
        json.addProperty("prompt", lastPrompt);
        json.addProperty("reply", lastReply);
        return json;
    }

    public void tick(ServerLevel level) {
        SmartColonyConfig config = SmartColonyConfig.get();
        if (pending != null) {
            if (!pending.isDone()) {
                return;
            }
            CompletableFuture<String> done = pending;
            pending = null;
            lastAsk = level.getGameTime();
            try {
                lastReply = done.join();
                apply(level, lastReply);
                state = "ok";
                error = "";
            } catch (RuntimeException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
                if (!message.equals(error)) {
                    colony.journal(level, "advisor unavailable: " + message);
                    SmartColony.LOGGER.warn("Smart Colony advisor for {} failed: {}", colony.data().name, message);
                }
                state = "error";
                error = message;
            }
            return;
        }
        if (!active()) {
            state = "off";
            return;
        }
        long now = level.getGameTime();
        if (!forced && now - lastAsk < config.aiIntervalTicks) {
            if (state.equals("off")) {
                state = "idle";
            }
            return;
        }
        forced = false;
        lastAsk = now;
        state = "asking";
        lastPrompt = snapshot(level).toString();
        pending = AiClient.chat(config, AdvisorPrompt.system(config.aiLanguage), lastPrompt);
    }

    private JsonObject snapshot(ServerLevel level) {
        ColonyData data = colony.data();
        JsonObject root = new JsonObject();
        root.addProperty("colony", data.name);
        root.addProperty("day", level.getGameTime() / 24000L);
        root.addProperty("timeOfDay", Math.floorMod(level.getDefaultClockTime(), 24000L));
        root.addProperty("ageDays", (level.getGameTime() - data.founded) / 24000L);
        root.addProperty("center", pos(colony.center()));
        root.addProperty("radius", data.radius);
        root.addProperty("techAge", data.age);
        root.addProperty("forgeBuilt", data.buildings.stream().anyMatch(b -> Tech.FORGE_BLUEPRINT.equals(b.blueprint)));
        int beds = (int) level.getPoiManager().getCountInRange(type -> type.is(net.minecraft.world.entity.ai.village.poi.PoiTypes.HOME),
                colony.center(), data.radius + 16, net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY);
        root.addProperty("beds", beds);
        EnumMap<Role, Integer> counts = new EnumMap<>(Role.class);
        int children = 0;
        JsonArray stuck = new JsonArray();
        lastStuck.clear();
        for (Map.Entry<UUID, Colonist> entry : colony.colonists().entrySet()) {
            Colonist colonist = entry.getValue();
            if (level.getEntity(entry.getKey()) instanceof Villager villager) {
                if (villager.isBaby()) {
                    children++;
                    continue;
                }
                String status = colonist.describe(level);
                if (colonist.mover().failures() >= 3 || status.contains("lost") || status.contains("stuck")) {
                    JsonObject person = new JsonObject();
                    person.addProperty("id", entry.getKey().toString().substring(0, 8));
                    lastStuck.add(entry.getKey().toString().substring(0, 8));
                    person.addProperty("role", colonist.role().key());
                    person.addProperty("pos", pos(villager.blockPosition()));
                    person.addProperty("status", status.length() > 60 ? status.substring(0, 60) : status);
                    person.addProperty("pathFailures", colonist.mover().failures());
                    stuck.add(person);
                }
            }
            counts.merge(colonist.role(), 1, Integer::sum);
        }
        JsonObject roles = new JsonObject();
        counts.forEach((role, count) -> roles.addProperty(role.key(), count));
        root.add("roles", roles);
        root.addProperty("children", children);
        JsonObject planned = new JsonObject();
        colony.desired().forEach((role, count) -> planned.addProperty(role.key(), count));
        root.add("plannedRoles", planned);
        root.add("stuckVillagers", stuck);
        Map<String, Integer> stock = new TreeMap<>(colony.warehouse().stock(level).sorted());
        List<Map.Entry<String, Integer>> items = new ArrayList<>(stock.entrySet());
        items.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        JsonObject stockJson = new JsonObject();
        for (Map.Entry<String, Integer> item : items.subList(0, Math.min(45, items.size()))) {
            stockJson.addProperty(Ids.path(item.getKey()), item.getValue());
        }
        root.add("stock", stockJson);
        root.addProperty("foodPoints", colony.foodPoints(level));
        root.addProperty("threat", Math.round(colony.defense().threatLevel() * 100) / 100.0);
        JsonArray projects = new JsonArray();
        for (ColonyData.Project project : colony.construction().projects()) {
            JsonObject site = new JsonObject();
            site.addProperty("blueprint", project.building.blueprint);
            site.addProperty("phase", project.phase);
            site.addProperty("at", pos(org.lts.smartcolony.util.WorldUtil.bp(project.building.origin)));
            projects.add(site);
        }
        root.add("buildingSites", projects);
        JsonObject missing = new JsonObject();
        colony.construction().missing().forEach((id, count) -> missing.addProperty(Ids.path(id), count));
        root.add("missingForWaitingSite", missing);
        JsonObject built = new JsonObject();
        for (ColonyData.Building building : data.buildings) {
            built.addProperty(building.blueprint, built.has(building.blueprint) ? built.get(building.blueprint).getAsInt() + 1 : 1);
        }
        root.add("buildings", built);
        JsonArray blueprints = new JsonArray();
        Blueprints.IDS.stream().filter(id -> !org.lts.smartcolony.core.Staged.isStaged(id)).forEach(blueprints::add);
        root.add("availableBlueprints", blueprints);
        int hallStage = org.lts.smartcolony.core.TownHall.stage(data);
        root.addProperty("townHallStage", hallStage);
        if (hallStage < org.lts.smartcolony.core.TownHall.STAGES) {
            root.addProperty("townHallNextStageNeedsAdults", org.lts.smartcolony.core.TownHall.requiredAdults(hallStage + 1,
                    org.lts.smartcolony.SmartColonyConfig.get().townHallAdults));
        }
        root.addProperty("maxParallelSites", colony.construction().maxProjects());
        if (data.mine != null) {
            root.addProperty("mine", data.mine.type + " " + data.mine.progress + "/" + data.mine.plan().size());
        }
        if (data.wall != null) {
            root.addProperty("wall", data.wall.material + " " + data.wall.built + "/" + data.wall.total);
        }
        root.addProperty("pendingOrders", data.orders.size());
        JsonArray requests = new JsonArray();
        colony.requests().stream().limit(12).forEach(id -> requests.add(Ids.path(id)));
        root.add("requests", requests);
        JsonArray journal = new JsonArray();
        List<String> lines = data.journal;
        for (String line : lines.subList(Math.max(0, lines.size() - 10), lines.size())) {
            journal.add(line);
        }
        root.add("recentJournal", journal);
        JsonArray previous = new JsonArray();
        feedback.forEach(previous::add);
        root.add("resultsOfYourLastActions", previous);
        root.add("activeRoleTargets", roleTargetsJson(level.getGameTime()));
        JsonArray hints = new JsonArray();
        int population = colony.colonists().size();
        if (beds < population + 2) {
            hints.add("beds " + beds + " for " + population + " villagers: more houses would let the colony grow");
        }
        long waiting = colony.construction().projects().stream().filter(p -> p.phase.equals("reserve")).count();
        if (waiting > 0 && !colony.construction().missing().isEmpty()) {
            hints.add(waiting + " site(s) wait for " + missing + ": craft or gather that before starting more sites");
        }
        if (!stuck.isEmpty()) {
            hints.add(stuck.size() + " villager(s) cannot find a path: rescue them by id");
        }
        if (colony.foodPoints(level) < 150) {
            hints.add("food is low: more farmers or fishermen");
        }
        if (colony.defense().threatLevel() >= 0.5 && counts.getOrDefault(Role.GUARD, 0) < population / 5) {
            hints.add("monsters around and few guards");
        }
        root.add("hints", hints);
        return root;
    }

    private JsonObject roleTargetsJson(long now) {
        JsonObject json = new JsonObject();
        roleTargets(now).forEach((role, count) -> json.addProperty(role.key(), count));
        return json;
    }

    private static String pos(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    void apply(ServerLevel level, String reply) {
        String text = reply.trim();
        int open = text.indexOf('{');
        int close = text.lastIndexOf('}');
        if (open < 0 || close < open) {
            throw new IllegalStateException("answer is not JSON");
        }
        JsonObject root = JsonParser.parseString(text.substring(open, close + 1)).getAsJsonObject();
        thoughts = root.has("thoughts") && root.get("thoughts").isJsonPrimitive() ? root.get("thoughts").getAsString().trim() : "";
        if (thoughts.length() > 400) {
            thoughts = thoughts.substring(0, 400);
        }
        if (!thoughts.isEmpty()) {
            colony.journal(level, "advisor: " + thoughts);
        }
        feedback.clear();
        JsonArray actions = root.has("actions") && root.get("actions").isJsonArray() ? root.getAsJsonArray("actions") : new JsonArray();
        int count = 0;
        for (JsonElement element : actions) {
            if (count++ >= MAX_ACTIONS) {
                feedback.add("ignored: at most " + MAX_ACTIONS + " actions");
                break;
            }
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject action = element.getAsJsonObject();
            String type = string(action, "type").toLowerCase(Locale.ROOT);
            String result;
            try {
                result = act(level, type, action);
            } catch (RuntimeException e) {
                result = "rejected: " + e.getMessage();
            }
            feedback.add(type + " -> " + result);
            colony.journal(level, "advisor " + type + ": " + result);
        }
        colony.markDirty();
    }

    private String act(ServerLevel level, String type, JsonObject action) {
        SmartColonyConfig config = SmartColonyConfig.get();
        return switch (type) {
            case "roles" -> config.aiCanAssignRoles ? roles(level, action) : "rejected: roles are disabled in the config";
            case "build" -> config.aiCanBuild ? build(level, action) : "rejected: building is disabled in the config";
            case "rescue" -> config.aiCanRescue ? rescue(level, action) : "rejected: rescue is disabled in the config";
            case "craft" -> config.aiCanCraft ? craft(level, action) : "rejected: crafting is disabled in the config";
            case "note" -> "noted";
            default -> "rejected: unknown action";
        };
    }

    private String roles(ServerLevel level, JsonObject action) {
        if (!action.has("targets") || !action.get("targets").isJsonObject()) {
            return "rejected: targets missing";
        }
        Map<Role, Integer> targets = new EnumMap<>(Role.class);
        for (Map.Entry<String, JsonElement> entry : action.getAsJsonObject("targets").entrySet()) {
            Role role = Role.byKey(entry.getKey().toLowerCase(Locale.ROOT));
            if (role == Role.IDLE || role == Role.UNDERTAKER || !entry.getValue().isJsonPrimitive()) {
                continue;
            }
            targets.put(role, Math.max(0, entry.getValue().getAsInt()));
        }
        if (targets.isEmpty()) {
            return "rejected: no known roles";
        }
        if (targets.size() > 3) {
            return "rejected: steer at most 3 roles at once, the colony balances the rest";
        }
        roleTargets = targets;
        rolesUntil = level.getGameTime() + 2L * SmartColonyConfig.get().aiIntervalTicks;
        colony.planRoles(level);
        return "role targets set " + targets;
    }

    private String build(ServerLevel level, JsonObject action) {
        String blueprint = string(action, "blueprint");
        if (!Blueprints.IDS.contains(blueprint)) {
            return "rejected: unknown blueprint " + blueprint;
        }
        if (colony.construction().projects().size() >= 3) {
            return "rejected: already 3 building sites";
        }
        BlockPos hint = null;
        if (action.has("x") && action.has("z")) {
            int x = action.get("x").getAsInt();
            int z = action.get("z").getAsInt();
            BlockPos center = colony.center();
            double distance = Math.hypot(x - center.getX(), z - center.getZ());
            if (distance > colony.data().radius + 28) {
                return "rejected: location is too far from the colony";
            }
            hint = new BlockPos(x, center.getY(), z);
        }
        boolean ok = colony.construction().forceProject(level, blueprint, hint);
        return ok ? "site planned for " + blueprint : "rejected: no free flat land" + (hint == null ? "" : " near the hint");
    }

    private String rescue(ServerLevel level, JsonObject action) {
        String id = string(action, "villager").toLowerCase(Locale.ROOT);
        if (id.length() < 4) {
            return "rejected: villager id too short";
        }
        for (Map.Entry<UUID, Colonist> entry : colony.colonists().entrySet()) {
            if (!entry.getKey().toString().startsWith(id) || !(level.getEntity(entry.getKey()) instanceof Villager villager)) {
                continue;
            }
            Colonist colonist = entry.getValue();
            String status = colonist.describe(level);
            if (!lastStuck.contains(entry.getKey().toString().substring(0, 8)) && colonist.mover().failures() < 2
                    && !status.contains("lost") && !status.contains("stuck")) {
                return "rejected: villager is not stuck";
            }
            if (Mover.rescue(level, villager, colony.depot().above())) {
                colonist.mover().reset();
                colony.data().bump("advisor_rescues", 1);
                return "moved to the storage";
            }
            return "rejected: no free spot at the storage";
        }
        return "rejected: no such villager";
    }

    private String craft(ServerLevel level, JsonObject action) {
        String item = Ids.path(string(action, "item"));
        if (item.equals("bed")) {
            item = "white_bed";
        }
        int count = action.has("count") ? action.get("count").getAsInt() : 1;
        if (!CRAFTABLE.contains(item) && !item.endsWith("_bed")) {
            return "rejected: " + item + " is not on the craft list";
        }
        count = Math.max(1, Math.min(16, count));
        String id = Ids.mc(item);
        int have = colony.stockOf(level, found -> found.equals(id));
        if (have >= 4) {
            return "rejected: the storage already holds " + have + " " + item;
        }
        return colony.craft(level, Ids.mc(item), count) ? "crafted " + count + " " + item : "rejected: not enough materials for " + item;
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }
}
