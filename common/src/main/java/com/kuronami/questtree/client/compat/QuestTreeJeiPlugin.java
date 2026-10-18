package com.kuronami.questtree.client.compat;

import com.kuronami.questtree.Constants;
import com.kuronami.questtree.client.RecipeViewer;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

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
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        RecipeViewer.Holder.set(new RecipeViewer() {
            @Override
            public void showRecipes(ItemStack stack) {
                show(runtime, stack, RecipeIngredientRole.OUTPUT);
            }

            @Override
            public void showUses(ItemStack stack) {
                show(runtime, stack, RecipeIngredientRole.INPUT);
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
        RecipeViewer.Holder.set(null);
    }

    private static void show(IJeiRuntime runtime, ItemStack stack, RecipeIngredientRole role) {
        if (!stack.isEmpty()) {
            runtime.getRecipesGui().show(runtime.getJeiHelpers().getFocusFactory().createFocus(role, VanillaTypes.ITEM_STACK, stack.copyWithCount(1)));
        }
    }

    private static ItemStack stackOf(Optional<ITypedIngredient<?>> typed) {
        return typed.flatMap(t -> t.getIngredient(VanillaTypes.ITEM_STACK)).orElse(ItemStack.EMPTY);
    }
}
