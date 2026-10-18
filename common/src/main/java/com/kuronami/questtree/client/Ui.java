package com.kuronami.questtree.client;

import com.kuronami.questtree.Constants;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * The screen's surfaces, tones and spacing, in one place (added 2026-10-17). Before this, every panel, row and button
 * picked its own numbers, which is what made the screen read as assembled rather than designed.
 * <p>
 * Every fill and border is a white mask under {@code textures/gui/shape}, tinted and nine-sliced at draw time, so a
 * resource pack restyles the whole interface without the code knowing (CRAFT_GUI_RULES). Text is never drawn with the
 * vanilla drop shadow: the tones below carry the hierarchy instead, which is what the drop shadow was faking.
 */
final class Ui {

    /**
     * Text in three steps: what it says, what supports it, what is only locational. Tones and surfaces follow Discord's
     * dark theme (2026-10-18, the player: the screen read "too grey" and Discord is the app players already know):
     * neutral greys with a faint violet cast instead of slate blue, brighter text, and one saturated accent.
     */
    static final int TEXT = 0xFFDBDEE1;
    static final int MUTED = 0xFFB5BAC1;
    static final int DIM = 0xFF949BA4;
    /** Text on a selected row, one step brighter than {@link #TEXT}. */
    static final int BRIGHT = 0xFFF2F3F5;

    /** The map's ground: Discord's main surface. */
    static final int GROUND = 0xFF313338;
    /** Sidebars and the detail sheet sit one step below the ground, as Discord's channel list does. */
    static final int PANEL = 0xFF2B2D31;
    /** Nearly opaque: the world behind only tints the panel, it must not show through as texture. */
    static final int PANEL_TRANSLUCENT = 0xF22B2D31;
    /** The closed icon rail: Discord's server column, the darkest layer. */
    static final int RAIL_CLOSED = 0xF21E1F22;
    /** Floating surfaces (tooltips) go darkest, so they read over anything they land on. */
    static final int FLOATING = 0xF2111214;
    /** The one saturated colour of the chrome: selection marks, focus and the spend action. */
    static final int ACCENT = 0xFF5865F2;
    /** Notification badges: a solid round chip with white digits, as on a phone's app icon. */
    static final int BADGE = 0xFFF2C230;

    /** Overlays laid on whatever is behind them: rows, cards, hover and selection (Discord's hover and selected greys). */
    static final int OVERLAY_1 = 0x4D4E5058;
    static final int OVERLAY_2 = 0x994E5058;
    static final int OVERLAY_3 = 0xCC5C5E66;
    static final int HAIRLINE = 0x1FFFFFFF;
    static final int DIVIDER = 0x14FFFFFF;
    static final int FOCUS = 0xFF5865F2;

    /** Spacing on a 4/8 grid. */
    static final int S1 = 4;
    static final int S2 = 8;
    static final int S3 = 12;
    static final int S4 = 16;

    /** Radii: marks and chips, controls and rows, cards and fields, panels. */
    static final int R1 = 4;
    static final int R2 = 6;
    static final int R3 = 8;
    static final int R4 = 12;

    private static final ResourceLocation FILL = texture("shape/rsquare");
    private static final ResourceLocation EDGE = texture("shape/rsquare_edge");
    /** shape/rsquare is a 128px mask whose corners take 23px; shape/rsquare_edge is 32px with 12px corners. */
    private static final int FILL_TEX = 128;
    private static final int FILL_CORNER = 23;
    private static final int EDGE_TEX = 32;
    private static final int EDGE_CORNER = 12;

    private Ui() {
    }

    private static ResourceLocation texture(String path) {
        return ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/" + path + ".png");
    }

