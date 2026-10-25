package com.kuronami.questtree.client;

import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * The bridge to a recipe viewer (JEI), set by its plugin when the viewer is installed and left empty otherwise, so the
 * screen never needs the viewer at runtime. Traffic goes both ways (2026-10-18, a gap against Tenet): an item in a task
 * or reward opens its recipes or uses, and the open key over an item in the viewer or an inventory opens the quest
 * that wants it.
 */
public interface RecipeViewer {

    void showRecipes(ItemStack stack);

    void showUses(ItemStack stack);

    /** The uses of {@code stack} in the quest category only; viewers without one fall back to all uses. */
    default void showQuestUses(ItemStack stack) {
        showUses(stack);
    }

    /** Opens the crate category (one crate and its possible rewards); viewers without one do nothing. */
    default void showCrates() {
    }

    /** Opens the crate category on one table's card; viewers that cannot focus a card show the whole category. */
    default void showCrates(String tableId) {
        showCrates();
    }

    /** Whether the viewer's own search field is being typed in. */
    boolean typing();

    /** Whether the press is the viewer's bookmark key (JEI's own key binding), so its bookmarks stay one concept. */
    default boolean isBookmarkKey(int key, int scan) {
        return false;
    }

    /**
     * Adds or removes {@code stack} in the viewer's bookmarks: {@code true} added, {@code false} removed, {@code null}
     * when this viewer has no bookmarks. Only JEI has them.
     */
    default Boolean toggleBookmark(ItemStack stack) {
        return null;
    }

    /** The item under the pointer in the viewer's own lists, or empty. */
    ItemStack underMouse();

    /**
     * The installed viewers, keyed by plugin. More than one can be present (a pack may ship JEI and a viewer that
     * embeds it), and the screen still talks to one: EMI first, then REI, then JEI, the order of their features.
     */
    final class Holder {
        private static final String[] PRIORITY = {"emi", "rei", "jei"};
        private static volatile Map<String, RecipeViewer> viewers = Map.of();
        private static volatile RecipeViewer viewer;

        private Holder() {
        }

        /** Registers a viewer under its plugin name, or removes it when {@code v} is null. */
        public static synchronized void set(String id, RecipeViewer v) {
            if (id == null) {
                return;
            }
            Map<String, RecipeViewer> next = new HashMap<>(viewers);
            if (v == null) {
                next.remove(id);
            } else {
                next.put(id, v);
            }
            viewers = Map.copyOf(next);
            RecipeViewer chosen = null;
            for (String name : PRIORITY) {
                chosen = next.get(name);
                if (chosen != null) {
                    break;
                }
            }
            viewer = chosen;
        }

        public static RecipeViewer get() {
            return viewer;
        }
    }
}
