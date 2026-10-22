package com.kuronami.questtree.client;

import com.kuronami.questtree.Constants;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

/**
 * Liquid Glass (Apple's material, 2025) for the screen's controls and navigation: the chapter list, the details
 * sheet, the search field, the zoom buttons and the minimap. Tooltips stay flat (mostly text, and often over glass).
 * Never on the map's tiles, which are the content the glass floats over (Apple HIG, Materials: "Don't use Liquid Glass in the content layer").
 * <p>
 * The screen behind the panel is copied off-screen and drawn back through a small grid whose UVs are pushed outward
 * at the rounded edge, so the picture behind the rim bends like it is seen through a thick slab (lensing, not only a
 * blur). Two variants, as Apple's:
 * <ul>
 *     <li>{@link Kind#REGULAR}: blurs what is behind and pulls it towards a dark tone, so text on it stays legible.
 *     For the panels with text.</li>
 *     <li>{@link Kind#CLEAR}: sharp and see-through with a strong lens and a light dimming layer, for a small control
 *     over a busy picture (the minimap over the map).</li>
 * </ul>
 * The rim catches a light from the top left; it leans towards the pointer while the pointer is near, and the panel
 * minimap glows softly under the pointer, the way Apple's interactive glass lights up under a finger. Only the
 * game's own {@code position_tex}, {@code position_tex_color} and {@code position_color} shaders are used: a shader
 * pack replaces the world shaders, and a core shader of our own is the first thing it takes with it (Iris docs).
 * All colours come from the palette.
 */
public final class GlassPanel {

    enum Kind { REGULAR, CLEAR }

    /** Off-screen copies for one place on the screen, kept so their sizes do not change from one panel to the next. */
    static final class Slot {
        private TextureTarget copy;
        private TextureTarget half;
        private TextureTarget quarter;
        private TextureTarget eighth;
        private TextureTarget panel;

        private void release() {
            for (TextureTarget t : new TextureTarget[]{copy, half, quarter, eighth, panel}) {
                if (t != null) {
                    t.destroyBuffers();
                }
            }
            copy = half = quarter = eighth = panel = null;
        }
    }

    static final Slot RAIL = new Slot();
    static final Slot SHEET = new Slot();
    static final Slot SEARCH = new Slot();
    static final Slot ZOOM = new Slot();
    static final Slot MINIMAP = new Slot();
    static final Slot HEADER = new Slot();
    private static final Slot[] SLOTS = {RAIL, SHEET, SEARCH, ZOOM, MINIMAP, HEADER};

    /** GUI pixels of the screen copied around the panel, so the lens can sample outside it. */
    private static final int PAD = 14;
    /** Size of one bending cell, in GUI pixels. The rim is a curve, so the cells have to be smaller than it. */
    private static final double CELL = 3.0;
    /**
     * What lies outside the rounded panel: opaque in the corners, clear inside, nine-sliced like {@code shape/rsquare}.
     * Stored inverted because the game's textured shader drops fully clear texels, which then multiply nothing.
     */
    private static final ResourceLocation MASK =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/glass_mask.png");
    private static final int MASK_TEX = 128;
    private static final int MASK_CORNER = 23;
    /** How far from the panel the pointer still pulls the light, in GUI pixels. */
    private static final double REACH = 48.0;

    private GlassPanel() {
    }

    /** The glass is on unless the player turned it off; off draws the flat panels instead. */
    static boolean on() {
        return ClientConfig.glass();
    }

