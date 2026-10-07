package org.lts.smartcolony.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class Blueprint {
    public static final char AIR = '.';
    public static final char ANY = ' ';
    private static final List<String> ATTACHED_SUFFIXES = List.of(
            "torch", "_door", "_bed", "_pane", "ladder", "lantern", "_sign", "_trapdoor", "_carpet", "_button",
            "_pressure_plate", "crafting_table", "chest", "barrel", "furnace", "bell", "flower_pot", "_banner");

    public record Cell(int x, int y, int z, String state) {
    }

    private final String id;
    private final String kind;
    private final int beds;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final int doorX;
    private final int doorZ;
    private final char[][][] grid;
    private final Map<Character, String> palette;
    private final List<int[]> containers;
    private final List<int[]> fishing;

    private Blueprint(String id, String kind, int beds, int sizeX, int sizeY, int sizeZ, int doorX, int doorZ,
                      char[][][] grid, Map<Character, String> palette, List<int[]> containers,
                      List<int[]> fishing) {
        this.id = id;
        this.kind = kind;
        this.beds = beds;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.doorX = doorX;
        this.doorZ = doorZ;
        this.grid = grid;
        this.palette = palette;
        this.containers = containers;
        this.fishing = fishing;
    }

    public static Blueprint parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        String id = root.get("id").getAsString();
        String kind = root.has("kind") ? root.get("kind").getAsString() : "misc";
        int beds = root.has("beds") ? root.get("beds").getAsInt() : 0;
        Map<Character, String> palette = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("palette").entrySet()) {
            if (entry.getKey().length() != 1) {
                throw new IllegalArgumentException("palette key must be one character: " + entry.getKey());
            }
            palette.put(entry.getKey().charAt(0), entry.getValue().getAsString());
        }
        JsonArray layers = root.getAsJsonArray("layers");
        int sizeY = layers.size();
        int sizeZ = layers.get(0).getAsJsonArray().size();
        int sizeX = layers.get(0).getAsJsonArray().get(0).getAsString().length();
        char[][][] grid = new char[sizeY][sizeZ][sizeX];
        for (int y = 0; y < sizeY; y++) {
            JsonArray rows = layers.get(y).getAsJsonArray();
            if (rows.size() != sizeZ) {
                throw new IllegalArgumentException(id + ": layer " + y + " has " + rows.size() + " rows, expected " + sizeZ);
            }
            for (int z = 0; z < sizeZ; z++) {
                String row = rows.get(z).getAsString();
                if (row.length() != sizeX) {
                    throw new IllegalArgumentException(id + ": layer " + y + " row " + z + " has length " + row.length());
                }
                for (int x = 0; x < sizeX; x++) {
                    char c = row.charAt(x);
                    if (c != AIR && c != ANY && !palette.containsKey(c)) {
                        throw new IllegalArgumentException(id + ": unknown palette key '" + c + "'");
                    }
                    grid[y][z][x] = c;
                }
            }
        }
        JsonArray door = root.getAsJsonArray("door");
        List<int[]> containers = new ArrayList<>();
        if (root.has("containers")) {
            for (JsonElement element : root.getAsJsonArray("containers")) {
                JsonArray arr = element.getAsJsonArray();
                containers.add(new int[]{arr.get(0).getAsInt(), arr.get(1).getAsInt(), arr.get(2).getAsInt()});
            }
        }
        List<int[]> fishing = new ArrayList<>();
        if (root.has("fishing")) {
            for (JsonElement element : root.getAsJsonArray("fishing")) {
                JsonArray arr = element.getAsJsonArray();
                fishing.add(new int[]{arr.get(0).getAsInt(), arr.get(1).getAsInt(), arr.get(2).getAsInt(),
                        arr.get(3).getAsInt(), arr.get(4).getAsInt(), arr.get(5).getAsInt()});
            }
        }
        return new Blueprint(id, kind, beds, sizeX, sizeY, sizeZ, door.get(0).getAsInt(), door.get(1).getAsInt(), grid,
                palette, containers, fishing);
    }

    public String id() {
        return id;
    }

    public String kind() {
        return kind;
    }

    public int beds() {
        return beds;
    }

    public int sizeX() {
        return sizeX;
    }

    public int sizeY() {
        return sizeY;
    }

    public int sizeZ() {
        return sizeZ;
    }

    public int doorX() {
        return doorX;
    }

    public int doorZ() {
        return doorZ;
    }

    public List<int[]> containers() {
        return containers;
    }

    public List<int[]> fishing() {
        return fishing;
    }

    public boolean solidFloor(int x, int z) {
        char c = grid[0][z][x];
        return c != AIR && c != ANY;
    }

    public char at(int x, int y, int z) {
        return grid[y][z][x];
    }

    public static String resolve(String state, String wood) {
        return state.replace("$wood", wood);
    }

    public List<Cell> cells(String wood) {
        List<Cell> solid = new ArrayList<>();
        List<Cell> attached = new ArrayList<>();
        List<Cell> ordered = new ArrayList<>();
        for (int y = 0; y < sizeY; y++) {
            solid.clear();
            attached.clear();
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    char c = grid[y][z][x];
                    if (c == AIR || c == ANY) {
                        continue;
                    }
                    Cell cell = new Cell(x, y, z, resolve(palette.get(c), wood));
                    if (isAttached(blockId(cell.state()))) {
                        attached.add(cell);
                    } else {
                        solid.add(cell);
                    }
                }
            }
            ordered.addAll(solid);
            ordered.addAll(attached);
        }
        List<Cell> late = new ArrayList<>();
        List<Cell> result = new ArrayList<>();
        for (Cell cell : ordered) {
            if (isAttached(blockId(cell.state()))) {
                late.add(cell);
            } else {
                result.add(cell);
            }
        }
        result.addAll(late);
        return result;
    }

    public List<int[]> airCells() {
        List<int[]> air = new ArrayList<>();
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    if (grid[y][z][x] == AIR) {
                        air.add(new int[]{x, y, z});
                    }
                }
            }
        }
        return air;
    }

    public Map<String, Integer> materials(String wood) {
        Map<String, Integer> materials = new TreeMap<>();
        for (Cell cell : cells(wood)) {
            Material material = material(cell.state());
            if (material != null) {
                materials.merge(material.item(), material.count(), Integer::sum);
            }
        }
        return materials;
    }

    public static boolean isAttached(String blockId) {
        String path = Ids.path(blockId);
        for (String suffix : ATTACHED_SUFFIXES) {
            if (path.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    public record Material(String item, int count) {
    }

    public static String blockId(String state) {
        int bracket = state.indexOf('[');
        return bracket < 0 ? state : state.substring(0, bracket);
    }

    public static Map<String, String> properties(String state) {
        Map<String, String> props = new LinkedHashMap<>();
        int open = state.indexOf('[');
        int close = state.lastIndexOf(']');
        if (open < 0 || close < open) {
            return props;
        }
        for (String pair : state.substring(open + 1, close).split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                props.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        return props;
    }

    public static Material material(String state) {
        String id = blockId(state);
        String path = Ids.path(id);
        Map<String, String> props = properties(state);
        if (path.equals("air") || path.equals("cave_air") || path.equals("void_air") || path.equals("water")
                || path.equals("lava")) {
            return null;
        }
        if ("upper".equals(props.get("half")) && (path.endsWith("_door") || path.equals("tall_grass"))) {
            return null;
        }
        if ("head".equals(props.get("part")) && path.endsWith("_bed")) {
            return null;
        }
        String ns = Ids.namespace(id);
        String item = path;
        if (path.endsWith("wall_torch")) {
            item = path.replace("wall_torch", "torch");
        } else if (path.endsWith("_wall_hanging_sign")) {
            item = path.replace("_wall_hanging_sign", "_hanging_sign");
        } else if (path.endsWith("_wall_sign")) {
            item = path.replace("_wall_sign", "_sign");
        } else if (path.equals("dirt_path") || path.equals("farmland") || path.equals("grass_block")) {
            item = "dirt";
        }
        int count = "double".equals(props.get("type")) && path.endsWith("_slab") ? 2 : 1;
        return new Material(ns + ":" + item, count);
    }

    public static int[] rotate(int x, int z, int sizeX, int sizeZ, int rotation) {
        return switch (Math.floorMod(rotation, 4)) {
            case 1 -> new int[]{sizeZ - 1 - z, x};
            case 2 -> new int[]{sizeX - 1 - x, sizeZ - 1 - z};
            case 3 -> new int[]{z, sizeX - 1 - x};
            default -> new int[]{x, z};
        };
    }

    public int rotatedSizeX(int rotation) {
        return rotation % 2 == 0 ? sizeX : sizeZ;
    }

    public int rotatedSizeZ(int rotation) {
        return rotation % 2 == 0 ? sizeZ : sizeX;
    }

    public static int rotationFacing(int towardX, int towardZ) {
        if (Math.abs(towardX) > Math.abs(towardZ)) {
            return towardX > 0 ? 1 : 3;
        }
        return towardZ > 0 ? 2 : 0;
    }
}
