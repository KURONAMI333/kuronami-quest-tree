package com.kuronami.questtree;

import com.kuronami.questtree.client.ClientProgress;
import com.kuronami.questtree.network.ActionPayload;
import com.kuronami.questtree.network.EditorOpPayload;
import com.kuronami.questtree.network.EditorReplyPayload;
import com.kuronami.questtree.network.ProgressPayload;
import com.kuronami.questtree.network.SyncPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@Mod(Constants.MOD_ID)
public class QuestTree {

    public QuestTree(IEventBus modBus) {
        CommonClass.init();
        modBus.addListener(QuestTree::registerPayloads);
        NeoForge.EVENT_BUS.addListener(QuestTree::addReloadListener);
        NeoForge.EVENT_BUS.addListener((OnDatapackSyncEvent e) -> e.getRelevantPlayers().forEach(CommonClass::onSyncToPlayer));
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> CommonClass.registerCommands(e.getDispatcher()));
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> CommonClass.onServerStarted(e.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> CommonClass.onServerStopped());
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> CommonClass.onServerTick(e.getServer()));
        NeoForge.EVENT_BUS.addListener((PlayerTickEvent.Post e) -> {
            if (e.getEntity() instanceof ServerPlayer sp) {
                CommonClass.onPlayerTick(sp);
            }
        });
        NeoForge.EVENT_BUS.addListener((LivingDeathEvent e) -> {
            if (!e.getEntity().level().isClientSide() && e.getSource().getEntity() instanceof ServerPlayer sp) {
                CommonClass.onKill(sp, e.getEntity());
            }
        });
        // lowest priority and not canceled: only breaks that really happen give experience
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, (BlockEvent.BreakEvent e) -> {
            if (!e.isCanceled() && e.getPlayer() instanceof ServerPlayer sp) {
                CommonClass.onBlockBroken(sp, e.getState());
            }
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post e) -> {
            if (e.getEntity().level().isClientSide() || e.getNewDamage() <= 0) {
                return;
            }
            if (e.getEntity() instanceof ServerPlayer sp) {
                CommonClass.onTakeDamage(sp, e.getSource(), e.getNewDamage());
            }
            if (e.getSource().getEntity() instanceof ServerPlayer sp && e.getEntity() != sp) {
                CommonClass.onDealDamage(sp, e.getEntity(), e.getSource(), e.getNewDamage());
            }
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerRespawnEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer sp) {
                CommonClass.onRespawn(sp, !e.isEndConquered());
            }
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer sp) {
                CommonClass.onLogout(sp);
            }
        });
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        // optional: a client without the mod can still join a server that has it
        var r = event.registrar("1").optional();
        r.playToClient(SyncPayload.TYPE, SyncPayload.STREAM_CODEC, (payload, context) -> ClientProgress.onSync(payload));
        r.playToClient(ProgressPayload.TYPE, ProgressPayload.STREAM_CODEC, (payload, context) -> ClientProgress.onProgress(payload));
        // phase 1: no editor screen consumes replies yet, but the server answers every op
        r.playToClient(EditorReplyPayload.TYPE, EditorReplyPayload.STREAM_CODEC, (payload, context) -> {
        });
        r.playToServer(ActionPayload.TYPE, ActionPayload.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer sp) {
                CommonClass.onAction(sp, payload);
            }
        });
        r.playToServer(EditorOpPayload.TYPE, EditorOpPayload.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer sp) {
                CommonClass.onEditorOp(sp, payload);
            }
        });
    }

    private static void addReloadListener(AddReloadListenerEvent event) {
        event.addListener(new SimplePreparableReloadListener<Void>() {
            @Override
            protected Void prepare(ResourceManager rm, ProfilerFiller profiler) {
                return null;
            }

            @Override
            protected void apply(Void nothing, ResourceManager rm, ProfilerFiller profiler) {
                CommonClass.onDataReload(rm);
            }
        });
    }
}
