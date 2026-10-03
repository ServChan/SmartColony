package org.lts.smartcolony.devbridge;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import org.lts.smartcolony.colony.Colony;
import org.lts.smartcolony.colony.ColonyManager;
import org.lts.smartcolony.colony.Founding;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.util.ItemIds;

public final class BridgeTools {
    private final MinecraftServer server;

    public BridgeTools(MinecraftServer server) {
        this.server = server;
    }

    static JsonObject text(String value, boolean error) {
        JsonObject result = new JsonObject();
        JsonArray content = new JsonArray();
        JsonObject item = new JsonObject();
        item.addProperty("type", "text");
        item.addProperty("text", value);
        content.add(item);
        result.add("content", content);
        result.addProperty("isError", error);
        return result;
    }

    private static JsonObject json(JsonElement element) {
        return text(new GsonBuilder().setPrettyPrinting().create().toJson(element), false);
    }

    private static JsonObject tool(String name, String description, JsonObject properties, String... required) {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", name);
        tool.addProperty("description", description);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", McpServer.array(required));
        tool.add("inputSchema", schema);
        return tool;
    }

    private static JsonObject props(String... pairs) {
        JsonObject object = new JsonObject();
        for (int i = 0; i < pairs.length; i += 3) {
            JsonObject prop = new JsonObject();
            prop.addProperty("type", pairs[i + 1]);
            prop.addProperty("description", pairs[i + 2]);
            if (pairs[i + 1].equals("array")) {
                JsonObject items = new JsonObject();
                items.addProperty("type", "string");
                prop.add("items", items);
            }
            object.add(pairs[i], prop);
        }
        return object;
    }

    public JsonArray list() {
        JsonArray tools = new JsonArray();
        tools.add(tool("status", "Server time, players and a short summary of every colony.", new JsonObject()));
        tools.add(tool("colony", "Full debug dump of a colony (data, stock, members with job status, construction, defense).",
                props("id", "string", "colony id, default first colony",
                        "section", "string", "optional top-level key: data, stock, fill, members, construction, defense, requests, desiredRoles")));
        tools.add(tool("brief", "Compact text overview of a colony: one line per member, stock, construction, mine, stats. Optionally waits N ticks first.",
                props("id", "string", "colony id", "ticks", "integer", "ticks to wait first (max 200000)")));
        tools.add(tool("command", "Run server commands as console and capture their output.",
                props("commands", "array", "commands without leading slash"), "commands"));
        tools.add(tool("wait", "Wait until N more server ticks have passed (use with /tick sprint), then return status.",
                props("ticks", "integer", "ticks to wait, max 200000", "timeoutSeconds", "integer", "max 110"), "ticks"));
        tools.add(tool("blocks", "Block states in a box as text layers (bottom to top). Volume limit 6000.",
                props("x1", "integer", "", "y1", "integer", "", "z1", "integer", "", "x2", "integer", "", "y2", "integer", "", "z2", "integer", ""),
                "x1", "y1", "z1", "x2", "y2", "z2"));
        tools.add(tool("topmap", "Top-down map of surface blocks around a point with a legend.",
                props("x", "integer", "", "z", "integer", "", "radius", "integer", "max 40"), "x", "z"));
        tools.add(tool("log", "Tail of logs/latest.log.", props("lines", "integer", "default 80", "contains", "string", "filter")));
        tools.add(tool("found", "Found a colony at the given block (normally a bell).",
                props("x", "integer", "", "y", "integer", "", "z", "integer", ""), "x", "y", "z"));
        tools.add(tool("role", "Lock a colonist to a role (lumberjack, miner, farmer, fisherman, herder, builder, guard, idle) or 'auto'.",
                props("uuid", "string", "uuid or prefix", "role", "string", ""), "uuid", "role"));
        tools.add(tool("give", "Put items straight into the colony storage.",
                props("item", "string", "item id", "count", "integer", "", "id", "string", "colony id"), "item", "count"));
        tools.add(tool("build", "Force the colony to plan a blueprint now (house_small, house_family, storage_shed, guard_post, pen).",
                props("blueprint", "string", "", "id", "string", "colony id"), "blueprint"));
        tools.add(tool("entities", "Entities near a point.",
                props("x", "integer", "", "y", "integer", "", "z", "integer", "", "radius", "integer", "max 96", "type", "string", "entity id filter"),
                "x", "y", "z"));
        return tools;
    }

