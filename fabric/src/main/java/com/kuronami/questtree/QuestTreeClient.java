package com.kuronami.questtree;

import com.kuronami.questtree.client.ClientProgress;
import com.kuronami.questtree.network.ProgressPayload;
import com.kuronami.questtree.network.SyncPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class QuestTreeClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        KeyBindingHelper.registerKeyBinding(ClientProgress.OPEN_KEY);
        KeyBindingHelper.registerKeyBinding(ClientProgress.TRACK_KEY);
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register((g, delta) -> com.kuronami.questtree.client.ProgressHud.render(g));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> ClientProgress.onClientTick());
        ClientPlayNetworking.registerGlobalReceiver(SyncPayload.TYPE, (payload, context) -> ClientProgress.onSync(payload));
        ClientPlayNetworking.registerGlobalReceiver(ProgressPayload.TYPE, (payload, context) -> ClientProgress.onProgress(payload));
        ClientProgress.sender = a -> {
            if (ClientPlayNetworking.canSend(a.type())) {
                ClientPlayNetworking.send(a);
            }
        };
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientProgress.onLogout());
    }
}
