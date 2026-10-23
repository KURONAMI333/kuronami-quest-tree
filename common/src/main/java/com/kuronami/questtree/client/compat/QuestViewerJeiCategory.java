package com.kuronami.questtree.client.compat;

import com.kuronami.questtree.Constants;
import com.kuronami.questtree.client.QuestViewerStyle;
import com.kuronami.questtree.progress.ProgressRules;
import com.mojang.blaze3d.platform.InputConstants;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.gui.inputs.IJeiInputHandler;
import mezz.jei.api.gui.inputs.IJeiUserInput;
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * A quest as a recipe: the quest book at the top, its icon and title, and the item the task needs or the reward gives.
 * Clicking the title opens the quest. The card is drawn here, in the same style as the quest screen, and
 * {@code needsRecipeBorder()} is false so JEI does not draw its own frame under it.
 */
public final class QuestViewerJeiCategory implements IRecipeCategory<QuestViewerRecipe> {

    public static final RecipeType<QuestViewerRecipe> TYPE =
            RecipeType.create(Constants.MOD_ID, "quest", QuestViewerRecipe.class);

    private final IDrawable icon;

    public QuestViewerJeiCategory(IGuiHelper helper) {
        this.icon = helper.createDrawableIngredient(VanillaTypes.ITEM_STACK, QuestViewerRecipe.book());
    }

    /** Our own card replaces JEI's frame, so its light border would only ring the dark panel. */
    @Override
    public boolean needsRecipeBorder() {
        return false;
    }

    @Override
    public RecipeType<QuestViewerRecipe> getRecipeType() {
        return TYPE;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("kuronami_quest_tree.viewer.category.quests");
    }

    @Override
    public IDrawable getIcon() {
        return icon;
    }

    @Override
    public int getWidth() {
        return QuestViewerRecipe.WIDTH;
    }

    @Override
    public int getHeight() {
        return QuestViewerRecipe.HEIGHT;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, QuestViewerRecipe recipe, IFocusGroup focuses) {
        IRecipeSlotBuilder slot = recipe.needsItem()
                ? builder.addInputSlot(8, 30)
                : builder.addOutputSlot(8, 30);
        slot.addItemStacks(recipe.stacks());
    }

    @Override
    public void draw(QuestViewerRecipe recipe, IRecipeSlotsView slots, GuiGraphics graphics,
                     double mouseX, double mouseY) {
        // the card goes down here: JEI calls draw() before it draws the slots (2026-10-23)
        QuestViewerStyle.card(graphics, 0, 0, QuestViewerRecipe.WIDTH, QuestViewerRecipe.HEIGHT);
        Font font = net.minecraft.client.Minecraft.getInstance().font;
        ProgressRules.State state = recipe.state();
        String stateText = recipe.stateLabel().getString();
        int right = QuestViewerRecipe.WIDTH - 4;
        int stateX = right - font.width(stateText);
        int markX = stateX - 9;
        int titleWidth = Math.max(20, markX - 4 - 26);

        graphics.renderItem(recipe.icon(), 4, 4);
        graphics.renderItemDecorations(font, recipe.icon(), 4, 4);
        graphics.drawString(font, Component.literal(trim(recipe.title(), titleWidth, font))
                .withStyle(ChatFormatting.UNDERLINE), 26, 4, QuestViewerStyle.textColor(), false);
        graphics.drawString(font, trim(recipe.chapter(), titleWidth, font), 26, 15, QuestViewerStyle.dimColor(), false);
        QuestViewerStyle.mark(graphics, state, markX, 5, 7);
        graphics.drawString(font, stateText, stateX, 4, QuestViewerStyle.textColor(), false);
        // say which way the item goes: the same slot is an input for a task and an output for a reward
        Component need = Component.translatable(recipe.needsItem()
                        ? "screen.kuronami_quest_tree.needs_list"
                        : "screen.kuronami_quest_tree.rewards_list",
                recipe.needLabelComponent());
        graphics.drawString(font, trim(need.getString(), right - 30, font),
                30, 35, QuestViewerStyle.textColor(), false);
    }

    @Override
    public void createRecipeExtras(IRecipeExtrasBuilder builder, QuestViewerRecipe recipe, IFocusGroup focuses) {
        builder.addInputHandler(new IJeiInputHandler() {
            private final ScreenRectangle area = new ScreenRectangle(0, 0, QuestViewerRecipe.WIDTH, 24);

            @Override
            public ScreenRectangle getArea() {
                return area;
            }

            @Override
            public boolean handleInput(double mouseX, double mouseY, IJeiUserInput input) {
                // only a real left click opens the quest: JEI also probes handlers with other buttons and with
                // simulated input, and opening during a simulation would replace the screen again on every pass
                if (input.isSimulate() || input.getKey().getType() != InputConstants.Type.MOUSE
                        || input.getKey().getValue() != InputConstants.MOUSE_BUTTON_LEFT) {
                    return false;
                }
                recipe.open();
                return true;
            }
        });
    }

    @Override
    public ResourceLocation getRegistryName(QuestViewerRecipe recipe) {
        return recipe.id();
    }

    private static String trim(String text, int width, Font font) {
        if (font.width(text) <= width) {
            return text;
        }
        String ellipsis = "…";
        return font.plainSubstrByWidth(text, Math.max(0, width - font.width(ellipsis))) + ellipsis;
    }
}
