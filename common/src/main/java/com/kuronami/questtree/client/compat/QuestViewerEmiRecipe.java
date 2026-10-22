package com.kuronami.questtree.client.compat;

import com.kuronami.questtree.client.QuestViewerStyle;
import com.kuronami.questtree.client.viewer.QuestViewerIndex.Spec;
import dev.emi.emi.api.recipe.BasicEmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.Bounds;
import dev.emi.emi.api.widget.DrawableWidget;
import dev.emi.emi.api.widget.TextWidget;
import dev.emi.emi.api.widget.Widget;
import dev.emi.emi.api.widget.WidgetHolder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;

import java.util.List;

/**
 * EMI has no live lookup hooks, so one of these is registered per quest entry and refreshed when the index changes.
 * State is read in {@link #addWidgets} through a drawable widget, so it still follows the player between refreshes.
 */
public final class QuestViewerEmiRecipe extends BasicEmiRecipe {

    private final QuestViewerRecipe recipe;

    public QuestViewerEmiRecipe(QuestViewerRecipe recipe) {
        super(QuestViewerEmiCategory.CATEGORY, ResourceLocation.fromNamespaceAndPath(
                recipe.id().getNamespace(), "/" + recipe.id().getPath()), QuestViewerRecipe.WIDTH, QuestViewerRecipe.HEIGHT);
        this.recipe = recipe;

        if (recipe.needsItem()) {
            this.inputs = List.of(ingredient(recipe));
            this.outputs = List.of();
        } else {
            this.inputs = List.of();
            // outputs are a stack list, so a tag has to be expanded: every member, not just the first 64
            this.outputs = recipe.stacks().stream().map(EmiStack::of).toList();
        }
    }

    @Override
    public void addWidgets(WidgetHolder widgets) {
        Font font = Minecraft.getInstance().font;
        int titleWidth = 76;

        widgets.add(new DrawableWidget(4, 4, 16, 16, (graphics, mouseX, mouseY, delta) ->
                graphics.renderItem(recipe.icon(), 0, 0)));
        widgets.add(new DrawableWidget(26, 4, titleWidth, 10, (graphics, mouseX, mouseY, delta) ->
                graphics.drawString(font, Component.literal(trim(recipe.title(), titleWidth, font))
                        .withStyle(ChatFormatting.UNDERLINE), 0, 0, 0xFFFFFFFF, false)));
        widgets.add(new ClickArea(26, 2, titleWidth, 11, recipe::open));

        widgets.add(new TextWidget(Component.literal(trim(recipe.chapter(), titleWidth, font)).getVisualOrderText(),
                26, 14, 0xFF9E9E9E, false));
        widgets.add(new TextWidget(Component.literal(trim(recipe.needLabel(), 136, font)).getVisualOrderText(),
                30, 35, 0xFFFFFFFF, false));

        widgets.add(new DrawableWidget(104, 3, 62, 11, (graphics, mouseX, mouseY, delta) -> {
            var state = recipe.state();
            String text = QuestViewerStyle.stateLabel(state).getString();
            QuestViewerStyle.mark(graphics, state, 2, 2, 7);
            graphics.drawString(font, text, 62 - font.width(text), 1, 0xFFFFFFFF, false);
        }));

        widgets.addSlot(ingredient(recipe), 8, 30);
    }

    /** A tag becomes one tag ingredient so EMI knows every member, however long the tag is. */
    private static EmiIngredient ingredient(QuestViewerRecipe recipe) {
        Spec spec = recipe.hit().need().spec();
        if (spec != null && spec.tag()) {
            ResourceLocation id = ResourceLocation.tryParse(spec.id());
            if (id != null) {
                return EmiIngredient.of(TagKey.create(Registries.ITEM, id),
                        Math.max(1, Math.min(99, recipe.hit().need().count())));
            }
        }
        return EmiIngredient.of(recipe.stacks().stream().map(EmiStack::of).toList());
    }

    private static String trim(String text, int width, Font font) {
        if (font.width(text) <= width) {
            return text;
        }
        String ellipsis = "…";
        return font.plainSubstrByWidth(text, Math.max(0, width - font.width(ellipsis))) + ellipsis;
    }

    /** A transparent click target over the title; drawing stays with the text widgets. */
    private static final class ClickArea extends Widget {

        private final Bounds bounds;
        private final Runnable action;

        private ClickArea(int x, int y, int width, int height, Runnable action) {
            this.bounds = new Bounds(x, y, width, height);
            this.action = action;
        }

        @Override
        public Bounds getBounds() {
            return bounds;
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        }

        @Override
        public boolean mouseClicked(int mouseX, int mouseY, int button) {
            if (button == 0 && bounds.contains(mouseX, mouseY)) {
                action.run();
                return true;
            }
            return false;
        }
    }
}
