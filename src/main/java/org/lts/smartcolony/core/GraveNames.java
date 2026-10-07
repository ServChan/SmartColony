package org.lts.smartcolony.core;

import java.util.List;

public final class GraveNames {
    private static final List<String> FIRST = List.of("Al", "Bra", "Cor", "Da", "El", "Fen", "Gar", "Hal", "Ira", "Jor", "Kel", "Lor",
            "Mar", "Nor", "Or", "Pen", "Ren", "Sar", "Tor", "Ul", "Val", "Wen", "Yor", "Zan");
    private static final List<String> LAST = List.of("ic", "wyn", "ric", "dan", "mir", "ton", "lin", "sen", "vik", "bert", "dra", "mon",
            "lan", "gar", "tis", "ro");

    private GraveNames() {
    }

    public static String of(String seed) {
        int hash = seed == null ? 0 : seed.hashCode();
        hash ^= hash >>> 16;
        int first = Math.floorMod(hash, FIRST.size());
        int last = Math.floorMod(hash / FIRST.size(), LAST.size());
        return FIRST.get(first) + LAST.get(last);
    }
}
