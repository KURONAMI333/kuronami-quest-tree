package com.kuronami.questtree.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped {@code paper} theme (2026-10-31) has to carry a complete palette and the two pictures that make it a
 * paper surface. A missing key would silently keep the default dark colour, and a wrongly sized picture would be
 * stretched or skipped, so both are checked from the classpath the game reads.
 */
class ThemeAssetsTest {

    private static final String ASSETS = "/assets/kuronami_quest_tree/";
    private static final String PAPER = ASSETS + "themes/paper/";

    private static JsonObject palette(String path) throws Exception {
        try (InputStream in = ThemeAssetsTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path + " is missing from the classpath");
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test
    void thePaperPaletteCarriesEveryKeyOfTheShippedPalette() throws Exception {
        JsonObject shipped = palette(ASSETS + "gui/palette.json");
        JsonObject paper = palette(PAPER + "palette.json");

        for (Map.Entry<String, JsonElement> e : shipped.entrySet()) {
            assertTrue(paper.has(e.getKey()), "paper/palette.json is missing " + e.getKey());
        }
        assertTrue(paper.has("corner_radius"), "paper/palette.json is missing corner_radius");
    }

    @Test
    void everyPaperPaletteValueIsAColourOrTheRadius() throws Exception {
        JsonObject paper = palette(PAPER + "palette.json");

        for (Map.Entry<String, JsonElement> e : paper.entrySet()) {
            if (e.getKey().equals("corner_radius")) {
                assertEquals(1, e.getValue().getAsInt(), "the paper corners are 1 pixel");
                continue;
            }
            String value = e.getValue().getAsString();
            assertTrue(value.matches("#(?:[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})"),
                    e.getKey() + " is not #RRGGBB or #AARRGGBB: " + value);
        }
    }

    @Test
    void thePaperGroundIsA64PixelTilingPicture() throws Exception {
        assertPicture(PAPER + "textures/gui/ground.png", 64, 64);
    }

    @Test
    void thePaperTileFaceIsA32PixelPicture() throws Exception {
        assertPicture(PAPER + "textures/gui/patch/tile.png", 32, 32);
    }

    private static void assertPicture(String path, int width, int height) throws Exception {
        try (InputStream in = ThemeAssetsTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path + " is missing from the classpath");
            BufferedImage image = ImageIO.read(in);
            assertNotNull(image, path + " is not a readable picture");
            assertEquals(width, image.getWidth(), path + " width");
            assertEquals(height, image.getHeight(), path + " height");
        }
    }
}