    /** A rounded fill. Corners are the top-left of {@code shape/rsquare}, so every rounded box matches. */
    static void surface(GuiGraphics g, int x, int y, int w, int h, int radius, int argb) {
        if (w <= 0 || h <= 0) {
            return;
        }
        int r = Math.min(Math.min(radius, Math.min(w, h) / 2), FILL_CORNER);
        if (r < 1) {
            g.fill(x, y, x + w, y + h, argb);
            return;
        }
        int m = FILL_TEX - 2 * FILL_CORNER;
        int e = FILL_TEX - FILL_CORNER;
        tint(g, argb);
        g.blit(FILL, x + r, y, w - 2 * r, r, FILL_CORNER, 0, m, FILL_CORNER, FILL_TEX, FILL_TEX);
        g.blit(FILL, x + r, y + h - r, w - 2 * r, r, FILL_CORNER, e, m, FILL_CORNER, FILL_TEX, FILL_TEX);
        g.blit(FILL, x, y + r, r, h - 2 * r, 0, FILL_CORNER, FILL_CORNER, m, FILL_TEX, FILL_TEX);
        g.blit(FILL, x + w - r, y + r, r, h - 2 * r, e, FILL_CORNER, FILL_CORNER, m, FILL_TEX, FILL_TEX);
        g.blit(FILL, x + r, y + r, w - 2 * r, h - 2 * r, FILL_CORNER, FILL_CORNER, m, m, FILL_TEX, FILL_TEX);
        g.blit(FILL, x, y, r, r, 0, 0, FILL_CORNER, FILL_CORNER, FILL_TEX, FILL_TEX);
        g.blit(FILL, x + w - r, y, r, r, e, 0, FILL_CORNER, FILL_CORNER, FILL_TEX, FILL_TEX);
        g.blit(FILL, x, y + h - r, r, r, 0, e, FILL_CORNER, FILL_CORNER, FILL_TEX, FILL_TEX);
        g.blit(FILL, x + w - r, y + h - r, r, r, e, e, FILL_CORNER, FILL_CORNER, FILL_TEX, FILL_TEX);
        g.setColor(1, 1, 1, 1);
    }

    /**
     * A 1px rounded border on the same bounds as {@link #surface}, drawn inside them. The radius is capped at the
     * asset's own, so the line never gets thicker than a hairline.
     */
    static void outline(GuiGraphics g, int x, int y, int w, int h, int radius, int argb) {
        if (w <= 2 || h <= 2) {
            return;
        }
        int r = Math.min(Math.min(radius, Math.min(w, h) / 2), EDGE_CORNER);
        if (r < 1) {
            g.renderOutline(x, y, w, h, argb);
            return;
        }
        int m = EDGE_TEX - 2 * EDGE_CORNER;
        int e = EDGE_TEX - EDGE_CORNER;
        tint(g, argb);
        g.blit(EDGE, x + r, y, w - 2 * r, r, EDGE_CORNER, 0, m, EDGE_CORNER, EDGE_TEX, EDGE_TEX);
        g.blit(EDGE, x + r, y + h - r, w - 2 * r, r, EDGE_CORNER, e, m, EDGE_CORNER, EDGE_TEX, EDGE_TEX);
        g.blit(EDGE, x, y + r, r, h - 2 * r, 0, EDGE_CORNER, EDGE_CORNER, m, EDGE_TEX, EDGE_TEX);
        g.blit(EDGE, x + w - r, y + r, r, h - 2 * r, e, EDGE_CORNER, EDGE_CORNER, m, EDGE_TEX, EDGE_TEX);
        g.blit(EDGE, x, y, r, r, 0, 0, EDGE_CORNER, EDGE_CORNER, EDGE_TEX, EDGE_TEX);
        g.blit(EDGE, x + w - r, y, r, r, e, 0, EDGE_CORNER, EDGE_CORNER, EDGE_TEX, EDGE_TEX);
        g.blit(EDGE, x, y + h - r, r, r, 0, e, EDGE_CORNER, EDGE_CORNER, EDGE_TEX, EDGE_TEX);
        g.blit(EDGE, x + w - r, y + h - r, r, r, e, e, EDGE_CORNER, EDGE_CORNER, EDGE_TEX, EDGE_TEX);
        g.setColor(1, 1, 1, 1);
    }

    /** A panel: a rounded fill with a hairline, the treatment every container in the screen shares. */
    static void panel(GuiGraphics g, int x, int y, int w, int h, int argb) {
        surface(g, x, y, w, h, R4, argb);
        outline(g, x, y, w, h, R4, HAIRLINE);
    }

    static final int BADGE_H = 11;

