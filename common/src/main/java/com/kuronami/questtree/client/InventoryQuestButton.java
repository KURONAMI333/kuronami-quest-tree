package com.kuronami.questtree.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * The quest book button beside the inventory (2026-10-20): the screen players already open, so the book is one click
 * away without a key or an item. A gold dot marks claimable rewards, like the chapter list's gift row.
 */
public final class InventoryQuestButton extends AbstractButton {

    private static final int SIZE = 18;
    private final Runnable action;

    private InventoryQuestButton(int x, int y, Runnable action) {
        super(x, y, SIZE, SIZE, Component.translatable("item.kuronami_quest_tree.quest_book"));
        this.action = action;
    }

    /** Adds the button to an inventory screen, once. Other screens are left alone. */
    public static void add(Screen screen, Consumer<AbstractButton> add) {
        if (!(screen instanceof InventoryScreen)) {
            return;
        }
        int left = (screen.width - 176) / 2;
        int top = (screen.height - 166) / 2;
        add.accept(new InventoryQuestButton(left - SIZE - 4, top + 6,
                () -> Minecraft.getInstance().setScreen(new ProgressScreen())));
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        boolean hover = isHoveredOrFocused();
        Ui.surface(g, getX(), getY(), SIZE, SIZE, Ui.R3, hover ? Ui.OVERLAY_2 : Ui.OVERLAY_1);
        Ui.outline(g, getX(), getY(), SIZE, SIZE, Ui.R3, Ui.HAIRLINE);
        Tiles.icon(g, "book", getX() + (SIZE - 12) / 2f, getY() + (SIZE - 12) / 2f, 12,
                hover ? Ui.TEXT : Ui.MUTED);
        if (ClientProgress.claimableCount() > 0) {
            Tiles.icon(g, "dot", getX() + SIZE - 6, getY(), 6, Tiles.GOLD);
        }
    }

    @Override
    public void onPress() {
        playDownSound(Minecraft.getInstance().getSoundManager());
        action.run();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
