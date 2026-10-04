package org.lts.smartcolony.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.lts.smartcolony.SmartColonyConfig;

public final class AiClient {
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private AiClient() {
    }

    public static boolean openAi(SmartColonyConfig config) {
        return "openai".equals(config.aiApi);
    }

    public static CompletableFuture<String> chat(SmartColonyConfig config, String system, String user) {
        String base = config.aiEndpoint.endsWith("/") ? config.aiEndpoint.substring(0, config.aiEndpoint.length() - 1) : config.aiEndpoint;
        JsonArray messages = new JsonArray();
        messages.add(message("system", system));
        messages.add(message("user", user));
        JsonObject body = new JsonObject();
        body.addProperty("model", config.aiModel);
        body.add("messages", messages);
        String url;
        if (openAi(config)) {
            url = base + "/v1/chat/completions";
            body.addProperty("temperature", 0.2);
            JsonObject format = new JsonObject();
            format.addProperty("type", "json_object");
            body.add("response_format", format);
        } else {
            url = base + "/api/chat";
            body.addProperty("stream", false);
            body.addProperty("format", "json");
            JsonObject options = new JsonObject();
            options.addProperty("temperature", 0.2);
            options.addProperty("num_ctx", 8192);
            body.add("options", options);
        }
        HttpRequest.Builder request;
        try {
            request = HttpRequest.newBuilder(URI.create(url));
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(new IllegalStateException("bad aiEndpoint " + config.aiEndpoint));
        }
        request.timeout(Duration.ofSeconds(config.aiTimeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8));
        if (!config.aiApiKey.isBlank()) {
            request.header("Authorization", "Bearer " + config.aiApiKey);
        }
        boolean openAi = openAi(config);
        return HTTP.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if (response.statusCode() / 100 != 2) {
                        String text = response.body() == null ? "" : response.body();
                        throw new IllegalStateException("HTTP " + response.statusCode() + " " + text.substring(0, Math.min(160, text.length())));
                    }
                    return content(response.body(), openAi);
                });
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    static String content(String body, boolean openAi) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        if (openAi) {
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new IllegalStateException("empty answer");
            }
            return choices.get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString();
        }
        JsonElement message = root.get("message");
        if (message == null || !message.isJsonObject()) {
            throw new IllegalStateException(root.has("error") ? root.get("error").getAsString() : "empty answer");
        }
        return message.getAsJsonObject().get("content").getAsString();
    }
}
