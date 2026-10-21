package com.kuronami.questtree.client;

/** Small tracker decisions, kept out of the HUD so they can be checked without a game. */
public final class TrackerRules {

    private TrackerRules() {
    }

    /** Auto-pin belongs to the "all" tracker; "pinned only" must stay what its name says. */
    public static boolean autoPin(boolean autoPin, boolean modeAll) {
        return autoPin && modeAll;
    }
}
