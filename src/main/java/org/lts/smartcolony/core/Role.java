package org.lts.smartcolony.core;

import java.util.Locale;

public enum Role {
    IDLE,
    LUMBERJACK,
    MINER,
    FARMER,
    FISHERMAN,
    HERDER,
    BUILDER,
    GUARD;

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Role byKey(String key) {
        if (key == null) {
            return IDLE;
        }
        for (Role role : values()) {
            if (role.key().equalsIgnoreCase(key)) {
                return role;
            }
        }
        return IDLE;
    }

    public static Role affinity(String professionPath) {
        return switch (professionPath) {
            case "farmer" -> FARMER;
            case "fisherman" -> FISHERMAN;
            case "shepherd", "butcher", "leatherworker" -> HERDER;
            case "armorer", "weaponsmith", "fletcher" -> GUARD;
            case "toolsmith", "mason" -> MINER;
            case "cartographer", "librarian", "cleric" -> BUILDER;
            default -> LUMBERJACK;
        };
    }
}
