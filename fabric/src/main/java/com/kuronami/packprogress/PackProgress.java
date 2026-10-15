package com.kuronami.packprogress;

import com.kuronami.packprogress.network.ActionPayload;
import com.kuronami.packprogress.network.ProgressPayload;
import com.kuronami.packprogress.network.SyncPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

public class PackProgress implements ModInitializer {

    @Override
    public void onInitialize() {
        CommonClass.init();
        PayloadTypeRegistry.playS2C().register(SyncPayload.TYPE, SyncPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(ProgressPayload.TYPE, ProgressPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(ActionPayload.TYPE, ActionPayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ActionPayload.TYPE, (payload, context) -> CommonClass.onAction(context.player(), payload));
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
        ServerTickEvents.END_SERVER_TICK.register(server -> server.getPlayerList().getPlayers().forEach(CommonClass::onPlayerTick));
        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((level, killer, killed) -> {
            if (killer instanceof ServerPlayer sp) {
                CommonClass.onKill(sp, killed);
            }
        });
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            if (player instanceof ServerPlayer sp) {
                CommonClass.onBlockBroken(sp, state);
            }
        });
        net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> CommonClass.onRespawn(newPlayer));
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (taken <= 0) {
                return;
            }
            if (entity instanceof ServerPlayer sp) {
                CommonClass.onTakeDamage(sp, source, taken);
            }
            if (source.getEntity() instanceof ServerPlayer sp && entity != sp) {
                CommonClass.onDealDamage(sp, entity, source, taken);
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> CommonClass.onLogout(handler.player));
    }
}
