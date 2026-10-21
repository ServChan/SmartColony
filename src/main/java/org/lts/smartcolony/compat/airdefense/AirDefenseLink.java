package org.lts.smartcolony.compat.airdefense;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import org.lts.smartcolony.ModLog;

public final class AirDefenseLink {
    public static final int API_VERSION = 1;
    private static final ModLog LOG = ModLog.of("AirDefense");
    private static final String API_CLASS = "org.lts.airdefense.api.ColonyLink";

    private static boolean resolved;
    private static boolean available;
    private static Method present;
    private static Method positions;
    private static Method airThreats;
    private static Method dangerousAirThreats;
    private static Method soundSirens;
    private static Method ammoNeeds;
    private static Method deliver;
    private static Method recipes;

    private AirDefenseLink() {
    }

    public static synchronized boolean available() {
        if (!resolved) {
            resolved = true;
            available = resolve();
        }
        return available;
    }

    private static boolean resolve() {
        if (!FabricLoader.getInstance().isModLoaded("airdefense")) {
            return false;
        }
        try {
            Class<?> api = Class.forName(API_CLASS);
            int version = api.getField("API_VERSION").getInt(null);
            if (version != API_VERSION) {
                LOG.warn("Air Defense link turned off: API version {} found, {} expected", version, API_VERSION);
                return false;
            }
            present = api.getMethod("present", ServerLevel.class, BlockPos.class, int.class);
            positions = api.getMethod("positions", ServerLevel.class, BlockPos.class, int.class);
            airThreats = api.getMethod("airThreats", ServerLevel.class, BlockPos.class, double.class);
            dangerousAirThreats = optional(api, "dangerousAirThreats", ServerLevel.class, BlockPos.class, double.class);
            soundSirens = api.getMethod("soundSirens", ServerLevel.class, BlockPos.class, int.class);
            ammoNeeds = api.getMethod("ammoNeeds", ServerLevel.class, BlockPos.class, int.class);
            deliver = api.getMethod("deliver", ServerLevel.class, BlockPos.class, int.class, ItemStack.class);
            recipes = api.getMethod("recipes");
            LOG.info("Air Defense link on: colonies supply and use air defense installations");
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            LOG.warn("Air Defense link turned off: {}", ModLog.describe(failure));
            return false;
        }
    }

    private static Method optional(Class<?> api, String name, Class<?>... parameters) {
        try {
            return api.getMethod(name, parameters);
        } catch (NoSuchMethodException missing) {
            return null;
        }
    }

    public static int dangerousAirThreats(ServerLevel level, BlockPos center, double radius) {
        if (!available()) {
            return 0;
        }
        Method method = dangerousAirThreats == null ? airThreats : dangerousAirThreats;
        return (Integer) call(method, 0, level, center, radius);
    }

    public static boolean present(ServerLevel level, BlockPos center, int radius) {
        return available() && (Boolean) call(present, false, level, center, radius);
    }

    @SuppressWarnings("unchecked")
    public static List<BlockPos> positions(ServerLevel level, BlockPos center, int radius) {
        return available() ? (List<BlockPos>) call(positions, List.of(), level, center, radius) : List.of();
    }

    public static int airThreats(ServerLevel level, BlockPos center, double radius) {
        return available() ? (Integer) call(airThreats, 0, level, center, radius) : 0;
    }

    public static int soundSirens(ServerLevel level, BlockPos center, int radius) {
        return available() ? (Integer) call(soundSirens, 0, level, center, radius) : 0;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Integer> ammoNeeds(ServerLevel level, BlockPos center, int radius) {
        return available() ? (Map<String, Integer>) call(ammoNeeds, Map.of(), level, center, radius) : Map.of();
    }

    public static int deliver(ServerLevel level, BlockPos center, int radius, ItemStack stack) {
        return available() ? (Integer) call(deliver, 0, level, center, radius, stack) : 0;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Integer>> recipes() {
        return available() ? (List<Map<String, Integer>>) call(recipes, List.of()) : List.of();
    }

    private static Object call(Method method, Object fallback, Object... args) {
        try {
            Object result = method.invoke(null, args);
            return result == null ? fallback : result;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            synchronized (AirDefenseLink.class) {
                if (available) {
                    available = false;
                    LOG.error("Air Defense link turned off after a failed call to {}", method.getName(), failure);
                }
            }
            return fallback;
        }
    }
}
