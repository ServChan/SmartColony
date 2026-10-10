package org.lts.smartcolony.devbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class McpServer {
    private static final String PROTOCOL = "2025-03-26";

    private final BridgeTools tools;
    private final InetSocketAddress address;
    private HttpServer http;
    private ExecutorService executor;

    public McpServer(BridgeTools tools, String host, int port) {
        this.tools = tools;
        this.address = new InetSocketAddress(host, port);
    }

    public void start() throws IOException {
        http = HttpServer.create(address, 0);
        http.createContext("/mcp", this::handle);
        executor = Executors.newFixedThreadPool(3, runnable -> {
            Thread thread = new Thread(runnable, "SmartColony-DevBridge");
            thread.setDaemon(true);
            return thread;
        });
        http.setExecutor(executor);
        http.start();
    }

    public void stop() {
        if (http != null) {
            http.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if ("GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                send(exchange, 400, error(null, -32600, "batch requests are not supported"));
                return;
            }
            JsonObject response = dispatch(parsed.getAsJsonObject());
            if (response == null) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            send(exchange, 200, response);
        } catch (RuntimeException e) {
            DevBridge.LOGGER.warn("Could not handle a bridge request", e);
            send(exchange, 400, error(null, -32700, "parse error: " + e.getMessage()));
        }
    }

    private JsonObject dispatch(JsonObject request) {
        JsonElement id = request.get("id");
        String method = request.has("method") ? request.get("method").getAsString() : "";
        if (id == null || id.isJsonNull()) {
            return null;
        }
        JsonObject params = request.has("params") && request.get("params").isJsonObject() ? request.getAsJsonObject("params") : new JsonObject();
        return switch (method) {
            case "initialize" -> {
                JsonObject result = new JsonObject();
                result.addProperty("protocolVersion", PROTOCOL);
                JsonObject capabilities = new JsonObject();
                capabilities.add("tools", new JsonObject());
                result.add("capabilities", capabilities);
                JsonObject info = new JsonObject();
                info.addProperty("name", "smartcolony-devbridge");
                info.addProperty("version", "1.0.0");
                result.add("serverInfo", info);
                yield result(id, result);
            }
            case "ping" -> result(id, new JsonObject());
            case "tools/list" -> {
                JsonObject result = new JsonObject();
                result.add("tools", tools.list());
                yield result(id, result);
            }
            case "tools/call" -> {
                String name = params.has("name") ? params.get("name").getAsString() : "";
                JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
                        ? params.getAsJsonObject("arguments") : new JsonObject();
                try {
                    yield result(id, tools.call(name, args));
                } catch (Exception e) {
                    yield result(id, BridgeTools.text("error: " + e, true));
                }
            }
            default -> error(id, -32601, "unknown method " + method);
        };
    }

    private static JsonObject result(JsonElement id, JsonObject result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id);
        response.add("result", result);
        return response;
    }

    private static JsonObject error(JsonElement id, int code, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id);
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        response.add("error", error);
        return response;
    }

    private static void send(HttpExchange exchange, int status, JsonObject body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    static JsonArray array(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }
}
