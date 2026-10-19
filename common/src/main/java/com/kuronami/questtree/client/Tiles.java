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
 * (rebuilt 2026-10-15): grey, white, blue, green, gold, and red only for the cross of a closed choice. Shapes are smooth
 * white masks so resource packs can restyle them.
 */
final class Tiles {

    static int GREEN = 0xFF5E9E4A;
    static int GREEN_DARK = 0xFF3F7231;
    static int GREEN_TEXT = 0xFF7BC862;
    /**
     * Not yet: an opaque grey a step above the screen's ground, so closed nodes keep the tree's shape visible. Picked
     * with the ground on 2026-10-17: the old near-black pair left a 196-quest chapter hard to read.
     */
    static int TILE_LOCKED = 0xFF6B707A;
    static int INK = 0xFF1A1A1A;

    static final Set<String> SHAPES = Set.of("square", "rsquare", "circle", "diamond", "octagon", "pentagon", "gear", "heart");

    /** Items render at z=150; flat overlays drawn after them need to sit above or the icons show through. */
    static final float OVERLAY_Z = 300;

    private Tiles() {
    }

    /**
     * Shape names outside {@link #SHAPES} draw as a square. FTB Quests' hexagon is one of them: dropped on 2026-10-15
     * (the player: it looked bad at every proportion tried), so imported hexagon quests show as squares.
     */
    static String shapeOf(String shape) {
        return SHAPES.contains(shape) ? shape : "square";
    }

    private static ResourceLocation texture(String path) {
        return ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/" + path + ".png");
    }

    /**
     * Shapes and marks are smooth white masks (128px shapes, 64px marks, filtered when scaled), tinted when drawn. Until
     * 2026-10-16 they were laid out pixel by pixel; the player compared them with FTB Quests' smooth shapes and asked to
     * stop drawing everything as pixel art. Resource packs can restyle any of them.
     */
    static void shape(GuiGraphics g, String shape, int x, int y, int w, int h, int argb) {
        shapeBand(g, shape, x, y, w, h, 0, 1, argb);
    }

    /** The shape's outline only (the reward rim, selection), same bounds as {@link #shape}. */
    static void ring(GuiGraphics g, String shape, int x, int y, int w, int h, int argb) {
        tinted(g, texture("shape/" + shapeOf(shape) + "_ring"), x, y, w, h, 0, 1, argb);
    }

    /** Draws the bottom {@code part} (0..1) of a shape, so fills stay inside round and diamond outlines. */
    static void shapeBottom(GuiGraphics g, String shape, int x, int y, int w, int h, double part, int argb) {
        shapeBand(g, shape, x, y, w, h, 1 - part, 1, argb);
    }

    /** Draws the horizontal band of a shape between {@code from} and {@code to} (0 = top, 1 = bottom). */
    static void shapeBand(GuiGraphics g, String shape, int x, int y, int w, int h, double from, double to, int argb) {
        tinted(g, texture("shape/" + shapeOf(shape)), x, y, w, h, from, to, argb);
    }

