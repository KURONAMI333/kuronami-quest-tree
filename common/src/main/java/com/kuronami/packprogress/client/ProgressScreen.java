package com.kuronami.packprogress.client;

import com.kuronami.packprogress.Constants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only progression screen (phase 1 of the implementation plan, see _research/progression_ui/PLAN.md).
 * <p>
 * Layout follows the accepted v12-v14 mocks: a chapter screen gives the tree the whole screen; the chapter list is a
 * rail that opens on hover; node details slide in from the right only while a node is selected; zoom buttons and the
 * minimap sit bottom-right. Progress is not tracked yet, so nodes show "open" (no requirements) or "locked".
 * Everything is drawn in GUI units with the vanilla font, so it follows the player's GUI scale (2 to 4 tested by
 * arithmetic; 480x270 at scale 4 on 1080p is the smallest target).
 */
public class ProgressScreen extends Screen {

    // palette (same values as the mocks)
    private static final int YELLOW = 0xFFF2C230;
    private static final int GRAY = 0xFFA8A8A8;
    private static final int TILE = 0x80000000;
    private static final int TILE_LOCKED = 0xA0000000;
    private static final int PANEL = 0xF20E0E10;

    private static final int UNIT = 24;      // GUI px per FTB Quests grid unit
    private static final int NODE = 20;      // GUI px of a size-1 node
    private static final int RAIL = 34;
    private static final int RAIL_OPEN = 150;
    private static final int BTN = 20;
    private static final float FAR = 0.6f;   // below this zoom, nodes are drawn as state-coloured shapes without icons
    /** Items render at z=150; flat overlays drawn after them need to sit above or the icons show through. */
    private static final float OVERLAY_Z = 300;

    private static final Set<String> SHAPES = Set.of("square", "rsquare", "circle", "diamond", "hexagon", "octagon", "pentagon", "gear", "heart");
    private static final String[] LOCK = {".###.", "#...#", "#####", "#####", "#####"};
    private static final String[] NOW_ICON = {"##.#####", "##......", "........", "##.#####", "##......", "........", "##.####."};

    /** Per-chapter camera, kept for the session (players asked for the position to be remembered). */
    private static final Map<String, float[]> VIEWS = new HashMap<>();
    private static int lastChapter = -1;
    private int railScroll;
    private int overviewScroll;

    /** Called on logout so another server starts with fresh cameras. */
    public static void clearViews() {
        VIEWS.clear();
        lastChapter = -1;
    }

    private int chapterIndex = lastChapter;
    private String selected;
    private float railT;
    private float sheetT;
    private boolean dragging;
    private boolean moved;
    private double dragStartX;
    private double dragStartY;

    public ProgressScreen() {
        super(Component.translatable("screen.pack_progress.title"));
    }

    private ClientModel model() {
        return ClientProgress.model();
    }

    private ClientModel.Chapter chapter() {
        ClientModel m = model();
        if (m == null || chapterIndex < 0 || chapterIndex >= m.chapters.size()) {
            return null;
        }
        return m.chapters.get(chapterIndex);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.fill(0, 0, width, height, 0xB80A0A0C);
        ClientModel m = model();
        if (m == null || m.chapters.isEmpty()) {
            g.drawCenteredString(font, Component.translatable("screen.pack_progress.empty"), width / 2, height / 2 - 4, GRAY);
            return;
        }
        boolean overRail = mouseX < (railT > 0.5f ? RAIL_OPEN : RAIL) + 8;
        railT = Mth.clamp(railT + (overRail ? 0.25f : -0.25f), 0, 1);
        sheetT = Mth.clamp(sheetT + (selected != null ? 0.3f : -0.35f), 0, 1);

        ClientModel.Chapter ch = chapter();
        if (ch == null) {
            renderOverview(g, m, mouseX, mouseY);
        } else {
            renderChapter(g, ch, mouseX, mouseY);
        }
        g.pose().pushPose();
        g.pose().translate(0, 0, OVERLAY_Z + 100);
        renderRail(g, m, mouseX, mouseY);
        g.pose().popPose();
    }

    private int railRows() {
        return Math.max(1, (height - 8 - 30 - 26) / 22);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // vanilla blur of the world behind, then our own scrim in render()
        this.renderBlurredBackground(partialTick);
    }

