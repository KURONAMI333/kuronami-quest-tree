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

/**
 * The minimap drawn as a glass slab over whatever is behind it.
 * <p>
 * The screen is copied off-screen, dropped into smaller copies for the frost, and drawn back through a small grid
 * whose UVs are pushed around at the bezel, so the picture behind the edge bends like it is seen through a thick
 * rounded slab. Only the game's own {@code position_tex} and {@code position_tex_color} shaders are used: a shader
 * pack replaces the world shaders, and a core shader of our own is the first thing it takes with it.
 */
public final class GlassPanel {

    /** GUI pixels of the screen copied around the panel, so the bezel can sample outside it. */
    private static final int PAD = 14;
    /** Size of one bending cell, in GUI pixels. The bezel is a curve, so the cells have to be smaller than it. */
    private static final double CELL = 3.0;
    /** Depth of the rounded edge, in GUI pixels. */
    private static final double BEZEL = 9.0;
    /** How far the bezel pushes the picture at the outer edge, in GUI pixels. */
    private static final double REFRACT = 8.1;
    /**
     * What lies outside the rounded panel: opaque in the corners, clear inside, nine-sliced like {@code shape/rsquare}.
     * Stored inverted because the game's textured shader drops fully clear texels, which then multiply nothing.
     */
    private static final ResourceLocation MASK =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/glass_mask.png");
    private static final int MASK_TEX = 128;
    private static final int MASK_CORNER = 23;

    private static TextureTarget copy;
    private static TextureTarget half;
    private static TextureTarget quarter;
    private static TextureTarget eighth;
    private static TextureTarget panel;

    private GlassPanel() {
    }

