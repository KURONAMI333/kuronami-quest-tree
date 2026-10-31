package com.kuronami.questtree.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Named look themes (2026-10-31): the default reads no theme layer, a theme sits above the mod and below a pack. */
class ThemesTest {

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    void anUnknownOrMissingIdReadsAsDefault() {
        assertEquals(Themes.DEFAULT, Themes.normalize(null));
        assertEquals(Themes.DEFAULT, Themes.normalize(""));
        assertEquals(Themes.DEFAULT, Themes.normalize("no_such_theme"));
        assertEquals("paper", Themes.normalize("paper"));
    }

    @Test
    void theSettingsListWrapsRound() {
        assertEquals("paper", Themes.next("default"));
        assertEquals("default", Themes.next("paper"));
        assertEquals("paper", Themes.next("no_such_theme"));
    }

    @Test
    void theDefaultThemeNamesNoFiles() {
        assertNull(Themes.palette("default"));
        assertNull(Themes.texture("default", "shape/rsquare"));
    }

    @Test
    void aThemeNamesItsOwnPaletteAndPictures() {
        assertEquals("themes/paper/palette.json", Themes.palette("paper"));
        assertEquals("themes/paper/textures/gui/shape/rsquare.png",
                Themes.texture("paper", "shape/rsquare"));
    }

    @Test
    void aPackBeatsAThemeAndAThemeBeatsTheFlatId() {
        assertEquals(Themes.Layer.PACK, Themes.layer(true, true));
        assertEquals(Themes.Layer.PACK, Themes.layer(true, false));
        assertEquals(Themes.Layer.THEME, Themes.layer(false, true));
        assertEquals(Themes.Layer.FLAT, Themes.layer(false, false));
    }

    @Test
    void thePaletteLayersAreModThenThemeThenPack() {
        JsonObject mod = json("""
                {"text": "#111111", "ground": "#222222"}
                """);
        JsonObject theme = json("""
                {"text": "#333333", "accent": "#444444"}
                """);
        JsonObject pack = json("""
                {"ground": "#555555"}
                """);

        JsonObject o = Themes.resolve(mod, "paper", theme, pack);

        assertEquals("#333333", o.get("text").getAsString());
        assertEquals("#555555", o.get("ground").getAsString());
        assertEquals("#444444", o.get("accent").getAsString());
    }

    @Test
    void theDefaultThemeSkipsTheThemeLayer() {
        JsonObject mod = json("""
                {"text": "#111111"}
                """);
        JsonObject theme = json("""
                {"text": "#333333"}
                """);

        JsonObject o = Themes.resolve(mod, "default", theme, null);

        assertEquals("#111111", o.get("text").getAsString());
    }

    @Test
    void aStackOfOnlyTheBuiltinHasNoPackLayer() {
        assertTrue(Themes.packResourceId(List.of("mod/kuronami_quest_tree"), "mod/kuronami_quest_tree").isEmpty());
    }

    @Test
    void aPackOnTopOfTheBuiltinIsThePackLayer() {
        assertEquals(Optional.of("file/mypack.zip"),
                Themes.packResourceId(List.of("mod/kuronami_quest_tree", "file/mypack.zip"), "mod/kuronami_quest_tree"));
    }

    @Test
    void anEmptyStackHasNoPackLayer() {
        assertTrue(Themes.packResourceId(List.of(), "mod/kuronami_quest_tree").isEmpty());
    }

    @Test
    void aMissingLayerChangesNothing() {
        JsonObject mod = json("""
                {"text": "#111111"}
                """);

        JsonObject o = Themes.resolve(mod, "paper", null, null);

        assertEquals("#111111", o.get("text").getAsString());
    }
}
