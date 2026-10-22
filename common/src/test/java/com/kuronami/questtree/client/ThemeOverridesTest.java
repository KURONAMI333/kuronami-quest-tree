package com.kuronami.questtree.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Default, then chapter, then quest (2026-10-22): the order {@code gui/theme.json} resolves in. */
class ThemeOverridesTest {

    private static final int PALETTE = 0xFF5E9E4A;

    private static ThemeOverrides theme(String json) {
        return ThemeOverrides.parse(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void withoutAnOverrideThePaletteValueStays() {
        ThemeOverrides empty = theme("{}");

        assertTrue(empty.isEmpty());
        assertEquals(PALETTE, empty.resolve("done_settled", PALETTE, "mypack:magic/first"));
    }

    @Test
    void aChapterEntryCoversItsQuests() {
        ThemeOverrides theme = theme("""
                {"chapters": {"mypack:magic": {"done_settled": "#FF7A4FC8"}}}
                """);

        assertFalse(theme.isEmpty());
        assertEquals(0xFF7A4FC8, theme.resolve("done_settled", PALETTE, "mypack:magic/first"));
        assertEquals(PALETTE, theme.resolve("done_settled", PALETTE, "mypack:other/first"));
    }

    @Test
    void aQuestEntryWinsOverItsChapter() {
        ThemeOverrides theme = theme("""
                {"chapters": {"mypack:magic": {"done_settled": "#FF7A4FC8", "progress": "#FF9B6BE0"}},
                 "quests": {"mypack:magic/first": {"done_settled": "#FFFF9B4F"}}}
                """);

        assertEquals(0xFFFF9B4F, theme.resolve("done_settled", PALETTE, "mypack:magic/first"));
        assertEquals(0xFF9B6BE0, theme.resolve("progress", PALETTE, "mypack:magic/first"));
    }

    @Test
    void aChapterIdWithSlashStillResolves() {
        ThemeOverrides theme = theme("""
                {"chapters": {"mypack:deep/ruins": {"tile_locked": "#FF4A4A55"}}}
                """);

        assertEquals(0xFF4A4A55, theme.resolve("tile_locked", PALETTE, "mypack:deep/ruins/quest"));
    }

    @Test
    void sixDigitColoursAreOpaqueAndBadColoursAreSkipped() {
        ThemeOverrides theme = theme("""
                {"quests": {"mypack:magic/first": {"done_settled": "#7A4FC8", "progress": "not a colour"}}}
                """);

        assertEquals(0xFF7A4FC8, theme.resolve("done_settled", PALETTE, "mypack:magic/first"));
        assertEquals(PALETTE, theme.resolve("progress", PALETTE, "mypack:magic/first"));
    }

    @Test
    void aNodeWithoutAChapterPartOnlyUsesItsOwnEntry() {
        ThemeOverrides theme = theme("""
                {"quests": {"first": {"done_settled": "#FFFF9B4F"}}}
                """);

        assertEquals(0xFFFF9B4F, theme.resolve("done_settled", PALETTE, "first"));
        assertEquals(PALETTE, theme.resolve("done_settled", PALETTE, null));
    }
}
