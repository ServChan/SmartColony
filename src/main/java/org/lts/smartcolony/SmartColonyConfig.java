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
    public int surveyMargin = 32;
    public int surveyColumnsPerTick = 96;
    public int surveyRefreshPerTick = 12;
    public int workStart = 1000;
    public int workEnd = 10500;
    public boolean showRoleNames = true;
    public boolean notifyPlayers = true;
    public boolean stuckTeleport = true;
    public boolean shelterInRain = true;
    public float rainWorkRatio = 0.0f;
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
    public int[] townHallAdults = {4, 10, 18, 28, 40};
    public int[] infirmaryAdults = {8, 16, 30};
    public int adultsPerMedic = 15;
    public boolean guardsAttackEnemyPlayers = true;
    public boolean lunchBreak = true;
    public boolean eveningGathering = true;
    public int restDayEvery = 7;
    public boolean firefighting = true;
    public boolean snowClearing = true;
    public boolean caravans = true;
    public boolean recruitFromVillages = true;
    public boolean aiEnabled = false;
    public String aiApi = "ollama";
    public String aiEndpoint = "http://127.0.0.1:11434";
    public String aiModel = "gemma4:latest";
    public String aiApiKey = "";
    public String aiLanguage = "ru";
    public int aiIntervalTicks = 3600;
    public int aiTimeoutSeconds = 120;
    public boolean aiCanAssignRoles = true;
    public boolean aiCanBuild = true;
    public boolean aiCanCraft = true;
    public boolean aiCanRescue = true;

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
                SmartColony.LOGGER.warn("Could not read {}; using defaults", file, e);
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
            SmartColony.LOGGER.warn("Could not save {}", file, e);
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
        surveyMargin = clamp(surveyMargin, 0, 64);
        surveyColumnsPerTick = clamp(surveyColumnsPerTick, 8, 512);
        surveyRefreshPerTick = clamp(surveyRefreshPerTick, 0, 128);
        workStart = clamp(workStart, 0, 23999);
        workEnd = clamp(workEnd, 0, 23999);
        rainWorkRatio = Math.max(0.0f, Math.min(1.0f, rainWorkRatio));
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
        townHallAdults = org.lts.smartcolony.core.TownHall.thresholds(townHallAdults);
        infirmaryAdults = org.lts.smartcolony.core.Hospital.thresholds(infirmaryAdults);
        adultsPerMedic = clamp(adultsPerMedic, 3, 100);
        restDayEvery = restDayEvery <= 1 ? 0 : clamp(restDayEvery, 2, 30);
        aiApi = aiApi == null || !aiApi.trim().equalsIgnoreCase("openai") ? "ollama" : "openai";
        aiEndpoint = aiEndpoint == null || aiEndpoint.isBlank() ? "http://127.0.0.1:11434" : aiEndpoint.trim();
        aiModel = aiModel == null || aiModel.isBlank() ? "gemma4:latest" : aiModel.trim();
        aiApiKey = aiApiKey == null ? "" : aiApiKey.trim();
        aiLanguage = aiLanguage == null || aiLanguage.isBlank() ? "ru" : aiLanguage.trim();
        aiIntervalTicks = clamp(aiIntervalTicks, 400, 72000);
        aiTimeoutSeconds = clamp(aiTimeoutSeconds, 5, 600);
    }

    public boolean isWorkTime(long dayTime) {
        long t = Math.floorMod(dayTime, 24000L);
        if (workStart <= workEnd) {
            return t >= workStart && t < workEnd;
        }
        return t >= workStart || t < workEnd;
    }
}
