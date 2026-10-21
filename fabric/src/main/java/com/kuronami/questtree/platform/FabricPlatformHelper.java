package com.kuronami.questtree.platform;

import com.kuronami.questtree.platform.services.IPlatformHelper;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;

public class FabricPlatformHelper implements IPlatformHelper {

    @Override
    public String getPlatformName() {
        return "Fabric";
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    @Override
    public Path getGameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        if (ServerPlayNetworking.canSend(player, payload.type())) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    @Override
    public void openProgressionScreen() {
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            ClientOnly.open("");
        }
    }

    @Override
    public void openProgressionScreen(String node) {
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            ClientOnly.open(node);
        }
    }

    /** Kept in its own class so a dedicated server never loads a client screen. */
    private static final class ClientOnly {
        static void open(String node) {
            if (node == null || node.isEmpty()) {
                net.minecraft.client.Minecraft.getInstance().setScreen(new com.kuronami.questtree.client.ProgressScreen());
            } else {
                com.kuronami.questtree.client.ProgressScreen.openAt(node);
            }
        }
    }
}
