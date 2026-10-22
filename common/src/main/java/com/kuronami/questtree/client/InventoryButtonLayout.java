package com.kuronami.questtree.client;

/**
 * Where the inventory quest book button goes: centred on the player preview (x 26..75 in the 176-wide panel) and in
 * the open space just above the panel. Kept as plain arithmetic so the position can be tested without a screen.
 */
final class InventoryButtonLayout {

    static final int WIDTH = 20;
    static final int HEIGHT = 18;
    /** The player preview's centre (26..75) minus half the button width. */
    private static final int PREVIEW_CENTRE = 40;
    /** Two GUI pixels between the button and the panel's top edge. */
    private static final int GAP = 2;

    private InventoryButtonLayout() {
    }

    static int x(int leftPos) {
        return leftPos + PREVIEW_CENTRE;
    }

    static int y(int topPos) {
        return topPos - HEIGHT - GAP;
    }

    /** False on a screen too short to leave room above the panel; the button hides rather than cover the preview. */
    static boolean fits(int topPos) {
        return y(topPos) >= 0;
    }
}
