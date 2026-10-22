package com.kuronami.questtree.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * What a crate just gave (FTB Quests' {@code RewardNotificationsScreen}). Icons and amounts only: a crate is opened to
 * see the loot, not to read instructions.
 */
public final class CrateLootScreen extends Screen {

    private static final int CELL = 22;
    private static final int PAD = 12;

    private record Entry(ItemStack stack, int count) {
    }

    private final List<Entry> entries;

    public CrateLootScreen(List<ItemStack> items) {
        super(Component.translatable("screen.kuronami_quest_tree.crate_loot"));
        this.entries = group(items);
    }

    private static List<Entry> group(List<ItemStack> items) {
        List<ItemStack> stacks = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (ItemStack stack : items) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            int found = -1;
            for (int i = 0; i < stacks.size(); i++) {
                if (ItemStack.isSameItemSameComponents(stacks.get(i), stack)) {
                    found = i;
                    break;
                }
            }
            if (found < 0) {
                stacks.add(stack.copyWithCount(1));
                counts.add(stack.getCount());
            } else {
                counts.set(found, counts.get(found) + stack.getCount());
            }
        }
        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < stacks.size(); i++) {
            out.add(new Entry(stacks.get(i), counts.get(i)));
        }
        return out;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int columns() {
        return Math.max(1, Math.min(9, entries.size()));
    }

    private int rows() {
        return Math.max(1, (entries.size() + columns() - 1) / columns());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Ui.textScale = ClientConfig.textScale();
        int w = columns() * CELL + PAD * 2;
        int h = rows() * CELL + PAD * 2 + 18;
        int left = (width - w) / 2;
        int top = (height - h) / 2;
        Ui.surface(g, left, top, w, h, Ui.R4, Ui.PANEL_TRANSLUCENT);
        Ui.outline(g, left, top, w, h, Ui.R4, Ui.HAIRLINE);
        Ui.textCenter(g, font, title, width / 2, top + 8, Ui.TEXT);
        ItemStack hovered = ItemStack.EMPTY;
        for (int i = 0; i < entries.size(); i++) {
            int col = i % columns();
            int row = i / columns();
            int x = left + PAD + col * CELL;
            int y = top + PAD + 16 + row * CELL;
            Entry entry = entries.get(i);
            g.renderItem(entry.stack(), x, y);
            g.renderItemDecorations(font, entry.stack(), x, y, entry.count() > 1 ? "×" + entry.count() : "");
            if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
                hovered = entry.stack();
            }
        }
        if (!hovered.isEmpty()) {
            g.renderTooltip(font, hovered, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        onClose();
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(null);
        }
    }
}
