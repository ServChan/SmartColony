package org.lts.smartcolony.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ThreatMap {
    public static final int SECTORS = 8;

    private ThreatMap() {
    }

    public static int sector(double dx, double dz) {
        double angle = Math.atan2(dz, dx);
        int index = (int) Math.floor((angle + Math.PI) / (2.0 * Math.PI) * SECTORS);
        return Math.floorMod(index, SECTORS);
    }

    public static double[] direction(int sector) {
        double angle = (sector + 0.5) / SECTORS * 2.0 * Math.PI - Math.PI;
        return new double[]{Math.cos(angle), Math.sin(angle)};
    }

    public static void add(double[] weights, double dx, double dz, double amount) {
        weights[sector(dx, dz)] += amount;
    }

    public static void decay(double[] weights, double factor) {
        for (int i = 0; i < weights.length; i++) {
            weights[i] *= factor;
            if (weights[i] < 0.01) {
                weights[i] = 0.0;
            }
        }
    }

    public static List<Integer> hottest(double[] weights, int count) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < weights.length; i++) {
            order.add(i);
        }
        order.sort(Comparator.<Integer>comparingDouble(i -> weights[i]).reversed().thenComparing(i -> i));
        return order.subList(0, Math.min(count, order.size()));
    }

    public static double total(double[] weights) {
        double sum = 0.0;
        for (double weight : weights) {
            sum += weight;
        }
        return sum;
    }
}
