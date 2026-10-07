package org.lts.smartcolony.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Blueprints {
    public static final List<String> IDS = List.of("house_small", "house_family", "storage_shed", "guard_post", "pen",
            "house_garden", "blacksmith", "market", "barn", "watchtower", "chapel", "kindergarten",
            "fletcher_hut", "bakery", "tree_farm", "townhall_1", "townhall_2", "townhall_3", "townhall_4", "townhall_5", "outpost");
    private static final Map<String, Blueprint> CACHE = new ConcurrentHashMap<>();

    private Blueprints() {
    }

    public static Blueprint get(String id) {
        return CACHE.computeIfAbsent(id, Blueprints::load);
    }

    private static Blueprint load(String id) {
        String path = "/smartcolony/blueprints/" + id + ".json";
        try (InputStream in = Blueprints.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("missing blueprint " + path);
            }
            return Blueprint.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read blueprint " + path, e);
        }
    }
}
