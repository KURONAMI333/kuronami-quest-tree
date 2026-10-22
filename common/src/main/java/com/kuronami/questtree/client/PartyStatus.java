package com.kuronami.questtree.client;

import net.minecraft.network.chat.Component;

/** The last party action's result, shown inside the party screen instead of chat. */
final class PartyStatus {

    private static final long SHOW = 4000;

    private static Component text;
    private static long until;

    private PartyStatus() {
    }

    static void set(Component value) {
        text = value;
        until = System.currentTimeMillis() + SHOW;
    }

    static Component text(long now) {
        return text != null && now < until ? text : null;
    }

    static void clear() {
        text = null;
        until = 0;
    }
}