    public JsonObject call(String name, JsonObject args) throws Exception {
        return switch (name) {
            case "status" -> onMain(this::status);
            case "colony" -> onMain(() -> colony(args));
            case "brief" -> brief(args);
            case "command" -> command(args);
            case "wait" -> waitTicks(args);
            case "blocks" -> onMain(() -> blocks(args));
            case "topmap" -> onMain(() -> topmap(args));
            case "log" -> log(args);
            case "found" -> onMain(() -> found(args));
            case "role" -> onMain(() -> role(args));
            case "give" -> onMain(() -> give(args));
            case "build" -> onMain(() -> build(args));
            case "entities" -> onMain(() -> entities(args));
            default -> text("unknown tool " + name, true);
        };
    }

    private JsonObject onMain(Supplier<JsonObject> supplier) throws Exception {
        return server.submit(supplier).get(30, TimeUnit.SECONDS);
    }

    private static int integer(JsonObject args, String key, int fallback) {
        return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsInt() : fallback;
    }

    private static String string(JsonObject args, String key, String fallback) {
        return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsString() : fallback;
    }

    private ServerLevel overworld() {
        return server.overworld();
    }

    private Colony pick(JsonObject args) {
        String id = string(args, "id", null);
        if (id != null) {
            return ColonyManager.byId(id);
        }
        return ColonyManager.all().stream().findFirst().orElse(null);
    }

