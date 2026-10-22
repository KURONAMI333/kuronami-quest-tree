package com.kuronami.questtree.client;

import com.kuronami.questtree.Constants;
import com.kuronami.questtree.mixin.AbstractContainerScreenAccessor;
import com.kuronami.questtree.platform.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Consumer;

/**
 * The quest book button above the player preview in the survival inventory (2026-10-22), made the way vanilla's recipe
 * book button is: a 20×18 {@link ImageButton} with a normal and a hover sprite. Packs can reskin it by replacing
 * {@code textures/gui/sprites/quest_book_button.png} and
 * {@code textures/gui/sprites/quest_book_button_highlighted.png}.
 * <p>
 * It reads {@code leftPos}/{@code topPos} every frame, so opening the recipe book (which moves the inventory) and
 * resizing keep it over the preview. With FTB Library installed its sidebar already puts a quest button on this screen,
 * so this one is left out (FTB's own sidebar setting hides that one); the client setting
 * {@code inventory_button} turns this one off in packs without it.
 */
public final class InventoryQuestButton extends ImageButton {

    private static final WidgetSprites SPRITES = new WidgetSprites(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "quest_book_button"),
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "quest_book_button_highlighted"));
    private final AbstractContainerScreen<?> screen;

    private InventoryQuestButton(AbstractContainerScreen<?> screen) {
        super(0, 0, InventoryButtonLayout.WIDTH, InventoryButtonLayout.HEIGHT, SPRITES,
                button -> Minecraft.getInstance().setScreen(new ProgressScreen()),
                Component.translatable("item.kuronami_quest_tree.quest_book"));
        this.screen = screen;
        setTooltip(Tooltip.create(Component.translatable("item.kuronami_quest_tree.quest_book")));
        followPanel();
    }

    /** Adds the button to the survival inventory, once. Other screens, creative, and FTB Library are left alone. */
    public static void add(Screen screen, Consumer<AbstractButton> add) {
        if (!(screen instanceof InventoryScreen) || !ClientConfig.inventoryButton()) {
            return;
        }
        if (Services.PLATFORM.isModLoaded("ftblibrary")) {
            return;
        }
        add.accept(new InventoryQuestButton((AbstractContainerScreen<?>) screen));
    }

    private void followPanel() {
        AbstractContainerScreenAccessor a = (AbstractContainerScreenAccessor) screen;
        setX(InventoryButtonLayout.x(a.questTree$leftPos()));
        setY(InventoryButtonLayout.y(a.questTree$topPos()));
    }

    @Override
    public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        followPanel();
        super.renderWidget(g, mouseX, mouseY, partialTick);
        if (ClientProgress.claimableCount() > 0) {
            Tiles.icon(g, "dot", getX() + getWidth() - 6, getY(), 6, Tiles.GOLD);
        }
    }
}
