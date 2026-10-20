package com.kuronami.questtree.client;

import com.kuronami.questtree.mixin.AbstractContainerScreenAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * The quest book button beside the inventory (2026-10-20): the screen players already open, so the book is one click
 * away without a key or an item. A gold dot marks claimable rewards, like the chapter list's gift row.
 * <p>
 * It sits on the panel's right edge and reads {@code leftPos}/{@code topPos} every frame, so opening the recipe book
 * (which moves the inventory) and resizing both keep it beside the panel instead of over the recipe book.
 */
public final class InventoryQuestButton extends AbstractButton {

    private static final int SIZE = 18;
    private static final int GAP = 4;
    private final AbstractContainerScreen<?> screen;
    private final Runnable action;

    private InventoryQuestButton(AbstractContainerScreen<?> screen, Runnable action) {
        super(0, 0, SIZE, SIZE, Component.translatable("item.kuronami_quest_tree.quest_book"));
        this.screen = screen;
        this.action = action;
        followPanel();
    }

    /** Adds the button to the player and creative inventories, once. Other screens are left alone. */
    public static void add(Screen screen, Consumer<AbstractButton> add) {
        if (!(screen instanceof InventoryScreen) && !(screen instanceof CreativeModeInventoryScreen)) {
            return;
        }
        add.accept(new InventoryQuestButton((AbstractContainerScreen<?>) screen,
                () -> Minecraft.getInstance().setScreen(new ProgressScreen())));
    }

    /** The panel's right edge, clear of the recipe book (left), the tab rows (above) and the vanilla slots. */
    private void followPanel() {
        AbstractContainerScreenAccessor a = (AbstractContainerScreenAccessor) screen;
        setX(a.questTree$leftPos() + a.questTree$imageWidth() + GAP);
        setY(a.questTree$topPos() + 6);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        followPanel();
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
