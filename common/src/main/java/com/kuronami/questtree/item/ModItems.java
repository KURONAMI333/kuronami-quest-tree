package com.kuronami.questtree.item;

import net.minecraft.world.item.Item;

/**
 * The one item the mod adds. Each loader registers it and fills this holder, so common code (the first-join gift and
 * the server config) never names a loader class.
 */
public final class ModItems {

    private static Item questBook;
    private static Item taskScreenConfigurator;

    private ModItems() {
    }

    public static Item questBook() {
        return questBook;
    }

    /** Called by the loader's registration; the last one wins, and there is only one. */
    public static void setQuestBook(Item item) {
        questBook = item;
    }

    public static Item taskScreenConfigurator() {
        return taskScreenConfigurator;
    }

    public static void setTaskScreenConfigurator(Item item) {
        taskScreenConfigurator = item;
    }
}
