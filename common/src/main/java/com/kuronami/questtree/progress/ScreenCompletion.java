package com.kuronami.questtree.progress;

/**
 * When a task screen's insertion may be completed on the spot (2026-10-23 review). Completion pays rewards to the
 * actor, so a screen used while its owner is offline must wait for the owner's login recheck instead of completing in
 * the body of whichever team member happens to be online.
 */
public final class ScreenCompletion {

    private ScreenCompletion() {
    }

    /**
     * True only when the owner is online. The check is a plain flag so it can be tested without a server; the caller
     * resolves it from the player list.
     */
    public static boolean shouldCompleteNow(boolean ownerOnline) {
        return ownerOnline;
    }
}
