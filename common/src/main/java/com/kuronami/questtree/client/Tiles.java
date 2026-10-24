package com.kuronami.questtree.client;

import com.kuronami.questtree.Constants;
import com.kuronami.questtree.progress.ProgressRules.State;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    /** Pixel size of a texture, read once from its file ({0, 0} = missing or unreadable). */
    static int[] textureSize(ResourceLocation id) {
        String cache = CacheKey.of(ClientProgress.model(), id);
        return TEXTURE_SIZES.computeIfAbsent(cache, k -> {
            var res = net.minecraft.client.Minecraft.getInstance().getResourceManager().getResource(id);
            if (res.isEmpty()) {
                Constants.LOG.warn("Texture {} does not exist", id);
                return new int[]{0, 0};
            }
            try (var in = res.get().open(); var img = com.mojang.blaze3d.platform.NativeImage.read(in)) {
                return new int[]{img.getWidth(), img.getHeight()};
            } catch (java.io.IOException ex) {
                Constants.LOG.warn("Could not read texture {}: {}", k, ex.toString());
                return new int[]{0, 0};
            }
        });
    }

    /**
     * The file a chapter image names, given the shape FTB Quests writes: a bare path is under {@code textures/}, an
     * {@code ns:block/x} sprite keeps its namespace, and a name ending in {@code .png} is already a texture path.
     */
    static ResourceLocation imagePath(String image) {
        if (image == null || image.isBlank()) {
            return null;
        }
        ResourceLocation parsed = ResourceLocation.tryParse(image.trim());
        if (parsed == null) {
            return null;
        }
        return parsed.getPath().endsWith(".png") ? parsed
                : ResourceLocation.fromNamespaceAndPath(parsed.getNamespace(), "textures/" + parsed.getPath() + ".png");
    }

    /**
     * Draws a whole texture into a square, however many pixels the file has (chapter icons are not always 16x16). A
     * missing or unreadable picture draws nothing: the missing texture's magenta says nothing about a chapter's icon.
     */
    static void textureIcon(GuiGraphics g, ResourceLocation id, float x, float y, float size) {
        int[] dims = textureSize(id);
        if (dims[0] <= 0 || dims[1] <= 0) {
            return;
        }
        int w = dims[0];
        int h = dims[1];
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(size / 16f, size / 16f, 1);
        g.blit(id, 0, 0, 16, 16, 0, 0, w, h, w, h);
        g.pose().popPose();
    }

    private static final Map<String, int[]> TEXTURE_SIZES = new HashMap<>();

    static String shapeOf(String shape) {
        return SHAPES.contains(shape) ? shape : "square";
    }

    private static ResourceLocation texture(String path) {
        return ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/" + path + ".png");
    }

    /** Optional screen ground picture; a pack without it keeps the flat palette ground. */
    private static final ResourceLocation GROUND = texture("ground");
    /** Optional picture on a tile's face; a pack without it keeps the flat state colour. */
    private static final ResourceLocation TILE_PATCH = texture("patch/tile");

    /** Sizes of optional pictures, read without the missing-texture warning {@link #textureSize} logs. */
    private static final Map<ResourceLocation, int[]> OPTIONAL_SIZES = new HashMap<>();

    /** A picture's pixel size, or {0, 0} when the pack does not ship it. */
    private static int[] optionalSize(ResourceLocation id) {
        return OPTIONAL_SIZES.computeIfAbsent(id, k -> {
            var res = net.minecraft.client.Minecraft.getInstance().getResourceManager().getResource(k);
            if (res.isEmpty()) {
                return new int[]{0, 0};
            }
            try (var in = res.get().open(); var img = com.mojang.blaze3d.platform.NativeImage.read(in)) {
                return new int[]{img.getWidth(), img.getHeight()};
            } catch (java.io.IOException ex) {
                Constants.LOG.warn("Could not read texture {}: {}", k, ex.toString());
                return new int[]{0, 0};
            }
        });
    }

    /**
     * The screen's map background: the palette's {@code ground} colour, or {@code textures/gui/ground.png} tinted by
     * it and repeated across the screen at one picture pixel per GUI pixel. A pack without the picture gets exactly
     * the flat fill the screen has always drawn.
     */
    static void ground(GuiGraphics g, int w, int h, int argb) {
        int[] size = optionalSize(GROUND);
        if (size[0] <= 0 || size[1] <= 0) {
            g.fill(0, 0, w, h, argb);
            return;
        }
        // the colour goes down first so a pack's transparent pixels show the ground colour, not whatever was
        // drawn before this screen (2026-10-24)
        g.fill(0, 0, w, h, argb);
        prepareTiled(GROUND);
        RenderSystem.enableBlend();
        g.setColor(((argb >> 16) & 255) / 255f, ((argb >> 8) & 255) / 255f, (argb & 255) / 255f, ((argb >>> 24) & 255) / 255f);
        g.blit(GROUND, 0, 0, w, h, 0, 0, w, h, size[0], size[1]);
        g.setColor(1, 1, 1, 1);
        RenderSystem.disableBlend();
    }

    private static final Set<ResourceLocation> PREPARED_TILED = new java.util.HashSet<>();

    /** Smooth, repeating in both directions: the ground is one picture tiled over the whole screen. */
    private static void prepareTiled(ResourceLocation tex) {
        if (!PREPARED_TILED.add(tex)
                || net.minecraft.client.Minecraft.getInstance().getResourceManager().getResource(tex).isEmpty()) {
            return;
        }
        var t = net.minecraft.client.Minecraft.getInstance().getTextureManager().getTexture(tex);
        t.setFilter(false, false);
        t.bind();
        com.mojang.blaze3d.platform.GlStateManager._texParameter(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S,
                org.lwjgl.opengl.GL11.GL_REPEAT);
        com.mojang.blaze3d.platform.GlStateManager._texParameter(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T,
                org.lwjgl.opengl.GL11.GL_REPEAT);
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

    /** A mark (textures/gui/icon): tick, cross, lock, link, plus, minus, center, head, dot, pin. */
    static void icon(GuiGraphics g, String name, float x, float y, float size, int argb) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(size / 16f, size / 16f, 1);
        tinted(g, texture("icon/" + name), 0, 0, 16, 16, 0, 1, argb);
        g.pose().popPose();
    }

    /**
     * A conveyor belt along the x axis of the current pose, from {@code from} to {@code to}, {@code w} wide and centred
     * on the axis. Since 2026-10-20 the picture is 32 texels along the line and 64 across it: one tile covers the belt's
     * full width {@code w} and {@code w / 2} along the line, so the chevron's arms reach both long edges and the period
     * is half the belt width (the player: the square tile looked cheap and its arms stopped short of the edges). The
     * picture is repeated along the line, slid forward by {@code phase} in map units, and tinted by the line's colour.
     * Direction reads anywhere along the line, the way FTB Quests' moving lines do (the player: with one arrowhead at
     * the end you had to find the end to know the direction).
     */
    static void belt(GuiGraphics g, float from, float to, float w, float phase, int argb) {
        if (to - from <= 0.01f || w <= 0) {
            return;
        }
        ResourceLocation tex = texture("line/belt");
        prepareRepeat(tex);
        float len = to - from;
        float period = w / 2f;
        int uw = Math.max(1, Math.round(len / period * 32));
        g.pose().pushPose();
        g.pose().translate(from, -w / 2f, 0);
        g.pose().scale(len / 64f, w / 32f, 1);
        RenderSystem.enableBlend();
        g.setColor(((argb >> 16) & 255) / 255f, ((argb >> 8) & 255) / 255f, (argb & 255) / 255f, ((argb >>> 24) & 255) / 255f);
        g.blit(tex, 0, 0, 64, 32, -phase / period * 32, 0, uw, 64, 32, 64);
        g.setColor(1, 1, 1, 1);
        g.pose().popPose();
    }

    private static final Set<ResourceLocation> PREPARED_REPEAT = new java.util.HashSet<>();

    /** Smooth, repeating along the belt and clamped across it. */
    private static void prepareRepeat(ResourceLocation tex) {
        if (!PREPARED_REPEAT.add(tex)
                || net.minecraft.client.Minecraft.getInstance().getResourceManager().getResource(tex).isEmpty()) {
            return;
        }
        var t = net.minecraft.client.Minecraft.getInstance().getTextureManager().getTexture(tex);
        t.setFilter(true, false);
        t.bind();
        com.mojang.blaze3d.platform.GlStateManager._texParameter(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S,
                org.lwjgl.opengl.GL11.GL_REPEAT);
        com.mojang.blaze3d.platform.GlStateManager._texParameter(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T,
                org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
    }

    private static void tinted(GuiGraphics g, ResourceLocation tex, int x, int y, int w, int h, double from, double to, int argb) {
        int y0 = (int) Math.round(h * Math.max(0, from));
        int y1 = (int) Math.round(h * Math.min(1, to));
        if (y1 <= y0 || w <= 0) {
            return;
        }
        prepare(tex);
        RenderSystem.enableBlend();
        g.setColor(((argb >> 16) & 255) / 255f, ((argb >> 8) & 255) / 255f, (argb & 255) / 255f, ((argb >>> 24) & 255) / 255f);
        // the texture is addressed as if it were w x h, so a band's UVs are exact at any size
        g.blit(tex, x, y + y0, w, y1 - y0, 0, y0, w, y1 - y0, w, h);
        g.setColor(1, 1, 1, 1);
    }

    private static final Set<ResourceLocation> PREPARED = new java.util.HashSet<>();

    /**
     * Masks are drawn smooth and clamped at their edges. Without the clamp a scaled mask samples across its border into
     * the opposite edge: a pentagon's flat, fully opaque bottom rows came back as thin lines along its top whenever the
     * tile grew under the pointer (ATM10's star, 2026-10-20). Done in code once per texture, so a resource pack's mask
     * without our .mcmeta behaves the same.
     */
    private static void prepare(ResourceLocation tex) {
        if (!PREPARED.add(tex)) {
            return;
        }
        // a missing picture draws the shared missing texture: leave that one's settings alone
        if (!tex.getPath().startsWith("derived/")
                && net.minecraft.client.Minecraft.getInstance().getResourceManager().getResource(tex).isEmpty()) {
            return;
        }
        var t = net.minecraft.client.Minecraft.getInstance().getTextureManager().getTexture(tex);
        t.setFilter(true, false);
        t.bind();
        com.mojang.blaze3d.platform.GlStateManager._texParameter(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S,
                org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
        com.mojang.blaze3d.platform.GlStateManager._texParameter(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T,
                org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
    }

    private static String packs;

    /** {@link #reload()} when the list of resource packs differs from the one the shapes were measured with. */
    static void reloadIfPacksChanged() {
        String now = packIds();
        if (!now.equals(packs)) {
            packs = now;
            reload();
        }
    }

    /** The enabled packs' ids, in load order; the same list across F3+T, which is why a reload hook also calls {@link #reload()}. */
    private static String packIds() {
        return net.minecraft.client.Minecraft.getInstance().getResourceManager().listPacks()
                .map(net.minecraft.server.packs.PackResources::packId).collect(java.util.stream.Collectors.joining(","));
    }

    /** Forgets everything worked out from the masks, so the next draw reads a resource pack's shapes again. */
    static void reload() {
        PREPARED.clear();
        PREPARED_REPEAT.clear();
        PREPARED_TILED.clear();
        OPTIONAL_SIZES.clear();
        Metrics.ALL.values().forEach(Metrics::release);
        Metrics.ALL.clear();
        TEXTURE_SIZES.clear();
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
    static int BLUE = 0xFF4F9FDB;
    static int BLUE_DARK = 0xFF2F6E9F;
    /** The waterline: {@link #BLUE} lifted and pulled towards white, the way a surface catches the light. */
    static int SURFACE = surfaceOf(BLUE);
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
     * A solid surface whose edge follows the shape: a darker rim of even width, then the face with a soft light along its
     * upper-left edge and a soft shade along its lower-right one, all from the one state colour (the player, 2026-10-19:
     * the edge colour should come from the main colour). The rim and bevel are masks worked out from the shape's own mask
     * ({@link Metrics}): until 2026-10-20 they were the same mask shrunk four times, and a shrunk gear, pentagon or heart
     * is not an inset one, so the bands ran thick on one side and vanished on another (the player: "the gloss is a mess").
     */
    private static void rim(GuiGraphics g, String shape, int x, int y, int s, int face) {
        Metrics m = Metrics.of(shape);
        shape(g, shape, x, y, s, s, shade(face, 0.62f, 1f));
        tinted(g, m.pattern, x, y, s, s, 0, 1, face);
        tinted(g, m.light, x, y, s, s, 0, 1, withAlpha(shade(face, 1.22f, 0.9f), 0.85f));
        tinted(g, m.shade, x, y, s, s, 0, 1, withAlpha(shade(face, 0.8f, 1f), 0.85f));
    }

    static int surfaceOf(int progress) {
        return shade(progress, 1.22f, 0.90f);
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
     * The blue that fills with the tasks done, inside the rim and behind the icon: the face's own mask cut at the water
     * level, with one lighter line on the waterline so its top reads as a surface. Until 2026-10-20 a dark band sat at
     * the bottom of the water and the same water was drawn over the icon again at low alpha; the band read as a leftover
     * thick bottom edge and the second pass tinted the item blue (the player: put the water behind the item).
     */
    private static void water(GuiGraphics g, String shape, int x, int y, int s, double fraction, String themeKey) {
        double f = Math.max(0, Math.min(1, fraction));
        if (f <= 0) {
            return;
        }
        Metrics m = Metrics.of(shape);
        // the face spans the inset part of the box, so the fill is measured over that span, not the whole tile
        double top = m.faceTop + (m.faceBottom - m.faceTop) * (1 - f);
        int blue = Ui.themed("progress", BLUE, themeKey);
        int surface = Ui.themed("progress_surface", blue == BLUE ? SURFACE : surfaceOf(blue), themeKey);
        tinted(g, m.face, x, y, s, s, top, 1, blue);
        if (f < 1 && f * s * (m.faceBottom - m.faceTop) > 3) {
            tinted(g, m.face, x, y, s, s, top, top + 1.0 / s, surface);
        }
    }

    static void tile(GuiGraphics g, String shape, int x, int y, int s, State state, double fraction, ItemStack icon, boolean far) {
        tile(g, shape, x, y, s, look(state), fraction, icon, far, null);
    }

    static void tile(GuiGraphics g, String shape, int x, int y, int s, State state, double fraction, ItemStack icon, boolean far, String themeKey) {
        tile(g, shape, x, y, s, look(state), fraction, icon, far, themeKey);
    }

    /**
     * A diamond in the same box as a square has half its area and reads smaller (the player, 2026-10-16), so it is drawn
     * 1.25x larger around the same centre. Use this for anything that hugs a tile (selection ring).
     */
    static int drawnSize(String shape, int s) {
        return shapeOf(shape).equals("diamond") ? Math.round(s * 1.25f / 2) * 2 : s;
    }

    /**
     * What a tile shows: an item, a drawn picture, or an entity's face. FTB Quests' custom icon item can carry either
     * of the last two, and the importer keeps that choice instead of dropping the icon (2026-10-22).
     */
    record Glyph(ItemStack stack, ResourceLocation texture, ResourceLocation entity) {
        static final Glyph EMPTY = new Glyph(ItemStack.EMPTY, null, null);

        static Glyph item(ItemStack stack) {
            return stack == null || stack.isEmpty() ? EMPTY : new Glyph(stack, null, null);
        }

        boolean empty() {
            return stack.isEmpty() && texture == null && entity == null;
        }
    }

    /** The face texture FTB Library/FTB Quests use for an entity icon ({@code textures/faces/<ns>/<path>.png}). */
    static ResourceLocation faceTexture(ResourceLocation entity) {
        return entity == null ? null
                : ResourceLocation.fromNamespaceAndPath(entity.getNamespace(),
                "textures/faces/" + entity.getNamespace() + "/" + entity.getPath() + ".png");
    }

    static void tile(GuiGraphics g, String shape, int x, int y, int s, Look look, double fraction, ItemStack icon, boolean far) {
        tile(g, shape, x, y, s, look, fraction, icon, far, null);
    }

    static void tile(GuiGraphics g, String shape, int x, int y, int s, Look look, double fraction, ItemStack icon,
                     boolean far, String themeKey) {
        tile(g, shape, x, y, s, look, fraction, Glyph.item(icon), 1, far, themeKey);
    }

    static void tile(GuiGraphics g, String shape, int x, int y, int s, Look look, double fraction, Glyph glyph,
                     double iconScale, boolean far, String themeKey) {
        tileBody(g, shape, x, y, s, look, fraction, glyph, iconScale, far, themeKey);
    }

    /**
     * A state tile with the node's own theme (2026-10-22, {@code gui/theme.json}): its chapter's and then its own
     * overrides recolour the face and the progress water. Without an override every colour is the palette's.
     */
    private static void tileBody(GuiGraphics g, String shape, int x, int y, int s, Look look, double fraction,
                                 Glyph glyph, double iconScale, boolean far, String themeKey) {
        shape = shapeOf(shape);
        int big = drawnSize(shape, s);
        x -= (big - s) / 2;
        y -= (big - s) / 2;
        s = big;
        switch (look) {
            // done: green. Rewards still waiting = the brighter green and the yellow notification dot; claimed = settled
            case DONE_REWARD -> rim(g, shape, x, y, s, Ui.themed("done_bright", GREEN_BRIGHT, themeKey));
            case DONE -> rim(g, shape, x, y, s, Ui.themed("done_settled", GREEN_SETTLED, themeKey));
            case AFFORDABLE -> rim(g, shape, x, y, s, Ui.themed("tile_white", WHITE, themeKey));
            case OPEN -> rim(g, shape, x, y, s, Ui.themed("tile_open", GREY, themeKey));
            case PROGRESS -> {
                rim(g, shape, x, y, s, Ui.themed("tile_open", GREY, themeKey));
                // the blue rises with the tasks done, inside the rim and behind the icon
                water(g, shape, x, y, s, fraction, themeKey);
            }
            case LOCKED_FAR, LOCKED_NEAR, EXCLUDED -> rim(g, shape, x, y, s, Ui.themed("tile_locked", TILE_LOCKED, themeKey));
        }
        if (far) {
            // zoomed out the surface colour is the information; nothing on top
            return;
        }
        if (glyph != null && !glyph.empty()) {
            // the icon fills the largest square that fits inside the shape's face, wherever that sits: a circle's or a
            // gear's is smaller than a square's, a heart's sits low. Worked out from the mask (Metrics), so every shape
            // and a resource pack's own shapes get an icon that stays inside them. Items leave a margin of their own,
            // so the icon may reach a little past the square. icon_scale (FTB Quests) multiplies that size.
            Metrics m = Metrics.of(shape);
            float size = (float) (Math.min(s * 0.8f, s * m.inner * 1.25f) * Math.max(0.25, Math.min(4, iconScale)));
            float cx = x + s / 2f - size / 2f;
            float cy = y + s * m.innerCentre - size / 2f;
            ResourceLocation tex = glyph.texture();
            if (tex == null && glyph.entity() != null) {
                ResourceLocation face = faceTexture(glyph.entity());
                if (face != null && textureSize(face)[0] > 0) {
                    tex = face;
                }
            }
            if (tex != null) {
                textureIcon(g, tex, cx, cy, size);
            } else if (!glyph.stack().isEmpty()) {
                float k = size / 16f;
                g.pose().pushPose();
                g.pose().translate(cx, cy, 0);
                g.pose().scale(k, k, 1);
                g.renderItem(glyph.stack(), 0, 0);
                g.pose().popPose();
            }
        }
        if (look == Look.DONE_REWARD) {
            // rewards waiting: the same yellow dot the chapter list and the claim badge use, on the tile's corner
            g.pose().pushPose();
            g.pose().translate(0, 0, OVERLAY_Z);
            float d = Math.max(5, s * 0.3f);
            icon(g, "dot", x + s - d * 0.8f, y - d * 0.2f, d, GOLD);
            g.pose().popPose();
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

    /**
     * How far the shape's edge is from its centre in the direction ({@code dx}, {@code dy}), as a share of the tile's
     * size (0.5 = the box's side). Lines end their arrowheads here, so they meet a circle or a diamond where its edge is
     * and not where its bounding box would be.
     */
    static float edge(String shape, double dx, double dy) {
        Metrics m = Metrics.of(shapeOf(shape));
        double a = Math.atan2(dy, dx);
        int i = (int) Math.floorMod(Math.round(a / (2 * Math.PI) * Metrics.RAYS), Metrics.RAYS);
        // a pack's shape whose centre lies outside it has no edge on the ray: fall back to the box
        return m.edge[i] > 0 ? m.edge[i] : 0.5f;
    }

    /**
     * What a mask tells about its shape, worked out once from the picture (so a resource pack's shapes get the same
     * treatment as ours): the face inset by an even rim and its bevel bands as masks of their own, the largest square
     * inside the face for the icon, and the edge's distance from the centre in every direction for the lines.
     */
    static final class Metrics {
        static final Map<String, Metrics> ALL = new HashMap<>();
        static final int RAYS = 72;
        /** Rim width and bevel width, in pixels of the 128px mask (the rim is s/16 at any size). */
        private static final float RIM = 8f;
        private static final float BEVEL = 7f;

        ResourceLocation face;
        /** The face as drawn: {@link #face}, or the pack's {@code patch/tile.png} carried in its alpha. */
        ResourceLocation pattern;
        ResourceLocation light;
        ResourceLocation shade;
        /** Side of the largest square inside the face, and the height of its centre, as shares of the tile. */
        float inner = 0.7f;
        float innerCentre = 0.5f;
        /** First and last rows of the face, as shares of the tile (the water fills between them). */
        double faceTop;
        double faceBottom = 1;
        final float[] edge = new float[RAYS];
        private final List<ResourceLocation> owned = new ArrayList<>();

        static Metrics of(String shape) {
            return ALL.computeIfAbsent(shape, Metrics::new);
        }

        private Metrics(String shape) {
            java.util.Arrays.fill(edge, 0.5f);
            ResourceLocation src = texture("shape/" + shape);
            face = src;
            pattern = src;
            light = src;
            shade = src;
            float[] a = read(src);
            if (a == null) {
                return;
            }
            int n = (int) Math.round(Math.sqrt(a.length));
            boolean[] in = new boolean[a.length];
            for (int i = 0; i < a.length; i++) {
                in[i] = a[i] >= 0.5f;
            }
            float[] d = distance(in, n);
            float[] fa = new float[a.length];
            float[] la = new float[a.length];
            float[] sa = new float[a.length];
            float rim = RIM * n / 128f;
            float bevel = BEVEL * n / 128f;
            int top = n;
            int bottom = -1;
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    int i = y * n + x;
                    // the face: the mask eroded by the rim, edges kept soft
                    float f = Math.min(a[i], clamp01(d[i] - rim + 0.5f));
                    fa[i] = f;
                    if (f > 0.5f) {
                        top = Math.min(top, y);
                        bottom = Math.max(bottom, y);
                    }
                    if (f <= 0) {
                        continue;
                    }
                    // the bevel: near the face's edge, lit where that edge faces up-left and shaded where it faces
                    // down-right. The distance field rises away from the edge, so its slope points inward
                    float gx = d[Math.min(n - 1, x + 1) + y * n] - d[Math.max(0, x - 1) + y * n];
                    float gy = d[x + Math.min(n - 1, y + 1) * n] - d[x + Math.max(0, y - 1) * n];
                    float len = (float) Math.hypot(gx, gy);
                    if (len < 1e-4f) {
                        continue;
                    }
                    float facing = (gx + gy) / len * 0.7071f;
                    float band = f * clamp01(1 - (d[i] - rim) / bevel);
                    la[i] = band * Math.max(0, facing);
                    sa[i] = band * Math.max(0, -facing);
                }
            }
            face = register(shape + "_face", fa, n);
            pattern = patchFace(shape + "_pattern", fa, n, face);
            light = register(shape + "_light", la, n);
            shade = register(shape + "_shade", sa, n);
            if (bottom >= top) {
                faceTop = top / (double) n;
                faceBottom = (bottom + 1) / (double) n;
            }
            innerSquare(d, n, rim);
            for (int r = 0; r < RAYS; r++) {
                double ang = r * 2 * Math.PI / RAYS;
                double cx = n / 2.0;
                double cy = n / 2.0;
                float hit = 0.5f;
                for (double t = 0; t < n; t += 0.5) {
                    int px = (int) Math.floor(cx + Math.cos(ang) * t);
                    int py = (int) Math.floor(cy + Math.sin(ang) * t);
                    if (px < 0 || py < 0 || px >= n || py >= n || !in[py * n + px]) {
                        hit = (float) (t / n);
                        break;
                    }
                }
                edge[r] = hit;
            }
        }

        /** The largest square inside the face (all of it at least a rim away from the edge), centred across the tile. */
        private void innerSquare(float[] d, int n, float rim) {
            int[] sum = new int[(n + 1) * (n + 1)];
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    int ok = d[y * n + x] >= rim + 1 ? 1 : 0;
                    sum[(y + 1) * (n + 1) + x + 1] = ok + sum[y * (n + 1) + x + 1] + sum[(y + 1) * (n + 1) + x] - sum[y * (n + 1) + x];
                }
            }
            for (int side = n; side > 4; side--) {
                int x0 = (n - side) / 2;
                int best = -1;
                for (int y0 = 0; y0 + side <= n; y0++) {
                    int c = sum[(y0 + side) * (n + 1) + x0 + side] - sum[y0 * (n + 1) + x0 + side] - sum[(y0 + side) * (n + 1) + x0] + sum[y0 * (n + 1) + x0];
                    if (c == side * side && (best < 0 || Math.abs(y0 + side / 2.0 - n / 2.0) < Math.abs(best + side / 2.0 - n / 2.0))) {
                        best = y0;
                    }
                }
                if (best >= 0) {
                    inner = side / (float) n;
                    innerCentre = (float) ((best + side / 2.0) / n);
                    return;
                }
            }
        }

        /**
         * The face with the pack's optional {@code patch/tile.png} carried inside the shape's own alpha: the picture is
         * scaled once over the tile, its colours are multiplied by the state colour at draw time, and its transparency
         * lets the rim through. No picture returns {@code flat}, so the face is exactly what it was before.
         */
        private ResourceLocation patchFace(String name, float[] fa, int n, ResourceLocation flat) {
            var res = net.minecraft.client.Minecraft.getInstance().getResourceManager().getResource(TILE_PATCH);
            if (res.isEmpty()) {
                return flat;
            }
            try (var is = res.get().open(); var img = com.mojang.blaze3d.platform.NativeImage.read(is)) {
                int pw = img.getWidth();
                int ph = img.getHeight();
                if (pw <= 0 || ph <= 0) {
                    return flat;
                }
                float[] alpha = fa.clone();
                int[] rgb = new int[n * n];
                for (int y = 0; y < n; y++) {
                    int py = Math.min(ph - 1, y * ph / n);
                    for (int x = 0; x < n; x++) {
                        int px = Math.min(pw - 1, x * pw / n);
                        int p = img.getPixelRGBA(px, py);
                        rgb[y * n + x] = ((p & 255) << 16) | (((p >> 8) & 255) << 8) | ((p >> 16) & 255);
                        alpha[y * n + x] *= ((p >>> 24) & 255) / 255f;
                    }
                }
                return register(name, alpha, n, rgb);
            } catch (java.io.IOException ex) {
                Constants.LOG.warn("Could not read tile face {}: {}", TILE_PATCH, ex.toString());
                return flat;
            }
        }

        private ResourceLocation register(String name, float[] alpha, int n) {
            return register(name, alpha, n, null);
        }

        private ResourceLocation register(String name, float[] alpha, int n, int[] rgb) {
            com.mojang.blaze3d.platform.NativeImage img = new com.mojang.blaze3d.platform.NativeImage(n, n, true);
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    int al = Math.round(clamp01(alpha[y * n + x]) * 255);
                    int color = 0xFFFFFF;
                    if (rgb != null) {
                        int c = rgb[y * n + x];
                        // NativeImage pixels are ABGR, the palette's are RGB
                        color = ((c & 255) << 16) | (((c >> 8) & 255) << 8) | ((c >> 16) & 255);
                    }
                    img.setPixelRGBA(x, y, (al << 24) | color);
                }
            }
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "derived/" + name);
            net.minecraft.client.Minecraft.getInstance().getTextureManager().register(id, new net.minecraft.client.renderer.texture.DynamicTexture(img));
            owned.add(id);
            return id;
        }

        void release() {
            for (ResourceLocation id : owned) {
                net.minecraft.client.Minecraft.getInstance().getTextureManager().release(id);
            }
            owned.clear();
        }

        /** The mask's alpha, 0..1, row by row; null when it is missing or not square. */
        private static float[] read(ResourceLocation id) {
            var res = net.minecraft.client.Minecraft.getInstance().getResourceManager().getResource(id);
            if (res.isEmpty()) {
                return null;
            }
            try (var is = res.get().open(); var img = com.mojang.blaze3d.platform.NativeImage.read(is)) {
                int n = img.getWidth();
                if (img.getHeight() != n || n < 16) {
                    return null;
                }
                float[] a = new float[n * n];
                for (int y = 0; y < n; y++) {
                    for (int x = 0; x < n; x++) {
                        a[y * n + x] = ((img.getPixelRGBA(x, y) >>> 24) & 255) / 255f;
                    }
                }
                return a;
            } catch (java.io.IOException ex) {
                Constants.LOG.warn("Could not read shape {}: {}", id, ex.toString());
                return null;
            }
        }

        /**
         * Euclidean distance from every inside pixel to the nearest outside one (0 outside), Felzenszwalb's two passes.
         * The picture's border counts as outside, so a square that fills its picture still gets a rim.
         */
        private static float[] distance(boolean[] in, int n) {
            int m = n + 2;
            float[] f = new float[m * m];
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    f[(y + 1) * m + x + 1] = in[y * n + x] ? 1e9f : 0;
                }
            }
            float[] col = new float[m];
            float[] out = new float[m];
            for (int x = 0; x < m; x++) {
                for (int y = 0; y < m; y++) {
                    col[y] = f[y * m + x];
                }
                edt1(col, out, m);
                for (int y = 0; y < m; y++) {
                    f[y * m + x] = out[y];
                }
            }
            float[] d = new float[n * n];
            for (int y = 0; y < m; y++) {
                System.arraycopy(f, y * m, col, 0, m);
                edt1(col, out, m);
                if (y >= 1 && y <= n) {
                    for (int x = 1; x <= n; x++) {
                        d[(y - 1) * n + x - 1] = (float) Math.sqrt(out[x]);
                    }
                }
            }
            return d;
        }

        private static void edt1(float[] f, float[] d, int n) {
            int[] v = new int[n];
            float[] z = new float[n + 1];
            int k = 0;
            z[0] = -Float.MAX_VALUE;
            z[1] = Float.MAX_VALUE;
            for (int q = 1; q < n; q++) {
                float s = ((f[q] + q * q) - (f[v[k]] + v[k] * v[k])) / (2f * q - 2f * v[k]);
                while (s <= z[k]) {
                    k--;
                    s = ((f[q] + q * q) - (f[v[k]] + v[k] * v[k])) / (2f * q - 2f * v[k]);
                }
                k++;
                v[k] = q;
                z[k] = s;
                z[k + 1] = Float.MAX_VALUE;
            }
            k = 0;
            for (int q = 0; q < n; q++) {
                while (z[k + 1] < q) {
                    k++;
                }
                d[q] = (q - v[k]) * (q - v[k]) + f[v[k]];
            }
        }

        private static float clamp01(float v) {
            return Math.max(0, Math.min(1, v));
        }
    }

    static int withAlpha(int rgb, float alpha) {
        return ((int) (Math.max(0, Math.min(1, alpha)) * ((rgb >>> 24) == 0 ? 255 : (rgb >>> 24))) << 24) | (rgb & 0xFFFFFF);
    }
}
