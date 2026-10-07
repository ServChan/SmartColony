package org.lts.smartcolony.core;

import java.util.List;

public final class Staged {
    public static final List<String> FAMILIES = List.of(Hospital.KIND, TownHall.KIND);

    public enum Failure {
        NONE,
        NOT_STAGED,
        BUSY,
        COMPLETE,
        WRONG_STAGE,
        IRON_AGE,
        NO_SITE
    }

    public record Order(String blueprint, Failure failure) {
        public boolean ok() {
            return failure == Failure.NONE;
        }
    }

    private Staged() {
    }

    public static int stageOf(String blueprint) {
        int stage = TownHall.stageOf(blueprint);
        return stage > 0 ? stage : Hospital.stageOf(blueprint);
    }

    public static boolean isStaged(String blueprint) {
        return stageOf(blueprint) > 0;
    }

    public static boolean isFamily(String id) {
        return FAMILIES.contains(id);
    }

    public static boolean sameFamily(String a, String b) {
        if (TownHall.stageOf(a) > 0) {
            return TownHall.stageOf(b) > 0;
        }
        return Hospital.stageOf(a) > 0 && Hospital.stageOf(b) > 0;
    }

    public static String familyOf(String blueprint) {
        if (isFamily(blueprint)) {
            return blueprint;
        }
        if (TownHall.stageOf(blueprint) > 0) {
            return TownHall.KIND;
        }
        return Hospital.stageOf(blueprint) > 0 ? Hospital.KIND : null;
    }

    public static int stages(String family) {
        return TownHall.KIND.equals(family) ? TownHall.STAGES : Hospital.KIND.equals(family) ? Hospital.STAGES : 0;
    }

    public static String blueprint(String family, int stage) {
        return TownHall.KIND.equals(family) ? TownHall.blueprint(stage) : Hospital.blueprint(stage);
    }

    public static Order order(String id, int finished, boolean busy, boolean ironAge) {
        String family = familyOf(id);
        if (family == null) {
            return new Order(id, Failure.NOT_STAGED);
        }
        int next = finished + 1;
        if (finished >= stages(family)) {
            return new Order(id, Failure.COMPLETE);
        }
        if (!isFamily(id) && stageOf(id) != next) {
            return new Order(id, Failure.WRONG_STAGE);
        }
        if (busy) {
            return new Order(id, Failure.BUSY);
        }
        if (TownHall.KIND.equals(family) && next >= TownHall.IRON_STAGE && !ironAge) {
            return new Order(id, Failure.IRON_AGE);
        }
        return new Order(blueprint(family, next), Failure.NONE);
    }

    public static List<String> suggestions(List<String> blueprints) {
        List<String> result = new java.util.ArrayList<>();
        for (String id : blueprints) {
            if (!isStaged(id)) {
                result.add(id);
            }
        }
        result.addAll(FAMILIES);
        return result;
    }
}
