package com.kuronami.questtree.client;

/**
 * Where the inventory quest book button goes: centred on the player preview (x 26..75 in the 176-wide panel) and at
 * the very top of the screen. Not right above the panel: other mods put tabs there (2026-10-22, the player). Kept as plain arithmetic so the position can be tested without a screen.
 */
final class InventoryButtonLayout {

    static final int WIDTH = 20;
    static final int HEIGHT = 18;
    /** The player preview's centre (26..75) minus half the button width. */
    private static final int PREVIEW_CENTRE = 40;
    /** Two GUI pixels from the top of the screen. */
    private static final int TOP = 2;
    /** Room kept free between the button and the panel for other mods' tabs. */
    private static final int TABS = 28;

    private InventoryButtonLayout() {
    }

    static int x(int leftPos) {
        return leftPos + PREVIEW_CENTRE;
    }

    static int y(int topPos) {
        return TOP;
    }

    /** False on a screen too short to keep the tab room free; the button hides rather than sit on other mods' tabs. */
    static boolean fits(int topPos) {
        return TOP + HEIGHT + TABS <= topPos;
    }
}
