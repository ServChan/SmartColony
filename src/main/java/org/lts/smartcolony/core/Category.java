package org.lts.smartcolony.core;

import java.util.Locale;

public enum Category {
    WOOD,
    MINING,
    FOOD,
    ANIMAL,
    ARSENAL;

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Category byKey(String key) {
        for (Category category : values()) {
            if (category.key().equals(key)) {
                return category;
            }
        }
        return MINING;
    }
}
