package com.kuronami.questtree.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a book still reads from a locale, and what an author can safely delete (2026-10-22). */
class TranslationCleanupTest {

    private static final JsonObject BUNDLE = JsonParser.parseString("""
            {
              "chapters": {
                "t:basics": {
                  "nodes": {
                    "n0": {"tasks": [{"type": "item", "text": "progression.t.basics.n0.task"}]},
                    "n1": {}
                  }
                },
                "t:chapter.one": {"nodes": {"a": {}}}
              },
              "groups": [{"id": "t:main"}],
              "reward_tables": {"t:loot": {"entries": []}}
            }""").getAsJsonObject();

    @Test
    void everyKeyTheChapterNodesGroupsAndTablesUse() {
        var used = TranslationCleanup.used(BUNDLE);
        assertTrue(used.contains("progression.t.basics.title"));
        assertTrue(used.contains("progression.t.basics.subtitle"));
        assertTrue(used.contains("progression.t.basics.n0.title"));
        assertTrue(used.contains("progression.t.basics.n0.desc"));
        assertTrue(used.contains("progression.t.basics.n0.task.title"));
        assertTrue(used.contains("progression.t.group.main.title"));
        assertTrue(used.contains("progression.t.table.loot.title"));
        // a chapter path may contain a dot
        assertTrue(used.contains("progression.t.chapter.one.title"));
    }

    @Test
    void staleNamesOnlyKeysOfAKnownNamespaceThatNoObjectUses() {
        JsonObject locale = JsonParser.parseString("""
                {
                  "progression.t.basics.title": "Basics",
                  "progression.t.basics.n0.title": "Pick",
                  "progression.t.basics.n0.task.title": "Pick up",
                  "progression.t.chapter.one.title": "One",
                  "progression.t.basics.n9.title": "Gone",
                  "progression.t.basics.n9.desc": "Gone",
                  "progression.t.group.old.title": "Old",
                  "progression.t.table.gone.title": "Gone",
                  "progression.t.basics.title.extra": "Not a suffix",
                  "some.other.mod.title": "Leave me"
                }""").getAsJsonObject();
        assertEquals(List.of("progression.t.basics.n9.desc", "progression.t.basics.n9.title",
                        "progression.t.group.old.title", "progression.t.table.gone.title"),
                TranslationCleanup.stale(BUNDLE, locale).stream().sorted().toList());
    }

    @Test
    void removeTakesExactlyTheStaleKeysAndCounts() {
        JsonObject locale = JsonParser.parseString("""
                {"progression.t.basics.title": "Basics", "progression.t.old.title": "Old"}""").getAsJsonObject();
        assertEquals(1, TranslationCleanup.remove(locale, List.of("progression.t.old.title")));
        assertEquals(0, TranslationCleanup.remove(locale, List.of("progression.t.old.title")));
        assertFalse(locale.has("progression.t.old.title"));
        assertTrue(locale.has("progression.t.basics.title"));
    }
}
