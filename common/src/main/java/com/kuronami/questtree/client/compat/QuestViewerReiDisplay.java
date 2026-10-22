package com.kuronami.questtree.client.compat;

import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.display.basic.BasicDisplay;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.util.EntryStacks;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

/** One quest entry as REI sees it. It is generated on demand, never saved, so it only refers to the live index. */
public final class QuestViewerReiDisplay extends BasicDisplay {

    private final QuestViewerRecipe recipe;

    public QuestViewerReiDisplay(QuestViewerRecipe recipe) {
        super(inputs(recipe), outputs(recipe), Optional.of(recipe.id()));
        this.recipe = recipe;
    }

    public QuestViewerRecipe recipe() {
        return recipe;
    }

    @Override
    public CategoryIdentifier<? extends QuestViewerReiDisplay> getCategoryIdentifier() {
        return QuestViewerReiCategory.ID;
    }

    private static List<EntryIngredient> inputs(QuestViewerRecipe recipe) {
        return recipe.needsItem() ? List.of(entries(recipe.stacks())) : List.of();
    }

    private static List<EntryIngredient> outputs(QuestViewerRecipe recipe) {
        return recipe.needsItem() ? List.of() : List.of(entries(recipe.stacks()));
    }

    private static EntryIngredient entries(List<ItemStack> stacks) {
        List<EntryStack<ItemStack>> entries = stacks.stream().map(EntryStacks::of).toList();
        return EntryIngredient.of(entries);
    }
}
