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
    GUARD,
    FLETCHER,
    SMITH,
    COOK,
    UNDERTAKER,
    INNKEEPER,
    TEACHER,
    PORTER,
    MEDIC,
    MASON,
    BEEKEEPER,
    ALCHEMIST,
    SCRIBE,
    SCOUT;

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
            case "shepherd", "leatherworker" -> HERDER;
            case "fletcher" -> FLETCHER;
            case "armorer", "weaponsmith", "toolsmith" -> SMITH;
            case "butcher" -> COOK;
            case "mason" -> MASON;
            case "librarian" -> TEACHER;
            case "cartographer" -> SCOUT;
            case "cleric" -> MEDIC;
            default -> LUMBERJACK;
        };
    }
}
