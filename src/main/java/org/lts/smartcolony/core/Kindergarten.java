package org.lts.smartcolony.core;

public final class Kindergarten {
    public static final String ID = "kindergarten";

    private Kindergarten() {
    }

    public static boolean needed(int children, int adults, boolean existsOrPlanned) {
        if (existsOrPlanned) {
            return false;
        }
        return children >= 3 || (children >= 2 && adults >= 10);
    }

    public static boolean inside(int x, int z, int minX, int minZ, int maxX, int maxZ) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    public static boolean unsafeNear(int x, int z, int margin, java.util.function.BiPredicate<Integer, Integer> unsafe) {
        if (unsafe.test(x, z)) {
            return true;
        }
        return unsafe.test(x + margin, z) || unsafe.test(x - margin, z) || unsafe.test(x, z + margin) || unsafe.test(x, z - margin);
    }
}
