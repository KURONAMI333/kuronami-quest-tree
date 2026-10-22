package com.kuronami.questtree.client.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * The translation editor's working copy (2026-10-23, R6 H1). A locale file is a flat map of dotted keys, but an
 * {@link EditorDoc} path reads a dot as a level, so the form edits the locale one level down: a wrapper object whose
 * {@code entries} member holds the values under numeric keys, one per key in the screen's list.
 * <p>
 * Numeric members are ordinary object keys, not array slots, so clearing one leaves every other index where it was. The
 * screen's list maps an index back to the locale's key, dots and all.
 */
public final class TranslationDraft {

    /** The wrapper member the form's rows live under. */
    public static final String ENTRIES = "entries";

    private TranslationDraft() {
    }

    /** {@code {"entries":{"0":"..."}}} for the values this locale already has; a blank value is left absent. */
    public static JsonObject wrap(JsonObject locale, List<String> keys) {
        JsonObject entries = new JsonObject();
        if (locale != null) {
            for (int i = 0; i < keys.size(); i++) {
                JsonElement value = locale.get(keys.get(i));
                if (value != null && value.isJsonPrimitive() && !value.getAsString().isBlank()) {
                    entries.add(String.valueOf(i), value.deepCopy());
                }
            }
        }
        JsonObject root = new JsonObject();
        root.add(ENTRIES, entries);
        return root;
    }

    /** The form path of the key at {@code index}; a bracket token keeps the key list's position out of the JSON. */
    public static String path(int index) {
        return ENTRIES + "[" + index + "]";
    }

    /** The locale's own map, rebuilt from the wrapper: blank values are dropped, never sent as empty strings. */
    public static JsonObject collect(JsonObject root, List<String> keys) {
        JsonObject out = new JsonObject();
        JsonElement wrapped = root == null ? null : root.get(ENTRIES);
        if (wrapped == null || !wrapped.isJsonObject()) {
            return out;
        }
        JsonObject entries = wrapped.getAsJsonObject();
        for (int i = 0; i < keys.size(); i++) {
            JsonElement value = entries.get(String.valueOf(i));
            if (value != null && value.isJsonPrimitive() && !value.getAsString().isBlank()) {
                out.add(keys.get(i), value.deepCopy());
            }
        }
        return out;
    }

    /** Drops the key at {@code index} (the form's × button); other indices do not move. */
    public static void remove(JsonObject root, int index) {
        EditorDoc.remove(root, path(index));
    }
}
