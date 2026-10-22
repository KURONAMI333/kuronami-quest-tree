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
import com.mojang.blaze3d.platform.InputConstants;
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
        registration.addRecipeCategories(new QuestViewerJeiCategory(registration.getJeiHelpers().getGuiHelper()),
                new CrateViewerJeiCategory(registration.getJeiHelpers().getGuiHelper()));
    }

    /**
     * Known JEI limitation, accepted: JEI computes the unfocused category list once and caches it
     * ({@code recipeCategoriesVisibleCache}). If the player opens that list before the bundle has synced, this
     * category stays out of it until JEI reloads. The focused lookups below are live, so nothing else is stale.
     */
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
            public void showCrates() {
                List<CrateViewerData.Recipe> recipes = CrateViewerData.all();
                if (recipes.isEmpty()) {
                    return;
                }
                runtime.getRecipesGui().showRecipes(
                        runtime.getRecipeManager().getRecipeCategory(CrateViewerJeiCategory.TYPE), recipes, List.of());
            }

            @Override
            public boolean typing() {
                return runtime.getIngredientListOverlay().hasKeyboardFocus();
            }

            @Override
            public boolean isBookmarkKey(int key, int scan) {
                return runtime.getKeyMappings().getBookmark()
                        .isActiveAndMatches(InputConstants.Type.KEYSYM.getOrCreate(key));
            }

            @Override
            public Boolean toggleBookmark(ItemStack stack) {
                if (stack.isEmpty()) {
                    return null;
                }
                return runtime.getIngredientManager()
                        .createTypedIngredient(VanillaTypes.ITEM_STACK, stack.copyWithCount(1), true)
                        .map(typed -> {
                            var bookmarks = runtime.getBookmarkManager();
                            if (bookmarks.contains(typed)) {
                                return bookmarks.remove(typed) ? Boolean.FALSE : null;
                            }
                            return bookmarks.add(typed) ? Boolean.TRUE : null;
                        }).orElse(null);
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
            Object ingredient = focus.getTypedValue().getIngredient();
            if (!(ingredient instanceof ItemStack stack) || stack.isEmpty()) {
                return List.of();
            }
            if (category instanceof CrateViewerJeiCategory) {
                if (focus.getRole() == RecipeIngredientRole.CATALYST) {
                    return stack.is(CrateViewerJeiCategory.iconStack().getItem()) ? crateRecipes() : List.of();
                }
                return crateRecipes(stack);
            }
            if (!(category instanceof QuestViewerJeiCategory)) {
                return List.of();
            }
            if (focus.getRole() == RecipeIngredientRole.CATALYST) {
                return stack.is(QuestViewerRecipe.book().getItem()) ? all() : List.of();
            }
            var index = QuestViewerData.index();
            var query = QuestViewerData.query(stack);
            RecipeIngredientRole role = focus.getRole();
            if (role != RecipeIngredientRole.INPUT && role != RecipeIngredientRole.OUTPUT) {
                return List.of();
            }
            List<Hit> hits = role == RecipeIngredientRole.INPUT ? index.uses(query) : index.rewards(query);
            return recipes(hits);
        }

        @Override
        public <T> List<T> getRecipes(IRecipeCategory<T> category) {
            if (category instanceof CrateViewerJeiCategory) {
                return crateRecipes();
            }
            return category instanceof QuestViewerJeiCategory ? all() : List.of();
        }

        @Override
        public <V> List<RecipeType<?>> getRecipeTypes(IFocus<V> focus) {
            Object ingredient = focus.getTypedValue().getIngredient();
            if (!(ingredient instanceof ItemStack stack) || stack.isEmpty()) {
                return List.of();
            }
            if (focus.getRole() == RecipeIngredientRole.CATALYST) {
                if (stack.is(QuestViewerRecipe.book().getItem())) {
                    return List.of(QuestViewerJeiCategory.TYPE);
                }
                return stack.is(CrateViewerJeiCategory.iconStack().getItem())
                        ? List.of(CrateViewerJeiCategory.TYPE) : List.of();
            }
            var index = QuestViewerData.index();
            var query = QuestViewerData.query(stack);
            RecipeIngredientRole role = focus.getRole();
            if (role != RecipeIngredientRole.INPUT && role != RecipeIngredientRole.OUTPUT) {
                return List.of();
            }
            List<RecipeType<?>> types = new java.util.ArrayList<>(2);
            // RENDER_ONLY and other roles are not a lookup in this category: only a real input/output focuses it
            boolean has = role == RecipeIngredientRole.INPUT
                    ? !index.uses(query).isEmpty() : !index.rewards(query).isEmpty();
            if (has) {
                types.add(QuestViewerJeiCategory.TYPE);
            }
            if (!CrateViewerData.matching(stack).isEmpty()) {
                types.add(CrateViewerJeiCategory.TYPE);
            }
            return types;
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

        @SuppressWarnings("unchecked")
        private static <T> List<T> crateRecipes() {
            return (List<T>) CrateViewerData.all();
        }

        @SuppressWarnings("unchecked")
        private static <T> List<T> crateRecipes(ItemStack stack) {
            return (List<T>) CrateViewerData.matching(stack);
        }
    }
}
