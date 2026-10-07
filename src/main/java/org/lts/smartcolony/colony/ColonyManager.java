package org.lts.smartcolony.colony;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.lts.smartcolony.SmartColony;
import org.lts.smartcolony.core.ColonyCodec;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.work.Colonist;

public final class ColonyManager {
    private static final Map<String, Colony> COLONIES = new LinkedHashMap<>();
    private static final Map<UUID, Colony> MEMBERS = new HashMap<>();
    private static final Map<BlockPos, Long> FAILED = new HashMap<>();
    private static MinecraftServer server;
    private static Path file;
    private static long ticks;
    private static ExecutorService io;

    private ColonyManager() {
    }

    public static MinecraftServer server() {
        return server;
    }

    public static Collection<Colony> all() {
        return COLONIES.values();
    }

    public static Colony byId(String id) {
        return COLONIES.get(id);
    }

    public static void start(MinecraftServer started) {
        server = started;
        COLONIES.clear();
        MEMBERS.clear();
        FAILED.clear();
        ticks = 0;
        io = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "SmartColony-IO");
            thread.setDaemon(true);
            return thread;
        });
        file = started.getWorldPath(LevelResource.ROOT).resolve("smartcolony").resolve("colonies.json");
        for (ColonyData data : load(file)) {
            register(data);
        }
        SmartColony.LOGGER.info("Smart Colony loaded {} colonies from {}", COLONIES.size(), file);
    }

    private static List<ColonyData> load(Path path) {
        for (Path candidate : List.of(path, path.resolveSibling(path.getFileName() + ".bak"))) {
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            try {
                return ColonyCodec.read(Files.readString(candidate, StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                SmartColony.LOGGER.error("Could not read {}: {}", candidate, e.toString());
            }
        }
        return new ArrayList<>();
    }

    public static void stop(MinecraftServer stopping) {
        for (Colony colony : COLONIES.values()) {
            ServerLevel level = levelOf(colony);
            if (level != null) {
                for (Villager villager : colony.loadedVillagers(level)) {
                    Colonist colonist = colony.colonists().get(villager.getUUID());
                    if (colonist != null && Colonist.isControlled(villager)) {
                        colonist.release(level, villager, "server stopping");
                    }
                }
            }
        }
        String json = snapshot();
        if (io != null) {
            io.shutdown();
            try {
                io.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        write(json);
        COLONIES.clear();
        MEMBERS.clear();
        server = null;
    }

    private static String snapshot() {
        List<ColonyData> list = new ArrayList<>();
        for (Colony colony : COLONIES.values()) {
            list.add(colony.data());
        }
        return ColonyCodec.write(list);
    }

    private static void write(String json) {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            if (Files.isRegularFile(file)) {
                Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            SmartColony.LOGGER.error("Could not save colonies: {}", e.toString());
        }
    }

    public static void saveAsync() {
        if (io == null || io.isShutdown()) {
            return;
        }
        String json = snapshot();
        io.execute(() -> write(json));
    }

    public static ServerLevel levelOf(Colony colony) {
        if (server == null) {
            return null;
        }
        Identifier id = Identifier.tryParse(colony.data().dimension);
        ResourceKey<Level> key = id == null ? Level.OVERWORLD : ResourceKey.create(Registries.DIMENSION, id);
        return server.getLevel(key);
    }

    public static void tick(MinecraftServer current) {
        if (server != current) {
            return;
        }
        ticks++;
        Founding.tickPreviews(current);
        boolean dirty = false;
        for (Colony colony : new ArrayList<>(COLONIES.values())) {
            ServerLevel level = levelOf(colony);
            if (level == null || !level.isPositionEntityTicking(colony.center())) {
                continue;
            }
            try {
                colony.tick(level);
            } catch (RuntimeException e) {
                SmartColony.LOGGER.error("Colony {} tick failed", colony.id(), e);
            }
            dirty |= colony.consumeDirty();
        }
        if (ticks % 200 == 50) {
            ServerLevel overworld = current.overworld();
            Founding.autoFound(overworld);
        }
        if (ticks % 6000 == 0 || (dirty && ticks % 1200 == 0)) {
            saveAsync();
        }
    }

    public static Colony register(ColonyData data) {
        Colony colony = new Colony(data);
        COLONIES.put(data.id, colony);
        for (UUID uuid : colony.colonists().keySet()) {
            MEMBERS.put(uuid, colony);
        }
        colony.markDirty();
        return colony;
    }

    public static void disband(ServerLevel level, Colony colony) {
        colony.disband(level);
        COLONIES.remove(colony.id());
        MEMBERS.values().removeIf(c -> c == colony);
        saveAsync();
    }

    public static void index(UUID uuid, Colony colony) {
        MEMBERS.put(uuid, colony);
    }

    public static void unindex(UUID uuid) {
        MEMBERS.remove(uuid);
    }

    public static Colony colonyOf(Villager villager) {
        return MEMBERS.get(villager.getUUID());
    }

    public static Colony colonyOf(UUID uuid) {
        return MEMBERS.get(uuid);
    }

    public static Colony colonyOfTrader(UUID uuid) {
        String id = uuid.toString();
        for (Colony colony : COLONIES.values()) {
            if (id.equals(colony.data().traderId)) {
                return colony;
            }
        }
        return null;
    }

    public static Colony colonyNear(ServerLevel level, BlockPos pos, int radius) {
        Colony best = null;
        double bestDistance = (double) radius * radius;
        String dimension = level.dimension().identifier().toString();
        for (Colony colony : COLONIES.values()) {
            if (!colony.data().dimension.equals(dimension)) {
                continue;
            }
            double dx = colony.center().getX() - pos.getX();
            double dz = colony.center().getZ() - pos.getZ();
            double distance = dx * dx + dz * dz;
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = colony;
            }
        }
        return best;
    }

    public static Colony colonyAt(ServerLevel level, BlockPos pos) {
        Colony colony = colonyNear(level, pos, 160);
        if (colony != null && colony.center().distToCenterSqr(pos.getX() + 0.5, colony.center().getY() + 0.5, pos.getZ() + 0.5) <= Math.pow(colony.data().radius + 24, 2)) {
            return colony;
        }
        return null;
    }

    public static Colony warehouseOwner(ServerLevel level, BlockPos pos) {
        for (Colony colony : COLONIES.values()) {
            if (colony.warehouse().contains(pos)) {
                return colony;
            }
        }
        return null;
    }

    public static boolean recentlyFailed(BlockPos bell) {
        Long at = FAILED.get(bell);
        return at != null && server != null && server.overworld().getGameTime() - at < 6000;
    }

    public static void markFailed(BlockPos bell, long time) {
        FAILED.put(bell.immutable(), time);
    }
}
