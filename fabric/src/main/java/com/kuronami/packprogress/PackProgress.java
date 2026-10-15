package com.kuronami.packprogress;

import com.kuronami.packprogress.network.SyncPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

public class PackProgress implements ModInitializer {

    @Override
    public void onInitialize() {
        CommonClass.init();
        PayloadTypeRegistry.playS2C().register(SyncPayload.TYPE, SyncPayload.STREAM_CODEC);
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override
            public ResourceLocation getFabricId() {
                return ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "progression");
            }

            @Override
            public void onResourceManagerReload(ResourceManager rm) {
                CommonClass.onDataReload(rm);
            }
        });
        // fires for every player on join and after /reload
        ServerLifecycleEvents.SYNC_DATA_PACK_CONTENTS.register((player, joined) -> CommonClass.onSyncToPlayer(player));
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> CommonClass.registerCommands(dispatcher));
    }
}
