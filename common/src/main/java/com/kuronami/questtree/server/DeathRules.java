package com.kuronami.questtree.server;

/** Whether a death should drop the quest book, kept pure so the keepInventory and cancelled cases can be tested. */
public final class DeathRules {

    private DeathRules() {
    }

    public static boolean dropBook(boolean configured, boolean keepInventory, boolean cancelled) {
        return configured && !keepInventory && !cancelled;
    }
}
