package org.lts.smartcolony.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lts.smartcolony.colony.ColonyUi;

public final class SmartColonyClient implements ClientModInitializer {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("smartcolony", "main"));
    private static KeyMapping openKey;

    @Override
    public void onInitializeClient() {
        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.smartcolony.open", InputConstants.Type.KEYBOARD, InputConstants.KEY_K, CATEGORY));
        ColonyUi.setOpener((view, tab) -> Minecraft.getInstance().execute(
                () -> Minecraft.getInstance().gui.setScreen(new ColonyScreen(view, tab))));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.consumeClick()) {
                open(client);
            }
        });
    }

    private static void open(Minecraft client) {
        if (client.player == null || client.gui.screen() != null) {
            return;
        }
        IntegratedServer server = client.getSingleplayerServer();
        if (server == null) {
            client.player.sendOverlayMessage(Component.translatable("smartcolony.ui.singleplayer"));
            return;
        }
        ColonyUi.request(server, client.player.getUUID()).thenAccept(view -> client.execute(() -> {
            if (client.player == null) {
                return;
            }
            if (view.isEmpty()) {
                client.player.sendOverlayMessage(Component.translatable("smartcolony.command.none"));
                return;
            }
            client.gui.setScreen(new ColonyScreen(view.get(), "overview"));
        }));
    }
}
