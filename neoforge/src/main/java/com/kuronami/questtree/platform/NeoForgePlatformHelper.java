package com.kuronami.questtree.platform;

import com.kuronami.questtree.platform.services.IPlatformHelper;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.file.Path;

public class NeoForgePlatformHelper implements IPlatformHelper {

    @Override
    public String getPlatformName() {
        return "NeoForge";
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLLoader.isProduction();
    }

    @Override
    public Path getGameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        // the registrar is optional, so vanilla/other clients can join; sending to them would throw and kick them
        if (player.connection.hasChannel(payload.type())) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    @Override
    public void openProgressionScreen() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientOnly.open();
        }
    }

    /** Kept in its own class so a dedicated server never loads a client screen. */
    private static final class ClientOnly {
        static void open() {
            net.minecraft.client.Minecraft.getInstance().setScreen(new com.kuronami.questtree.client.ProgressScreen());
        }
    }
}
