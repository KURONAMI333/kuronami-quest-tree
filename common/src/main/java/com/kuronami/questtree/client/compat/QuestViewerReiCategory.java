package com.kuronami.questtree.client.compat;

import com.kuronami.questtree.Constants;
import com.kuronami.questtree.client.QuestViewerStyle;
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

/** REI's category: quest icon and title at the top, the item in one slot, and the player's state on the right. */
public final class QuestViewerReiCategory implements DisplayCategory<QuestViewerReiDisplay> {

    public static final CategoryIdentifier<QuestViewerReiDisplay> ID =
            CategoryIdentifier.of(Constants.MOD_ID, "quest");

    @Override
    public CategoryIdentifier<? extends QuestViewerReiDisplay> getCategoryIdentifier() {
        return ID;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("kuronami_quest_tree.viewer.category.quests");
    }

    @Override
    public Renderer getIcon() {
        var icon = EntryStacks.of(QuestViewerRecipe.book());
        return new Renderer() {
            @Override
            public void render(GuiGraphics graphics, Rectangle bounds, int mouseX, int mouseY, float delta) {
                icon.render(graphics, bounds, mouseX, mouseY, delta);
            }
        };
    }

    @Override
    public List<Widget> setupDisplay(QuestViewerReiDisplay display, Rectangle bounds) {
        List<Widget> widgets = new ArrayList<>();
        // REI's own base is light; the labels below use the mod's palette, so the card is drawn here instead
        // (2026-10-24: light text on REI's light base was unreadable)
        widgets.add(Widgets.createDrawableWidget((graphics, mouseX, mouseY, delta) ->
                QuestViewerStyle.card(graphics, bounds.x, bounds.y, bounds.width, bounds.height)));

        QuestViewerRecipe recipe = display.recipe();
        Font font = Minecraft.getInstance().font;
        String stateText = recipe.stateLabel().getString();
        int stateX = bounds.x + bounds.width - 8 - font.width(stateText);
        int titleWidth = Math.max(20, stateX - (bounds.x + 28));

        var slot = Widgets.createSlot(new Point(bounds.x + 10, bounds.y + 32))
                .entries(display.getInputEntries().isEmpty()
                        ? display.getOutputEntries().get(0) : display.getInputEntries().get(0));
        widgets.add(recipe.needsItem() ? slot.markInput() : slot.markOutput());

        if (!recipe.stacks().isEmpty()) {
            widgets.add(Widgets.createClickableLabel(new Point(bounds.x + 28, bounds.y + 3),
                    Component.literal(trim(recipe.title(), titleWidth, font)).withStyle(ChatFormatting.UNDERLINE)
                            .withStyle(style -> style.withColor(QuestViewerStyle.textColor())),
                    label -> recipe.open()));
        }
        widgets.add(Widgets.createLabel(new Point(bounds.x + 28, bounds.y + 14),
                Component.literal(trim(recipe.chapter(), titleWidth, font))
                        .withStyle(style -> style.withColor(QuestViewerStyle.dimColor()))));
        widgets.add(Widgets.createLabel(new Point(stateX, bounds.y + 3),
                Component.literal(stateText).withStyle(style -> style.withColor(QuestViewerStyle.textColor()))));
        Component need = Component.translatable(recipe.needsItem()
                        ? "screen.kuronami_quest_tree.needs_list"
                        : "screen.kuronami_quest_tree.rewards_list",
                recipe.needLabelComponent());
        widgets.add(Widgets.createLabel(new Point(bounds.x + 30, bounds.y + 36),
                Component.literal(trim(need.getString(), bounds.width - 40, font))
                        .withStyle(style -> style.withColor(QuestViewerStyle.textColor()))));
        return widgets;
    }

    @Override
    public int getDisplayWidth(QuestViewerReiDisplay display) {
        return QuestViewerRecipe.WIDTH;
    }

    @Override
    public int getDisplayHeight() {
        return QuestViewerRecipe.HEIGHT;
    }

    private static String trim(String text, int width, Font font) {
        if (font.width(text) <= width) {
            return text;
        }
        String ellipsis = "…";
        return font.plainSubstrByWidth(text, Math.max(0, width - font.width(ellipsis))) + ellipsis;
    }
}
