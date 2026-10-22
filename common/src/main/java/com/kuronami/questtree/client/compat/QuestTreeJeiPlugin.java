package com.kuronami.questtree.client.compat;

import com.kuronami.questtree.Constants;
import com.kuronami.questtree.client.RecipeViewer;
import com.kuronami.questtree.client.viewer.QuestViewerData;
import com.kuronami.questtree.client.viewer.QuestViewerIndex;
import com.kuronami.questtree.client.viewer.QuestViewerIndex.Hit;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.advanced.IRecipeManagerPlugin;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.registration.IAdvancedRegistration;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

/**
 * JEI's side of {@link RecipeViewer}. JEI finds this class itself (the annotation on NeoForge, the {@code jei_mod_plugin}
 * entrypoint on Fabric), so it is only ever loaded when JEI is installed.
 */
@JeiPlugin
public final class QuestTreeJeiPlugin implements IModPlugin {

    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "jei");
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        registration.addRecipeCategories(new QuestViewerJeiCategory(registration.getJeiHelpers().getGuiHelper()));
    }

    @Override
    public void registerAdvanced(IAdvancedRegistration registration) {
        registration.addRecipeManagerPlugin(new QuestViewerRecipeManager());
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        registration.addRecipeCatalyst(QuestViewerRecipe.book(), QuestViewerJeiCategory.TYPE);
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        RecipeViewer.Holder.set("jei", new RecipeViewer() {
            @Override
            public void showRecipes(ItemStack stack) {
                show(runtime, stack, RecipeIngredientRole.OUTPUT);
            }

            @Override
            public void showUses(ItemStack stack) {
                show(runtime, stack, RecipeIngredientRole.INPUT);
            }

            @Override
            public void showQuestUses(ItemStack stack) {
                if (stack.isEmpty()) {
                    return;
                }
                var focus = runtime.getJeiHelpers().getFocusFactory()
                        .createFocus(RecipeIngredientRole.INPUT, VanillaTypes.ITEM_STACK, stack.copyWithCount(1));
                var manager = runtime.getRecipeManager();
                var category = manager.getRecipeCategory(QuestViewerJeiCategory.TYPE);
                List<QuestViewerRecipe> recipes = manager.createRecipeLookup(QuestViewerJeiCategory.TYPE)
                        .limitFocus(List.of(focus)).get().toList();
                runtime.getRecipesGui().showRecipes(category, recipes, List.of(focus));
            }

            @Override
            public boolean typing() {
                return runtime.getIngredientListOverlay().hasKeyboardFocus();
            }

            @Override
            public ItemStack underMouse() {
                ItemStack s = stackOf(runtime.getIngredientListOverlay().getIngredientUnderMouse());
                if (s.isEmpty()) {
                    s = stackOf(runtime.getBookmarkOverlay().getIngredientUnderMouse());
                }
                if (s.isEmpty()) {
                    s = runtime.getRecipesGui().getIngredientUnderMouse(VanillaTypes.ITEM_STACK).orElse(ItemStack.EMPTY);
                }
                return s;
            }
        });
    }

    @Override
    public void onRuntimeUnavailable() {
        RecipeViewer.Holder.set("jei", null);
    }

    private static void show(IJeiRuntime runtime, ItemStack stack, RecipeIngredientRole role) {
        if (!stack.isEmpty()) {
            runtime.getRecipesGui().show(runtime.getJeiHelpers().getFocusFactory().createFocus(role, VanillaTypes.ITEM_STACK, stack.copyWithCount(1)));
        }
    }

    private static ItemStack stackOf(Optional<ITypedIngredient<?>> typed) {
        return typed.flatMap(t -> t.getIngredient(VanillaTypes.ITEM_STACK)).orElse(ItemStack.EMPTY);
    }

    /**
     * Answers JEI's ingredient lookups from the live index, so a quest becomes visible as soon as its chapter arrives
     * and its state text follows the player without rebuilding the display list.
     */
    private static final class QuestViewerRecipeManager implements IRecipeManagerPlugin {

        /** The whole list is only rebuilt when the index is replaced; JEI asks for it on every category open. */
        private static QuestViewerIndex.Index cachedIndex;
        private static List<QuestViewerRecipe> cachedRecipes = List.of();

        @Override
        public <T, V> List<T> getRecipes(IRecipeCategory<T> category, IFocus<V> focus) {
            if (!(category instanceof QuestViewerJeiCategory)) {
                return List.of();
            }
            Object ingredient = focus.getTypedValue().getIngredient();
            if (!(ingredient instanceof ItemStack stack) || stack.isEmpty()) {
                return List.of();
            }
            if (focus.getRole() == RecipeIngredientRole.CATALYST) {
                return stack.is(QuestViewerRecipe.book().getItem()) ? all() : List.of();
            }
            var index = QuestViewerData.index();
            var query = QuestViewerData.query(stack);
            List<Hit> hits = focus.getRole() == RecipeIngredientRole.INPUT
                    ? index.uses(query) : index.rewards(query);
            return recipes(hits);
        }

        @Override
        public <T> List<T> getRecipes(IRecipeCategory<T> category) {
            return category instanceof QuestViewerJeiCategory ? all() : List.of();
        }

        @Override
        public <V> List<RecipeType<?>> getRecipeTypes(IFocus<V> focus) {
            Object ingredient = focus.getTypedValue().getIngredient();
            if (!(ingredient instanceof ItemStack stack) || stack.isEmpty()) {
                return List.of();
            }
            if (focus.getRole() == RecipeIngredientRole.CATALYST) {
                return stack.is(QuestViewerRecipe.book().getItem())
                        ? List.of(QuestViewerJeiCategory.TYPE) : List.of();
            }
            var index = QuestViewerData.index();
            var query = QuestViewerData.query(stack);
            boolean has = focus.getRole() == RecipeIngredientRole.INPUT
                    ? !index.uses(query).isEmpty() : !index.rewards(query).isEmpty();
            return has ? List.of(QuestViewerJeiCategory.TYPE) : List.of();
        }

        @SuppressWarnings("unchecked")
        private static <T> List<T> all() {
            var index = QuestViewerData.index();
            if (index != cachedIndex) {
                cachedRecipes = recipes(index.hits());
                cachedIndex = index;
            }
            return (List<T>) cachedRecipes;
        }

        @SuppressWarnings("unchecked")
        private static <T> List<T> recipes(List<Hit> hits) {
            return (List<T>) hits.stream()
                    .map(QuestViewerRecipe::new)
                    .filter(recipe -> !recipe.stacks().isEmpty())
                    .toList();
        }
    }
}
