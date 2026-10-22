package com.kuronami.questtree.client;

import net.minecraft.client.Minecraft;

/**
 * The action behind FTB Library's sidebar button (2026-10-22). The button is declared in
 * {@code assets/kuronami_quest_tree/sidebar_buttons/quest_book.json}; its {@code static_method:} click reaches the
 * public no-argument {@link #openQuestBook()} when FTB Library's sidebar is on screen. FTB Library's common artifact
 * carries intermediary names, so this class does not link against its classes: it is loaded only when the click runs.
 */
public final class FtbSidebarButton {

    private FtbSidebarButton() {
    }

    public static void openQuestBook() {
        ProgressScreen.open();
    }
}
