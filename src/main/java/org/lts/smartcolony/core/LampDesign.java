package org.lts.smartcolony.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LampDesign {
    public static final int GROUND_TORCH = 0;
    public static final int STONE_POST = 1;
    public static final int LANTERN_POST = 2;
    public static final int SPACING = 10;
    public static final int STYLE = 1;

    public record Layer(int dx, int dy, int dz, String state) {
        public String block() {
            int bracket = state.indexOf('[');
            return bracket < 0 ? state : state.substring(0, bracket);
        }
    }

    private LampDesign() {
    }

    public static int tierFor(String age, boolean lanternReady) {
        return Tech.ironAge(age) && lanternReady ? LANTERN_POST : STONE_POST;
    }

    public static List<Layer> layers(int tier, String wood, String base, int armX, int armZ) {
        String fence = Ids.mc(wood + "_fence");
        List<Layer> layers = new ArrayList<>();
        if (tier <= STONE_POST) {
            layers.add(new Layer(0, 0, 0, base));
            layers.add(new Layer(0, 1, 0, fence));
            layers.add(new Layer(0, 2, 0, fence));
            layers.add(new Layer(0, 3, 0, Ids.mc("torch")));
            layers.addAll(cage(wood));
            return layers;
        }
        layers.add(new Layer(0, 0, 0, base));
        layers.add(new Layer(0, 1, 0, fence));
        layers.add(new Layer(0, 2, 0, fence));
        layers.add(new Layer(0, 3, 0, fence));
        if (armX != 0 || armZ != 0) {
            layers.add(new Layer(armX, 3, armZ, fence));
            layers.add(new Layer(armX, 2, armZ, Ids.mc("lantern") + "[hanging=true]"));
        } else {
            layers.add(new Layer(0, 4, 0, Ids.mc("lantern") + "[hanging=false]"));
        }
        return layers;
    }

    public static List<Layer> cage(String wood) {
        String trapdoor = Ids.mc(wood + "_trapdoor");
        List<Layer> cage = new ArrayList<>();
        cage.add(new Layer(1, 3, 0, trapdoor + "[facing=east,half=bottom,open=true]"));
        cage.add(new Layer(-1, 3, 0, trapdoor + "[facing=west,half=bottom,open=true]"));
        cage.add(new Layer(0, 3, 1, trapdoor + "[facing=south,half=bottom,open=true]"));
        cage.add(new Layer(0, 3, -1, trapdoor + "[facing=north,half=bottom,open=true]"));
        cage.add(new Layer(0, 4, 0, Ids.mc(wood + "_slab") + "[type=bottom]"));
        return cage;
    }

    public static Map<String, Integer> needs(List<Layer> layers) {
        Map<String, Integer> needs = new LinkedHashMap<>();
        for (Layer layer : layers) {
            needs.merge(layer.block(), 1, Integer::sum);
        }
        return needs;
    }

    public static boolean spaced(Iterable<Pos> lamps, Pos at, int distance) {
        long limit = (long) distance * distance;
        for (Pos lamp : lamps) {
            if (lamp.horizontalDistSq(at) < limit) {
                return false;
            }
        }
        return true;
    }

    public static boolean bottomUp(List<Layer> layers) {
        for (int i = 0; i < layers.size(); i++) {
            Layer layer = layers.get(i);
            if (layer.dy() == 0) {
                continue;
            }
            boolean supported = false;
            for (int j = 0; j < i; j++) {
                Layer below = layers.get(j);
                boolean column = below.dx() == layer.dx() && below.dz() == layer.dz() && below.dy() == layer.dy() - 1;
                boolean hung = layer.state().contains("hanging=true") && below.dx() == layer.dx() && below.dz() == layer.dz()
                        && below.dy() == layer.dy() + 1;
                boolean attach = below.dy() == layer.dy() && Math.abs(below.dx() - layer.dx()) + Math.abs(below.dz() - layer.dz()) == 1;
                if (column || hung || attach) {
                    supported = true;
                    break;
                }
            }
            if (!supported) {
                return false;
            }
        }
        return true;
    }
}
