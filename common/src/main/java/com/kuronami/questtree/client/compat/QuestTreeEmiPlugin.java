package com.kuronami.questtree.client.compat;

import com.kuronami.questtree.client.RecipeViewer;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.EmiStackInteraction;
import net.minecraft.world.item.ItemStack;

/**
 * EMI's side of {@link RecipeViewer}. The class carries no annotation: the Fabric entrypoint names it, and the NeoForge
 * subclass is the one EMI's annotation scanner finds. It is only loaded when EMI is installed.
 */
public class QuestTreeEmiPlugin implements EmiPlugin {

    @Override
    public void register(EmiRegistry registry) {
        RecipeViewer.Holder.set(new Viewer());
    }

    private static final class Viewer implements RecipeViewer {

        @Override
        public void showRecipes(ItemStack stack) {
            if (!stack.isEmpty()) {
                EmiApi.displayRecipes(EmiStack.of(stack.copyWithCount(1)));
            }
        }

        @Override
        public void showUses(ItemStack stack) {
            if (!stack.isEmpty()) {
                EmiApi.displayUses(EmiStack.of(stack.copyWithCount(1)));
            }
        }

        @Override
        public boolean typing() {
            return EmiApi.isSearchFocused();
        }

        @Override
        public ItemStack underMouse() {
            EmiStackInteraction hit = EmiApi.getHoveredStack(false);
            if (hit == null || hit.isEmpty()) {
                return ItemStack.EMPTY;
            }
            for (EmiStack stack : hit.getStack().getEmiStacks()) {
                ItemStack item = stack.getItemStack();
                if (!item.isEmpty()) {
                    return item;
                }
            }
            return ItemStack.EMPTY;
        }
    }
}
