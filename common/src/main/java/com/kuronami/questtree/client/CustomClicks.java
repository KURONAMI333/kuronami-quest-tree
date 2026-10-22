package com.kuronami.questtree.client;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * FTB Quests' {@code custom_event} image click, for addons that want to react to a picture in a quest book. The event
 * arrives on the client, where the click happened; a listener that needs the server sends its own message.
 */
public final class CustomClicks {

    private static final List<Consumer<ResourceLocation>> LISTENERS = new CopyOnWriteArrayList<>();

    private CustomClicks() {
    }

    public static void register(Consumer<ResourceLocation> listener) {
        if (listener != null) {
            LISTENERS.add(listener);
        }
    }

    public static void fire(ResourceLocation event) {
        if (event == null) {
            return;
        }
        for (Consumer<ResourceLocation> listener : LISTENERS) {
            listener.accept(event);
        }
    }
}
