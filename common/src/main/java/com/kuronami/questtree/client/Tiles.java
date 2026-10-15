package com.kuronami.questtree.client;

import com.kuronami.questtree.Constants;
import com.kuronami.questtree.progress.ProgressRules.State;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.Set;

/**
 * State tiles, shared by the screen, the in-game tracker and the toasts. The state is a filled surface (yellow: claim
 * or unlock now, green: done), progress fills a node from the bottom, locked nodes are dark with a lock, nodes closed by
 * an exclusive choice carry a cross. Shapes are 32x32 white masks so resource packs can restyle them.
 */
final class Tiles {

    static final int YELLOW = 0xFFF2C230;
    static final int YELLOW_DARK = 0xFFB98C10;
    static final int GREEN = 0xFF5E9E4A;
    static final int GREEN_DARK = 0xFF3F7231;
    static final int GREEN_TEXT = 0xFF7BC862;
    static final int GRAY = 0xFFA8A8A8;
    static final int TILE = 0x80000000;
    static final int TILE_LOCKED = 0x9E000000;
    static final int INK = 0xFF1A1A1A;

    static final Set<String> SHAPES = Set.of("square", "rsquare", "circle", "diamond", "hexagon", "octagon", "pentagon", "gear", "heart");
    static final String[] LOCK = {".###.", "#...#", "#####", "#####", "#####"};
    static final String[] CROSS = {"#...#", ".#.#.", "..#..", ".#.#.", "#...#"};
    static final String[] TICK = {"....#", "...#.", "#.#..", ".#...", "....."};

    /** Items render at z=150; flat overlays drawn after them need to sit above or the icons show through. */
    static final float OVERLAY_Z = 300;

    private Tiles() {
    }

    static String shapeOf(String shape) {
        return SHAPES.contains(shape) ? shape : "square";
    }

    private static ResourceLocation texture(String shape) {
        return ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/shape/" + shape + ".png");
    }

    /** Draws a white 32x32 shape mask tinted with {@code argb}. */
    static void shape(GuiGraphics g, String shape, int x, int y, int w, int h, int argb) {
        shapeBottom(g, shape, x, y, w, h, 1, argb);
    }

    /** Draws the bottom {@code part} (0..1) of a shape, so fills stay inside round and diamond outlines. */
    static void shapeBottom(GuiGraphics g, String shape, int x, int y, int w, int h, double part, int argb) {
        shapeBand(g, shape, x, y, w, h, 1 - part, 1, argb);
    }

    /** Draws the horizontal band of a shape between {@code from} and {@code to} (0 = top, 1 = bottom). */
    static void shapeBand(GuiGraphics g, String shape, int x, int y, int w, int h, double from, double to, int argb) {
        int y0 = (int) Math.round(h * Math.max(0, from));
        int y1 = (int) Math.round(h * Math.min(1, to));
        if (y1 <= y0) {
            return;
        }
        RenderSystem.enableBlend();
        g.setColor(((argb >> 16) & 255) / 255f, ((argb >> 8) & 255) / 255f, (argb & 255) / 255f, ((argb >>> 24) & 255) / 255f);
        float v0 = 32f * y0 / h;
        float v1 = 32f * y1 / h;
        g.blit(texture(shape), x, y + y0, w, y1 - y0, 0, v0, 32, Math.max(1, Math.round(v1 - v0)), 32, 32);
        g.setColor(1, 1, 1, 1);
    }

    /**
     * Full tile with icon. {@code far} draws the state colour only (map zoomed out). Overlays (lock, cross, dimming)
     * are drawn at {@link #OVERLAY_Z} so they sit above the item.
     */
    static void tile(GuiGraphics g, String shape, int x, int y, int s, State state, double fraction, ItemStack icon, boolean far) {
        shape = shapeOf(shape);
        int edge = Math.max(1, s / 16);
        switch (state) {
            case DONE -> {
                shape(g, shape, x, y, s, s, GREEN_DARK);
                shape(g, shape, x, y, s, s - edge, GREEN);
            }
            case CLAIM -> {
                shape(g, shape, x, y, s, s, YELLOW_DARK);
                shape(g, shape, x, y, s, s - edge, YELLOW);
            }
            case PROGRESS -> {
                shape(g, shape, x, y, s, s, TILE);
                shapeBottom(g, shape, x, y, s, s, fraction, 0x3DF2C230);
                if (fraction > 0) {
                    // the bright edge of the fill, clipped to the shape
                    shapeBand(g, shape, x, y, s, s, 1 - fraction, 1 - fraction + (double) edge / s, 0xD9F2C230);
                }
            }
            case LOCKED, EXCLUDED -> shape(g, shape, x, y, s, s, TILE_LOCKED);
            default -> shape(g, shape, x, y, s, s, TILE);
        }
        if (far) {
            if (state == State.OPEN || state == State.PROGRESS) {
                shape(g, shape, x + s / 4, y + s / 4, s / 2, s / 2, 0xFF9A9A9A);
            }
            return;
        }
        if (!icon.isEmpty()) {
            float k = s / 20f;
            g.pose().pushPose();
            g.pose().translate(x + s / 2f - 8 * k, y + s / 2f - 8 * k - (state == State.DONE || state == State.CLAIM ? edge / 2f : 0), 0);
            g.pose().scale(k, k, 1);
            g.renderItem(icon, 0, 0);
            g.pose().popPose();
        }
        if (state == State.LOCKED || state == State.EXCLUDED) {
            g.pose().pushPose();
            g.pose().translate(0, 0, OVERLAY_Z);
            shape(g, shape, x, y, s, s, 0x99000000);
            int px = Math.max(1, s / 20);
            pixels(g, state == State.LOCKED ? LOCK : CROSS, x + s / 2 - 5 * px / 2, y + s / 2 - 5 * px / 2, px, 0xFFC8C8C8);
            g.pose().popPose();
        }
    }

    static void pixels(GuiGraphics g, String[] rows, int x, int y, int scale, int argb) {
        for (int r = 0; r < rows.length; r++) {
            for (int c = 0; c < rows[r].length(); c++) {
                if (rows[r].charAt(c) == '#') {
                    g.fill(x + c * scale, y + r * scale, x + (c + 1) * scale, y + (r + 1) * scale, argb);
                }
            }
        }
    }

    static int withAlpha(int rgb, float alpha) {
        return ((int) (Math.max(0, Math.min(1, alpha)) * ((rgb >>> 24) == 0 ? 255 : (rgb >>> 24))) << 24) | (rgb & 0xFFFFFF);
    }
}
