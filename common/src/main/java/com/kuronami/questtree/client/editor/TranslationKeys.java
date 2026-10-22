package com.kuronami.questtree.client.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The keys a translation screen offers (2026-10-22, p6-editor): every key any loaded locale has, plus every text in
 * the book that names one. A key can be written in a chapter before any lang file has it, and the author still has to
 * be able to translate it; a pack's own lang files are the other half, because a locale may have a line the English
 * file does not. The keys are not interpreted: a dotted name is ordinary text to this class.
 */
public final class TranslationKeys {

    /** The prefix the import and export paths give progression text. */
    private static final String TEXT_PREFIX = "progression.";

    private TranslationKeys() {
    }

    public static List<String> collect(JsonObject chapters, JsonObject tables, JsonObject langs) {
        Set<String> keys = new TreeSet<>();
        for (Map.Entry<String, JsonElement> locale : langs.entrySet()) {
            if (locale.getValue().isJsonObject()) {
                keys.addAll(locale.getValue().getAsJsonObject().keySet());
            }
        }
        collectStrings(chapters, keys);
        collectStrings(tables, keys);
        return new ArrayList<>(keys);
    }

    /**
     * Walks the whole tree rather than named members: a task's text, a reward's title, a table's title and a group's
     * name are all keys, and a member added by a later version must not need this class edited again.
     */
    private static void collectStrings(JsonElement element, Set<String> into) {
        if (element == null) {
            return;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String text = element.getAsString();
            if (text.startsWith(TEXT_PREFIX)) {
                into.add(text);
            }
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectStrings(child, into);
            }
            return;
        }
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> child : element.getAsJsonObject().entrySet()) {
                collectStrings(child.getValue(), into);
            }
        }
    }
}
