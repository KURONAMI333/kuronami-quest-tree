package com.kuronami.questtree.client;

import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The light-theme text correction behind {@link Ui#onLight} (2026-10-31): a dark theme keeps every fixed colour
 * exactly as it was, and a light one keeps the hue while reaching the WCAG 4.5:1 contrast.
 */
class TextContrastTest {

    private static final int PAPER = 0xFFDCCDB0;
    private static final int DARK = 0xFF313338;

    @Test
    void aDarkBackgroundReturnsEveryColourUnchanged() {
        int[] colours = {
                0xFFFFFFFF, 0xFFFFFF55, 0xFF55FFFF, 0xFFB8862F, 0xFFC8C8C8, 0xFFB5BAC1
        };
        for (int c : colours) {
            assertEquals(c, TextContrast.readableOn(DARK, c));
        }
    }

    @Test
    void aLightBackgroundDarkensUntilTheContrastReachesFourAndAHalf() {
        int[] colours = {0xFFFFFFFF, 0xFFFFFF55, 0xFF55FFFF, 0xFFB8862F};
        for (int c : colours) {
            int out = TextContrast.readableOn(PAPER, c);
            assertTrue(TextContrast.luminance(out) < TextContrast.luminance(c),
                    "not darkened: " + Integer.toHexString(c));
            assertTrue(TextContrast.contrast(out, PAPER) >= TextContrast.MIN_CONTRAST,
                    "contrast " + TextContrast.contrast(out, PAPER) + " for " + Integer.toHexString(c));
        }
    }

    @Test
    void aLightBackgroundKeepsTheHueAndAlpha() {
        int[] colours = {0xFFFFFF55, 0xFF55FFFF, 0xFFB8862F, 0x80FFFFFF};
        for (int c : colours) {
            int out = TextContrast.readableOn(PAPER, c);
            assertEquals(hue(c), hue(out), 0.02f, "hue moved for " + Integer.toHexString(c));
            assertEquals(c >>> 24, out >>> 24, "alpha changed for " + Integer.toHexString(c));
        }
    }

    @Test
    void coloursThatAlreadyReadAreLeftAlone() {
        int text = 0xFF2A2018;
        assertTrue(TextContrast.contrast(text, PAPER) >= TextContrast.MIN_CONTRAST);
        assertEquals(text, TextContrast.readableOn(PAPER, text));
    }

    private static float hue(int argb) {
        return Color.RGBtoHSB((argb >> 16) & 255, (argb >> 8) & 255, argb & 255, null)[0];
    }

    @Test
    void againstADarkPictureDarkTextIsLightenedUntilItReads() {
        int stone = 0xFF6E6E6E;
        int[] colours = {0xFF3A2E22, 0xFF6B5A44, 0xFF8A6A2A};
        for (int c : colours) {
            int out = TextContrast.readableAgainst(stone, c);
            assertTrue(TextContrast.luminance(out) > TextContrast.luminance(c),
                    "not lightened: " + Integer.toHexString(c));
            assertTrue(TextContrast.contrast(out, stone) >= TextContrast.MIN_CONTRAST,
                    "contrast " + TextContrast.contrast(out, stone) + " for " + Integer.toHexString(c));
        }
    }

    @Test
    void againstALightBackgroundItDarkensLikeReadableOn() {
        for (int c : new int[]{0xFFFFFFFF, 0xFF55FFFF}) {
            assertEquals(TextContrast.readableOn(PAPER, c), TextContrast.readableAgainst(PAPER, c));
        }
    }

    @Test
    void againstAnyBackgroundAReadableColourIsUnchanged() {
        assertEquals(0xFFFFFFFF, TextContrast.readableAgainst(0xFF202020, 0xFFFFFFFF));
        assertEquals(0xFF202020, TextContrast.readableAgainst(PAPER, 0xFF202020));
    }

    @Test
    void onAMidToneGroundTextIsDarkenedBecauseWhiteCannotReach() {
        int mid = 0xFF9A9A9A;
        assertTrue(TextContrast.contrast(0xFFFFFFFF, mid) < TextContrast.MIN_CONTRAST);
        int out = TextContrast.readableAgainst(mid, 0xFF8A8A8A);
        assertTrue(TextContrast.luminance(out) < TextContrast.luminance(0xFF8A8A8A), Integer.toHexString(out));
        assertTrue(TextContrast.contrast(out, mid) >= TextContrast.MIN_CONTRAST);
    }
}
