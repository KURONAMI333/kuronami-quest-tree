package com.kuronami.questtree.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.util.function.Consumer;

/**
 * Runs one action per JSON entry and keeps the rest when one is malformed (2026-10-22). A single bad decoration or
 * quest link must not drop the whole chapter from the map.
 */
public final class SafeJson {

    private SafeJson() {
    }

    public static void each(JsonArray entries, Consumer<JsonElement> action, Consumer<RuntimeException> onError) {
        for (JsonElement entry : entries) {
            try {
                action.accept(entry);
            } catch (RuntimeException ex) {
                onError.accept(ex);
            }
        }
    }
}
