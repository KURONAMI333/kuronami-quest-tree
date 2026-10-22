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
    static int TEXT = 0xFFDBDEE1;
    static int MUTED = 0xFFB5BAC1;
    static int DIM = 0xFF949BA4;
    /** Text on a selected row, one step brighter than {@link #TEXT}. */
    static int BRIGHT = 0xFFF2F3F5;

    /** The map's ground: Discord's main surface. */
    static int GROUND = 0xFF313338;
    /** Sidebars and the detail sheet sit one step below the ground, as Discord's channel list does. */
    static int PANEL = 0xFF2B2D31;
    /** Nearly opaque: the world behind only tints the panel, it must not show through as texture. */
    static int PANEL_TRANSLUCENT = 0xF22B2D31;
    /** The closed icon rail: Discord's server column, the darkest layer. */
    static int RAIL_CLOSED = 0xF21E1F22;
    /** Floating surfaces (tooltips) go darkest, so they read over anything they land on. */
    static int FLOATING = 0xF2111214;
    /** The one saturated colour of the chrome: selection marks, focus and the spend action. */
    static int ACCENT = 0xFF5865F2;
    /**
     * Notification badges: a solid round chip, as on a phone's app icon. The digits are dark, not white: white on this
     * yellow reads at about 1.6:1 (2026-10-18, the player found it hard to read); the dark grey gives about 10:1.
     */
    static int BADGE = 0xFFF2C230;
    static int BADGE_TEXT = 0xFF1E1F22;

    /** Overlays laid on whatever is behind them: rows, cards, hover and selection (Discord's hover and selected greys). */
    static int OVERLAY_1 = 0x4D4E5058;
    static int OVERLAY_2 = 0x994E5058;
    static int OVERLAY_3 = 0xCC5C5E66;
    static int HAIRLINE = 0x1FFFFFFF;
    static int DIVIDER = 0x14FFFFFF;
    /** The focused field: white, not the accent, so the accent stays on two things (selection and the spend action). */
    static int FOCUS = 0x66FFFFFF;
    /** Whatever is switched off (a disabled button), and the fade where a list runs under its edge: the panel's own hue. */
    static int DISABLED = 0x4D1E1F22;

    /** A search that matched nothing. */
    static int TEXT_ERROR = 0xFFFF8A8A;
    /** Scrollbar thumb, and while the pointer is over its list. */
    static int SCROLLBAR = 0x5FFFFFFF;
    static int SCROLLBAR_HOVER = 0x99FFFFFF;
    /** Small white marks on tiles (quest link arrow, the Now list dot). */
    static int MARK = 0xFFFFFFFF;
    /** The selected tile ring, the path to it, the picked reward. */
    static int SELECTION = 0xCCFFFFFF;
    /** Dashed outline of optional quests. */
    static int OPTIONAL_OUTLINE = 0x66FFFFFF;
    /** The flash when a tile changes state. */
    static int FLASH = 0xFFFFFFFF;
    /** Minimap: the frame of the visible area, and a startable quest. */
    static int MINIMAP_VIEW = 0xAAFFFFFF;
    static int MINIMAP_OPEN = 0xFF9A9A9A;
    /** Clear glass (the minimap): a cool tint multiplied into the sharp picture behind it. */
    static int GLASS_TINT = 0xFFD6E0F5;
    /** Clear glass: the dimming layer, alpha = how much (Apple suggests 35% over bright content). */
    static int GLASS_CLEAR = 0x5912141A;
    /** Regular glass (panels with text): the tone the blurred picture is pulled towards, alpha = how far. */
    static int GLASS_REGULAR = 0xE01E2025;
    /** An open line under the pointer. */
    static int LINE_OPEN_HOVER = 0xFFEDEDEF;
    /** Behind the pinned-quest overlay and its notices (over the world). */
    static int HUD_BACK = 0x66111214;
    static int HUD_TEXT = 0xFFE0E0E0;
    static int HUD_MUTED = 0xFFB0B0B0;
    /** The task line's own text, its have/need count when unfinished, the dot and the quest name. */
    static int HUD_TASK = 0xFFFFFFFF;
    static int HUD_COUNT_DOT = 0xFF888888;
    static int HUD_QUEST = 0xFFC8C8C8;
    /**
     * The J tracker's frame (2026-10-22, after Jade's tooltip): Jade's dark theme is #131313 at 70% opacity, with a
     * 1px border that runs from #383838 at the top to #242424 at the bottom. The tones are ours, the structure is
     * Jade's (see {@link #frame}). Read from {@code gui/palette.json}, so a pack can restyle it.
     */
    /**
     * The tracker card's Jade-matching frame (2026-10-22, the player: still too dark). Jade's dark theme is #131313
     * with the tooltip alpha of 0.7, and Jade applies it as 0.7 * 255 / 256, so the drawn alpha is 0.697 = 0xB1:
     * #B1131313 inside, #B1383838 at the top and sides, #B1242424 at the bottom.
     */
    static int HUD_TRACKER = 0xB1131313;
    static int HUD_TRACKER_EDGE = 0xB1383838;
    static int HUD_TRACKER_EDGE_BOTTOM = 0xB1242424;
    /** A toast's level line (yellow), and its title (white). */
    static int TOAST_LEVEL = 0xFFFFFF55;
    static int TOAST_TITLE = 0xFFFFFFFF;
    /** The empty part of the experience toast bar, and the toast's own frame (vanilla's #212121 on #555555). */
    static int TOAST_TRACK = 0xFF2A2A2A;
    static int TOAST_FILL = 0xFF212121;
    static int TOAST_EDGE = 0xFF555555;

    /** Screen text scale, separate from Minecraft's GUI scale (2026-10-22). */
    static float textScale = 1f;

    /** Spacing on a 4/8 grid. */
    static final int S1 = 4;
    static final int S2 = 8;
    static final int S3 = 12;
    static final int S4 = 16;

    /**
     * The corner radius of every piece of chrome, in GUI pixels, from {@code corner_radius} in the palette
     * (2026-10-22, the player: the screen was too round to read as Minecraft; 0 is square). The four names keep the
     * old call sites readable, but all of them are the one value now: chips, rows, cards, fields and panels share it.
     * Quest tile shapes and the belt's chevrons are content, not chrome, and do not use it.
     */
    static int CORNER = 2;
    static int R1 = CORNER;
    static int R2 = CORNER;
    static int R3 = CORNER;
    static int R4 = CORNER;

    private static final ResourceLocation FILL = texture("shape/rsquare");
    private static final ResourceLocation EDGE = texture("shape/rsquare_edge");
    /** shape/rsquare is a 128px mask whose corners take 23px; shape/rsquare_edge is 32px with 12px corners. */
    private static final int FILL_TEX = 128;
    private static final int FILL_CORNER = 23;
    private static final int EDGE_TEX = 32;
    private static final int EDGE_CORNER = 12;

    private Ui() {
    }

    private static final ResourceLocation PALETTE = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "gui/palette.json");
    private static final ResourceLocation THEME = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "gui/theme.json");

    /** Per-chapter and per-quest colour overrides, empty unless a pack supplies {@code gui/theme.json}. */
    private static ThemeOverrides theme = ThemeOverrides.EMPTY;

    /**
     * Reads {@code assets/kuronami_quest_tree/gui/palette.json} so a resource pack can recolour the whole screen
     * (CRAFT_GUI_RULES: the look is replaceable, no colour lives only in code). Missing keys keep their current value;
     * colours are "#RRGGBB" or "#AARRGGBB". Called when the book arrives and whenever the screen opens, so a pack
     * change applies the next time it is opened.
     */
    static void loadPalette(net.minecraft.server.packs.resources.ResourceManager rm) {
        restoreDefaults();
        theme = ThemeOverrides.EMPTY;
        try {
            var res = rm.getResource(THEME);
            if (res.isPresent()) {
                try (var reader = res.get().openAsReader()) {
                    theme = ThemeOverrides.parse(com.google.gson.JsonParser.parseReader(reader).getAsJsonObject());
                }
            }
        } catch (Exception ex) {
            Constants.LOG.warn("Could not read {}: {}", THEME, ex.toString());
        }
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        try {
            var res = rm.getResource(PALETTE);
            if (res.isPresent()) {
                try (var reader = res.get().openAsReader()) {
                    o = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
                }
            }
        } catch (Exception ex) {
            Constants.LOG.warn("Could not read {}: {}", PALETTE, ex.toString());
        }
        TEXT = color(o, "text", TEXT);
        MUTED = color(o, "text_muted", MUTED);
        DIM = color(o, "text_dim", DIM);
        BRIGHT = color(o, "text_bright", BRIGHT);
        GROUND = color(o, "ground", GROUND);
        PANEL = color(o, "panel", PANEL);
        PANEL_TRANSLUCENT = color(o, "panel_translucent", PANEL_TRANSLUCENT);
        RAIL_CLOSED = color(o, "rail_closed", RAIL_CLOSED);
        FLOATING = color(o, "floating", FLOATING);
        ACCENT = color(o, "accent", ACCENT);
        BADGE = color(o, "badge", BADGE);
        BADGE_TEXT = color(o, "badge_text", BADGE_TEXT);
        OVERLAY_1 = color(o, "overlay_hover", OVERLAY_1);
        OVERLAY_2 = color(o, "overlay_selected", OVERLAY_2);
        OVERLAY_3 = color(o, "overlay_pressed", OVERLAY_3);
        HAIRLINE = color(o, "hairline", HAIRLINE);
        DIVIDER = color(o, "divider", DIVIDER);
        FOCUS = color(o, "focus", FOCUS);
        DISABLED = color(o, "disabled", DISABLED);
        Tiles.GREEN = color(o, "done", Tiles.GREEN);
        Tiles.GREEN_DARK = color(o, "done_dark", Tiles.GREEN_DARK);
        Tiles.GREEN_TEXT = color(o, "done_text", Tiles.GREEN_TEXT);
        Tiles.TILE_LOCKED = color(o, "tile_locked", Tiles.TILE_LOCKED);
        Tiles.INK = color(o, "ink", Tiles.INK);
        Tiles.WHITE = color(o, "tile_white", Tiles.WHITE);
        Tiles.GREEN_BRIGHT = color(o, "done_bright", Tiles.GREEN_BRIGHT);
        Tiles.GREEN_SETTLED = color(o, "done_settled", Tiles.GREEN_SETTLED);
        Tiles.GREY = color(o, "tile_open", Tiles.GREY);
        Tiles.BLUE = color(o, "progress", Tiles.BLUE);
        Tiles.BLUE_DARK = color(o, "progress_dark", Tiles.BLUE_DARK);
        // the waterline follows "progress" unless a pack sets its own
        Tiles.SURFACE = o.has("progress_surface") ? color(o, "progress_surface", Tiles.SURFACE) : Tiles.surfaceOf(Tiles.BLUE);
        Tiles.GOLD = color(o, "reward", Tiles.GOLD);
        Tiles.RED = color(o, "excluded", Tiles.RED);
        ProgressScreen.LINE_OPEN = color(o, "line_open", ProgressScreen.LINE_OPEN);
        ProgressScreen.LINE_CLOSED = color(o, "line_closed", ProgressScreen.LINE_CLOSED);
        ProgressScreen.LINE_CLOSED_ON = color(o, "line_closed_hover", ProgressScreen.LINE_CLOSED_ON);
        TEXT_ERROR = color(o, "text_error", TEXT_ERROR);
        SCROLLBAR = color(o, "scrollbar", SCROLLBAR);
        SCROLLBAR_HOVER = color(o, "scrollbar_hover", SCROLLBAR_HOVER);
        MARK = color(o, "mark", MARK);
        SELECTION = color(o, "selection", SELECTION);
        OPTIONAL_OUTLINE = color(o, "optional_outline", OPTIONAL_OUTLINE);
        FLASH = color(o, "flash", FLASH);
        MINIMAP_VIEW = color(o, "minimap_view", MINIMAP_VIEW);
        MINIMAP_OPEN = color(o, "minimap_open", MINIMAP_OPEN);
        GLASS_TINT = color(o, "glass_tint", GLASS_TINT);
        GLASS_CLEAR = color(o, "glass_clear", GLASS_CLEAR);
        GLASS_REGULAR = color(o, "glass_regular", GLASS_REGULAR);
        ProgressScreen.LINE_REQUIRES = color(o, "line_requires", ProgressScreen.LINE_REQUIRES);
        ProgressScreen.LINE_UNLOCKS = color(o, "line_unlocks", ProgressScreen.LINE_UNLOCKS);
        LINE_OPEN_HOVER = color(o, "line_open_hover", LINE_OPEN_HOVER);
        HUD_BACK = color(o, "hud_back", HUD_BACK);
        HUD_TEXT = color(o, "hud_text", HUD_TEXT);
        HUD_MUTED = color(o, "hud_muted", HUD_MUTED);
        HUD_TRACKER = color(o, "hud_tracker", HUD_TRACKER);
        HUD_TRACKER_EDGE = color(o, "hud_tracker_edge", HUD_TRACKER_EDGE);
        HUD_TRACKER_EDGE_BOTTOM = color(o, "hud_tracker_edge_bottom", HUD_TRACKER_EDGE_BOTTOM);
        TOAST_TRACK = color(o, "toast_track", TOAST_TRACK);
        TOAST_FILL = color(o, "toast_fill", TOAST_FILL);
        TOAST_EDGE = color(o, "toast_edge", TOAST_EDGE);
        applyCornerRadius(cornerOverride != null ? cornerOverride : integer(o, "corner_radius", CORNER));
        if (ClientConfig.highContrast()) {
            // readability over the pack's palette: white text on a near-black ground, and a warmer selection
            TEXT = 0xFFFFFFFF;
            MUTED = 0xFFEAEAEA;
            DIM = 0xFFCFCFCF;
            BRIGHT = 0xFFFFFFFF;
            GROUND = 0xFF0E0F12;
            PANEL = 0xFF17181C;
            PANEL_TRANSLUCENT = 0xFA17181C;
            RAIL_CLOSED = 0xFA0B0C0E;
            FLOATING = 0xFF000000;
            HAIRLINE = 0x66FFFFFF;
            DIVIDER = 0x33FFFFFF;
            ACCENT = 0xFFFFC400;
            SELECTION = 0xFFFFFFFF;
            HUD_TEXT = 0xFFFFFFFF;
            HUD_MUTED = 0xFFE0E0E0;
            HUD_TRACKER = 0xFA0B0C0E;
            HUD_TRACKER_EDGE = 0x66FFFFFF;
            HUD_TRACKER_EDGE_BOTTOM = 0x33FFFFFF;
        }
        ProgressScreen.paletteChanged();
    }

    /** The colours as compiled, so switching from a pack to one that sets fewer keys does not keep the first pack's. */
    private static int[] defaults;

    private static int[] current() {
        return new int[]{TEXT, MUTED, DIM, BRIGHT, GROUND, PANEL, PANEL_TRANSLUCENT, RAIL_CLOSED, FLOATING, ACCENT, BADGE, BADGE_TEXT, OVERLAY_1, OVERLAY_2, OVERLAY_3, HAIRLINE, DIVIDER, FOCUS, DISABLED, Tiles.GREEN, Tiles.GREEN_DARK, Tiles.GREEN_TEXT, Tiles.TILE_LOCKED, Tiles.INK, Tiles.WHITE, Tiles.GREEN_BRIGHT, Tiles.GREEN_SETTLED, Tiles.GREY, Tiles.BLUE, Tiles.BLUE_DARK, Tiles.GOLD, Tiles.RED, ProgressScreen.LINE_OPEN, ProgressScreen.LINE_CLOSED, ProgressScreen.LINE_CLOSED_ON, TEXT_ERROR, SCROLLBAR, SCROLLBAR_HOVER, MARK, SELECTION, OPTIONAL_OUTLINE, FLASH, MINIMAP_VIEW, MINIMAP_OPEN, LINE_OPEN_HOVER, HUD_BACK, HUD_TEXT, HUD_MUTED, HUD_TRACKER, HUD_TRACKER_EDGE, HUD_TRACKER_EDGE_BOTTOM, TOAST_TRACK, TOAST_FILL, TOAST_EDGE, GLASS_TINT, GLASS_CLEAR, GLASS_REGULAR, ProgressScreen.LINE_REQUIRES, ProgressScreen.LINE_UNLOCKS};
    }

    private static void restoreDefaults() {
        applyCornerRadius(2);
        if (defaults == null) {
            defaults = current();
            return;
        }
        int i = 0;
        TEXT = defaults[i++];
        MUTED = defaults[i++];
        DIM = defaults[i++];
        BRIGHT = defaults[i++];
        GROUND = defaults[i++];
        PANEL = defaults[i++];
        PANEL_TRANSLUCENT = defaults[i++];
        RAIL_CLOSED = defaults[i++];
        FLOATING = defaults[i++];
        ACCENT = defaults[i++];
        BADGE = defaults[i++];
        BADGE_TEXT = defaults[i++];
        OVERLAY_1 = defaults[i++];
        OVERLAY_2 = defaults[i++];
        OVERLAY_3 = defaults[i++];
        HAIRLINE = defaults[i++];
        DIVIDER = defaults[i++];
        FOCUS = defaults[i++];
        DISABLED = defaults[i++];
        Tiles.GREEN = defaults[i++];
        Tiles.GREEN_DARK = defaults[i++];
        Tiles.GREEN_TEXT = defaults[i++];
        Tiles.TILE_LOCKED = defaults[i++];
        Tiles.INK = defaults[i++];
        Tiles.WHITE = defaults[i++];
        Tiles.GREEN_BRIGHT = defaults[i++];
        Tiles.GREEN_SETTLED = defaults[i++];
        Tiles.GREY = defaults[i++];
        Tiles.BLUE = defaults[i++];
        Tiles.BLUE_DARK = defaults[i++];
        Tiles.GOLD = defaults[i++];
        Tiles.RED = defaults[i++];
        ProgressScreen.LINE_OPEN = defaults[i++];
        ProgressScreen.LINE_CLOSED = defaults[i++];
        ProgressScreen.LINE_CLOSED_ON = defaults[i++];
        TEXT_ERROR = defaults[i++];
        SCROLLBAR = defaults[i++];
        SCROLLBAR_HOVER = defaults[i++];
        MARK = defaults[i++];
        SELECTION = defaults[i++];
        OPTIONAL_OUTLINE = defaults[i++];
        FLASH = defaults[i++];
        MINIMAP_VIEW = defaults[i++];
        MINIMAP_OPEN = defaults[i++];
        LINE_OPEN_HOVER = defaults[i++];
        HUD_BACK = defaults[i++];
        HUD_TEXT = defaults[i++];
        HUD_MUTED = defaults[i++];
        HUD_TRACKER = defaults[i++];
        HUD_TRACKER_EDGE = defaults[i++];
        HUD_TRACKER_EDGE_BOTTOM = defaults[i++];
        TOAST_TRACK = defaults[i++];
        TOAST_FILL = defaults[i++];
        TOAST_EDGE = defaults[i++];
        GLASS_TINT = defaults[i++];
        GLASS_CLEAR = defaults[i++];
        GLASS_REGULAR = defaults[i++];
        ProgressScreen.LINE_REQUIRES = defaults[i++];
        ProgressScreen.LINE_UNLOCKS = defaults[i++];
    }

    /**
     * A palette colour for one node: the palette's value, overridden by the node's chapter, then by the node itself
     * ({@code gui/theme.json}). High contrast is a readability switch, so it wins over any pack override.
     */
    static int themed(String key, int fallback, String nodeKey) {
        if (ClientConfig.highContrast()) {
            return fallback;
        }
        return theme.resolve(key, fallback, nodeKey);
    }

    private static int color(com.google.gson.JsonObject o, String key, int fallback) {
        if (!o.has(key)) {
            return fallback;
        }
        try {
            String s = o.get(key).getAsString().trim();
            String hex = s.startsWith("#") ? s.substring(1) : s;
            long v = Long.parseLong(hex, 16);
            return (int) (hex.length() <= 6 ? 0xFF000000L | v : v);
        } catch (RuntimeException ex) {
            Constants.LOG.warn("Bad colour for {} in {}: {}", key, PALETTE, o.get(key));
            return fallback;
        }
    }

    /** A whole number of GUI pixels; a missing or bad value keeps the fallback. */
    private static int integer(com.google.gson.JsonObject o, String key, int fallback) {
        if (!o.has(key)) {
            return fallback;
        }
        try {
            return o.get(key).getAsInt();
        } catch (RuntimeException ex) {
            Constants.LOG.warn("Bad number for {} in {}: {}", key, PALETTE, o.get(key));
            return fallback;
        }
    }

    /** The one corner radius every chrome surface uses; 0 is square, and the mask is nine-sliced for anything between. */
    static void applyCornerRadius(int guiPixels) {
        CORNER = Math.max(0, Math.min(64, guiPixels));
        R1 = R2 = R3 = R4 = CORNER;
    }

    /** Dev tour only: a radius that outlives palette reloads (the screen reloads the palette when it opens). */
    private static Integer cornerOverride;

    /** Dev tour only: hold the radius at {@code guiPixels} until {@link #clearCornerRadius()}. */
    static void setCornerRadius(int guiPixels) {
        cornerOverride = guiPixels;
        applyCornerRadius(guiPixels);
    }

    /** Dev tour only: go back to the palette's radius at the next load. */
    static void clearCornerRadius() {
        cornerOverride = null;
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

    /**
     * A thin border around a fill (2026-10-22, the in-game tracker after Jade's tooltip). Jade's border is a vertical
     * gradient, #383838 at the top to #242424 at the bottom; the bottom row carries the darker tone and the sides keep
     * the lighter one. Every pixel is painted exactly once: the border and the fill never overlap, so a translucent
     * fill shows the world through it at its own alpha. (Until 2026-10-22 the fill sat on top of a full-size border
     * surface, which doubled the alpha inside the card to about 0.9 and read darker than Jade's 0.7.)
     * Corners are cut row by row from the radius, so 0 is square and 1-2 is the slight rounding the screens use.
     */
    static void frame(GuiGraphics g, int x, int y, int w, int h, int radius, int edge, int edgeBottom, int fill) {
        if (w <= 2 || h <= 2) {
            return;
        }
        int r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
        for (int i = 0; i < h; i++) {
            int in = cornerInset(r, i, h);
            int x0 = x + in;
            int x1 = x + w - in;
            if (i == 0 || i == h - 1) {
                g.fill(x0, y + i, x1, y + i + 1, i == h - 1 ? edgeBottom : edge);
                continue;
            }
            // the side border is wider where the row above or below is cut further in, so the outline stays closed
            int side = Math.max(1, Math.max(cornerInset(r, i - 1, h), cornerInset(r, i + 1, h)) - in);
            side = Math.min(side, (x1 - x0) / 2);
            g.fill(x0, y + i, x0 + side, y + i + 1, edge);
            g.fill(x1 - side, y + i, x1, y + i + 1, edge);
            if (x1 - side > x0 + side) {
                g.fill(x0 + side, y + i, x1 - side, y + i + 1, fill);
            }
        }
    }

    /** How far row {@code i} of an {@code h}-tall shape with corner radius {@code r} starts in from its sides. */
    static int cornerInset(int r, int i, int h) {
        if (r <= 0 || i < 0 || i >= h) {
            return 0;
        }
        int d = Math.min(i, h - 1 - i);
        if (d >= r) {
            return 0;
        }
        if (r == 1) {
            return 1; // the slightest rounding: the four corner pixels are left out, as Ui.surface does at radius 1
        }
        double dy = r - d - 0.5;
        return r - (int) Math.round(Math.sqrt(Math.max(0.0, (double) r * r - dy * dy)));
    }

    /** A panel: a rounded fill with a hairline, the treatment every container in the screen shares. */
    static void panel(GuiGraphics g, int x, int y, int w, int h, int argb) {
        surface(g, x, y, w, h, R4, argb);
        outline(g, x, y, w, h, R4, HAIRLINE);
    }

    static final int BADGE_H = 11;

    /**
     * A count badge whose right edge is {@code right}: round for one digit, a pill for more, dark digits on solid
     * {@link #BADGE}. Returns the width it took.
     */
    static int badge(GuiGraphics g, Font font, String count, int right, int y) {
        int textW = textWidth(font, count);
        int w = Math.max(BADGE_H, textW + 4);
        int x = right - w;
        surface(g, x, y, w, BADGE_H, CORNER, BADGE);
        text(g, font, count, x + (w - textW + 1) / 2, y + 2, BADGE_TEXT);
        return w;
    }

    static final int CORNER_BADGE_H = 9;

    /**
     * A small count badge at an icon's top-right corner that stays outside the icon: its left edge starts at
     * {@code iconRight}. Footer icons use it so the number never sits on top of the mark it belongs to (2026-10-22).
     */
    static void cornerBadge(GuiGraphics g, Font font, String count, int iconRight, int iconTop) {
        int textW = textWidth(font, count);
        int w = Math.max(CORNER_BADGE_H, textW + 3);
        int h = CORNER_BADGE_H;
        int x = iconRight + 1;
        int y = iconTop - 2;
        surface(g, x, y, w, h, CORNER, BADGE);
        text(g, font, count, x + (w - textW + 1) / 2, y + 1, BADGE_TEXT);
    }

    /** The advancement toast's frame, drawn here so its corner follows {@link #CORNER} (vanilla's sprite is fixed at 2). */
    static void toastFrame(GuiGraphics g, int w, int h) {
        surface(g, 0, 0, w, h, CORNER, TOAST_FILL);
        outline(g, 0, 0, w, h, CORNER, TOAST_EDGE);
    }

    /**
     * A surface that floats over the rest (sheet, minimap): fill and hairline only. It had a shadow as well until
     * 2026-10-18; border, shadow and fill on one element is one grouping device too many (negative_list §7), and the
     * shadow was pure black, which the same list rules out.
     */
    static void floating(GuiGraphics g, int x, int y, int w, int h, int argb) {
        panel(g, x, y, w, h, argb);
    }

    /** A 1px separator inside a panel. */
    static void divider(GuiGraphics g, int x, int y, int w) {
        g.fill(x, y, x + w, y + 1, DIVIDER);
    }

    /** A string's width on screen at the current text scale. */
    static int textWidth(Font font, String s) {
        return TextFit.width(font.width(s), ClientConfig.textScale());
    }

    static int textWidth(Font font, Component c) {
        return textWidth(font, c.getString());
    }

    static int textWidth(Font font, FormattedCharSequence s) {
        return TextFit.width(font.width(s), ClientConfig.textScale());
    }

    /** The room a fixed-width box has for text in the font's own units. */
    static int textRoom(int pixels) {
        return TextFit.room(pixels, ClientConfig.textScale());
    }

    /** Text without the vanilla drop shadow; the colour does the work instead. */
    static void text(GuiGraphics g, Font font, Component c, int x, int y, int argb) {
        scaled(g, x, y, () -> g.drawString(font, c, 0, 0, argb, false));
    }

    static void text(GuiGraphics g, Font font, String s, int x, int y, int argb) {
        scaled(g, x, y, () -> g.drawString(font, s, 0, 0, argb, false));
    }

    static void text(GuiGraphics g, Font font, FormattedCharSequence s, int x, int y, int argb) {
        scaled(g, x, y, () -> g.drawString(font, s, 0, 0, argb, false));
    }

    private static void scaled(GuiGraphics g, int x, int y, Runnable draw) {
        float scale = ClientConfig.textScale();
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        if (scale != 1f) {
            g.pose().scale(scale, scale, 1);
        }
        draw.run();
        g.pose().popPose();
    }

    static void textRight(GuiGraphics g, Font font, Component c, int right, int y, int argb) {
        text(g, font, c, (int) Math.round(right - font.width(c) * ClientConfig.textScale()), y, argb);
    }

    static void textRight(GuiGraphics g, Font font, String s, int right, int y, int argb) {
        text(g, font, s, (int) Math.round(right - font.width(s) * ClientConfig.textScale()), y, argb);
    }

    static void textCenter(GuiGraphics g, Font font, Component c, int cx, int y, int argb) {
        text(g, font, c, (int) Math.round(cx - font.width(c) * ClientConfig.textScale() / 2), y, argb);
    }

    /**
     * Our own tooltip: the first line is the title, the rest is what supports it. Replaces the vanilla tooltip, whose
     * purple frame and drop shadow were the loudest thing on the screen.
     */
    static void tooltip(GuiGraphics g, Font font, List<Component> lines, int mouseX, int mouseY) {
        if (lines.isEmpty()) {
            return;
        }
        int w = tooltipWidth(font, lines);
        int lineH = tooltipLineH();
        int h = lines.size() * lineH + 2 * S1 - 2;
        // vanilla's DefaultTooltipPositioner (1.21.1): right of and above the pointer, flipped to the left and lifted
        // off the bottom edge when it would leave the screen
        int x = mouseX + 12;
        int y = mouseY - 12;
        if (x + w > g.guiWidth()) {
            x = Math.max(x - 24 - w, 4);
        }
        if (y + h + 3 > g.guiHeight()) {
            y = g.guiHeight() - h - 3;
        }
        drawTooltip(g, font, lines, x, y, w, h, lineH);
    }

    /**
     * A tooltip at an anchor rather than at the pointer, kept inside the screen: the chapter rail's locked hint sits to
     * the right of the row, so it never covers the chapter name it explains (2026-10-22).
     */
    static void tooltipAt(GuiGraphics g, Font font, List<Component> lines, int x, int y) {
        if (lines.isEmpty()) {
            return;
        }
        int w = tooltipWidth(font, lines);
        int lineH = tooltipLineH();
        int h = lines.size() * lineH + 2 * S1 - 2;
        x = Math.max(4, Math.min(x, g.guiWidth() - w - 4));
        y = Math.max(4, Math.min(y, g.guiHeight() - h - 4));
        drawTooltip(g, font, lines, x, y, w, h, lineH);
    }

    private static int tooltipWidth(Font font, List<Component> lines) {
        int textW = 0;
        for (Component c : lines) {
            textW = Math.max(textW, textWidth(font, c));
        }
        return textW + 2 * S2;
    }

    private static int tooltipLineH() {
        return Math.max(10, (int) Math.ceil(10 * ClientConfig.textScale()));
    }

    private static void drawTooltip(GuiGraphics g, Font font, List<Component> lines, int x, int y, int w, int h, int lineH) {
        // flat, not glass: a tooltip is mostly text, and it often opens over the glass of the list or the minimap,
        // where glass on glass is what Apple's guidance rules out
        surface(g, x, y, w, h, R3, FLOATING);
        outline(g, x, y, w, h, R3, HAIRLINE);
        int ty = y + S1 - 1;
        for (int i = 0; i < lines.size(); i++) {
            text(g, font, lines.get(i), x + S2, ty, i == 0 ? TEXT : MUTED);
            ty += lineH;
        }
    }

    private static void tint(GuiGraphics g, int argb) {
        RenderSystem.enableBlend();
        g.setColor(((argb >> 16) & 255) / 255f, ((argb >> 8) & 255) / 255f, (argb & 255) / 255f, ((argb >>> 24) & 255) / 255f);
    }
}
