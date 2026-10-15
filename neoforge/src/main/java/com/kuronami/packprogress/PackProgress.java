package com.kuronami.packprogress;

import com.kuronami.packprogress.client.ClientProgress;
import com.kuronami.packprogress.network.SyncPayload;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@Mod(Constants.MOD_ID)
public class PackProgress {

    public PackProgress(IEventBus modBus) {
        CommonClass.init();
        modBus.addListener(PackProgress::registerPayloads);
        NeoForge.EVENT_BUS.addListener(PackProgress::addReloadListener);
        NeoForge.EVENT_BUS.addListener((OnDatapackSyncEvent e) -> e.getRelevantPlayers().forEach(CommonClass::onSyncToPlayer));
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> CommonClass.registerCommands(e.getDispatcher()));
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        // optional: a client without the mod can still join a server that has it
        event.registrar("1").optional().playToClient(SyncPayload.TYPE, SyncPayload.STREAM_CODEC,
                (payload, context) -> ClientProgress.onSync(payload));
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
