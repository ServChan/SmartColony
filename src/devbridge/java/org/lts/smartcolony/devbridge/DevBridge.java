package org.lts.smartcolony.devbridge;

import java.io.IOException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DevBridge implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("smartcolony_devbridge");
    private static McpServer server;
    private static volatile long serverTicks;

    public static long serverTicks() {
        return serverTicks;
    }

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(DevBridge::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(minecraft -> stop());
        ServerTickEvents.END_SERVER_TICK.register(minecraft -> serverTicks++);
    }

    private static void start(MinecraftServer minecraft) {
        int port = Integer.getInteger("smartcolony.bridge.port", 25599);
        try {
            server = new McpServer(new BridgeTools(minecraft), "127.0.0.1", port);
            server.start();
            LOGGER.info("Smart Colony dev bridge listening on http://127.0.0.1:{}/mcp", port);
        } catch (IOException e) {
            LOGGER.error("Dev bridge could not start on port {}: {}", port, e.toString());
        }
    }

    private static void stop() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }
}
