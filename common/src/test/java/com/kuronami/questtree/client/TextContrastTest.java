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
}
