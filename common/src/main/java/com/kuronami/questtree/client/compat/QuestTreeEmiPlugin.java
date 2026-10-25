package com.kuronami.questtree.client.compat;

import com.kuronami.questtree.Constants;
import com.kuronami.questtree.client.RecipeViewer;
import com.kuronami.questtree.client.viewer.QuestViewerData;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.EmiStackInteraction;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * EMI's side of {@link RecipeViewer}. The class carries no annotation: the Fabric entrypoint names it, and the NeoForge
 * subclass is the one EMI's annotation scanner finds. It is only loaded when EMI is installed.
 */
public class QuestTreeEmiPlugin implements EmiPlugin {

    private static final AtomicBoolean WARNED = new AtomicBoolean();

    @Override
    public void register(EmiRegistry registry) {
        registry.addCategory(QuestViewerEmiCategory.CATEGORY);
        registry.addCategory(CrateViewerEmiCategory.CATEGORY);
        registry.addWorkstation(QuestViewerEmiCategory.CATEGORY, EmiStack.of(QuestViewerRecipe.book()));
        registry.addWorkstation(CrateViewerEmiCategory.CATEGORY, EmiStack.of(ViewerIcons.crate()));
        for (var crate : CrateViewerData.all()) {
            if (!crate.contents().isEmpty()) {
                registry.addRecipe(new CrateViewerEmiRecipe(crate));
            }
        }
        for (var hit : QuestViewerData.index().hits()) {
            QuestViewerRecipe recipe = new QuestViewerRecipe(hit);
            if (!recipe.stacks().isEmpty()) {
                registry.addRecipe(new QuestViewerEmiRecipe(recipe));
            }
        }
        QuestViewerData.setRefreshHook(QuestTreeEmiPlugin::reload);
        RecipeViewer.Holder.set("emi", new Viewer());
    }

    /**
     * EMI builds its list once per reload; ask it to rebuild after the index has been quiet. Returns {@code false}
     * while EMI is still loading (the caller keeps the request pending) and {@code true} once it was asked.
     */
    private static boolean reload() {
        if (Minecraft.getInstance().level == null) {
            return false;
        }
        try {
            Class<?> manager = Class.forName("dev.emi.emi.runtime.EmiReloadManager");
            if (!(Boolean) manager.getMethod("isLoaded").invoke(null)) {
                return false;
            }
            manager.getMethod("reload").invoke(null);
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            if (WARNED.compareAndSet(false, true)) {
                Constants.LOG.warn("EMI is present but its reload entry point could not be called", ex);
            }
            return true;
        }
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
