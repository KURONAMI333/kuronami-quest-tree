package com.kuronami.questtree.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

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
    private float scroll;
    private float scrollTarget;

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

    private int contentHeight() {
        return labels.size() * ROW_H + 8;
    }

    private int titleY() {
        int natural = Math.max(16, (height - (contentHeight() + 30)) / 2);
        int room = height - (natural + 22) - 8;
        return contentHeight() <= room ? natural : 8;
    }

    private int panelTop() {
        return titleY() + 22;
    }

    private int panelHeight() {
        return Math.min(contentHeight(), Math.max(ROW_H + 8, height - panelTop() - 8));
    }

    private float maxScroll() {
        return Math.max(0, contentHeight() - panelHeight());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Ui.textScale = ClientConfig.textScale();
        g.fill(0, 0, width, height, Ui.GROUND);
        int x = (width - PANEL_W) / 2;
        int top = panelTop();
        int h = panelHeight();
        scrollTarget = Mth.clamp(scrollTarget, 0, maxScroll());
        scroll += (scrollTarget - scroll) * 0.35f;
        scroll = Mth.clamp(scroll, 0, maxScroll());
        int off = Math.round(scroll);
        Ui.textCenter(g, font, title, width / 2, titleY(), Ui.TEXT);
        Ui.surface(g, x, top, PANEL_W, h, Ui.R4, Ui.PANEL_TRANSLUCENT);
        Ui.outline(g, x, top, PANEL_W, h, Ui.R4, Ui.HAIRLINE);
        int listTop = top + 4;
        int listBottom = top + h - 4;
        g.enableScissor(x, listTop, x + PANEL_W, listBottom);
        for (int i = 0; i < labels.size(); i++) {
            int ry = listTop + i * ROW_H - off;
            if (ry + ROW_H <= listTop || ry >= listBottom) {
                continue;
            }
            Ui.text(g, font, Component.translatable(labels.get(i)), x + 12, ry + (ROW_H - 8) / 2, Ui.MUTED);
            Ui.textRight(g, font, keys.get(i), x + PANEL_W - 12, ry + (ROW_H - 8) / 2, Ui.TEXT);
        }
        g.disableScissor();
        if (maxScroll() > 0) {
            int track = h - 8;
            int knobH = Math.max(12, Math.round(track * h / (float) contentHeight()));
            int knobY = listTop + Math.round((track - knobH) * (scroll / maxScroll()));
            Ui.surface(g, x + PANEL_W - 4, listTop, 2, track, Ui.CORNER, Ui.OVERLAY_2);
            Ui.surface(g, x + PANEL_W - 4, knobY, 2, knobH, Ui.CORNER, Ui.MUTED);
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        int x = (width - PANEL_W) / 2;
        int top = panelTop();
        if (maxScroll() > 0 && mx >= x && mx < x + PANEL_W && my >= top && my < top + panelHeight()) {
            scrollTarget = Mth.clamp(scrollTarget - (float) scrollY * ROW_H * 2, 0, maxScroll());
            return true;
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
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
