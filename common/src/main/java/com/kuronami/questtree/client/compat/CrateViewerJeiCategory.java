package com.kuronami.questtree.client.compat;

import com.kuronami.questtree.Constants;
import com.kuronami.questtree.client.QuestViewerStyle;
import com.kuronami.questtree.item.ModItems;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** A crate as a recipe: the crate at the top, the items it can give in a grid below, and the table's id. */
public final class CrateViewerJeiCategory implements IRecipeCategory<CrateViewerData.Recipe> {

    public static final RecipeType<CrateViewerData.Recipe> TYPE =
            RecipeType.create(Constants.MOD_ID, "crate", CrateViewerData.Recipe.class);

    private static final int COLUMNS = 4;
    private static final int ROWS = 3;
    private static final int SLOT = 18;

    private final IDrawable icon;

    public CrateViewerJeiCategory(IGuiHelper helper) {
        this.icon = helper.createDrawableIngredient(VanillaTypes.ITEM_STACK, iconStack());
    }

    static ItemStack iconStack() {
        ItemStack stack = ModItems.lootCrate() == null ? ItemStack.EMPTY : new ItemStack(ModItems.lootCrate());
        return stack.isEmpty() ? QuestViewerRecipe.book() : stack;
    }

    @Override
    public RecipeType<CrateViewerData.Recipe> getRecipeType() {
        return TYPE;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("kuronami_quest_tree.viewer.category.crates");
    }

    @Override
    public IDrawable getIcon() {
        return icon;
    }

    @Override
    public int getWidth() {
        return 170;
    }

    @Override
    public int getHeight() {
        return 84;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, CrateViewerData.Recipe recipe, IFocusGroup focuses) {
        builder.addInputSlot(8, 8).addItemStack(recipe.crate());
        for (int i = 0; i < Math.min(recipe.contents().size(), COLUMNS * ROWS); i++) {
            int x = 8 + (i % COLUMNS) * SLOT;
            int y = 40 + (i / COLUMNS) * SLOT;
            builder.addOutputSlot(x, y).addItemStack(recipe.contents().get(i));
        }
    }

    @Override
    public void draw(CrateViewerData.Recipe recipe, IRecipeSlotsView slots, GuiGraphics graphics,
                     double mouseX, double mouseY) {
        Font font = Minecraft.getInstance().font;
        graphics.drawString(font, recipe.crate().getHoverName().copy().withStyle(ChatFormatting.UNDERLINE),
                30, 5, QuestViewerStyle.textColor(), false);
        graphics.drawString(font, trim(recipe.tableId(), 134, font), 30, 17, QuestViewerStyle.dimColor(), false);
        graphics.drawString(font, Component.translatable("kuronami_quest_tree.viewer.crate.rewards",
                        recipe.contents().size()), 30, 27, QuestViewerStyle.textColor(), false);
        int hidden = recipe.contents().size() - COLUMNS * ROWS;
        if (hidden > 0) {
            graphics.drawString(font, Component.translatable("kuronami_quest_tree.viewer.crate.more", hidden),
                    8, 75, QuestViewerStyle.dimColor(), false);
        }
    }

    @Override
    public ResourceLocation getRegistryName(CrateViewerData.Recipe recipe) {
        return recipe.id();
    }

    private static String trim(String text, int width, Font font) {
        return font.width(text) <= width ? text
                : font.plainSubstrByWidth(text, Math.max(0, width - font.width("…"))) + "…";
    }
}
