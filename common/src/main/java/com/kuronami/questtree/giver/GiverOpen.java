package com.kuronami.questtree.giver;

/**
 * What a giver click should show, decided from the target and the player's own view of the book. Kept apart from the
 * screen so the hidden and locked rules are one small table that a test can read.
 */
public final class GiverOpen {

    public enum Mode {
        /** Open the book on this quest. */
        NODE,
        /** Open the book on this chapter. */
        CHAPTER,
        /** Open the book at the chapter list only: a locked chapter shows its hint there, not its map. */
        RAIL,
        /** Show nothing: the target is hidden from this player, and opening anything would reveal it. */
        NONE
    }

    private GiverOpen() {
    }

    /**
     * @param target       the linked quest or chapter
     * @param alwaysHidden the target's chapter is marked hidden as a whole
     * @param unlocked     the target's chapter is open to this player
     * @param nodeVisible  the target quest is visible to this player (ignored for a chapter target)
     */
    public static Mode mode(GiverTarget target, boolean alwaysHidden, boolean unlocked, boolean nodeVisible) {
        if (target == null || alwaysHidden) {
            return Mode.NONE;
        }
        if (!unlocked) {
            return Mode.RAIL;
        }
        if (target.chapter()) {
            return Mode.CHAPTER;
        }
        return nodeVisible ? Mode.NODE : Mode.NONE;
    }
}
