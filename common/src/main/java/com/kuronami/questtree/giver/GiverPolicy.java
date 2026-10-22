package com.kuronami.questtree.giver;

/**
 * Who may change a quest giver. Linking is an administrative action like editing the book: permission level 2, and
 * only from a player, because the target is what that player is looking at (2026-10-22, G3).
 */
public final class GiverPolicy {

    public static final int PERMISSION = 2;

    private GiverPolicy() {
    }

    public static boolean mayManage(boolean allowed, boolean playerSource) {
        return playerSource && allowed;
    }
}
