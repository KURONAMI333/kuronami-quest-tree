package com.kuronami.packprogress;

import com.kuronami.packprogress.client.ClientProgress;
import com.kuronami.packprogress.network.SyncPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class PackProgressClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        KeyBindingHelper.registerKeyBinding(ClientProgress.OPEN_KEY);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> ClientProgress.onClientTick());
        ClientPlayNetworking.registerGlobalReceiver(SyncPayload.TYPE, (payload, context) -> ClientProgress.onSync(payload));
    }
}
