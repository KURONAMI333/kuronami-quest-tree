package com.kuronami.questtree.client.compat;

import com.kuronami.questtree.item.ModItems;
import net.minecraft.world.item.ItemStack;

/**
 * The item stacks the recipe-viewer categories use as their icon. Kept in a class with no viewer API on purpose:
 * REI and EMI call this, and loading a class that implements a JEI interface fails when JEI is not installed.
 */
final class ViewerIcons {

    private ViewerIcons() {
    }

    static ItemStack crate() {
        ItemStack stack = ModItems.lootCrate() == null ? ItemStack.EMPTY : new ItemStack(ModItems.lootCrate());
        return stack.isEmpty() ? QuestViewerRecipe.book() : stack;
    }
}
