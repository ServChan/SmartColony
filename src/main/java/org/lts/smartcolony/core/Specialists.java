package org.lts.smartcolony.core;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class Specialists {
    public static final String MASONRY = "masonry";
    public static final String APIARY = "apiary";
    public static final String APOTHECARY = "apothecary";
    public static final String LIBRARY = "library";
    public static final String STABLE = "stable";

    public record Wants(int masons, int beekeepers, int alchemists, int scribes, int scouts) {
        public static final Wants NONE = new Wants(0, 0, 0, 0, 0);

        public int total() {
            return masons + beekeepers + alchemists + scribes + scouts;
        }
    }

    private static final List<Role> DONORS = List.of(Role.IDLE, Role.LUMBERJACK, Role.FARMER, Role.MINER, Role.FISHERMAN, Role.HERDER,
            Role.BUILDER);

    private Specialists() {
    }

    public static int masons(boolean masonry, int adults) {
        if (!masonry || adults < 8) {
            return 0;
        }
        return adults >= 24 ? 2 : 1;
    }

    public static int beekeepers(boolean apiary, boolean nestsKnown, int adults) {
        return (apiary || nestsKnown) && adults >= 9 ? 1 : 0;
    }

    public static int alchemists(boolean apothecary, int adults) {
        return apothecary && adults >= 10 ? 1 : 0;
    }

    public static int scribes(boolean library, int adults) {
        return library && adults >= 10 ? 1 : 0;
    }

    public static int scouts(int adults, boolean worthIt) {
        if (!worthIt || adults < 7) {
            return 0;
        }
        return adults >= 22 ? 2 : 1;
    }

    private static int floor(Role role) {
        return role == Role.LUMBERJACK || role == Role.FARMER ? 1 : 0;
    }

    public static EnumMap<Role, Integer> settle(Map<Role, Integer> base, Wants wants, int adults) {
        EnumMap<Role, Integer> result = new EnumMap<>(Role.class);
        for (Role role : Role.values()) {
            result.put(role, base.getOrDefault(role, 0));
        }
        result.put(Role.MASON, 0);
        result.put(Role.BEEKEEPER, 0);
        result.put(Role.ALCHEMIST, 0);
        result.put(Role.SCRIBE, 0);
        result.put(Role.SCOUT, 0);
        int budget = Math.max(0, adults / 3);
        Role[] order = {Role.MASON, Role.SCOUT, Role.BEEKEEPER, Role.ALCHEMIST, Role.SCRIBE, Role.MASON, Role.SCOUT};
        int[] left = {wants.masons(), wants.scouts(), wants.beekeepers(), wants.alchemists(), wants.scribes()};
        int given = 0;
        for (int pass = 0; pass < 3 && given < budget; pass++) {
            for (Role role : order) {
                int index = switch (role) {
                    case MASON -> 0;
                    case SCOUT -> 1;
                    case BEEKEEPER -> 2;
                    case ALCHEMIST -> 3;
                    default -> 4;
                };
                if (left[index] <= 0 || given >= budget) {
                    continue;
                }
                Role donor = null;
                for (Role candidate : DONORS) {
                    if (result.get(candidate) > floor(candidate)
                            && (donor == null || candidate == Role.IDLE && result.get(candidate) > 0
                            || donor != Role.IDLE && result.get(candidate) > result.get(donor))) {
                        donor = candidate;
                    }
                }
                if (donor == null) {
                    return result;
                }
                result.merge(donor, -1, Integer::sum);
                result.merge(role, 1, Integer::sum);
                left[index]--;
                given++;
            }
        }
        return result;
    }
}
