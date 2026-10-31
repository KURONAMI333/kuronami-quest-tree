package com.kuronami.questtree.client;

/**
 * The colour maths behind {@link Ui#onLight}: pull a fixed text colour towards the background until it reads. It is
 * a separate class only so the rule can be tested; the test runtime has no Minecraft classes to load {@code Ui}
 * with (2026-10-31).
 */
final class TextContrast {

    /** The WCAG contrast ratio where body text is considered readable. */
    static final double MIN_CONTRAST = 4.5;
    /** A surface counts as light from this relative luminance up: paper's ground is 0.62, the dark ground 0.03. */
    static final double LIGHT_BACKGROUND = 0.5;

    private TextContrast() {
    }

    /**
     * {@code argb} on {@code background}, darkened only as far as {@link #MIN_CONTRAST} needs and only when the
     * background is light. RGB is scaled as one, so hue and saturation stay; a colour that already reads is unchanged.
     */
    static int readableOn(int background, int argb) {
        if ((argb >>> 24) == 0 || luminance(background) < LIGHT_BACKGROUND) {
            return argb;
        }
        if (contrast(argb, background) >= MIN_CONTRAST) {
            return argb;
        }
        int r = (argb >> 16) & 255;
        int g = (argb >> 8) & 255;
        int b = argb & 255;
        int lo = 0;
        int hi = 255;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (contrast(scaled(argb, r, g, b, mid), background) >= MIN_CONTRAST) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        int out = scaled(argb, r, g, b, lo);
        // integer rounding can leave the contrast a hair under the target; one more step down guarantees it
        return contrast(out, background) >= MIN_CONTRAST ? out : scaled(argb, r, g, b, Math.max(0, lo - 1));
    }

    /**
     * {@code argb} made readable on {@code background} whichever way that needs: darkened on a light background as
     * {@link #readableOn} does, lightened towards white on a dark one. Used only where a light theme's text lands on a
     * dark picture (a chapter's own background, 2026-10-31); the default dark theme never calls it.
     */
    static int readableAgainst(int background, int argb) {
        if (luminance(background) >= LIGHT_BACKGROUND) {
            return readableOn(background, argb);
        }
        if ((argb >>> 24) == 0 || contrast(argb, background) >= MIN_CONTRAST) {
            return argb;
        }
        int lo = 0;
        int hi = 255;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (contrast(towardsWhite(argb, mid), background) >= MIN_CONTRAST) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return towardsWhite(argb, lo);
    }

    /** The colour moved {@code t / 255} of the way to white, keeping alpha. */
    private static int towardsWhite(int argb, int t) {
        int r = (argb >> 16) & 255;
        int g = (argb >> 8) & 255;
        int b = argb & 255;
        return (argb & 0xFF000000)
                | ((r + (255 - r) * t / 255) << 16)
                | ((g + (255 - g) * t / 255) << 8)
                | (b + (255 - b) * t / 255);
    }

    /** The colour with its RGB scaled by {@code scale / 255}, keeping hue, saturation and alpha. */
    private static int scaled(int argb, int r, int g, int b, int scale) {
        return (argb & 0xFF000000)
                | ((r * scale / 255) << 16)
                | ((g * scale / 255) << 8)
                | (b * scale / 255);
    }

    /** WCAG relative luminance of an sRGB colour, 0 (black) to 1 (white); alpha is ignored. */
    static double luminance(int rgb) {
        return 0.2126 * linear((rgb >> 16) & 255)
                + 0.7152 * linear((rgb >> 8) & 255)
                + 0.0722 * linear(rgb & 255);
    }

    private static double linear(int channel) {
        double s = channel / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    /** WCAG contrast ratio between two colours, 1 (equal) to 21 (black on white); alpha is ignored. */
    static double contrast(int a, int b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }
}
