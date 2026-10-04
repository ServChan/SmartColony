package org.lts.smartcolony.colony;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class ColonyUi {
    private static volatile BiConsumer<ColonyView, String> opener;

    private ColonyUi() {
    }

    public static void setOpener(BiConsumer<ColonyView, String> value) {
        opener = value;
    }

    public static boolean open(ServerPlayer player, Colony colony, String tab) {
        BiConsumer<ColonyView, String> current = opener;
        MinecraftServer server = player.level().getServer();
        if (current == null || server == null || server.isDedicatedServer()) {
            return false;
        }
        current.accept(ColonyView.capture(player.level(), colony, player.getUUID()), tab);
        return true;
    }

    public static Colony colonyFor(ServerLevel level, BlockPos pos) {
        Colony colony = ColonyManager.colonyAt(level, pos);
        return colony != null ? colony : ColonyManager.colonyNear(level, pos, 256);
    }

    public static CompletableFuture<Optional<ColonyView>> request(MinecraftServer server, UUID playerId) {
        return server.submit(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                return Optional.<ColonyView>empty();
            }
            Colony colony = colonyFor(player.level(), player.blockPosition());
            return colony == null ? Optional.<ColonyView>empty() : Optional.of(ColonyView.capture(player.level(), colony, playerId));
        });
    }
}
