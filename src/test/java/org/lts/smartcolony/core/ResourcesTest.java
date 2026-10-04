package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ResourcesTest {
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() { }.getType();

    private static Map<String, String> lang(String locale) throws IOException {
        try (Reader reader = Files.newBufferedReader(Path.of("src/main/resources/assets/smartcolony/lang", locale + ".json"))) {
            return new Gson().fromJson(reader, MAP_TYPE);
        }
    }

    @Test
    void allLocalesHaveTheSameKeys() throws IOException {
        Set<String> expected = lang("en_us").keySet();
        for (String locale : List.of("ru_ru", "es_es", "es_mx")) {
            assertEquals(expected, lang(locale).keySet(), locale);
        }
    }

    @Test
    void everyTranslationKeyInCodeExists() throws IOException {
        Set<String> keys = lang("en_us").keySet();
        Pattern literal = Pattern.compile("\"(smartcolony\\.[a-z_]+\\.[a-z_]+)\"");
        try (Stream<Path> files = Stream.concat(Files.walk(Path.of("src/main/java")), Files.walk(Path.of("src/client/java")))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher matcher = literal.matcher(Files.readString(file));
                while (matcher.find()) {
                    String key = matcher.group(1);
                    if (key.endsWith(".") || key.startsWith("smartcolony.bridge")) {
                        continue;
                    }
                    assertTrue(keys.contains(key), key + " used in " + file.getFileName());
                }
            }
        }
        for (Role role : Role.values()) {
            assertTrue(keys.contains("smartcolony.role." + role.key()));
        }
        for (Category category : Category.values()) {
            assertTrue(keys.contains("smartcolony.storage." + category.key()));
        }
        for (Reputation.Status status : Reputation.Status.values()) {
            assertTrue(keys.contains("smartcolony.status." + status.key()));
        }
        for (String id : Blueprints.IDS) {
            assertTrue(keys.contains("smartcolony.building." + id), id);
        }
    }

    @Test
    void blueprintsParseAndCanBeCrafted() {
        Stock rich = new Stock();
        rich.add(Ids.mc("oak_log"), 400);
        rich.add(Ids.mc("cobblestone"), 400);
        rich.add(Ids.mc("glass"), 30);
        rich.add(Ids.mc("white_wool"), 12);
        rich.add(Ids.mc("coal"), 20);
        for (String id : Blueprints.IDS) {
            Blueprint blueprint = Blueprints.get(id);
            Map<String, Integer> materials = blueprint.materials("oak");
            assertFalse(materials.isEmpty(), id);
            assertTrue(CraftPlanner.plan(rich, materials).isPresent(), id + " needs " + materials);
            assertTrue(blueprint.doorX() >= 0 && blueprint.doorX() < blueprint.sizeX(), id);
        }
        assertEquals(2, Blueprints.get("house_family").beds());
        assertEquals(6, Blueprints.get("storage_shed").containers().size());
    }

    @Test
    void gridPathAvoidsCliffsAndBlocks() {
        int[][] heights = new int[10][10];
        boolean[][] blocked = new boolean[10][10];
        for (int z = 0; z < 9; z++) {
            blocked[5][z] = true;
        }
        heights[3][9] = 70;
        GridPath.Terrain terrain = new GridPath.Terrain() {
            @Override
            public int height(int x, int z) {
                return x < 0 || z < 0 || x >= 10 || z >= 10 ? 1000 : heights[x][z];
            }

            @Override
            public boolean blocked(int x, int z) {
                return x < 0 || z < 0 || x >= 10 || z >= 10 || blocked[x][z];
            }

            @Override
            public boolean preferred(int x, int z) {
                return false;
            }
        };
        List<int[]> path = GridPath.find(terrain, 0, 0, 9, 0, 5000);
        assertFalse(path.isEmpty());
        assertEquals(0, path.getFirst()[0]);
        assertEquals(9, path.getLast()[0]);
        for (int[] cell : path) {
            assertFalse(blocked[cell[0]][cell[1]]);
            assertFalse(cell[0] == 3 && cell[1] == 9);
        }
        blocked[5][9] = true;
        assertTrue(GridPath.find(terrain, 0, 0, 9, 0, 5000).isEmpty());
    }
}
