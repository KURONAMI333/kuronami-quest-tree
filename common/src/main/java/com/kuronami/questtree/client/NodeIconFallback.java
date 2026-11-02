package com.kuronami.questtree.client;

/**
 * What a quest tile shows when it has no icon of its own and its first task has no item to show. FTB Quests draws the
 * first task's icon there (a checkmark task draws a check), so a tile is never an empty frame.
 * <p>
 * The order, from the first that applies: the node's own icon (item, picture or entity face), the first task's icon
 * (its own {@code icon}, else what the task is about; a smart filter steps through its items), and then this symbol, a
 * picture from {@code textures/gui/icon/<name>.png} that a resource pack can replace. An item id this game does not
 * have is not empty: it is shown as the "missing item" barrier.
 */
final class NodeIconFallback {

    /** The picture a checkmark task stands for. */
    static final String CHECK = "tick";
    /** Any other task that has no item to show (an observation of nothing in particular, a custom task type). */
    static final String OTHER = "gear";

    private NodeIconFallback() {
    }

    /**
     * @param drawn          the node names a picture or an entity face as its icon
     * @param itemIconShown  the node's own icon or its first task's icon is an item that can be drawn
     * @param firstTaskType  the type of the first task, or null for a node without tasks
     * @return the {@code textures/gui/icon} name to draw instead, or null when something else already shows
     */
    static String symbol(boolean drawn, boolean itemIconShown, String firstTaskType) {
        if (drawn || itemIconShown || firstTaskType == null) {
            return null;
        }
        return firstTaskType.equals("checkmark") ? CHECK : OTHER;
    }
}
