package com.kuronami.questtree.client.compat;

import com.kuronami.questtree.client.viewer.QuestViewerData;
import com.kuronami.questtree.client.viewer.QuestViewerIndex.Hit;
import me.shedaniel.rei.api.client.registry.display.DynamicDisplayGenerator;
import me.shedaniel.rei.api.common.entry.EntryStack;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

/**
 * Answers REI's lookups from the live index. The index changes when a sync packet arrives, so the same generator keeps
 * returning the current quests without a resource reload.
 */
public final class QuestViewerReiGenerator implements DynamicDisplayGenerator<QuestViewerReiDisplay> {

    @Override
    public Optional<List<QuestViewerReiDisplay>> getRecipeFor(EntryStack<?> stack) {
        return find(stack, false);
    }

    @Override
    public Optional<List<QuestViewerReiDisplay>> getUsageFor(EntryStack<?> stack) {
        return find(stack, true);
    }

    private static Optional<List<QuestViewerReiDisplay>> find(EntryStack<?> stack, boolean uses) {
        if (!(stack.getValue() instanceof ItemStack item) || item.isEmpty()) {
            return Optional.empty();
        }
        var index = QuestViewerData.index();
        List<Hit> hits;
        if (item.is(QuestViewerRecipe.book().getItem())) {
            hits = index.hits();
        } else {
            var query = QuestViewerData.query(item);
            hits = uses ? index.uses(query) : index.rewards(query);
        }
        List<QuestViewerReiDisplay> displays = hits.stream()
                .map(QuestViewerRecipe::new)
                .filter(recipe -> !recipe.stacks().isEmpty())
                .map(QuestViewerReiDisplay::new)
                .toList();
        return displays.isEmpty() ? Optional.empty() : Optional.of(displays);
    }
}
