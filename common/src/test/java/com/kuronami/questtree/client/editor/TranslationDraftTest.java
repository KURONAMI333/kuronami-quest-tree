package com.kuronami.questtree.client.editor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The translation editor's wrapped working copy, for locale keys that contain dots (2026-10-23, R6 H1). */
class TranslationDraftTest {

    private static final List<String> KEYS = List.of("other", "progression.parity.title");

    private static JsonObject locale() {
        return JsonParser.parseString("""
                {"other":"X","progression.parity.title":"Old"}
                """).getAsJsonObject();
    }

    @Test
    void anExistingValueIsReadByIndexAndNotEmpty() {
        JsonObject root = TranslationDraft.wrap(locale(), KEYS);

        assertEquals("Old", EditorDoc.text(root, TranslationDraft.path(1)));
        assertEquals("X", EditorDoc.text(root, TranslationDraft.path(0)));
        assertTrue(root.get(TranslationDraft.ENTRIES).isJsonObject());
        assertFalse(root.has("progression.parity.title"), "the dotted key must not be a level in the working copy");
    }

    @Test
    void aRewriteGoesBackUnderTheDottedKey() {
        JsonObject root = TranslationDraft.wrap(locale(), KEYS);

        EditorDoc.setString(root, TranslationDraft.path(1), "New");
        JsonObject sent = TranslationDraft.collect(root, KEYS);

        assertEquals("New", sent.get("progression.parity.title").getAsString());
        assertEquals("X", sent.get("other").getAsString());
    }

    @Test
    void aBlankBoxDropsOnlyItsOwnKeyAndLeavesTheOthersInPlace() {
        JsonObject root = TranslationDraft.wrap(locale(), KEYS);

        EditorDoc.setString(root, TranslationDraft.path(1), "  ");
        JsonObject sent = TranslationDraft.collect(root, KEYS);

        assertFalse(sent.has("progression.parity.title"));
        assertEquals("X", sent.get("other").getAsString());
        assertEquals("X", EditorDoc.text(root, TranslationDraft.path(0)), "an index does not shift when another clears");
    }

    @Test
    void theRemoveButtonDropsTheKeyAtItsOwnIndex() {
        JsonObject root = TranslationDraft.wrap(locale(), KEYS);

        TranslationDraft.remove(root, 1);

        JsonObject sent = TranslationDraft.collect(root, KEYS);
        assertFalse(sent.has("progression.parity.title"));
        assertEquals("X", sent.get("other").getAsString());
    }

    @Test
    void aKeyWithNoTranslationIsAbsentRatherThanEmpty() {
        JsonObject root = TranslationDraft.wrap(locale(), List.of("other", "progression.parity.missing"));

        assertEquals("", EditorDoc.text(root, TranslationDraft.path(1)));
        assertFalse(TranslationDraft.collect(root, List.of("other", "progression.parity.missing"))
                .has("progression.parity.missing"));
    }
}
