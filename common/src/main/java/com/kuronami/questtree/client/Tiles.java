package com.kuronami.questtree.client;

import com.kuronami.questtree.Constants;
import com.kuronami.questtree.progress.ProgressRules.State;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.Set;

/**
 * State tiles, shared by the screen, the in-game tracker and the toasts. See {@link Look} for the colour meanings
 * (rebuilt 2026-10-15): grey, white, blue, green, gold, and red only for the cross of a closed choice. Shapes are 32x32
 * white masks so resource packs can restyle them.
 */
final class Tiles {

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
    /**
     * What a tile shows (2026-10-15 colour rebuild, DESIGN_NOTES). The state is a solid surface with a darker bottom
     * edge (the treatment the player rated best); never a coloured rim around a pale tint of the same colour
     * (negative_list section 7). Dark = not yet, light grey (Minecraft's GUI grey) = can start / can unlock, blue rising
     * from the bottom = in progress, green = done. A done quest with rewards waiting is a raised bright green tile (a
     * button waiting to be pressed); once claimed it lies flat in a settled green. One colour for "done", so claiming is
     * never read as a second achievement. Gold is kept for rewards and currency elsewhere (claim button, points).
     */
    enum Look { LOCKED_FAR, LOCKED_NEAR, EXCLUDED, OPEN, AFFORDABLE, PROGRESS, DONE, DONE_REWARD }

    static final int WHITE = 0xFFE8E8E8;
    static final int GREEN_BRIGHT = 0xFF6DB556;
    static final int GREEN_SETTLED = 0xFF3D6431;
    static final int GREY = 0xFFC6C6C6;
    static final int GREY_DARK = 0xFF8B8B8B;
    static final int BLUE = 0xFF4F9FDB;
    static final int BLUE_DARK = 0xFF2F6E9F;
    static final int GOLD = 0xFFF2C230;
    static final int RED = 0xFFD05656;

    /** For callers that only know the state: quests' "claim" is done with a reward waiting. */
    static Look look(State state) {
        return switch (state) {
            case DONE -> Look.DONE;
            case CLAIM -> Look.DONE_REWARD;
            case PROGRESS -> Look.PROGRESS;
            case OPEN -> Look.OPEN;
            case EXCLUDED -> Look.EXCLUDED;
            default -> Look.LOCKED_NEAR;
        };
    }

    /** A solid surface with its darker bottom edge. */
    private static void surface(GuiGraphics g, String shape, int x, int y, int s, int top, int edgeColor) {
        int edge = Math.max(1, s / 16);
        shape(g, shape, x, y, s, s, edgeColor);
        shape(g, shape, x, y, s, s - edge, top);
    }

    static void tile(GuiGraphics g, String shape, int x, int y, int s, State state, double fraction, ItemStack icon, boolean far) {
        tile(g, shape, x, y, s, look(state), fraction, icon, far);
    }

    static void tile(GuiGraphics g, String shape, int x, int y, int s, Look look, double fraction, ItemStack icon, boolean far) {
        shape = shapeOf(shape);
        switch (look) {
            // done: green. Rewards still waiting = a raised bright tile, like a button to press; claimed = flat and settled
            case DONE_REWARD -> {
                int edge = Math.max(2, s / 8);
                shape(g, shape, x, y, s, s, GREEN_DARK);
                shape(g, shape, x, y, s, s - edge, GREEN_BRIGHT);
            }
            case DONE -> shape(g, shape, x, y, s, s, GREEN_SETTLED);
            case AFFORDABLE -> surface(g, shape, x, y, s, WHITE, GREY_DARK);
            case OPEN -> surface(g, shape, x, y, s, GREY, GREY_DARK);
            case PROGRESS -> {
                surface(g, shape, x, y, s, GREY, GREY_DARK);
                // the blue rises with the tasks done
                shapeBottom(g, shape, x, y, s, s, fraction, BLUE_DARK);
                shapeBottom(g, shape, x, y, s, s - Math.max(1, s / 16), Math.max(0, fraction - 1.0 / 16), BLUE);
            }
            case LOCKED_FAR, LOCKED_NEAR, EXCLUDED -> shape(g, shape, x, y, s, s, TILE_LOCKED);
        }
        if (far) {
            // zoomed out the surface colour is the information; nothing on top
            return;
        }
        if (!icon.isEmpty()) {
            float k = s / 20f;
            g.pose().pushPose();
            float lift = look == Look.DONE_REWARD ? Math.max(2, s / 8) / 2f
                    : look == Look.OPEN || look == Look.AFFORDABLE || look == Look.PROGRESS ? Math.max(1, s / 16) / 2f : 0;
            g.pose().translate(x + s / 2f - 8 * k, y + s / 2f - 8 * k - lift, 0);
            g.pose().scale(k, k, 1);
            g.renderItem(icon, 0, 0);
            g.pose().popPose();
        }
        if (look == Look.LOCKED_FAR || look == Look.LOCKED_NEAR || look == Look.EXCLUDED) {
            g.pose().pushPose();
            g.pose().translate(0, 0, OVERLAY_Z);
            // not yet: the icon stays visible but dimmed, so players see what lies ahead; only the next step carries a lock
            shape(g, shape, x, y, s, s, look == Look.LOCKED_FAR ? 0xA6000000 : 0x5C000000);
            int px = Math.max(1, s / 20);
            if (look == Look.LOCKED_NEAR) {
                // the next step: what it is stays readable; a small lock in the corner says it is not open yet
                pixels(g, LOCK, x + s - 5 * px - Math.max(1, s / 16), y + s - 5 * px - Math.max(1, s / 16), px, 0xFFD8D8D8);
            } else if (look == Look.EXCLUDED) {
                pixels(g, CROSS, x + s / 2 - 5 * px / 2, y + s / 2 - 5 * px / 2, px, RED);
            }
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