    /** A mark (textures/gui/icon): tick, cross, lock, link, now, plus, minus, center, chevron, dot. */
    static void icon(GuiGraphics g, String name, float x, float y, float size, int argb) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(size / 16f, size / 16f, 1);
        tinted(g, texture("icon/" + name), 0, 0, 16, 16, 0, 1, argb);
        g.pose().popPose();
    }

    private static void tinted(GuiGraphics g, ResourceLocation tex, int x, int y, int w, int h, double from, double to, int argb) {
        int y0 = (int) Math.round(h * Math.max(0, from));
        int y1 = (int) Math.round(h * Math.min(1, to));
        if (y1 <= y0 || w <= 0) {
            return;
        }
        RenderSystem.enableBlend();
        g.setColor(((argb >> 16) & 255) / 255f, ((argb >> 8) & 255) / 255f, (argb & 255) / 255f, ((argb >>> 24) & 255) / 255f);
        // the texture is addressed as if it were w x h, so a band's UVs are exact at any size
        g.blit(tex, x, y + y0, w, y1 - y0, 0, y0, w, y1 - y0, w, h);
        g.setColor(1, 1, 1, 1);
    }

    /**
     * Full tile with icon. {@code far} draws the state colour only (map zoomed out). Overlays (cross, dimming)
     * are drawn at {@link #OVERLAY_Z} so they sit above the item.
     */
    /**
     * What a tile shows (2026-10-15 colour rebuild, DESIGN_NOTES). The state is a solid surface (the treatment the player
     * rated best); never a coloured rim around a pale tint of the same colour (negative_list section 7). Dark = not yet,
     * light grey (Minecraft's GUI grey) = can start / can unlock, blue rising from the bottom = in progress, green = done.
     * A done quest with rewards waiting keeps the green, bright with a thick bottom edge like a button before it is
     * pressed; once claimed it is a plain settled green. One colour for "done", so claiming is never read as a second achievement. Gold is kept for
     * rewards and currency elsewhere (claim button, points).
     */
    enum Look { LOCKED_FAR, LOCKED_NEAR, EXCLUDED, OPEN, AFFORDABLE, PROGRESS, DONE, DONE_REWARD }

    static int WHITE = 0xFFE8E8E8;
    static int GREEN_BRIGHT = 0xFF6DB556;
    static int GREEN_SETTLED = 0xFF54883F;
    static int GREY = 0xFFC6C6C6;
    static int GREY_DARK = 0xFF8B8B8B;
    static int BLUE = 0xFF4F9FDB;
    static int BLUE_DARK = 0xFF2F6E9F;
    /** The waterline: {@link #BLUE} lifted and pulled towards white, the way a surface catches the light. */
    static int SURFACE = shade(BLUE, 1.22f, 0.90f);
    static int GOLD = 0xFFF2C230;
    static int RED = 0xFFD05656;

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

    /**
     * A solid surface whose edge follows the shape: a darker rim, a lighter top-left, a darker bottom-right, all made
     * from the one state colour (the player, 2026-10-19: the edge colour should come from the main colour). Four
     * stretched copies of the same mask, so round, diamond, gear and heart work too; an earlier attempt failed only
     * because it drew straight lines along the four sides of a rectangle.
     */
    private static void rim(GuiGraphics g, String shape, int x, int y, int s, int face) {
        int e = Math.max(1, s / 16);
        shape(g, shape, x, y, s, s, shade(face, 0.58f, 1f));
        g.enableScissor(x + e, y + e, x + s - e, y + s - e);
        shape(g, shape, x + e, y + e, s - 2 * e, s - 2 * e, shade(face, 1.30f, 0.92f));
        shape(g, shape, x + 2 * e, y + 2 * e, s - 2 * e, s - 2 * e, shade(face, 0.74f, 1f));
        shape(g, shape, x + 2 * e, y + 2 * e, s - 4 * e, s - 4 * e, face);
        g.disableScissor();
    }

    /** A tone of {@code argb}: value scaled by {@code v}, saturation by {@code s} (both clamped). */
    private static int shade(int argb, float v, float s) {
        float r = ((argb >> 16) & 255) / 255f;
        float gr = ((argb >> 8) & 255) / 255f;
        float b = (argb & 255) / 255f;
        float max = Math.max(r, Math.max(gr, b));
        float min = Math.min(r, Math.min(gr, b));
        float d = max - min;
        float hue = 0;
        if (d > 0) {
            if (max == r) {
                hue = ((gr - b) / d + 6) % 6;
            } else if (max == gr) {
                hue = (b - r) / d + 2;
            } else {
                hue = (r - gr) / d + 4;
            }
            hue /= 6;
        }
        float sat = Math.min(1, max <= 0 ? 0 : d / max * s);
        float val = Math.min(1, max * v);
        int i = (int) (hue * 6) % 6;
        float f = hue * 6 - (int) (hue * 6);
        float p = val * (1 - sat);
        float q = val * (1 - sat * f);
        float t = val * (1 - sat * (1 - f));
        float nr;
        float ng;
        float nb;
        switch (i) {
            case 0 -> { nr = val; ng = t; nb = p; }
            case 1 -> { nr = q; ng = val; nb = p; }
            case 2 -> { nr = p; ng = val; nb = t; }
            case 3 -> { nr = p; ng = q; nb = val; }
            case 4 -> { nr = t; ng = p; nb = val; }
            default -> { nr = val; ng = p; nb = q; }
        }
        return (argb & 0xFF000000) | (Math.round(nr * 255) << 16) | (Math.round(ng * 255) << 8) | Math.round(nb * 255);
    }


    /**
     * The blue that fills with the tasks done. Water seen from above is lighter at the surface and darker with depth, so
     * the light tone sits on the waterline and the dark one at the bottom. Until 2026-10-20 the dark tone sat on the
     * waterline, which read as a shadow lying on top of the water instead of a surface. With a low alpha the same shape
     * is drawn over the icon again, so an item standing in water is tinted below the surface.
     */
    private static void water(GuiGraphics g, String shape, int x, int y, int s, double fraction, float alpha) {
        int e = Math.max(1, s / 16);
        int x0 = x + e;
        int y0 = y + e;
        int is = s - 2 * e;
        double f = Math.max(0, Math.min(1, fraction));
        if (f <= 0) {
            return;
        }
        int deep = Math.max(1, s / 16);
        shapeBottom(g, shape, x0, y0, is, is, f, withAlpha(BLUE, alpha));
        if (f * is > 5) {
            // the depth only shows when there is water for it: in a 4px sliver three tones turn into stripes
            shapeBottom(g, shape, x0, y0, is, is, Math.min(f, deep / (double) is), withAlpha(BLUE_DARK, alpha));
        }
        if (alpha >= 1f && f * is > 3) {
            // 1px of the lighter tone on the waterline, so the top of the water reads as a surface and not a cut. Only
            // on the first pass: drawn again over the icon it would look like a scratch across the item
            shapeBand(g, shape, x0, y0, is, is, 1 - f, 1 - f + 1.0 / is, withAlpha(SURFACE, alpha));
        }
    }

    private static void rewardFrame(GuiGraphics g, String shape, int x, int y, int s) {
        // rewards waiting: a button before it is pressed. Bright green with a thick dark bottom edge, so it stands
        // off the flat settled green of a claimed quest. Not a lighter green rim inside the shape (negative list
        // section 7) and not the gold rim that was here until 2026-10-19, which the player called lame.
        rim(g, shape, x, y, s, GREEN_BRIGHT);
        shapeBottom(g, shape, x, y, s, s, Math.max(2, s / 8) / (double) s, GREEN_DARK);
    }

    static void tile(GuiGraphics g, String shape, int x, int y, int s, State state, double fraction, ItemStack icon, boolean far) {
        tile(g, shape, x, y, s, look(state), fraction, icon, far);
    }

    /**
     * A diamond in the same box as a square has half its area and reads smaller (the player, 2026-10-16), so it is drawn
     * 1.25x larger around the same centre. Use this for anything that hugs a tile (selection ring).
     */
    static int drawnSize(String shape, int s) {
        return shapeOf(shape).equals("diamond") ? Math.round(s * 1.25f / 2) * 2 : s;
    }

    static void tile(GuiGraphics g, String shape, int x, int y, int s, Look look, double fraction, ItemStack icon, boolean far) {
        shape = shapeOf(shape);
        int big = drawnSize(shape, s);
        x -= (big - s) / 2;
        y -= (big - s) / 2;
        s = big;
        switch (look) {
            // done: green. Rewards still waiting = a gold rim; claimed = flat and settled
            case DONE_REWARD -> rewardFrame(g, shape, x, y, s);
            case DONE -> rim(g, shape, x, y, s, GREEN_SETTLED);
            case AFFORDABLE -> rim(g, shape, x, y, s, WHITE);
            case OPEN -> rim(g, shape, x, y, s, GREY);
            case PROGRESS -> {
                rim(g, shape, x, y, s, GREY);
                // the blue rises with the tasks done, inside the rim
                water(g, shape, x, y, s, fraction, 1f);
            }
            case LOCKED_FAR, LOCKED_NEAR, EXCLUDED -> rim(g, shape, x, y, s, TILE_LOCKED);
        }
        if (far) {
            // zoomed out the surface colour is the information; nothing on top
            return;
        }
        if (!icon.isEmpty()) {
            // a diamond's and a pentagon's insides are small: a full-size icon spills over their slopes. The pentagon's
            // widest part is below its middle, so its icon sits a little lower
            boolean penta = shape.equals("pentagon");
            float k = s / 20f * (shape.equals("diamond") ? 0.62f : penta ? 0.8f : 1f);
            float drop = penta ? s * 0.06f : 0;
            g.pose().pushPose();
            float lift = look == Look.DONE_REWARD ? 0
                    : look == Look.OPEN || look == Look.AFFORDABLE || look == Look.PROGRESS ? Math.max(1, s / 16) / 2f : 0;
            g.pose().translate(x + s / 2f - 8 * k, y + s / 2f - 8 * k - lift + drop, 0);
            g.pose().scale(k, k, 1);
            g.renderItem(icon, 0, 0);
            g.pose().popPose();
        }
        if (look == Look.PROGRESS) {
            // the item stands in the water: the fill goes over the part below the surface, so the tile reads as depth
            // and not as a bar clipped into the shape
            water(g, shape, x, y, s, fraction, 0.30f);
        }
        if (look == Look.LOCKED_FAR || look == Look.LOCKED_NEAR || look == Look.EXCLUDED) {
            g.pose().pushPose();
            g.pose().translate(0, 0, OVERLAY_Z);
            // not yet: the dark tile under it already says "closed", so the icon on top only needs a light dim. The
            // player compared our map with FTB Quests on ATM10 (2026-10-16): the old 0.72/0.45 overlay read as rows of
            // black squares, where FTB Quests keeps a locked quest's icon clearly visible. EXCLUDED keeps the 0.45 dim
            // because its red cross is what matters. No lock mark: the grey tile says it is closed.
            shape(g, shape, x, y, s, s, withAlpha(TILE_LOCKED,
                    look == Look.LOCKED_FAR ? 0.30f : look == Look.LOCKED_NEAR ? 0.15f : 0.45f));
            if (look == Look.EXCLUDED) {
                float c = s * 0.36f;
                icon(g, "cross", x + (s - c) / 2f, y + (s - c) / 2f, c, RED);
            }
            g.pose().popPose();
        }
    }

    static int withAlpha(int rgb, float alpha) {
        return ((int) (Math.max(0, Math.min(1, alpha)) * ((rgb >>> 24) == 0 ? 255 : (rgb >>> 24))) << 24) | (rgb & 0xFFFFFF);
    }
}
