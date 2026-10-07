package org.lts.smartcolony.core;

public final class Staged {
    private Staged() {
    }

    public static int stageOf(String blueprint) {
        int stage = TownHall.stageOf(blueprint);
        return stage > 0 ? stage : Hospital.stageOf(blueprint);
    }

    public static boolean isStaged(String blueprint) {
        return stageOf(blueprint) > 0;
    }

    public static boolean sameFamily(String a, String b) {
        if (TownHall.stageOf(a) > 0) {
            return TownHall.stageOf(b) > 0;
        }
        return Hospital.stageOf(a) > 0 && Hospital.stageOf(b) > 0;
    }
}
