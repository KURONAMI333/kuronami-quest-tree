package com.kuronami.questtree.client.editor;

/**
 * Chapter-image proportions (2026-10-22, p6-editor). FTB Quests' "fix aspect ratio" button changes one side so a
 * picture matches its file; the arithmetic is here, apart from the screen, so a test can hold the roundings down.
 */
public final class DecorationGeometry {

    private DecorationGeometry() {
    }

    /** The width that keeps a texture's own ratio at height {@code h}; the input when the file has no usable size. */
    public static double widthFor(double h, int pixelWidth, int pixelHeight) {
        if (pixelWidth <= 0 || pixelHeight <= 0 || !Double.isFinite(h)) {
            return h;
        }
        return round(h * pixelWidth / (double) pixelHeight);
    }

    /** The height that keeps a texture's own ratio at width {@code w}; the input when the file has no usable size. */
    public static double heightFor(double w, int pixelWidth, int pixelHeight) {
        if (pixelWidth <= 0 || pixelHeight <= 0 || !Double.isFinite(w)) {
            return w;
        }
        return round(w * pixelHeight / (double) pixelWidth);
    }

    /** Two decimals: a map coordinate is a small number, and a long float only makes the overlay file harder to read. */
    static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
