package com.kuronami.questtree.client.compat;

import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.display.basic.BasicDisplay;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.util.EntryStacks;

import java.util.List;
import java.util.Optional;

/** One crate as REI sees it: the crate in, every item it can give out. Generated on demand, never saved. */
public final class CrateViewerReiDisplay extends BasicDisplay {

    private final CrateViewerData.Recipe recipe;

    public CrateViewerReiDisplay(CrateViewerData.Recipe recipe) {
        super(List.of(EntryIngredient.of(EntryStacks.of(recipe.crate()))),
                List.of(EntryIngredient.of(recipe.contents().stream()
                        .map(content -> EntryStacks.of(content.stack())).toList())),
                Optional.of(recipe.id()));
        this.recipe = recipe;
    }

    public CrateViewerData.Recipe recipe() {
        return recipe;
    }

    @Override
    public CategoryIdentifier<? extends CrateViewerReiDisplay> getCategoryIdentifier() {
        return CrateViewerReiCategory.ID;
    }
}