    /**
     * A count badge whose right edge is {@code right}: round for one digit, a pill for more, white digits on solid
     * {@link #BADGE}. Returns the width it took.
     */
    static int badge(GuiGraphics g, Font font, String count, int right, int y) {
        int w = Math.max(BADGE_H, font.width(count) + 4);
        int x = right - w;
        surface(g, x, y, w, BADGE_H, BADGE_H / 2 + 1, BADGE);
        text(g, font, count, x + (w - font.width(count) + 1) / 2, y + 2, 0xFFFFFFFF);
        return w;
    }

    /** A lifted panel with its shadow, for the surfaces that float over the rest (sheet, minimap, tooltip). */
    static void floating(GuiGraphics g, int x, int y, int w, int h, int argb) {
        shadow(g, x, y, w, h);
        surface(g, x, y, w, h, R4, argb);
        outline(g, x, y, w, h, R4, HAIRLINE);
    }

    /**
     * A soft shadow under a panel: three rounded layers that overlap, so the dark part hugs the panel and fades out.
     * Cheap on purpose (no blur pass, 21 blits), and only drawn for the few floating surfaces.
     */
    static void shadow(GuiGraphics g, int x, int y, int w, int h) {
        for (int i = 3; i >= 1; i--) {
            surface(g, x - i, y - i + 1, w + 2 * i, h + 2 * i, R4, 0x11000000);
        }
    }

    /** A 1px separator inside a panel. */
    static void divider(GuiGraphics g, int x, int y, int w) {
        g.fill(x, y, x + w, y + 1, DIVIDER);
    }

    /** Text without the vanilla drop shadow; the colour does the work instead. */
    static void text(GuiGraphics g, Font font, Component c, int x, int y, int argb) {
        g.drawString(font, c, x, y, argb, false);
    }

    static void text(GuiGraphics g, Font font, String s, int x, int y, int argb) {
        g.drawString(font, s, x, y, argb, false);
    }

    static void text(GuiGraphics g, Font font, FormattedCharSequence s, int x, int y, int argb) {
        g.drawString(font, s, x, y, argb, false);
    }

    static void textRight(GuiGraphics g, Font font, Component c, int right, int y, int argb) {
        g.drawString(font, c, right - font.width(c), y, argb, false);
    }

    static void textRight(GuiGraphics g, Font font, String s, int right, int y, int argb) {
        g.drawString(font, s, right - font.width(s), y, argb, false);
    }

    static void textCenter(GuiGraphics g, Font font, Component c, int cx, int y, int argb) {
        g.drawString(font, c, cx - font.width(c) / 2, y, argb, false);
    }

    /**
     * Our own tooltip: the first line is the title, the rest is what supports it. Replaces the vanilla tooltip, whose
     * purple frame and drop shadow were the loudest thing on the screen.
     */
    static void tooltip(GuiGraphics g, Font font, List<Component> lines, int mouseX, int mouseY) {
        if (lines.isEmpty()) {
            return;
        }
        int textW = 0;
        for (Component c : lines) {
            textW = Math.max(textW, font.width(c));
        }
        int w = textW + 2 * S2;
        int h = lines.size() * 10 + 2 * S1 - 2;
        int x = mouseX + 12;
        int y = mouseY - 12;
        if (x + w + 4 > g.guiWidth()) {
            x = Math.max(4, g.guiWidth() - w - 4);
        }
        if (y + h + 4 > g.guiHeight()) {
            y = g.guiHeight() - h - 4;
        }
        y = Math.max(4, y);
        shadow(g, x, y, w, h);
        surface(g, x, y, w, h, R3, FLOATING);
        outline(g, x, y, w, h, R3, HAIRLINE);
        int ty = y + S1 - 1;
        for (int i = 0; i < lines.size(); i++) {
            text(g, font, lines.get(i), x + S2, ty, i == 0 ? TEXT : MUTED);
            ty += 10;
        }
    }

    private static void tint(GuiGraphics g, int argb) {
        RenderSystem.enableBlend();
        g.setColor(((argb >> 16) & 255) / 255f, ((argb >> 8) & 255) / 255f, (argb & 255) / 255f, ((argb >>> 24) & 255) / 255f);
    }
}
