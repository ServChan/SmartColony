package org.lts.smartcolony.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;

public final class ColonyCodec {
    public static final int SCHEMA = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private ColonyCodec() {
    }

    public static Gson gson() {
        return GSON;
    }

    public static String write(List<ColonyData> colonies) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA);
        JsonArray array = new JsonArray();
        for (ColonyData colony : colonies) {
            array.add(GSON.toJsonTree(colony));
        }
        root.add("colonies", array);
        return GSON.toJson(root);
    }

    public static List<ColonyData> read(String json) {
        List<ColonyData> result = new ArrayList<>();
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject()) {
            return result;
        }
        JsonObject root = parsed.getAsJsonObject();
        int schema = root.has("schemaVersion") ? root.get("schemaVersion").getAsInt() : 0;
        if (schema > SCHEMA) {
            throw new IllegalStateException("colonies file schema " + schema + " is newer than supported " + SCHEMA);
        }
        if (!root.has("colonies")) {
            return result;
        }
        for (JsonElement element : root.getAsJsonArray("colonies")) {
            ColonyData colony = GSON.fromJson(element, ColonyData.class);
            if (colony.threat == null || colony.threat.length != ThreatMap.SECTORS) {
                colony.threat = new double[ThreatMap.SECTORS];
            }
            result.add(colony);
        }
        return result;
    }
}