    private void renderRail(GuiGraphics g, ClientModel m, int mouseX, int mouseY) {
        int w = (int) Mth.lerp(ease(railT), RAIL, RAIL_OPEN);
        g.fill(4, 4, 4 + w, height - 4, railT > 0.01f ? lerpColor(0x73000000, PANEL, railT) : 0x73000000);
        g.enableScissor(4, 4, 4 + w, height - 4);
        int y = 8;
        rowRail(g, y, -1, null, Component.translatable("screen.pack_progress.now"), w, mouseX, mouseY);
        y += 22;
        railScroll = Mth.clamp(railScroll, 0, Math.max(0, m.chapters.size() - railRows()));
        for (int i = railScroll; i < m.chapters.size() && i < railScroll + railRows(); i++) {
            ClientModel.Chapter c = m.chapters.get(i);
            rowRail(g, y, i, c.displayIcon(), c.title, w, mouseX, mouseY);
            y += 22;
        }
        if (railScroll > 0) {
            g.fill(8, 30, w, 31, 0x55FFFFFF);
        }
        if (railScroll + railRows() < m.chapters.size()) {
            g.fill(8, height - 9, w, height - 8, 0x55FFFFFF);
        }
        g.disableScissor();
    }

    private void rowRail(GuiGraphics g, int y, int index, ItemStack icon, Component label, int w, int mouseX, int mouseY) {
        boolean on = index == chapterIndex;
        boolean hover = mouseX >= 4 && mouseX < 4 + w && mouseY >= y && mouseY < y + 20;
        if (on) {
            g.fill(6, y, 2 + w, y + 20, 0x2EFFFFFF);
            g.fill(4, y, 6, y + 20, 0xFFFFFFFF);
        } else if (hover) {
            g.fill(6, y, 2 + w, y + 20, 0x12FFFFFF);
        }
        if (icon == null) {
            pixels(g, NOW_ICON, 13, y + 6, 1, on ? 0xFFFFFFFF : 0xFFD8D8D8);
        } else {
            g.renderItem(icon, 13, y + 2);
        }
        if (railT > 0.6f) {
            int alpha = (int) (255 * Mth.clamp((railT - 0.6f) / 0.4f, 0, 1)) << 24;
            String s = trim(label.getString(), w - 44);
            g.drawString(font, s, 34, y + 6, (on ? 0xFFFFFF : 0xD0D0D0) | alpha, true);
        }
    }

    private void renderOverview(GuiGraphics g, ClientModel m, int mouseX, int mouseY) {
        int left = RAIL + 18;
        g.pose().pushPose();
        g.pose().translate(left, 12, 0);
        g.pose().scale(2, 2, 1);
        g.drawString(font, Component.translatable("screen.pack_progress.now"), 0, 0, 0xFFFFFFFF, true);
        g.pose().popPose();
        int tile = 44;
        int gap = 6;
        int cols = Math.max(1, (width - left - 12) / (tile + gap));
        int y0 = 40 - overviewScroll;
        g.enableScissor(0, 36, width, height);
        for (int i = 0; i < m.chapters.size(); i++) {
            ClientModel.Chapter c = m.chapters.get(i);
            int x = left + (i % cols) * (tile + gap);
            int y = y0 + (i / cols) * (tile + gap + 10);
            if (y + tile < 36 || y > height) {
                continue;
            }
            boolean hover = mouseX >= x && mouseX < x + tile && mouseY >= y && mouseY < y + tile;
            g.fill(x, y, x + tile, y + tile, hover ? 0x99000000 : TILE);
            g.pose().pushPose();
            g.pose().translate(x + tile / 2f - 12, y + tile / 2f - 12, 0);
            g.pose().scale(1.5f, 1.5f, 1);
            g.renderItem(c.displayIcon(), 0, 0);
            g.pose().popPose();
            g.pose().pushPose();
            g.pose().translate(0, 0, OVERLAY_Z);
            g.drawString(font, trim(c.title.getString(), tile + gap), x, y + tile + 2, GRAY, true);
            g.pose().popPose();
        }
        g.disableScissor();
    }

    private int overviewMaxScroll(ClientModel m) {
        int left = RAIL + 18;
        int cols = Math.max(1, (width - left - 12) / 50);
        int rows = (m.chapters.size() + cols - 1) / cols;
        return Math.max(0, 40 + rows * 60 - height + 8);
    }

