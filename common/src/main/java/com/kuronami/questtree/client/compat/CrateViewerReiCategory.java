package com.kuronami.questtree.client.compat;

import com.kuronami.questtree.Constants;
import me.shedaniel.math.Point;
import me.shedaniel.math.Rectangle;
import me.shedaniel.rei.api.client.gui.Renderer;
import me.shedaniel.rei.api.client.gui.widgets.Widget;
import me.shedaniel.rei.api.client.gui.widgets.Widgets;
import me.shedaniel.rei.api.client.registry.display.DisplayCategory;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.util.EntryStacks;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** REI's crate view: crate and table id at the top, the possible rewards in a 4 by 3 grid. */
public final class CrateViewerReiCategory implements DisplayCategory<CrateViewerReiDisplay> {

    public static final CategoryIdentifier<CrateViewerReiDisplay> ID =
            CategoryIdentifier.of(Constants.MOD_ID, "crate");

    private static final int COLUMNS = 4;
    private static final int ROWS = 3;

    @Override
    public CategoryIdentifier<? extends CrateViewerReiDisplay> getCategoryIdentifier() {
        return ID;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("kuronami_quest_tree.viewer.category.crates");
    }

    @Override
    public Renderer getIcon() {
        var icon = EntryStacks.of(CrateViewerJeiCategory.iconStack());
        return new Renderer() {
            @Override
            public void render(GuiGraphics graphics, Rectangle bounds, int mouseX, int mouseY, float delta) {
                icon.render(graphics, bounds, mouseX, mouseY, delta);
            }
        };
    }

    @Override
    public List<Widget> setupDisplay(CrateViewerReiDisplay display, Rectangle bounds) {
        List<Widget> widgets = new ArrayList<>();
        widgets.add(Widgets.createRecipeBase(bounds));
        CrateViewerData.Recipe recipe = display.recipe();
        Font font = Minecraft.getInstance().font;

        widgets.add(Widgets.createSlot(new Point(bounds.x + 10, bounds.y + 8))
                .entry(EntryStacks.of(recipe.crate())).markInput());
        widgets.add(Widgets.createLabel(new Point(bounds.x + 30, bounds.y + 4),
                recipe.crate().getHoverName().copy().withStyle(ChatFormatting.UNDERLINE)
                        .withStyle(style -> style.withColor(0xFF404040))));
        widgets.add(Widgets.createLabel(new Point(bounds.x + 30, bounds.y + 16),
                Component.literal(trim(recipe.tableId(), bounds.width - 40, font))
                        .withStyle(style -> style.withColor(0xFF808080))));
        widgets.add(Widgets.createLabel(new Point(bounds.x + 30, bounds.y + 26),
                Component.translatable("kuronami_quest_tree.viewer.crate.rewards", recipe.contents().size())
                        .withStyle(style -> style.withColor(0xFF404040))));

        for (int i = 0; i < Math.min(recipe.contents().size(), COLUMNS * ROWS); i++) {
            int x = bounds.x + 10 + (i % COLUMNS) * 18;
            int y = bounds.y + 40 + (i / COLUMNS) * 18;
            widgets.add(Widgets.createSlot(new Point(x, y))
                    .entry(EntryStacks.of(recipe.contents().get(i))).markOutput());
        }
        return widgets;
    }

    @Override
    public int getDisplayWidth(CrateViewerReiDisplay display) {
        return 170;
    }

    @Override
    public int getDisplayHeight() {
        return 84;
    }

    private static String trim(String text, int width, Font font) {
        return font.width(text) <= width ? text
                : font.plainSubstrByWidth(text, Math.max(0, width - font.width("…"))) + "…";
    }
}
