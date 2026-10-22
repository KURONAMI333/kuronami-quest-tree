package com.kuronami.questtree.client.compat;

import me.shedaniel.rei.api.client.registry.display.DynamicDisplayGenerator;
import me.shedaniel.rei.api.common.entry.EntryStack;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

/** Answers REI's lookups with the crates that hold the focused item, and with the focused crate itself. */
public final class CrateViewerReiGenerator implements DynamicDisplayGenerator<CrateViewerReiDisplay> {

    @Override
    public Optional<List<CrateViewerReiDisplay>> getRecipeFor(EntryStack<?> stack) {
        return find(stack);
    }

    @Override
    public Optional<List<CrateViewerReiDisplay>> getUsageFor(EntryStack<?> stack) {
        return find(stack);
    }

    private static Optional<List<CrateViewerReiDisplay>> find(EntryStack<?> stack) {
        if (!(stack.getValue() instanceof ItemStack item) || item.isEmpty()) {
            return Optional.empty();
        }
        List<CrateViewerReiDisplay> displays = CrateViewerData.matching(item).stream()
                .map(CrateViewerReiDisplay::new)
                .toList();
        return displays.isEmpty() ? Optional.empty() : Optional.of(displays);
    }
}
