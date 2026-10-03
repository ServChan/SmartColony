package org.lts.smartcolony;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.loader.api.FabricLoader;

public final class SmartColonyConfig {
    public static final int CURRENT_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile SmartColonyConfig instance = new SmartColonyConfig();

    public int configVersion = CURRENT_VERSION;
    public boolean autoFound = true;
    public int minVillagersToFound = 3;
    public int colonyRadius = 32;
    public int maxColonyRadius = 64;
    public int workStart = 1000;
    public int workEnd = 10500;
    public boolean showRoleNames = true;
    public boolean notifyPlayers = true;
    public boolean stuckTeleport = true;
    public int blockPlaceTicks = 6;
    public int mineTargetY = 12;
    public int treeTarget = 12;
    public int forestRadius = 64;
    public int golemIronCost = 20;
    public int maxGolems = 4;
    public int villagersPerGolem = 6;
    public int animalsPerSpecies = 6;
    public int marketIntervalTicks = 2400;
    public float guardRetreatHealth = 0.35f;
    public int woodSurplus = 320;
    public boolean guardsAttackEnemyPlayers = true;

    public static SmartColonyConfig get() {
        return instance;
    }

    public static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("smartcolony.json");
    }

    public static void load() {
        Path file = path();
        SmartColonyConfig loaded = new SmartColonyConfig();
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                SmartColonyConfig parsed = GSON.fromJson(reader, SmartColonyConfig.class);
                if (parsed != null) {
                    loaded = parsed;
                }
            } catch (IOException | RuntimeException e) {
                SmartColony.LOGGER.warn("Smart Colony config is unreadable, using defaults: {}", e.toString());
            }
        }
        loaded.sanitize();
        instance = loaded;
        save(loaded);
    }

    private static void save(SmartColonyConfig config) {
        Path file = path();
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(config, writer);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            SmartColony.LOGGER.warn("Could not write Smart Colony config: {}", e.toString());
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    void sanitize() {
        configVersion = CURRENT_VERSION;
        minVillagersToFound = clamp(minVillagersToFound, 1, 50);
        colonyRadius = clamp(colonyRadius, 16, 96);
        maxColonyRadius = clamp(maxColonyRadius, colonyRadius, 128);
        workStart = clamp(workStart, 0, 23999);
        workEnd = clamp(workEnd, 0, 23999);
        blockPlaceTicks = clamp(blockPlaceTicks, 1, 100);
        mineTargetY = clamp(mineTargetY, -58, 100);
        treeTarget = clamp(treeTarget, 0, 200);
        forestRadius = clamp(forestRadius, 16, 96);
        golemIronCost = clamp(golemIronCost, 1, 640);
        maxGolems = clamp(maxGolems, 0, 16);
        villagersPerGolem = clamp(villagersPerGolem, 1, 50);
        animalsPerSpecies = clamp(animalsPerSpecies, 2, 32);
        marketIntervalTicks = clamp(marketIntervalTicks, 200, 72000);
        guardRetreatHealth = Math.max(0.05f, Math.min(0.9f, guardRetreatHealth));
        woodSurplus = clamp(woodSurplus, 64, 100000);
    }

    public boolean isWorkTime(long dayTime) {
        long t = Math.floorMod(dayTime, 24000L);
        if (workStart <= workEnd) {
            return t >= workStart && t < workEnd;
        }
        return t >= workStart || t < workEnd;
    }
}
