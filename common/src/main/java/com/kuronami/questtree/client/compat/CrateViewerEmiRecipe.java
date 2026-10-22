package com.kuronami.questtree.client.compat;

import dev.emi.emi.api.recipe.BasicEmiRecipe;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.TextWidget;
import dev.emi.emi.api.widget.WidgetHolder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** EMI's crate entry: the crate in, its possible rewards out. */
public final class CrateViewerEmiRecipe extends BasicEmiRecipe {

    private static final int COLUMNS = 4;
    private static final int ROWS = 3;

    private final CrateViewerData.Recipe recipe;

    public CrateViewerEmiRecipe(CrateViewerData.Recipe recipe) {
        super(CrateViewerEmiCategory.CATEGORY, ResourceLocation.fromNamespaceAndPath(
                recipe.id().getNamespace(), "/" + recipe.id().getPath()), 170, 84);
        this.recipe = recipe;
        this.inputs = List.of(EmiStack.of(recipe.crate()));
        this.outputs = recipe.contents().stream().map(EmiStack::of).toList();
    }

    @Override
    public void addWidgets(WidgetHolder widgets) {
        Font font = Minecraft.getInstance().font;
        widgets.addSlot(EmiStack.of(recipe.crate()), 8, 8);
        widgets.add(new TextWidget(recipe.crate().getHoverName().copy().withStyle(ChatFormatting.UNDERLINE)
                .getVisualOrderText(), 30, 5, 0xFFFFFFFF, false));
        widgets.add(new TextWidget(Component.literal(trim(recipe.tableId(), 132, font)).getVisualOrderText(),
                30, 17, 0xFF9E9E9E, false));
        widgets.add(new TextWidget(Component.translatable("kuronami_quest_tree.viewer.crate.rewards",
                recipe.contents().size()).getVisualOrderText(), 30, 27, 0xFFFFFFFF, false));

        for (int i = 0; i < Math.min(recipe.contents().size(), COLUMNS * ROWS); i++) {
            int x = 8 + (i % COLUMNS) * 18;
            int y = 40 + (i / COLUMNS) * 18;
            widgets.addSlot(EmiStack.of(recipe.contents().get(i)), x, y).recipeContext(this);
        }
    }

    private static String trim(String text, int width, Font font) {
        return font.width(text) <= width ? text
                : font.plainSubstrByWidth(text, Math.max(0, width - font.width("…"))) + "…";
    }
}
