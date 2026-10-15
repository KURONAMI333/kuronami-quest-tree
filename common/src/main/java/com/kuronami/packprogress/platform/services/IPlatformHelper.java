package com.kuronami.packprogress.platform.services;

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

    default String getEnvironmentName() {
        return isDevelopmentEnvironment() ? "development" : "production";
    }
}
