package com.kuronami.questtree.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * A searchable list that returns one id (2026-10-21, phase 3): items, entities, dimensions, biomes, structures,
 * advancements, task and reward types, reward tables. Typed text that matches nothing is offered as it is
 * ({@code allowFree}), so a tag or an id from a mod the client has not loaded still gets in.
 */
final class EditPickerScreen extends Screen {

    private static final int ROW_H = 18;

    private final Screen parent;
    private final List<EditPickers.Option> all;
    private final boolean allowFree;
    private final Consumer<String> picked;
    private final List<EditPickers.Option> shown = new ArrayList<>();
    private EditBox search;
    private double scroll;
    private int px;
    private int py;
    private int pw;
    private int ph;

    EditPickerScreen(Screen parent, Component title, List<EditPickers.Option> options, boolean allowFree,
                     Consumer<String> picked) {
        super(title);
        this.parent = parent;
        this.all = options;
        this.allowFree = allowFree;
        this.picked = picked;
    }

    @Override
    protected void init() {
        pw = Math.min(width - 16, 300);
        ph = height - 16;
        px = (width - pw) / 2;
        py = 8;
        String keep = search == null ? "" : search.getValue();
        search = new EditBox(font, px + 10, py + 22, pw - 20, 12, title);
        search.setBordered(false);
        search.setTextColor(Ui.TEXT);
        search.setMaxLength(256);
        search.setValue(keep);
        search.setResponder(v -> refilter());
        addRenderableWidget(search);
        setInitialFocus(search);
        refilter();
    }

    private void refilter() {
        shown.clear();
        String q = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        boolean exact = false;
        for (EditPickers.Option o : all) {
            if (q.isEmpty() || o.id().toLowerCase(Locale.ROOT).contains(q)
                    || o.label().getString().toLowerCase(Locale.ROOT).contains(q)) {
                shown.add(o);
            }
            exact |= o.id().equalsIgnoreCase(q);
        }
        if (allowFree && !q.isEmpty() && !exact) {
            shown.add(0, new EditPickers.Option(search.getValue().trim(), Component.literal(search.getValue().trim()),
                    net.minecraft.world.item.ItemStack.EMPTY));
        }
        scroll = 0;
    }

    private int listTop() {
        return py + 42;
    }

    private int listHeight() {
        return py + ph - 8 - listTop();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(0, 0, width, height, Ui.GROUND);
        Ui.panel(g, px, py, pw, ph, Ui.PANEL);
        Ui.text(g, font, title, px + 10, py + 8, Ui.BRIGHT);
        Ui.surface(g, px + 6, py + 19, pw - 12, 18, Ui.R1, Ui.OVERLAY_1);
        Ui.outline(g, px + 6, py + 19, pw - 12, 18, Ui.R1, search.isFocused() ? Ui.FOCUS : Ui.HAIRLINE);
        search.render(g, mouseX, mouseY, partialTick);
        int top = listTop();
        int h = listHeight();
        double max = Math.max(0, shown.size() * ROW_H - h);
        scroll = Mth.clamp(scroll, 0, max);
        g.enableScissor(px + 4, top, px + pw - 4, top + h);
        int first = (int) (scroll / ROW_H);
        for (int i = first; i < shown.size() && i * ROW_H - scroll < h; i++) {
            EditPickers.Option o = shown.get(i);
            int ry = top + (int) (i * ROW_H - scroll);
            boolean over = mouseX >= px + 4 && mouseX < px + pw - 8 && mouseY >= ry && mouseY < ry + ROW_H
                    && mouseY >= top && mouseY < top + h;
            if (over) {
                Ui.surface(g, px + 6, ry, pw - 16, ROW_H, Ui.R1, Ui.OVERLAY_1);
            }
            int tx = px + 12;
            if (!o.icon().isEmpty()) {
                g.pose().pushPose();
                g.pose().translate(tx, ry + 2, 0);
                g.pose().scale(0.875f, 0.875f, 1);
                g.renderItem(o.icon(), 0, 0);
                g.pose().popPose();
                tx += 18;
            }
            String name = font.plainSubstrByWidth(o.label().getString(), Ui.textRoom(pw - 40 - (tx - px - 12) - 70));
            Ui.text(g, font, name, tx, ry + (ROW_H - 8) / 2, Ui.TEXT);
            String id = font.plainSubstrByWidth(o.id(), Ui.textRoom(140));
            Ui.textRight(g, font, id, px + pw - 14, ry + (ROW_H - 8) / 2, Ui.DIM);
        }
        g.disableScissor();
        if (max > 0) {
            int thumb = Math.max(12, (int) (h * (h / (double) (shown.size() * ROW_H))));
            int ty = top + (int) ((h - thumb) * (scroll / max));
            Ui.surface(g, px + pw - 8, ty, 3, thumb, 2, Ui.SCROLLBAR);
        }
        if (shown.isEmpty()) {
            Ui.text(g, font, "-", px + 12, top + 4, Ui.DIM);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (my >= listTop() && my < listTop() + listHeight() && mx >= px + 4 && mx < px + pw - 8 && button == 0) {
            int index = (int) ((my - listTop() + scroll) / ROW_H);
            if (index >= 0 && index < shown.size()) {
                choose(shown.get(index).id());
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        scroll = Mth.clamp(scroll - dy * ROW_H * 2, 0, Math.max(0, shown.size() * ROW_H - listHeight()));
        return true;
    }

    private void choose(String id) {
        onClose();
        picked.accept(id);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && !shown.isEmpty()) {
            choose(shown.get(0).id());
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }
}
