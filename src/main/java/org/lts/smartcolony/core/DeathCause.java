package org.lts.smartcolony.core;

import java.util.Set;

public final class DeathCause {
    private static final Set<String> BURNING = Set.of("lava", "in_fire", "on_fire", "campfire", "hot_floor", "lightning_bolt");
    private static final Set<String> VOID = Set.of("out_of_world", "genericKill", "generic_kill");

    private DeathCause() {
    }

    public static String classify(String messageId, boolean killedByCreature, boolean killedByProjectile) {
        if (messageId == null) {
            return "other";
        }
        if (messageId.equals("lava")) {
            return "lava";
        }
        if (BURNING.contains(messageId)) {
            return "fire";
        }
        if (VOID.contains(messageId)) {
            return "void";
        }
        if (messageId.equals("drown")) {
            return "drown";
        }
        if (messageId.equals("fall") || messageId.equals("flyIntoWall") || messageId.equals("fly_into_wall")
                || messageId.equals("stalagmite") || messageId.equals("fallingBlock") || messageId.equals("falling_block")
                || messageId.equals("falling_stalactite") || messageId.equals("anvil")) {
            return "fall";
        }
        if (messageId.equals("starve") || messageId.equals("wither")) {
            return "starve";
        }
        if (messageId.contains("explosion") || messageId.equals("fireworks")) {
            return "explosion";
        }
        if (messageId.equals("magic") || messageId.equals("indirectMagic") || messageId.equals("indirect_magic")
                || messageId.equals("thorns") || messageId.equals("sonic_boom")) {
            return "magic";
        }
        if (killedByProjectile || messageId.equals("arrow") || messageId.equals("trident")) {
            return "arrow";
        }
        if (killedByCreature || messageId.equals("mob") || messageId.equals("player")) {
            return "mob";
        }
        return "other";
    }

    public static boolean itemsLost(String cause) {
        return cause.equals("lava") || cause.equals("void") || cause.equals("fire");
    }
}
