package com.kuronami.questtree;

import com.kuronami.questtree.client.ClientProgress;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/** Client-only hooks: the open key and its polling. */
public final class QuestTreeClient {

    private QuestTreeClient() {
    }

    @EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class ModEvents {
        @SubscribeEvent
        public static void keys(RegisterKeyMappingsEvent event) {
            event.register(ClientProgress.OPEN_KEY);
            event.register(ClientProgress.TRACK_KEY);
            ClientProgress.sender = a -> {
                var conn = net.minecraft.client.Minecraft.getInstance().getConnection();
                if (conn != null && conn.hasChannel(a.type())) {
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(a);
                }
            };
        }
    }

    @EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class HudEvents {
        @SubscribeEvent
        public static void layers(net.neoforged.neoforge.client.event.RegisterGuiLayersEvent event) {
            event.registerAboveAll(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "hud"),
                    (g, delta) -> com.kuronami.questtree.client.ProgressHud.render(g));
        }
    }

    @EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
    public static final class GameEvents {
        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            ClientProgress.onClientTick();
        }

        @SubscribeEvent
        public static void tooltip(net.neoforged.neoforge.event.entity.player.ItemTooltipEvent event) {
            if (event.getEntity() != null) {
                com.kuronami.questtree.client.ItemTooltips.append(event.getItemStack(), event.getToolTip());
            }
        }

        /** The open key over an item in another screen opens the quest that wants it. */
        @SubscribeEvent
        public static void screenKey(net.neoforged.neoforge.client.event.ScreenEvent.KeyPressed.Pre event) {
            if (ClientProgress.onScreenKey(event.getScreen(), event.getKeyCode(), event.getScanCode())) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
            ClientProgress.onLogout();
        }
    }
}
