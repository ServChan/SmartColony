package org.lts.smartcolony.core;

import java.util.EnumMap;
import java.util.Map;

public final class RoleDamper {
    public static final int RAISE_AFTER = 2;
    public static final int LOWER_AFTER = 6;

    private final EnumMap<Role, Integer> settled = new EnumMap<>(Role.class);
    private final EnumMap<Role, Integer> streak = new EnumMap<>(Role.class);
    private final EnumMap<Role, Integer> direction = new EnumMap<>(Role.class);
    private boolean primed;

    public EnumMap<Role, Integer> settle(Map<Role, Integer> wanted) {
        if (!primed) {
            primed = true;
            for (Role role : Role.values()) {
                settled.put(role, wanted.getOrDefault(role, 0));
            }
            return copy();
        }
        for (Role role : Role.values()) {
            int want = wanted.getOrDefault(role, 0);
            int have = settled.getOrDefault(role, 0);
            if (want == have) {
                streak.remove(role);
                direction.remove(role);
                continue;
            }
            int sign = Integer.signum(want - have);
            int run = direction.getOrDefault(role, 0) == sign ? streak.getOrDefault(role, 0) + 1 : 1;
            if (run >= (sign > 0 ? RAISE_AFTER : LOWER_AFTER)) {
                settled.put(role, want);
                streak.remove(role);
                direction.remove(role);
            } else {
                streak.put(role, run);
                direction.put(role, sign);
            }
        }
        return copy();
    }

    private EnumMap<Role, Integer> copy() {
        return new EnumMap<>(settled);
    }
}
