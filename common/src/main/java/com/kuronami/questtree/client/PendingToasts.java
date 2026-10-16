package com.kuronami.questtree.client;

import net.minecraft.client.Minecraft;

/**
 * The mod's own toasts while the progress screen is open. The screen already animates the same quest and experience
 * events, so the vanilla toasts would double them and cover the details sheet; they are queued and released when the
 * screen closes.
 */
final class PendingToasts {

    /** Set while the queue is released, so releasing does not just queue the toasts again (the screen is still current). */
    private static boolean flushing;

    private PendingToasts() {
    }

    static boolean suppressed() {
        return !flushing && Minecraft.getInstance().screen instanceof ProgressScreen;
    }

    static void beginFlush() {
        flushing = true;
    }

    static void endFlush() {
        flushing = false;
    }
}
