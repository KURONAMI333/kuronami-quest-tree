package com.kuronami.questtree.client;

import net.minecraft.world.item.ItemStack;

/**
 * The bridge to a recipe viewer (JEI), set by its plugin when the viewer is installed and left empty otherwise, so the
 * screen never needs the viewer at runtime. Traffic goes both ways (2026-10-18, a gap against Tenet): an item in a task
 * or reward opens its recipes or uses, and the open key over an item in the viewer or an inventory opens the quest
 * that wants it.
 */
public interface RecipeViewer {

    void showRecipes(ItemStack stack);

    void showUses(ItemStack stack);

    /** The item under the pointer in the viewer's own lists, or empty. */
    ItemStack underMouse();

    /** The installed viewer, or null. */
    final class Holder {
        static RecipeViewer viewer;

        private Holder() {
        }

        public static void set(RecipeViewer v) {
            viewer = v;
        }

        public static RecipeViewer get() {
            return viewer;
        }
    }
}
