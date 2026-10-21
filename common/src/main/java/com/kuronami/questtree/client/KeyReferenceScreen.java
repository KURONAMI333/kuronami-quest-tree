package com.kuronami.questtree.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The book's keys in one place, opened from the settings (2026-10-22). Label and key only: no sentences, as on the
 * settings screen, so nothing here needs translating twice.
 */
public final class KeyReferenceScreen extends Screen {

    private static final int ROW_H = 22;
    private static final int PANEL_W = 300;

    private final Screen parent;
    private final List<String> labels = new ArrayList<>();
    private final List<Component> keys = new ArrayList<>();

    public KeyReferenceScreen(Screen parent) {
        super(Component.translatable("screen.kuronami_quest_tree.keys"));
        this.parent = parent;
        add("open", ClientProgress.OPEN_KEY.getTranslatedKeyMessage());
        add("track", ClientProgress.TRACK_KEY.getTranslatedKeyMessage());
        add("search", Component.literal("Ctrl + F"));
        add("tab", Component.literal("Tab"));
        add("back", Component.literal("Backspace"));
        add("recenter", Component.literal("Space"));
        add("page", Component.literal("Page Up / Page Down"));
        add("zoom", Component.literal("Ctrl + " + Component.translatable("screen.kuronami_quest_tree.keys.wheel").getString()));
        add("pan", Component.translatable("screen.kuronami_quest_tree.keys.wheel"));
        add("debug", Component.literal("F3"));
        add("close", Component.literal("Esc"));
    }

    private void add(String label, Component key) {
        labels.add("screen.kuronami_quest_tree.keys." + label);
        keys.add(key);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Ui.textScale = ClientConfig.textScale();
        g.fill(0, 0, width, height, Ui.GROUND);
        int x = (width - PANEL_W) / 2;
        int h = labels.size() * ROW_H + 30;
        int y = Math.max(16, (height - h) / 2);
        Ui.textCenter(g, font, title, width / 2, y, Ui.TEXT);
        y += 22;
        Ui.surface(g, x, y, PANEL_W, labels.size() * ROW_H + 8, Ui.R4, Ui.PANEL_TRANSLUCENT);
        Ui.outline(g, x, y, PANEL_W, labels.size() * ROW_H + 8, Ui.R4, Ui.HAIRLINE);
        for (int i = 0; i < labels.size(); i++) {
            int ry = y + 4 + i * ROW_H;
            Ui.text(g, font, Component.translatable(labels.get(i)), x + 12, ry + (ROW_H - 8) / 2, Ui.MUTED);
            Ui.textRight(g, font, keys.get(i), x + PANEL_W - 12, ry + (ROW_H - 8) / 2, Ui.TEXT);
        }
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
            minecraft.setScreen(parent);
        }
    }
}