    private float[] view(ClientModel.Chapter ch) {
        return VIEWS.computeIfAbsent(ch.id, k -> fitView(ch));
    }

    private float[] fitView(ClientModel.Chapter ch) {
        double[] b = bounds(ch);
        float availW = width - RAIL - 24;
        float availH = height - 40;
        float z = (float) Mth.clamp(Math.min(availW / (b[2] - b[0] + 2 * UNIT), availH / (b[3] - b[1] + 2 * UNIT)), 0.3, 1.5);
        float cx = (float) (b[0] + b[2]) / 2f;
        float cy = (float) (b[1] + b[3]) / 2f;
        return new float[]{RAIL + 12 + availW / 2f - cx * z, 30 + availH / 2f - cy * z, z};
    }

    private static double[] bounds(ClientModel.Chapter ch) {
        double minX = 0;
        double minY = 0;
        double maxX = 0;
        double maxY = 0;
        boolean first = true;
        for (ClientModel.Node n : ch.nodes.values()) {
            double x = n.x * UNIT;
            double y = n.y * UNIT;
            if (first) {
                minX = maxX = x;
                minY = maxY = y;
                first = false;
            }
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
        return new double[]{minX, minY, maxX, maxY};
    }

    private void renderChapter(GuiGraphics g, ClientModel.Chapter ch, int mouseX, int mouseY) {
        float[] v = view(ch);
        boolean far = v[2] < FAR;
        g.pose().pushPose();
        g.pose().translate(v[0], v[1], 0);
        g.pose().scale(v[2], v[2], 1);
        // decorations behind everything (FTB Quests chapter images)
        for (ClientModel.Decoration d : ch.decorations) {
            if (d.dev || d.image == null) {
                continue;
            }
            int dw = (int) (d.w * UNIT);
            int dh = (int) (d.h * UNIT);
            g.pose().pushPose();
            g.pose().translate(d.x * UNIT, d.y * UNIT, 0);
            if (d.rotation != 0) {
                g.pose().mulPose(Axis.ZP.rotationDegrees((float) d.rotation));
            }
            RenderSystem.enableBlend();
            g.blit(d.image, -dw / 2, -dh / 2, dw, dh, 0, 0, 1, 1, 1, 1);
            g.pose().popPose();
        }
        // lines
        for (ClientModel.Node n : ch.nodes.values()) {
            for (String r : n.requires) {
                ClientModel.Node p = ch.nodes.get(r);
                if (p == null) {
                    continue;
                }
                line(g, p.x * UNIT, p.y * UNIT, n.x * UNIT, n.y * UNIT, n.optional ? 1 : 2, 0x2EFFFFFF);
            }
        }
        // nodes
        String hovered = null;
        for (Map.Entry<String, ClientModel.Node> e : ch.nodes.entrySet()) {
            ClientModel.Node n = e.getValue();
            int s = (int) Math.round(NODE * n.size);
            int x = (int) Math.round(n.x * UNIT) - s / 2;
            int y = (int) Math.round(n.y * UNIT) - s / 2;
            boolean locked = !n.requires.isEmpty();
            String shape = SHAPES.contains(n.shape) ? n.shape : "square";
            if (e.getKey().equals(selected)) {
                shape(g, shape, x - 3, y - 3, s + 6, s + 6, 0xFFFFFFFF);
                shape(g, shape, x - 1, y - 1, s + 2, s + 2, 0xFF141416);
            }
            shape(g, shape, x, y, s, s, locked ? TILE_LOCKED : TILE);
            if (!far) {
                float k = s / 20f;
                g.pose().pushPose();
                g.pose().translate(x + s / 2f - 8 * k, y + s / 2f - 8 * k, 0);
                g.pose().scale(k, k, 1);
                g.renderItem(n.icon, 0, 0);
                g.pose().popPose();
                if (locked) {
                    g.pose().pushPose();
                    g.pose().translate(0, 0, OVERLAY_Z);
                    shape(g, shape, x, y, s, s, 0x99000000);
                    pixels(g, LOCK, x + s / 2 - 2, y + s / 2 - 2, 1, 0xFFC8C8C8);
                    g.pose().popPose();
                }
            } else {
                shape(g, shape, x + s / 4, y + s / 4, s / 2, s / 2, locked ? 0xFF3A3A3C : 0xFF9A9A9A);
            }
            double sx = v[0] + n.x * UNIT * v[2];
            double sy = v[1] + n.y * UNIT * v[2];
            if (Math.abs(mouseX - sx) < s * v[2] / 2 && Math.abs(mouseY - sy) < s * v[2] / 2 && mouseX > railRight() && mouseX < (sheetT > 0 ? sheetLeft() : width)) {
                hovered = e.getKey();
            }
        }
        g.pose().popPose();

        // everything below sits above the map's item icons
        g.pose().pushPose();
        g.pose().translate(0, 0, OVERLAY_Z);
        // header over the map
        g.pose().pushPose();
        g.pose().translate(RAIL + 14, 10, 0);
        g.pose().scale(2, 2, 1);
        g.drawString(font, ch.title, 0, 0, 0xFFFFFFFF, true);
        g.pose().popPose();
        g.drawString(font, Component.translatable("screen.pack_progress.nodes", ch.nodes.size()), RAIL + 14, 30, GRAY, true);

        renderMinimap(g, ch, v);
        renderZoomButtons(g, mouseX, mouseY);
        if (sheetT > 0) {
            renderSheet(g, ch);
        }
        g.pose().popPose();
        if (hovered != null && !dragging && selected == null) {
            g.renderTooltip(font, ch.nodes.get(hovered).title, mouseX, mouseY);
        }
    }

    /** Right edge of the rail as currently drawn (it opens to RAIL_OPEN on hover). */
    private int railRight() {
        return (int) Mth.lerp(ease(railT), RAIL, RAIL_OPEN) + 4;
    }

    private int sheetWidth() {
        return Math.min(200, (int) (width * 0.45f));
    }

    private int sheetLeft() {
        return width - (int) (sheetWidth() * ease(sheetT));
    }

    private void renderSheet(GuiGraphics g, ClientModel.Chapter ch) {
        int sw = sheetWidth();
        int x0 = sheetLeft();
        g.fill(x0, 0, x0 + sw, height, PANEL);
        ClientModel.Node n = selected == null ? null : ch.nodes.get(selected);
        if (n == null) {
            return;
        }
        int x = x0 + 12;
        int w = sw - 24;
        int y = 14;
        pixels(g, new String[]{"#...#", ".#.#.", "..#..", ".#.#.", "#...#"}, x0 + sw - 14, 10, 1, 0xFFC8C8C8);
        g.drawString(font, ch.title, x, y, GRAY, true);
        y += 12;
        List<FormattedCharSequence> titleLines = font.split(n.title, w / 2);
        if (titleLines.size() <= 2) {
            for (FormattedCharSequence line : titleLines) {
                g.pose().pushPose();
                g.pose().translate(x, y, 0);
                g.pose().scale(2, 2, 1);
                g.drawString(font, line, 0, 0, 0xFFFFFFFF, true);
                g.pose().popPose();
                y += 20;
            }
        } else {
            for (FormattedCharSequence line : font.split(n.title, w)) {
                g.drawString(font, line, x, y, 0xFFFFFFFF, true);
                y += 10;
            }
        }
        y += 4;
        for (String d : n.description) {
            for (FormattedCharSequence line : font.split(Component.literal(d), w)) {
                if (y > height - 60) {
                    break;
                }
                g.drawString(font, line, x, y, GRAY, true);
                y += 10;
            }
        }
        if (!n.requires.isEmpty()) {
            y += 8;
            g.drawString(font, Component.translatable("screen.pack_progress.needs"), x, y, GRAY, true);
            y += 12;
            for (String r : n.requires) {
                ClientModel.Node p = ch.nodes.get(r);
                Component name = p != null ? p.title : Component.literal(r);
                if (p != null) {
                    g.renderItem(p.icon, x, y);
                }
                g.drawString(font, trim(name.getString(), w - 20), x + 20, y + 4, 0xFFFFFFFF, true);
                y += 18;
                if (y > height - 80) {
                    break;
                }
            }
        }
        if (!n.tasks.isEmpty()) {
            y += 8;
            g.drawString(font, Component.translatable("screen.pack_progress.tasks"), x, y, GRAY, true);
            y += 12;
            for (ClientModel.Task t : n.tasks) {
                g.fill(x, y - 2, x + w, y - 1, 0x1AFFFFFF);
                if (!t.item.isEmpty()) {
                    g.renderItem(t.item, x, y + 2);
                }
                Component name = t.item.isEmpty() ? Component.translatable("task.pack_progress." + t.type) : t.item.getHoverName();
                g.drawString(font, trim(name.getString(), w - 60), x + 20, y + 6, 0xFFFFFFFF, true);
                String cnt = "0/" + t.count;
                g.drawString(font, cnt, x + w - font.width(cnt), y + 6, GRAY, true);
                y += 20;
                if (y > height - 50) {
                    break;
                }
            }
        }
        if (!n.rewards.isEmpty() || !n.rewardLines.isEmpty()) {
            y += 8;
            g.drawString(font, Component.translatable("screen.pack_progress.rewards"), x, y, GRAY, true);
            y += 12;
            int rx = x;
            for (ItemStack r : n.rewards) {
                g.fill(rx, y, rx + 22, y + 22, TILE);
                g.renderItem(r, rx + 3, y + 3);
                g.renderItemDecorations(font, r, rx + 3, y + 3);
                rx += 26;
                if (rx + 22 > x + w) {
                    break;
                }
            }
            if (!n.rewards.isEmpty()) {
                y += 28;
            }
            for (String line : n.rewardLines) {
                g.drawString(font, line, x, y, 0xFFFFFFFF, true);
                y += 10;
            }
        }
        if (n.cost > 0) {
            y += 8;
            g.drawString(font, Component.translatable("screen.pack_progress.cost", n.cost), x, y, YELLOW, true);
        }
    }

    private void renderZoomButtons(GuiGraphics g, int mouseX, int mouseY) {
        int right = (sheetT > 0 ? sheetLeft() : width) - 8;
        String[][] glyphs = {
                {"..#..", "#####", "#.#.#", "#####", "..#.."},
                {".....", ".....", "#####", ".....", "....."},
                {"..#..", "..#..", "#####", "..#..", "..#.."}};
        for (int i = 0; i < 3; i++) {
            int x = right - (3 - i) * (BTN + 3);
            int y = height - BTN - 8;
            boolean hover = mouseX >= x && mouseX < x + BTN && mouseY >= y && mouseY < y + BTN;
            g.fill(x, y, x + BTN, y + BTN, hover ? 0xCC28282C : 0x99000000);
            pixels(g, glyphs[i], x + 7, y + 7, 1, 0xFFFFFFFF);
        }
    }

    /** Minimap only when the chapter does not fit on screen at the current zoom. */
    private int[] minimapRect(ClientModel.Chapter ch, float[] v) {
        double[] b = bounds(ch);
        double cw = (b[2] - b[0] + 2 * UNIT) * v[2];
        double chh = (b[3] - b[1] + 2 * UNIT) * v[2];
        if (cw <= width - RAIL - 24 && chh <= height - 40) {
            return null;
        }
        int mw = 110;
        int mh = 70;
        int right = (sheetT > 0 ? sheetLeft() : width) - 8;
        return new int[]{right - mw, height - BTN - 16 - mh, mw, mh};
    }

    private void renderMinimap(GuiGraphics g, ClientModel.Chapter ch, float[] v) {
        int[] r = minimapRect(ch, v);
        if (r == null) {
            return;
        }
        g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], 0x99000000);
        double[] b = bounds(ch);
        double s = Math.min((r[2] - 8) / Math.max(1, b[2] - b[0]), (r[3] - 8) / Math.max(1, b[3] - b[1]));
        for (ClientModel.Node n : ch.nodes.values()) {
            int px = r[0] + 4 + (int) ((n.x * UNIT - b[0]) * s);
            int py = r[1] + 4 + (int) ((n.y * UNIT - b[1]) * s);
            g.fill(px - 1, py - 1, px + 1, py + 1, n.requires.isEmpty() ? 0xFF9A9A9A : 0xFF4A4A4C);
        }
        // the part of the chapter that is on screen
        double vx0 = (-v[0]) / v[2];
        double vy0 = (-v[1]) / v[2];
        double vx1 = vx0 + width / v[2];
        double vy1 = vy0 + height / v[2];
        int x0 = Mth.clamp(r[0] + 4 + (int) ((vx0 - b[0]) * s), r[0], r[0] + r[2]);
        int y0 = Mth.clamp(r[1] + 4 + (int) ((vy0 - b[1]) * s), r[1], r[1] + r[3]);
        int x1 = Mth.clamp(r[0] + 4 + (int) ((vx1 - b[0]) * s), r[0], r[0] + r[2]);
        int y1 = Mth.clamp(r[1] + 4 + (int) ((vy1 - b[1]) * s), r[1], r[1] + r[3]);
        g.renderOutline(x0, y0, Math.max(2, x1 - x0), Math.max(2, y1 - y0), 0xFFFFFFFF);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        ClientModel m = model();
        if (m == null || button != 0) {
            return super.mouseClicked(mx, my, button);
        }
        int railW = railT > 0.5f ? RAIL_OPEN : RAIL;
        if (mx < railW + 4) {
            int row = (int) Math.floor((my - 8) / 22);
            if (row == 0) {
                select(-1);
            } else if (row >= 1 && row <= railRows()) {
                int index = railScroll + row - 1;
                if (index < m.chapters.size()) {
                    select(index);
                }
            }
            return true;
        }
        ClientModel.Chapter ch = chapter();
        if (ch == null) {
            return clickOverview(m, mx, my);
        }
        float[] v = view(ch);
        if (sheetT > 0 && selected != null && mx >= sheetLeft()) {
            if (mx >= sheetLeft() + sheetWidth() - 18 && my < 22) {
                selected = null;
            }
            return true;
        }
        int right = (sheetT > 0 ? sheetLeft() : width) - 8;
        for (int i = 0; i < 3; i++) {
            int x = right - (3 - i) * (BTN + 3);
            int y = height - BTN - 8;
            if (mx >= x && mx < x + BTN && my >= y && my < y + BTN) {
                zoomButton(ch, v, i);
                return true;
            }
        }
        int[] r = minimapRect(ch, v);
        if (r != null && mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
            double[] b = bounds(ch);
            double s = Math.min((r[2] - 8) / Math.max(1, b[2] - b[0]), (r[3] - 8) / Math.max(1, b[3] - b[1]));
            double wx = (mx - r[0] - 4) / s + b[0];
            double wy = (my - r[1] - 4) / s + b[1];
            v[0] = (float) (width / 2.0 - wx * v[2]);
            v[1] = (float) (height / 2.0 - wy * v[2]);
            return true;
        }
        dragging = true;
        moved = false;
        dragStartX = mx;
        dragStartY = my;
        return true;
    }

    private boolean clickOverview(ClientModel m, double mx, double my) {
        int left = RAIL + 18;
        int tile = 44;
        int gap = 6;
        int cols = Math.max(1, (width - left - 12) / (tile + gap));
        int col = (int) ((mx - left) / (tile + gap));
        int row = (int) ((my - 40 + overviewScroll) / (tile + gap + 10));
        if (mx >= left && my >= 40 && col < cols) {
            int index = row * cols + col;
            if (index < m.chapters.size()) {
                select(index);
            }
        }
        return true;
    }

    private void select(int index) {
        chapterIndex = index;
        lastChapter = index;
        selected = null;
        sheetT = 0;
    }

    private void zoomButton(ClientModel.Chapter ch, float[] v, int which) {
        switch (which) {
            case 0 -> VIEWS.put(ch.id, fitView(ch));
            case 1 -> zoomAt(v, width / 2.0, height / 2.0, 1 / 1.25f);
            default -> zoomAt(v, width / 2.0, height / 2.0, 1.25f);
        }
    }

    private static void zoomAt(float[] v, double cx, double cy, float f) {
        float z = Mth.clamp(v[2] * f, 0.3f, 3f);
        float r = z / v[2];
        v[0] = (float) (cx - (cx - v[0]) * r);
        v[1] = (float) (cy - (cy - v[1]) * r);
        v[2] = z;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        ClientModel.Chapter ch = chapter();
        if (dragging && ch != null) {
            if (!moved && Math.hypot(mx - dragStartX, my - dragStartY) > 3) {
                moved = true;
            }
            if (moved) {
                float[] v = view(ch);
                v[0] += (float) dx;
                v[1] += (float) dy;
            }
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        ClientModel.Chapter ch = chapter();
        if (dragging && ch != null && !moved) {
            float[] v = view(ch);
            String hit = null;
            for (Map.Entry<String, ClientModel.Node> e : ch.nodes.entrySet()) {
                ClientModel.Node n = e.getValue();
                double sx = v[0] + n.x * UNIT * v[2];
                double sy = v[1] + n.y * UNIT * v[2];
                double half = NODE * n.size * v[2] / 2;
                if (Math.abs(mx - sx) <= half && Math.abs(my - sy) <= half) {
                    hit = e.getKey();
                }
            }
            selected = hit;
            if (hit != null) {
                keepVisible(ch, v, ch.nodes.get(hit));
            }
        }
        dragging = false;
        return super.mouseReleased(mx, my, button);
    }

    /** Shift the map so the selected node is not hidden under the sheet. */
    private void keepVisible(ClientModel.Chapter ch, float[] v, ClientModel.Node n) {
        double sx = v[0] + n.x * UNIT * v[2];
        int limit = width - sheetWidth() - 24;
        if (sx > limit) {
            v[0] -= (float) (sx - (RAIL + (limit - RAIL) / 2.0));
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        ClientModel m = model();
        if (m == null) {
            return super.mouseScrolled(mx, my, scrollX, scrollY);
        }
        if (mx < railRight()) {
            railScroll = Mth.clamp(railScroll - (int) Math.signum(scrollY), 0, Math.max(0, m.chapters.size() - railRows()));
            return true;
        }
        ClientModel.Chapter ch = chapter();
        if (ch == null) {
            overviewScroll = Mth.clamp(overviewScroll - (int) (scrollY * 20), 0, overviewMaxScroll(m));
            return true;
        }
        float[] v = view(ch);
        if (scrollX != 0) {
            // trackpads send both axes: pan (FTB Quests issue #2059 asked for this)
            v[0] += (float) (scrollX * 12);
            v[1] += (float) (scrollY * 12);
        } else {
            zoomAt(v, mx, my, (float) Math.pow(1.15, scrollY));
        }
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (ClientProgress.OPEN_KEY.matches(key, scan)) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override
    public void removed() {
        lastChapter = chapterIndex;
        super.removed();
    }

    // ------------------------------------------------------------------ drawing helpers

    private static ResourceLocation shapeTexture(String shape) {
        return ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/shape/" + shape + ".png");
    }

    /** Draws a white 32x32 shape mask tinted with {@code argb}. */
    private static void shape(GuiGraphics g, String shape, int x, int y, int w, int h, int argb) {
        RenderSystem.enableBlend();
        g.setColor(((argb >> 16) & 255) / 255f, ((argb >> 8) & 255) / 255f, (argb & 255) / 255f, ((argb >>> 24) & 255) / 255f);
        g.blit(shapeTexture(shape), x, y, w, h, 0, 0, 32, 32, 32, 32);
        g.setColor(1, 1, 1, 1);
    }

    /** Draws a line of {@code thickness} GUI px between two points in the current pose. */
    private static void line(GuiGraphics g, double x1, double y1, double x2, double y2, int thickness, int argb) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1) {
            return;
        }
        g.pose().pushPose();
        g.pose().translate(x1, y1, 0);
        g.pose().mulPose(Axis.ZP.rotation((float) Math.atan2(dy, dx)));
        g.fill(0, -thickness / 2, (int) len, thickness - thickness / 2, argb);
        g.pose().popPose();
    }

    private static void pixels(GuiGraphics g, String[] rows, int x, int y, int scale, int argb) {
        for (int r = 0; r < rows.length; r++) {
            for (int c = 0; c < rows[r].length(); c++) {
                if (rows[r].charAt(c) == '#') {
                    g.fill(x + c * scale, y + r * scale, x + (c + 1) * scale, y + (r + 1) * scale, argb);
                }
            }
        }
    }

    private String trim(String s, int w) {
        if (font.width(s) <= w) {
            return s;
        }
        return font.plainSubstrByWidth(s, Math.max(0, w - font.width("..."))) + "...";
    }

    private static float ease(float t) {
        float u = 1 - t;
        return 1 - u * u * u;
    }

    private static int lerpColor(int a, int b, float t) {
        int r = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int ca = (a >>> shift) & 255;
            int cb = (b >>> shift) & 255;
            r |= ((int) (ca + (cb - ca) * t) & 255) << shift;
        }
        return r;
    }
}
