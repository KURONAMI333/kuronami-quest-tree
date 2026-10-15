package com.kuronami.packprogress;

import com.kuronami.packprogress.client.ClientProgress;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/** Client-only hooks: the open key and its polling. */
public final class PackProgressClient {

    private PackProgressClient() {
    }

    @EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class ModEvents {
        @SubscribeEvent
        public static void keys(RegisterKeyMappingsEvent event) {
            event.register(ClientProgress.OPEN_KEY);
        }
    }

    @EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
    public static final class GameEvents {
        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            ClientProgress.onClientTick();
        }

        @SubscribeEvent
        public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
            ClientProgress.onLogout();
        }
    }
}