    public static void render(GuiGraphics g, int x, int y, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        double scale = mc.getWindow().getGuiScale();
        if (scale <= 0 || w <= 2 || h <= 2) {
            return;
        }
        var main = mc.getMainRenderTarget();
        int fbw = main.width;
        int fbh = main.height;
        int pad = Math.max(1, (int) Math.round(PAD * scale));

        int px0 = (int) Math.round(x * scale);
        int py0 = (int) Math.round(y * scale);
        int px1 = (int) Math.round((x + w) * scale);
        int py1 = (int) Math.round((y + h) * scale);
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
        copy = target(copy, tw, th);
        half = target(half, Math.max(1, tw / 2), Math.max(1, th / 2));
        quarter = target(quarter, Math.max(1, tw / 4), Math.max(1, th / 4));
        eighth = target(eighth, Math.max(1, tw / 8), Math.max(1, th / 8));
        panel = target(panel, pw, ph);

        GlStateManager._glBindFramebuffer(36008, main.frameBufferId);
        GlStateManager._glBindFramebuffer(36009, copy.frameBufferId);
        GlStateManager._glBlitFrameBuffer(x0, y0, x1, y1, 0, 0, tw, th, 0x4000, 0x2600);
        GlStateManager._glBindFramebuffer(36160, main.frameBufferId);

        RenderSystem.disableDepthTest();
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.backupProjectionMatrix();
        Matrix4f projection = new Matrix4f().setOrtho(0f, 1f, 1f, 0f, -1f, 1f);
        RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);
        // the passes draw in 0..1 with nothing else applied; the GUI's model-view pushes z back by a loader-dependent
        // amount, which a fixed far plane would not always cover
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.identity();
        RenderSystem.applyModelViewMatrix();
        try {
            // the copied frame may have any alpha in it; the passes below want the picture's alpha out of the way
            opaque(copy);
            blur(copy.getColorTextureId(), half);
            blur(half.getColorTextureId(), quarter);
            blur(quarter.getColorTextureId(), eighth);

            panel.setClearColor(0f, 0f, 0f, 0f);
            GlStateManager._colorMask(true, true, true, true);
            panel.clear(false);
            panel.bindWrite(true);
            RenderSystem.enableBlend();

            bend(px0 - x0, py0 - top, pw, ph, tw, th, scale, quarter.getColorTextureId(), panel);

            // only the coverage is cut: the colour is multiplied by it once, when the panel is drawn onto the screen
            GlStateManager._colorMask(false, false, false, true);
            RenderSystem.blendFunc(GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            mask(pw, ph, scale);
        } finally {
            GlStateManager._colorMask(true, true, true, true);
            RenderSystem.defaultBlendFunc();
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
            main.bindWrite(true);
        }

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, panel.getColorTextureId());
        Matrix4f pose = g.pose().last().pose();
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        // the target holds the copy in GL order (v=0 is the bottom), so the top of the panel samples v=1
        bb.addVertex(pose, x, y, 0f).setUv(0f, 1f);
        bb.addVertex(pose, x, y + h, 0f).setUv(0f, 0f);
        bb.addVertex(pose, x + w, y + h, 0f).setUv(1f, 0f);
        bb.addVertex(pose, x + w, y, 0f).setUv(1f, 1f);
        BufferUploader.drawWithShader(bb.buildOrThrow());

        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
        // a hairline instead of a solid white edge; the body's own rim already carries most of the highlight
        Ui.outline(g, x, y, w, h, Ui.R4, (Ui.BRIGHT & 0xFFFFFF) | 0x50000000);
        RenderSystem.disableBlend();
    }

    /** Frees the off-screen copies; the next panel makes them again. */
    public static void release() {
        for (TextureTarget t : new TextureTarget[]{copy, half, quarter, eighth, panel}) {
            if (t != null) {
                t.destroyBuffers();
            }
        }
        copy = half = quarter = eighth = panel = null;
    }

    private static TextureTarget target(TextureTarget target, int w, int h) {
        if (target == null) {
            target = new TextureTarget(w, h, false, true);
        } else if (target.width != w || target.height != h) {
            target.resize(w, h, true);
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

    /** Drops the source into a smaller target, which is the whole blur: each level averages four texels. */
    private static void blur(int texture, TextureTarget to) {
        to.bindWrite(true);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, texture);
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        quad(bb, 0f, 0f, 1f, 1f, 0f, 1f, 1f, 0f);
        BufferUploader.drawWithShader(bb.buildOrThrow());
    }

    /**
     * Draws the frosted picture through a bent grid. The offsets are worked out per vertex, so the quads share their
     * corners and no seams show; the colour is added one channel at a time, and the coverage is written last so the
     * background's own channels stay out of it.
     */
    private static void bend(int ox, int oy, int pw, int ph, int tw, int th, double scale, int texture,
                             TextureTarget to) {
        int nx = Math.max(2, (int) Math.ceil(pw / (CELL * scale)));
        int ny = Math.max(2, (int) Math.ceil(ph / (CELL * scale)));
        int vw = nx + 1;
        int vh = ny + 1;
        int n = vw * vh;
        float[] gx = new float[n];
        float[] gy = new float[n];
        float[] uu = new float[n];
        float[] vv = new float[n];
        float[] tint = new float[n];
        float[] alpha = new float[n];

        double radius = Math.min(Ui.R4 * scale, Math.min(pw, ph) * 0.5);
        double bezel = Math.max(BEZEL * scale, 1.0);
        double refract = REFRACT * scale;
        double halfW = pw * 0.5;
        double halfH = ph * 0.5;
        double boxW = Math.max(halfW - radius, 0.0);
        double boxH = Math.max(halfH - radius, 0.0);
        double cap = bezel * 1.3;
        double rimWidth = Math.max(1.5, bezel * 0.10);

        for (int j = 0; j < vh; j++) {
            double vy = ph * (double) j / ny;
            for (int i = 0; i < vw; i++) {
                double vx = pw * (double) i / nx;
                int k = j * vw + i;
                gx[k] = (float) (vx / pw);
                gy[k] = (float) (vy / ph);

                double qx = vx - halfW;
                double qy = vy - halfH;
                double dx = Math.abs(qx) - boxW;
                double dy = Math.abs(qy) - boxH;
                double sx = Math.max(dx, 0.0);
                double sy = Math.max(dy, 0.0);
                double sd = Math.sqrt(sx * sx + sy * sy) + Math.min(Math.max(dx, dy), 0.0) - radius;
                double depth = Math.max(-sd, 0.0);
                double t = Math.min(depth / bezel, 1.0);

                // the outward normal of the rounded box, in the panel's top-down pixels
                double nxv;
                double nyv;
                double len = Math.sqrt(sx * sx + sy * sy);
                if (len > 1.0e-5) {
                    nxv = sx / len;
                    nyv = sy / len;
                } else if (dx > dy) {
                    nxv = 1.0;
                    nyv = 0.0;
                } else {
                    nxv = 0.0;
                    nyv = 1.0;
                }
                nxv *= qx < 0.0 ? -1.0 : 1.0;
                nyv *= qy < 0.0 ? -1.0 : 1.0;

                // rays through the rounded edge come out of the glass bent, most at the outer rim
                double xr = 1.0 - t;
                double thetaI = Math.asin(xr * xr);
                double thetaT = Math.asin(Math.min(Math.sin(thetaI) / 1.5, 1.0));
                double bend = depth >= bezel ? 0.0 : -Math.tan(thetaT - thetaI);
                double offX = nxv * bend * refract;
                double offY = nyv * bend * refract;
                double offLen = Math.sqrt(offX * offX + offY * offY);
                if (offLen > cap) {
                    offX *= cap / offLen;
                    offY *= cap / offLen;
                }

                double sampleX = vx + offX;
                double sampleY = vy + offY;
                uu[k] = (float) clamp((ox + sampleX) / tw, 0.5 / tw, 1.0 - 0.5 / tw);
                vv[k] = (float) clamp(1.0 - (oy + sampleY) / th, 0.5 / th, 1.0 - 0.5 / th);

                double inside = clamp(0.5 - sd, 0.0, 1.0);
                double wash = (1.0 - smooth(0.0, bezel, depth)) * inside;
                double rim = 1.0 - smooth(0.0, rimWidth, Math.abs(sd));
                tint[k] = (float) (1.0 + 0.22 * wash + 0.30 * rim);
                alpha[k] = (float) clamp(0.34 + 0.34 * wash + 0.24 * rim, 0.0, 1.0);
            }
        }

        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texture);
        RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
        int tintArgb = Ui.GLASS_TINT;
        float[] base = {((tintArgb >> 16) & 255) / 255f, ((tintArgb >> 8) & 255) / 255f, (tintArgb & 255) / 255f};
        for (int channel = 0; channel < 3; channel++) {
            GlStateManager._colorMask(channel == 0, channel == 1, channel == 2, false);
            grid(gx, gy, uu, vv, tint, alpha, vw, vh, channel, base[channel]);
        }
        GlStateManager._colorMask(false, false, false, true);
        grid(gx, gy, uu, vv, tint, alpha, vw, vh, 3, 0f);
    }

    private static void grid(float[] gx, float[] gy, float[] uu, float[] vv, float[] tint, float[] alpha,
                             int vw, int vh, int channel, float base) {
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (int j = 0; j < vh - 1; j++) {
            for (int i = 0; i < vw - 1; i++) {
                int a = j * vw + i;
                int b = a + 1;
                int c = a + vw;
                int d = c + 1;
                vertex(bb, a, gx, gy, uu, vv, tint, alpha, channel, base);
                vertex(bb, c, gx, gy, uu, vv, tint, alpha, channel, base);
                vertex(bb, d, gx, gy, uu, vv, tint, alpha, channel, base);
                vertex(bb, b, gx, gy, uu, vv, tint, alpha, channel, base);
            }
        }
        BufferUploader.drawWithShader(bb.buildOrThrow());
    }

    private static void vertex(BufferBuilder bb, int i, float[] gx, float[] gy, float[] uu, float[] vv,
                               float[] tint, float[] alpha, int channel, float base) {
        bb.addVertex(gx[i], gy[i], 0f).setUv(uu[i], vv[i]);
        if (channel == 3) {
            int a = Math.round(alpha[i] * 255f);
            bb.setColor(255, 255, 255, a);
        } else {
            int value = Math.round(Math.min(tint[i] * base, 1f) * 255f);
            bb.setColor(channel == 0 ? value : 0, channel == 1 ? value : 0, channel == 2 ? value : 0, 255);
        }
    }

    /** Nine-slices the corner mask, which cuts the slab's coverage outside the rounded corners. */
    private static void mask(int pw, int ph, double scale) {
        double r = Math.min(Ui.R4 * scale, Math.min(pw, ph) * 0.5);
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