    /**
     * Draws the glass at {@code x, y, w, h} (GUI coordinates under the current pose, which may only translate) with
     * corner radius {@code radius}. Draw it after everything it floats over and before what sits on it. A scissor set by
     * the caller clips the panel itself, not the passes that make it.
     */
    static void render(GuiGraphics g, Slot slot, Kind kind, int x, int y, int w, int h, int radius, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        double scale = mc.getWindow().getGuiScale();
        if (scale <= 0 || w <= 2 || h <= 2) {
            return;
        }
        // where the panel is on the screen: the caller may have moved the pose (tooltips are lifted, the map is not)
        Vector3f at = g.pose().last().pose().transformPosition(new Vector3f(x, y, 0));
        Vector3f end = g.pose().last().pose().transformPosition(new Vector3f(x + w, y + h, 0));
        double sx = at.x;
        double sy = at.y;
        double sw = end.x - at.x;
        double sh = end.y - at.y;

        var main = mc.getMainRenderTarget();
        int fbw = main.width;
        int fbh = main.height;
        int pad = Math.max(1, (int) Math.round(PAD * scale));
        int px0 = (int) Math.round(sx * scale);
        int py0 = (int) Math.round(sy * scale);
        int px1 = (int) Math.round((sx + sw) * scale);
        int py1 = (int) Math.round((sy + sh) * scale);
        int pw = px1 - px0;
        int ph = py1 - py0;
        int x0 = Math.max(0, px0 - pad);
        int x1 = Math.min(fbw, px1 + pad);
        int top = Math.max(0, py0 - pad);
        int bottom = Math.min(fbh, py1 + pad);
        int y0 = fbh - bottom;
        int y1 = fbh - top;
        int tw = x1 - x0;
        int th = y1 - y0;
        if (pw <= 2 || ph <= 2 || tw <= 2 || th <= 2) {
            return;
        }

        // everything drawn behind the panel has to be on the screen before it can be copied, and before a target is
        // made or resized: both rebind the framebuffer and the viewport
        g.flush();
        int hw = Math.max(1, tw / 2);
        int hh = Math.max(1, th / 2);
        int qw = Math.max(1, tw / 4);
        int qh = Math.max(1, th / 4);
        int ew = Math.max(1, tw / 8);
        int eh = Math.max(1, th / 8);
        slot.copy = target(slot.copy, tw, th);
        if (kind == Kind.REGULAR) {
            slot.half = target(slot.half, hw, hh);
            slot.quarter = target(slot.quarter, qw, qh);
            slot.eighth = target(slot.eighth, ew, eh);
        }
        slot.panel = target(slot.panel, pw, ph);

        GlStateManager._glBindFramebuffer(36008, main.frameBufferId);
        GlStateManager._glBindFramebuffer(36009, slot.copy.frameBufferId);
        GlStateManager._glBlitFrameBuffer(x0, y0, x1, y1, 0, 0, tw, th, 0x4000, 0x2600);
        GlStateManager._glBindFramebuffer(36160, main.frameBufferId);

        // a caller's scissor is in window pixels and would cut the off-screen passes; it applies again to the composite
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        if (scissor) {
            GlStateManager._disableScissorTest();
        }
        RenderSystem.disableDepthTest();
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0f, 1f, 1f, 0f, -1f, 1f), VertexSorting.ORTHOGRAPHIC_Z);
        // the passes draw in 0..1 with nothing else applied; the GUI's model-view pushes z back by a loader-dependent
        // amount, which a fixed far plane would not always cover
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.identity();
        RenderSystem.applyModelViewMatrix();
        try {
            // the copied frame may have any alpha in it; the passes below want the picture's alpha out of the way
            opaque(slot.copy);
            TextureTarget source = slot.copy;
            int usedW = tw;
            int usedH = th;
            if (kind == Kind.REGULAR) {
                blur(slot.copy, tw, th, slot.half, hw, hh);
                blur(slot.half, hw, hh, slot.quarter, qw, qh);
                blur(slot.quarter, qw, qh, slot.eighth, ew, eh);
                source = slot.eighth;
                usedW = ew;
                usedH = eh;
            }

            // the panel starts fully covered; the corner mask takes the coverage away outside the rounded shape
            slot.panel.setClearColor(0f, 0f, 0f, 1f);
            GlStateManager._colorMask(true, true, true, true);
            slot.panel.clear(false);
            slot.panel.bindWrite(true);
            RenderSystem.viewport(0, 0, pw, ph);
            double lx = (mouseX - sx) * scale;
            double ly = (mouseY - sy) * scale;
            lens(kind, px0 - x0, py0 - top, pw, ph, tw, th, scale, radius, lx, ly, source.getColorTextureId(),
                    usedW / (double) source.width, usedH / (double) source.height, source.width, source.height);

            // only the coverage is cut: the colour is multiplied by it once, when the panel is drawn onto the screen
            RenderSystem.enableBlend();
            GlStateManager._colorMask(false, false, false, true);
            RenderSystem.blendFunc(GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            mask(pw, ph, scale, radius);
        } finally {
            GlStateManager._colorMask(true, true, true, true);
            RenderSystem.defaultBlendFunc();
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
            main.bindWrite(true);
            if (scissor) {
                GlStateManager._enableScissorTest();
            }
        }

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, slot.panel.getColorTextureId());
        Matrix4f pose = g.pose().last().pose();
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        // the panel fills the bottom-left of its (possibly larger) target in GL order, so its top is at v = ph / height
        float pu = pw / (float) slot.panel.width;
        float pv = ph / (float) slot.panel.height;
        bb.addVertex(pose, x, y, 0f).setUv(0f, pv);
        bb.addVertex(pose, x, y + h, 0f).setUv(0f, 0f);
        bb.addVertex(pose, x + w, y + h, 0f).setUv(pu, 0f);
        bb.addVertex(pose, x + w, y, 0f).setUv(pu, pv);
        // written to the depth buffer at its z like the flat panels it replaces, so things drawn on it later at the
        // same z are not dropped behind marks that were under it
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_ALWAYS);
        RenderSystem.depthMask(true);
        BufferUploader.drawWithShader(bb.buildOrThrow());
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.disableBlend();
    }

    /** Frees every off-screen copy; the next panel makes them again. */
    public static void release() {
        for (Slot s : SLOTS) {
            s.release();
        }
    }

    /**
     * A target at least {@code w x h}, grown in steps and never shrunk: the chapter list and the sheet change size on
     * every frame while they slide, and making the buffers again each frame is a driver allocation each time. The
     * passes draw into the bottom-left {@code w x h} of it.
     */
    private static TextureTarget target(TextureTarget target, int w, int h) {
        int gw = (w + 63) / 64 * 64;
        int gh = (h + 63) / 64 * 64;
        if (target == null) {
            target = new TextureTarget(gw, gh, false, true);
        } else if (target.width < w || target.height < h) {
            target.resize(Math.max(gw, target.width), Math.max(gh, target.height), true);
        }
        // shrinking and growing has to blend, or the frost keeps the texels of the frame before
        target.setFilterMode(9729);
        return target;
    }

    /** Replaces the copied frame's alpha with 1: the game's textured shaders drop fragments whose alpha is low. */
    private static void opaque(TextureTarget target) {
        target.bindWrite(true);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        GlStateManager._colorMask(false, false, false, true);
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        bb.addVertex(0f, 0f, 0f).setColor(255, 255, 255, 255);
        bb.addVertex(0f, 1f, 0f).setColor(255, 255, 255, 255);
        bb.addVertex(1f, 1f, 0f).setColor(255, 255, 255, 255);
        bb.addVertex(1f, 0f, 0f).setColor(255, 255, 255, 255);
        BufferUploader.drawWithShader(bb.buildOrThrow());
        GlStateManager._colorMask(true, true, true, true);
    }

    /**
     * Drops the used {@code sw x sh} of the source into {@code dw x dh} of a smaller target, which is the whole blur:
     * each level averages four texels.
     */
    private static void blur(TextureTarget from, int sw, int sh, TextureTarget to, int dw, int dh) {
        to.bindWrite(true);
        RenderSystem.viewport(0, 0, dw, dh);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, from.getColorTextureId());
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        quad(bb, 0f, 0f, 1f, 1f, 0f, sw / (float) from.width, sh / (float) from.height, 0f);
        BufferUploader.drawWithShader(bb.buildOrThrow());
    }

    /**
     * Draws the picture behind through the bent grid, then adds the glass's own light on top. Both passes work per
     * vertex, so the quads share their corners and no seams show.
     * <ul>
     *     <li>Lens: inside the rim the picture is pushed outward, most at the outer edge (a ray through a rounded
     *     slab, refractive index 1.5). The clear variant has a deeper rim and bends further.</li>
     *     <li>Tone: the regular variant mixes the blurred picture with {@code glass_regular} (its alpha is how much);
     *     the clear one keeps the picture sharp, dims it by {@code glass_clear} and tints it with {@code glass_tint}.</li>
     *     <li>Light: a thin rim highlight from the top left, leaning towards the pointer when it is near, a fainter one
     *     on the opposite rim, and on the clear kind a soft glow under the pointer while it is over the panel.</li>
     * </ul>
     */
    private static void lens(Kind kind, int ox, int oy, int pw, int ph, int tw, int th, double scale, int radiusGui,
                             double lx, double ly, int texture, double su, double sv, int texW, int texH) {
        boolean clear = kind == Kind.CLEAR;
        double radius = Math.min(radiusGui * scale, Math.min(pw, ph) * 0.5);
        double rimDepth = Math.min((clear ? 12.0 : 7.0) * scale, Math.min(pw, ph) * 0.45);
        // fine cells where the rim bends and lights; the regular kind has nothing else going on inside, so its middle
        // is one cell (the clear one keeps the fine grid for the glow under the pointer)
        double band = clear ? -1 : Math.max(radius, rimDepth) + CELL * scale;
        double[] xs = axis(pw, band, CELL * scale);
        double[] ys = axis(ph, band, CELL * scale);
        int vw = xs.length;
        int vh = ys.length;
        int n = vw * vh;
        float[] gx = new float[n];
        float[] gy = new float[n];
        float[] uu = new float[n];
        float[] vv = new float[n];
        float[] mul = new float[n * 3];
        float[] add = new float[n * 3];

        double refract = (clear ? 11.0 : 5.0) * scale;
        double halfW = pw * 0.5;
        double halfH = ph * 0.5;
        double boxW = Math.max(halfW - radius, 0.0);
        double boxH = Math.max(halfH - radius, 0.0);
        double cap = rimDepth * 1.2;
        double rimWidth = Math.max(1.0, 1.2 * scale);

        // the light: from the top left, pulled towards the pointer as it comes near
        double lightX = -0.6;
        double lightY = -0.8;
        double cx = lx - halfW;
        double cy = ly - halfH;
        double outside = Math.max(Math.abs(cx) - halfW, 0) + Math.max(Math.abs(cy) - halfH, 0);
        double pull = clamp(1.0 - outside / (REACH * scale), 0.0, 1.0);
        double cl = Math.sqrt(cx * cx + cy * cy);
        if (pull > 0 && cl > 1e-3) {
            lightX = lightX * (1 - pull) + cx / cl * pull;
            lightY = lightY * (1 - pull) + cy / cl * pull;
            double ll = Math.sqrt(lightX * lightX + lightY * lightY);
            if (ll > 1e-3) {
                lightX /= ll;
                lightY /= ll;
            }
        }
        // the glow under the pointer only on the clear kind: on the panels with text it sits behind the row being read
        boolean hover = clear && lx >= 0 && ly >= 0 && lx < pw && ly < ph;
        double glowR = 28.0 * scale;

        int toneArgb = clear ? Ui.GLASS_CLEAR : Ui.GLASS_REGULAR;
        double toneA = ((toneArgb >>> 24) & 255) / 255.0;
        double[] tone = {((toneArgb >> 16) & 255) / 255.0, ((toneArgb >> 8) & 255) / 255.0, (toneArgb & 255) / 255.0};
        int tintArgb = clear ? Ui.GLASS_TINT : 0xFFFFFFFF;
        double[] tint = {((tintArgb >> 16) & 255) / 255.0, ((tintArgb >> 8) & 255) / 255.0, (tintArgb & 255) / 255.0};

        for (int j = 0; j < vh; j++) {
            double vy = ys[j];
            for (int i = 0; i < vw; i++) {
                double vx = xs[i];
                int k = j * vw + i;
                gx[k] = (float) (vx / pw);
                gy[k] = (float) (vy / ph);

                double qx = vx - halfW;
                double qy = vy - halfH;
                double dx = Math.abs(qx) - boxW;
                double dy = Math.abs(qy) - boxH;
                double ex = Math.max(dx, 0.0);
                double ey = Math.max(dy, 0.0);
                double sd = Math.sqrt(ex * ex + ey * ey) + Math.min(Math.max(dx, dy), 0.0) - radius;
                double depth = Math.max(-sd, 0.0);
                double t = Math.min(depth / rimDepth, 1.0);

                // the outward normal of the rounded box, in the panel's top-down pixels
                double nxv;
                double nyv;
                double len = Math.sqrt(ex * ex + ey * ey);
                if (len > 1.0e-5) {
                    nxv = ex / len;
                    nyv = ey / len;
                } else if (dx > dy) {
                    nxv = 1.0;
                    nyv = 0.0;
                } else {
                    nxv = 0.0;
                    nyv = 1.0;
                }
                nxv *= qx < 0.0 ? -1.0 : 1.0;
                nyv *= qy < 0.0 ? -1.0 : 1.0;

                // rays through the rounded rim come out bent, most at the outer edge
                double xr = 1.0 - t;
                double thetaI = Math.asin(clear ? xr : xr * xr);
                double thetaT = Math.asin(Math.min(Math.sin(thetaI) / 1.5, 1.0));
                double bend = depth >= rimDepth ? 0.0 : -Math.tan(thetaT - thetaI);
                double offX = nxv * bend * refract;
                double offY = nyv * bend * refract;
                double offLen = Math.sqrt(offX * offX + offY * offY);
                if (offLen > cap) {
                    offX *= cap / offLen;
                    offY *= cap / offLen;
                }
                // the source holds the copy in its bottom-left su x sv, in GL order
                uu[k] = (float) clamp((ox + vx + offX) / tw * su, 0.5 / texW, su - 0.5 / texW);
                vv[k] = (float) clamp((1.0 - (oy + vy + offY) / th) * sv, 0.5 / texH, sv - 0.5 / texH);

                // light on the rim: strongest where the rim faces the light, a weaker echo on the far side. Kept low: at
                // +0.55 white (until 2026-10-20) the rim read as a white outline, not as a glint (the player: too white)
                double rim = 1.0 - smooth(0.0, rimWidth, depth);
                double facing = nxv * lightX + nyv * lightY;
                double spec = rim * (0.03 + 0.20 * Math.max(facing, 0.0) + 0.07 * Math.max(-facing, 0.0));
                // a soft sheen across the rim band, so the lens reads as a curved edge and not a cut
                spec += (1.0 - t) * (clear ? 0.03 : 0.015) * (0.5 + 0.5 * Math.max(facing, 0.0));
                if (hover) {
                    double gd = Math.sqrt((vx - lx) * (vx - lx) + (vy - ly) * (vy - ly));
                    spec += 0.07 * (1.0 - smooth(0.0, glowR, gd));
                }
                for (int c = 0; c < 3; c++) {
                    mul[k * 3 + c] = (float) ((1.0 - toneA) * tint[c]);
                    add[k * 3 + c] = (float) (toneA * tone[c] + spec);
                }
            }
        }

        // the picture, scaled by the tone; written straight into the cleared panel
        RenderSystem.disableBlend();
        GlStateManager._colorMask(true, true, true, false);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texture);
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        grid(bb, vw, vh, k -> {
            bb.addVertex(gx[k], gy[k], 0f).setUv(uu[k], vv[k]);
            bb.setColor(unit(mul[k * 3]), unit(mul[k * 3 + 1]), unit(mul[k * 3 + 2]), 255);
        });
        BufferUploader.drawWithShader(bb.buildOrThrow());

        // the glass's own tone and light, added on top
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder light = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        grid(light, vw, vh, k -> light.addVertex(gx[k], gy[k], 0f)
                .setColor(unit(add[k * 3]), unit(add[k * 3 + 1]), unit(add[k * 3 + 2]), 255));
        BufferUploader.drawWithShader(light.buildOrThrow());
        GlStateManager._colorMask(true, true, true, true);
    }

    /** Vertex positions along one side: every {@code cell} within {@code band} of either end, nothing in between. */
    private static double[] axis(int len, double band, double cell) {
        java.util.List<Double> at = new java.util.ArrayList<>();
        if (band < 0 || 2 * band >= len) {
            int n = Math.max(2, (int) Math.ceil(len / cell));
            for (int i = 0; i <= n; i++) {
                at.add(len * (double) i / n);
            }
        } else {
            int n = Math.max(1, (int) Math.ceil(band / cell));
            for (int i = 0; i <= n; i++) {
                at.add(band * i / n);
            }
            for (int i = 0; i <= n; i++) {
                at.add(len - band + band * i / n);
            }
        }
        double[] out = new double[at.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = at.get(i);
        }
        return out;
    }

    private interface VertexWriter {
        void write(int k);
    }

    private static void grid(BufferBuilder bb, int vw, int vh, VertexWriter v) {
        for (int j = 0; j < vh - 1; j++) {
            for (int i = 0; i < vw - 1; i++) {
                int a = j * vw + i;
                v.write(a);
                v.write(a + vw);
                v.write(a + vw + 1);
                v.write(a + 1);
            }
        }
    }

    private static int unit(float v) {
        return Math.round(Math.max(0f, Math.min(1f, v)) * 255f);
    }

    /** Nine-slices the corner mask, which cuts the slab's coverage outside the rounded corners. */
    private static void mask(int pw, int ph, double scale, int radiusGui) {
        double r = Math.min(radiusGui * scale, Math.min(pw, ph) * 0.5);
        if (r < 0.5) {
            return; // square: the slab already covers the whole rectangle
        }
        double sx0 = r / pw;
        double sy0 = r / ph;
        double[] dx = {0.0, sx0, 1.0 - sx0, 1.0};
        double[] dy = {0.0, sy0, 1.0 - sy0, 1.0};
        double[] su = {0.0, MASK_CORNER / (double) MASK_TEX, 1.0 - MASK_CORNER / (double) MASK_TEX, 1.0};
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, MASK);
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                quad(bb, (float) dx[col], (float) dy[row], (float) dx[col + 1], (float) dy[row + 1],
                        (float) su[col], (float) su[col + 1], (float) su[row], (float) su[row + 1]);
            }
        }
        BufferUploader.drawWithShader(bb.buildOrThrow());
    }

    /** A quad with image UVs: {@code v0} is the top row, and the corners are wound like the game's GUI quads. */
    private static void quad(BufferBuilder bb, float x0, float y0, float x1, float y1,
                             float u0, float u1, float v0, float v1) {
        bb.addVertex(x0, y0, 0f).setUv(u0, v0);
        bb.addVertex(x0, y1, 0f).setUv(u0, v1);
        bb.addVertex(x1, y1, 0f).setUv(u1, v1);
        bb.addVertex(x1, y0, 0f).setUv(u1, v0);
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : Math.min(v, max);
    }

    private static double smooth(double edge0, double edge1, double x) {
        if (edge1 <= edge0) {
            return x < edge0 ? 0.0 : 1.0;
        }
        double t = clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }
}
