package com.kuronami.questtree;

import com.kuronami.questtree.client.ClientProgress;
import com.kuronami.questtree.network.EditorReplyPayload;
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
        // phase 1: the editor screen that consumes replies is not built yet; the server still answers every op
        ClientPlayNetworking.registerGlobalReceiver(EditorReplyPayload.TYPE, (payload, context) -> {
        });
        ClientProgress.sender = a -> {
            if (ClientPlayNetworking.canSend(a.type())) {
                ClientPlayNetworking.send(a);
            }
        };
        net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
            if (net.minecraft.client.Minecraft.getInstance().player != null) {
                com.kuronami.questtree.client.ItemTooltips.append(stack, lines);
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientProgress.onLogout());
        // the open key over an item in another screen opens the quest that wants it
        // Fabric makes a screen's key events anew on every init (resize, coming back from another screen), so the
        // listener is added on every init, not once per screen
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.BEFORE_INIT.register((client, screen, w, h) ->
                net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents.allowKeyPress(screen).register((s, key, scan, mods) ->
                        !ClientProgress.onScreenKey(s, key, scan)));
    }
}
