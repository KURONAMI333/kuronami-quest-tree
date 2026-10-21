package com.kuronami.questtree.client;

/** Converts between the font's own width and the screen pixels our text scale gives it. */
public final class TextFit {

    private TextFit() {
    }

    public static int width(int fontWidth, float scale) {
        // float scales such as 0.8f sit just above 0.8, so nudge before rounding up an exact product
        return (int) Math.ceil(fontWidth * (double) scale - 1e-5);
    }

    public static int room(int pixels, float scale) {
        // float scales such as 1.2f sit just above 1.2, so nudge before flooring to keep the exact divide whole
        return Math.max(1, (int) Math.floor(pixels / (double) Math.max(0.01f, scale) + 1e-5));
    }
}
