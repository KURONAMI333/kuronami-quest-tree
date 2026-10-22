package com.kuronami.questtree.client;

import com.kuronami.questtree.progress.ProgressRules;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * The state wording and mark the recipe viewers share. Colours come from the screen's tiles, so a resource pack that
 * restyles the book restyles these too.
 */
public final class QuestViewerStyle {

    private QuestViewerStyle() {
    }

    public static Component stateLabel(ProgressRules.State state) {
        return switch (state) {
            case DONE, CLAIM -> Component.translatable("kuronami_quest_tree.viewer.state.done");
            case LOCKED, EXCLUDED -> Component.translatable("kuronami_quest_tree.viewer.state.locked");
            default -> Component.translatable("kuronami_quest_tree.viewer.state.open");
        };
    }

    public static int stateColor(ProgressRules.State state) {
        return switch (state) {
            case DONE, CLAIM -> Tiles.GREEN_SETTLED;
            case PROGRESS -> Tiles.GREEN;
            case LOCKED, EXCLUDED -> Tiles.TILE_LOCKED;
            default -> Tiles.GREY;
        };
    }

    /**
     * The recipe viewers' body text, from the screen's palette so a pack restyles it too (2026-10-23, R6 L3). A viewer
     * draws on JEI's or REI's own frame, which is why these used to be fixed greys that a dark theme could not read.
     */
    public static int textColor() {
        return Ui.TEXT;
    }

    /** Supporting text (a chapter, a table id, a remainder count), one step below {@link #textColor()}. */
    public static int dimColor() {
        return Ui.DIM;
    }

    /** A small tinted dot before the state label. */
    public static void mark(GuiGraphics graphics, ProgressRules.State state, float x, float y, float size) {
        RenderSystem.enableBlend();
        Tiles.icon(graphics, "dot", x, y, size, stateColor(state));
    }
}
