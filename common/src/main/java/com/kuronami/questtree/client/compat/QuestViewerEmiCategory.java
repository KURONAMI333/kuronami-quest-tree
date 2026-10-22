package com.kuronami.questtree.client.compat;

import com.kuronami.questtree.Constants;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiStack;
import net.minecraft.resources.ResourceLocation;

/** EMI's category, shown with the quest book icon and the same state wording as the other two viewers. */
public final class QuestViewerEmiCategory {

    public static final EmiRecipeCategory CATEGORY = new EmiRecipeCategory(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "quest"),
            EmiStack.of(QuestViewerRecipe.book()));

    private QuestViewerEmiCategory() {
    }
}
