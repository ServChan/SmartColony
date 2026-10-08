package org.lts.smartcolony.core;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class Decree {
    public static final int MAX_ZONES = 16;
    public static final int MIN_ZONE = 3;
    public static final int MAX_ZONE = 48;
    public static final int MAX_CONTRACTS = 8;
    public static final int MAX_CONTRACT = 1728;

    public enum Focus {
        BALANCED,
        FOOD,
        DEFENSE,
        BUILD,
        RESOURCES;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Focus byKey(String key) {
            if (key != null) {
                for (Focus focus : values()) {
                    if (focus.key().equalsIgnoreCase(key)) {
                        return focus;
                    }
                }
            }
            return BALANCED;
        }
    }

    public record Zone(int x, int z, int radius) {
        public boolean contains(int px, int pz, int margin) {
            long dx = px - x;
            long dz = pz - z;
            long r = radius + margin;
            return dx * dx + dz * dz <= r * r;
        }

        public boolean overlapsBox(int minX, int minZ, int maxX, int maxZ, int margin) {
            int cx = Math.max(minX, Math.min(x, maxX));
            int cz = Math.max(minZ, Math.min(z, maxZ));
            return contains(cx, cz, margin);
        }
    }

    private Decree() {
    }

    public static boolean inAny(List<Zone> zones, int x, int z, int margin) {
        if (zones == null) {
            return false;
        }
        for (Zone zone : zones) {
            if (zone.contains(x, z, margin)) {
                return true;
            }
        }
        return false;
    }

    public static boolean boxInAny(List<Zone> zones, int minX, int minZ, int maxX, int maxZ, int margin) {
        if (zones == null) {
            return false;
        }
        for (Zone zone : zones) {
            if (zone.overlapsBox(minX, minZ, maxX, maxZ, margin)) {
                return true;
            }
        }
        return false;
    }

    public static int clampRadius(int radius) {
        return Math.max(MIN_ZONE, Math.min(MAX_ZONE, radius));
    }

    static List<Role> favoured(Focus focus) {
        return switch (focus) {
            case FOOD -> List.of(Role.FARMER, Role.FISHERMAN, Role.HERDER);
            case DEFENSE -> List.of(Role.GUARD, Role.GUARD, Role.FLETCHER);
            case BUILD -> List.of(Role.BUILDER, Role.BUILDER, Role.LUMBERJACK);
            case RESOURCES -> List.of(Role.MINER, Role.LUMBERJACK, Role.MASON);
            case BALANCED -> List.of();
        };
    }

    static List<Role> donors(Focus focus) {
        return switch (focus) {
            case FOOD -> List.of(Role.IDLE, Role.LUMBERJACK, Role.MINER, Role.BUILDER, Role.SCOUT);
            case DEFENSE -> List.of(Role.IDLE, Role.LUMBERJACK, Role.FISHERMAN, Role.MINER, Role.HERDER, Role.SCOUT);
            case BUILD -> List.of(Role.IDLE, Role.FISHERMAN, Role.MINER, Role.HERDER, Role.SCOUT);
            case RESOURCES -> List.of(Role.IDLE, Role.FISHERMAN, Role.HERDER, Role.SCOUT, Role.FARMER);
            case BALANCED -> List.of();
        };
    }

    public static int shift(int adults) {
        return adults < 4 ? 0 : Math.max(1, adults / 6);
    }

    public static EnumMap<Role, Integer> apply(Map<Role, Integer> base, Focus focus, int adults) {
        EnumMap<Role, Integer> result = new EnumMap<>(Role.class);
        for (Role role : Role.values()) {
            result.put(role, base.getOrDefault(role, 0));
        }
        int moves = shift(adults);
        List<Role> favoured = favoured(focus);
        List<Role> donors = donors(focus);
        if (moves == 0 || favoured.isEmpty()) {
            return result;
        }
        int moved = 0;
        while (moved < moves) {
            Role donor = null;
            for (Role role : donors) {
                int floor = role == Role.LUMBERJACK || role == Role.FARMER ? 1 : 0;
                if (result.get(role) > floor && (donor == null || result.get(role) > result.get(donor))) {
                    donor = role;
                }
            }
            if (donor == null) {
                break;
            }
            Role target = favoured.get(moved % favoured.size());
            if (target == Role.FLETCHER && result.get(Role.FLETCHER) >= 2 || target == Role.MASON && result.get(Role.MASON) == 0) {
                target = favoured.getFirst();
            }
            result.merge(donor, -1, Integer::sum);
            result.merge(target, 1, Integer::sum);
            moved++;
        }
        return result;
    }
}
