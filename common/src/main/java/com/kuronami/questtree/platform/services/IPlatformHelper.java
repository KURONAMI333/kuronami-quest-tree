package com.kuronami.questtree.platform.services;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;

public interface IPlatformHelper {

    String getPlatformName();

    boolean isModLoaded(String modId);

    boolean isDevelopmentEnvironment();

    /** The game directory (instance folder on the client, server folder on a dedicated server). */
    Path getGameDir();

    /** Sends a payload that was registered on both loaders as server-to-client. */
    void sendToPlayer(ServerPlayer player, CustomPacketPayload payload);

    /** Opens the progression screen. Called from the quest book on the client only. */
    void openProgressionScreen();

    /** Opens the progression screen on one quest; an empty key opens the usual last-viewed place. */
    default void openProgressionScreen(String node) {
        openProgressionScreen();
    }

    default String getEnvironmentName() {
        return isDevelopmentEnvironment() ? "development" : "production";
    }
}
