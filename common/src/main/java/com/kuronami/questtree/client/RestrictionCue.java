package com.kuronami.questtree.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.Util;

/** A lock beside the crosshair for a second when a restriction refuses an action; no text. */
public final class RestrictionCue {

    private static final long SHOW = 1000;

    private static long until;

    private RestrictionCue() {
    }

    public static void show() {
        until = Util.getMillis() + SHOW;
    }

    public static void clear() {
        until = 0;
    }

    public static void render(GuiGraphics g) {
        long left = until - Util.getMillis();
        Minecraft minecraft = Minecraft.getInstance();
        if (left <= 0 || minecraft.options.hideGui || minecraft.player == null) {
            return;
        }
        float alpha = Math.min(1f, left / 300f);
        Tiles.icon(g, "lock", g.guiWidth() / 2 + 10, g.guiHeight() / 2 + 8, 12, Tiles.withAlpha(Tiles.GOLD, alpha));
    }
}
