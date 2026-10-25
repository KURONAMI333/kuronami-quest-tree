package com.kuronami.questtree.client.compat;

import com.kuronami.questtree.Constants;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiStack;
import net.minecraft.resources.ResourceLocation;

/** EMI's crate category. */
public final class CrateViewerEmiCategory {

    public static final EmiRecipeCategory CATEGORY = new EmiRecipeCategory(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "crate"),
            EmiStack.of(ViewerIcons.crate()));

    private CrateViewerEmiCategory() {
    }
}