    private JsonObject status() {
        JsonObject root = new JsonObject();
        ServerLevel level = overworld();
        root.addProperty("serverTicks", DevBridge.serverTicks());
        root.addProperty("gameTime", level.getGameTime());
        root.addProperty("dayTime", Math.floorMod(level.getDefaultClockTime(), 24000L));
        root.addProperty("tps", Math.round(1000.0 / Math.max(1.0, server.getCurrentSmoothedTickTime()) * 10) / 10.0);
        root.addProperty("mspt", Math.round(server.getCurrentSmoothedTickTime() * 100) / 100.0);
        JsonArray players = new JsonArray();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            JsonObject p = new JsonObject();
            p.addProperty("name", player.getName().getString());
            p.addProperty("pos", player.blockPosition().toShortString());
            p.addProperty("mode", player.gameMode().getName());
            players.add(p);
        }
        root.add("players", players);
        JsonArray colonies = new JsonArray();
        for (Colony colony : ColonyManager.all()) {
            JsonObject c = new JsonObject();
            c.addProperty("id", colony.id());
            c.addProperty("name", colony.data().name);
            c.addProperty("center", colony.center().toShortString());
            c.addProperty("depot", colony.depot().toShortString());
            c.addProperty("radius", colony.data().radius);
            c.addProperty("members", colony.colonists().size());
            c.addProperty("ticking", level.isPositionEntityTicking(colony.center()));
            c.addProperty("project", colony.data().project == null ? "none"
                    : colony.data().project.building.blueprint + "/" + colony.data().project.phase);
            JsonObject roles = new JsonObject();
            colony.colonists().values().forEach(colonist -> roles.addProperty(colonist.role().key(),
                    roles.has(colonist.role().key()) ? roles.get(colonist.role().key()).getAsInt() + 1 : 1));
            c.add("roles", roles);
            c.add("stats", com.google.gson.JsonParser.parseString(new com.google.gson.Gson().toJson(colony.data().stats)));
            colonies.add(c);
        }
        root.add("colonies", colonies);
        return json(root);
    }

    private JsonObject colony(JsonObject args) {
        Colony colony = pick(args);
        if (colony == null) {
            return text("no colony", true);
        }
        ServerLevel level = ColonyManager.levelOf(colony);
        JsonObject dump = colony.debugJson(level);
        String section = string(args, "section", null);
        if (section != null) {
            if (section.equals("data")) {
                JsonObject data = dump.getAsJsonObject("data").deepCopy();
                data.remove("journal");
                return json(data);
            }
            if (section.equals("journal")) {
                return json(dump.getAsJsonObject("data").get("journal"));
            }
            return dump.has(section) ? json(dump.get(section)) : text("no section " + section, true);
        }
        dump.getAsJsonObject("data").remove("journal");
        return json(dump);
    }

    private JsonObject brief(JsonObject args) throws Exception {
        int ticks = Math.min(200000, Math.max(0, integer(args, "ticks", 0)));
        if (ticks > 0) {
            long start = DevBridge.serverTicks();
            long deadline = System.currentTimeMillis() + 105_000L;
            while (DevBridge.serverTicks() - start < ticks && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
        }
        return onMain(() -> {
            Colony colony = pick(args);
            if (colony == null) {
                return text("no colony", true);
            }
            ServerLevel level = ColonyManager.levelOf(colony);
            JsonObject dump = colony.debugJson(level);
            StringBuilder out = new StringBuilder();
            out.append("t=").append(level.getGameTime()).append(" day=").append(Math.floorMod(level.getDefaultClockTime(), 24000L))
                    .append(" mspt=").append(Math.round(server.getCurrentSmoothedTickTime() * 100) / 100.0).append('\n');
            for (JsonElement element : dump.getAsJsonArray("members")) {
                JsonObject m = element.getAsJsonObject();
                out.append(String.format("%-10s %s %-14s %s | %s | hand=%s inv=%s%n", m.get("role").getAsString(),
                        m.get("uuid").getAsString().substring(0, 4), m.has("pos") ? m.get("pos").getAsString() : "-",
                        m.has("status") ? m.get("status").getAsString() : "unloaded", m.has("mover") ? m.get("mover").getAsString() : "",
                        m.has("hand") ? m.get("hand").getAsString().replace("minecraft:", "") : "",
                        m.has("inventory") ? m.get("inventory").toString().replace("minecraft:", "") : ""));
            }
            out.append("stock: ").append(dump.get("stock").toString().replace("minecraft:", "")).append('\n');
            out.append("construction: ").append(dump.get("construction")).append('\n');
            out.append("desired: ").append(dump.get("desiredRoles")).append(" requests: ").append(dump.get("requests")).append('\n');
            out.append("food=").append(dump.get("foodPoints")).append(" forest=").append(dump.get("forestTrees"))
                    .append(" threat=").append(dump.get("threat")).append('\n');
            JsonObject data = dump.getAsJsonObject("data");
            out.append("mine: ").append(data.has("mine") ? data.get("mine") : "none").append('\n');
            out.append("stats: ").append(data.get("stats")).append('\n');
            JsonArray journal = data.getAsJsonArray("journal");
            for (int i = Math.max(0, journal.size() - 6); i < journal.size(); i++) {
                out.append("  > ").append(journal.get(i).getAsString()).append('\n');
            }
            return text(out.toString(), false);
        });
    }

    private JsonObject command(JsonObject args) throws Exception {
        JsonArray results = new JsonArray();
        for (JsonElement element : args.getAsJsonArray("commands")) {
            String command = element.getAsString();
            if (command.startsWith("/")) {
                command = command.substring(1);
            }
            String finalCommand = command;
            List<String> output = new ArrayList<>();
            server.submit(() -> {
                CommandSource capture = new CommandSource() {
                    @Override
                    public void sendSystemMessage(Component message) {
                        output.add(message.getString());
                    }

                    @Override
                    public boolean acceptsSuccess() {
                        return true;
                    }

                    @Override
                    public boolean acceptsFailure() {
                        return true;
                    }

                    @Override
                    public boolean shouldInformAdmins() {
                        return false;
                    }
                };
                CommandSourceStack stack = server.createCommandSourceStack().withSource(capture);
                server.getCommands().performPrefixedCommand(stack, finalCommand);
                return null;
            }).get(30, TimeUnit.SECONDS);
            JsonObject item = new JsonObject();
            item.addProperty("command", command);
            JsonArray lines = new JsonArray();
            output.forEach(lines::add);
            item.add("output", lines);
            results.add(item);
        }
        return json(results);
    }

    private JsonObject waitTicks(JsonObject args) throws Exception {
        int ticks = Math.min(200000, Math.max(1, integer(args, "ticks", 20)));
        int timeout = Math.min(110, Math.max(1, integer(args, "timeoutSeconds", 100)));
        long start = DevBridge.serverTicks();
        long deadline = System.currentTimeMillis() + timeout * 1000L;
        while (DevBridge.serverTicks() - start < ticks && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        JsonObject result = onMain(this::status);
        String prefix = "waited " + (DevBridge.serverTicks() - start) + " of " + ticks + " ticks\n";
        JsonObject first = result.getAsJsonArray("content").get(0).getAsJsonObject();
        first.addProperty("text", prefix + first.get("text").getAsString());
        return result;
    }

    private JsonObject blocks(JsonObject args) {
        ServerLevel level = overworld();
        int x1 = Math.min(integer(args, "x1", 0), integer(args, "x2", 0));
        int x2 = Math.max(integer(args, "x1", 0), integer(args, "x2", 0));
        int y1 = Math.min(integer(args, "y1", 0), integer(args, "y2", 0));
        int y2 = Math.max(integer(args, "y1", 0), integer(args, "y2", 0));
        int z1 = Math.min(integer(args, "z1", 0), integer(args, "z2", 0));
        int z2 = Math.max(integer(args, "z1", 0), integer(args, "z2", 0));
        long volume = (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
        if (volume > 6000) {
            return text("volume " + volume + " exceeds 6000", true);
        }
        Map<String, Character> legend = new LinkedHashMap<>();
        String symbols = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@#$%^&*()-+=[]{}<>?/|";
        legend.put("minecraft:air", '.');
        StringBuilder out = new StringBuilder();
        for (int y = y1; y <= y2; y++) {
            out.append("y=").append(y).append(" (rows z=").append(z1).append("..").append(z2).append(", columns x=").append(x1).append("..").append(x2).append(")\n");
            for (int z = z1; z <= z2; z++) {
                for (int x = x1; x <= x2; x++) {
                    BlockState state = level.getBlockState(new BlockPos(x, y, z));
                    String key = state.isAir() ? "minecraft:air" : state.toString().replace("Block{", "").replace("}", "");
                    Character symbol = legend.get(key);
                    if (symbol == null) {
                        symbol = legend.size() - 1 < symbols.length() ? symbols.charAt(legend.size() - 1) : '?';
                        legend.put(key, symbol);
                    }
                    out.append(symbol);
                }
                out.append('\n');
            }
        }
        out.append("legend:\n");
        legend.forEach((key, symbol) -> out.append(symbol).append(" = ").append(key).append('\n'));
        return text(out.toString(), false);
    }

    private JsonObject topmap(JsonObject args) {
        ServerLevel level = overworld();
        int cx = integer(args, "x", 0);
        int cz = integer(args, "z", 0);
        int radius = Math.min(40, Math.max(1, integer(args, "radius", 16)));
        Map<String, Character> legend = new LinkedHashMap<>();
        String symbols = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder out = new StringBuilder();
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int z = cz - radius; z <= cz + radius; z++) {
            for (int x = cx - radius; x <= cx + radius; x++) {
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                BlockState state = level.getBlockState(new BlockPos(x, y, z));
                String key = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
                Character symbol = legend.get(key);
                if (symbol == null) {
                    symbol = legend.size() < symbols.length() ? symbols.charAt(legend.size()) : '?';
                    legend.put(key, symbol);
                }
                out.append(x == cx && z == cz ? '*' : symbol);
            }
            out.append('\n');
        }
        out.append("rows z=").append(cz - radius).append("..").append(cz + radius).append(", columns x=").append(cx - radius)
                .append("..").append(cx + radius).append(", surface y ").append(minY).append("..").append(maxY).append("\nlegend: ");
        legend.forEach((key, symbol) -> out.append(symbol).append('=').append(key).append(' '));
        return text(out.toString(), false);
    }

    private JsonObject log(JsonObject args) throws Exception {
        int lines = Math.min(600, Math.max(1, integer(args, "lines", 80)));
        String contains = string(args, "contains", null);
        Path file = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("latest.log");
        if (!Files.isRegularFile(file)) {
            return text("no log at " + file, true);
        }
        List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<String> filtered = contains == null ? all : all.stream().filter(line -> line.contains(contains)).toList();
        return text(String.join("\n", filtered.subList(Math.max(0, filtered.size() - lines), filtered.size())), false);
    }

    private JsonObject found(JsonObject args) {
        ServerLevel level = overworld();
        BlockPos pos = new BlockPos(integer(args, "x", 0), integer(args, "y", 0), integer(args, "z", 0));
        Colony colony = Founding.found(level, pos);
        if (colony == null) {
            return text("no site for the storage near " + pos.toShortString(), true);
        }
        return text("founded " + colony.id() + " " + colony.data().name + " members=" + colony.colonists().size()
                + " depot=" + colony.depot().toShortString(), false);
    }

    private JsonObject role(JsonObject args) {
        String prefix = string(args, "uuid", "");
        String key = string(args, "role", "auto");
        for (Colony colony : ColonyManager.all()) {
            ServerLevel level = ColonyManager.levelOf(colony);
            for (UUID uuid : colony.colonists().keySet()) {
                if (uuid.toString().startsWith(prefix) && level.getEntity(uuid) instanceof Villager villager) {
                    colony.lockRole(level, villager, key.equals("auto") ? null : Role.byKey(key));
                    return text("role of " + uuid + " set to " + key, false);
                }
            }
        }
        return text("no loaded colonist " + prefix, true);
    }

    private JsonObject give(JsonObject args) {
        Colony colony = pick(args);
        if (colony == null) {
            return text("no colony", true);
        }
        String item = string(args, "item", "");
        int count = integer(args, "count", 1);
        if (ItemIds.stack(item, 1).isEmpty()) {
            return text("unknown item " + item, true);
        }
        int overflow = colony.warehouse().give(ColonyManager.levelOf(colony), item, count);
        return text("gave " + count + " " + item + (overflow > 0 ? ", " + overflow + " dropped at the depot" : ""), false);
    }

    private JsonObject build(JsonObject args) {
        Colony colony = pick(args);
        if (colony == null) {
            return text("no colony", true);
        }
        if (colony.data().project != null) {
            return text("busy with " + colony.data().project.building.blueprint, true);
        }
        boolean ok = colony.construction().forceProject(ColonyManager.levelOf(colony), string(args, "blueprint", "house_small"));
        return text(ok ? "planned" : "no site", !ok);
    }

    private JsonObject entities(JsonObject args) {
        ServerLevel level = overworld();
        BlockPos pos = new BlockPos(integer(args, "x", 0), integer(args, "y", 64), integer(args, "z", 0));
        int radius = Math.min(96, Math.max(1, integer(args, "radius", 32)));
        String type = string(args, "type", null);
        JsonArray list = new JsonArray();
        for (Entity entity : level.getEntities((Entity) null, new AABB(pos).inflate(radius), e -> true)) {
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            if (type != null && !id.contains(type)) {
                continue;
            }
            JsonObject e = new JsonObject();
            e.addProperty("type", id);
            e.addProperty("uuid", entity.getUUID().toString());
            e.addProperty("pos", entity.blockPosition().toShortString());
            if (entity.hasCustomName()) {
                e.addProperty("name", entity.getCustomName().getString());
            }
            if (entity instanceof LivingEntity living) {
                e.addProperty("health", living.getHealth());
                e.addProperty("hand", ItemIds.id(living.getMainHandItem()));
            }
            if (entity instanceof Mob mob && mob.getTarget() != null) {
                e.addProperty("target", BuiltInRegistries.ENTITY_TYPE.getKey(mob.getTarget().getType()).getPath());
            }
            list.add(e);
            if (list.size() >= 200) {
                break;
            }
        }
        return json(list);
    }
}
