package com.kuronami.questtree.client;

import com.kuronami.questtree.Constants;
import com.kuronami.questtree.mixin.AbstractContainerScreenAccessor;
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
 * book button is: a 20×18 {@link ImageButton}. Its frame is vanilla's {@code widget/button} sprite and its mark is the
 * quest book item, so a resource pack that restyles buttons or the book restyles this too (2026-10-22: the first,
 * own beige frame did not fit the inventory).
 * <p>
 * It reads {@code leftPos}/{@code topPos} every frame, so opening the recipe book (which moves the inventory) and
 * resizing keep it over the preview. The player chose this spot (2026-10-22), so it stays even when FTB Library adds
 * our entry to its sidebar as well; the client setting {@code inventory_button} turns this one off, and FTB's own
 * sidebar settings turn that one off.
 */
public final class InventoryQuestButton extends ImageButton {

    /** Vanilla's own button frame, so the button follows whatever the resource pack does to buttons. */
    private static final WidgetSprites SPRITES = new WidgetSprites(
            ResourceLocation.withDefaultNamespace("widget/button"),
            ResourceLocation.withDefaultNamespace("widget/button_highlighted"));
    private static final ResourceLocation BOOK = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "quest_book");
    private final AbstractContainerScreen<?> screen;

    private InventoryQuestButton(AbstractContainerScreen<?> screen) {
        super(0, 0, InventoryButtonLayout.WIDTH, InventoryButtonLayout.HEIGHT, SPRITES,
                button -> Minecraft.getInstance().setScreen(new ProgressScreen()),
                Component.translatable("item.kuronami_quest_tree.quest_book"));
        this.screen = screen;
        setTooltip(Tooltip.create(Component.translatable("item.kuronami_quest_tree.quest_book")));
        followPanel();
    }

    /** Adds the button to the survival inventory, once. Other screens and creative are left alone. */
    public static void add(Screen screen, Consumer<AbstractButton> add) {
        if (!(screen instanceof InventoryScreen) || !ClientConfig.inventoryButton()) {
            return;
        }
        add.accept(new InventoryQuestButton((AbstractContainerScreen<?>) screen));
    }

    private void followPanel() {
        AbstractContainerScreenAccessor a = (AbstractContainerScreenAccessor) screen;
        setX(InventoryButtonLayout.x(a.questTree$leftPos()));
        setY(InventoryButtonLayout.y(a.questTree$topPos()));
        visible = InventoryButtonLayout.fits(a.questTree$topPos());
    }

    @Override
    public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        followPanel();
        super.renderWidget(g, mouseX, mouseY, partialTick);
        g.renderItem(new net.minecraft.world.item.ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(BOOK)),
                getX() + (getWidth() - 16) / 2, getY() + (getHeight() - 16) / 2);
        if (ClientProgress.claimableCount() > 0) {
            Tiles.icon(g, "dot", getX() + getWidth() - 6, getY(), 6, Tiles.GOLD);
        }
    }
}
