package com.kuronami.questtree.client;

/** Converts between the font's own width and the screen pixels our text scale gives it. */
public final class TextFit {

    private TextFit() {
    }

    public static int width(int fontWidth, float scale) {
        return (int) Math.ceil(fontWidth * scale);
    }

    public static int room(int pixels, float scale) {
        return Math.max(1, (int) Math.floor(pixels / Math.max(0.01f, scale)));
    }
}
