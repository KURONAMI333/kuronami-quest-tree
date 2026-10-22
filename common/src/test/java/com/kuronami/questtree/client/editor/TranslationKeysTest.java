package com.kuronami.questtree.client.editor;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The translation screen's key list (2026-10-22, p6-editor). */
class TranslationKeysTest {

    @Test
    void langFilesAndBookTextBothContributeKeysSortedOnce() {
        var chapters = JsonParser.parseString("""
                {"t:basics": {"title": "progression.t.basics.title", "desc": "plain text",
                  "nodes": {"n0": {"tasks": [{"text": "progression.t.basics.n0.task"}]}}}}
                """).getAsJsonObject();
        var tables = JsonParser.parseString("""
                {"t:loot": {"title": "progression.t.table.loot.title"}}
                """).getAsJsonObject();
        var langs = JsonParser.parseString("""
                {"en_us": {"progression.t.basics.title": "Basics", "progression.t.old.title": "Old"},
                 "ja_jp": {"progression.t.basics.title": "基本"}}
                """).getAsJsonObject();

        List<String> keys = TranslationKeys.collect(chapters, tables, langs);

        assertEquals(List.of(
                "progression.t.basics.n0.task",
                "progression.t.basics.title",
                "progression.t.old.title",
                "progression.t.table.loot.title"), keys);
    }

    @Test
    void ordinaryTextIsNotAKey() {
        var chapters = JsonParser.parseString("""
                {"t:basics": {"title": "Basics", "subtitle": "Note: progression.t.basics.title"}}
                """).getAsJsonObject();

        List<String> keys = TranslationKeys.collect(chapters, JsonParser.parseString("{}").getAsJsonObject(),
                JsonParser.parseString("{}").getAsJsonObject());

        assertTrue(keys.isEmpty(), keys.toString());
        assertFalse(keys.contains("progression.t.basics.title"));
    }
}
