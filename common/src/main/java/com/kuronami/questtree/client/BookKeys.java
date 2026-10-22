package com.kuronami.questtree.client;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

/**
 * Every key the book itself uses, registered so the player can rebind it in vanilla's controls screen (2026-10-22,
 * FTB Quests parity). Modifiers are not part of a vanilla {@link KeyMapping}, so the control keys keep the modifier
 * the book has always asked for: {@link #ctrl} for search, preferences and the editor's clipboard actions, and
 * {@link #shift} for the previous chapter. Rebind the letter and the modifier stays the same.
 */
public final class BookKeys {

    private static final String CATEGORY = "key.categories.kuronami_quest_tree";

    public static final KeyMapping SEARCH = key("search", GLFW.GLFW_KEY_F);
    public static final KeyMapping PREFS = key("prefs", GLFW.GLFW_KEY_P);
    public static final KeyMapping RECENTER = key("recenter", GLFW.GLFW_KEY_SPACE);
    public static final KeyMapping ZOOM_IN = key("zoom_in", GLFW.GLFW_KEY_EQUAL);
    public static final KeyMapping ZOOM_OUT = key("zoom_out", GLFW.GLFW_KEY_MINUS);
    public static final KeyMapping ZOOM_RESET = key("zoom_reset", GLFW.GLFW_KEY_0);
    public static final KeyMapping INFO = key("info", GLFW.GLFW_KEY_F1);
    public static final KeyMapping NEXT_CHAPTER = key("next_chapter", GLFW.GLFW_KEY_TAB);
    public static final KeyMapping PREV_CHAPTER = key("prev_chapter", GLFW.GLFW_KEY_TAB);

    public static final KeyMapping UNDO = key("editor_undo", GLFW.GLFW_KEY_Z);
    public static final KeyMapping REDO = key("editor_redo", GLFW.GLFW_KEY_Y);
    public static final KeyMapping SELECT_ALL = key("editor_select_all", GLFW.GLFW_KEY_A);
    public static final KeyMapping COPY = key("editor_copy", GLFW.GLFW_KEY_C);
    public static final KeyMapping PASTE = key("editor_paste", GLFW.GLFW_KEY_V);
    public static final KeyMapping DELETE = key("editor_delete", GLFW.GLFW_KEY_DELETE);
    public static final KeyMapping LINK = key("editor_link", GLFW.GLFW_KEY_L);

    private BookKeys() {
    }

    private static KeyMapping key(String name, int code) {
        return new KeyMapping("key.kuronami_quest_tree." + name, com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM,
                code, CATEGORY);
    }

    /** The mapping's key, with Ctrl held (a plain press does not count). */
    public static boolean ctrl(KeyMapping mapping, int key, int scan) {
        return Screen.hasControlDown() && mapping.matches(key, scan);
    }

    /** The mapping's key, with Shift held. */
    public static boolean shift(KeyMapping mapping, int key, int scan) {
        return Screen.hasShiftDown() && mapping.matches(key, scan);
    }

    /** The mapping's key with neither Ctrl nor Shift, for actions a modifier would change. */
    public static boolean plain(KeyMapping mapping, int key, int scan) {
        return !Screen.hasControlDown() && !Screen.hasShiftDown() && mapping.matches(key, scan);
    }
}
