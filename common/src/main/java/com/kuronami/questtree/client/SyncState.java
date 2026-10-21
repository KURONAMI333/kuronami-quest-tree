package com.kuronami.questtree.client;

import com.google.gson.JsonObject;

/**
 * Everything one progression sync replaces, kept together so a failed rebuild never leaves the map with a new model and
 * the old rules (2026-10-22). Pure and generic so the all-or-nothing swap is tested without a client.
 */
public final class SyncState<M, R> {

    private final M model;
    private final R rules;
    private final JsonObject chapters;
    private final JsonObject tables;
    private final JsonObject deleted;

    public SyncState(M model, R rules, JsonObject chapters, JsonObject tables, JsonObject deleted) {
        this.model = model;
        this.rules = rules;
        this.chapters = chapters;
        this.tables = tables;
        this.deleted = deleted;
    }

    public static <M, R> SyncState<M, R> empty() {
        return new SyncState<>(null, null, null, new JsonObject(), new JsonObject());
    }

    public M model() {
        return model;
    }

    public R rules() {
        return rules;
    }

    public JsonObject chapters() {
        return chapters;
    }

    public JsonObject tables() {
        return tables;
    }

    public JsonObject deleted() {
        return deleted;
    }
}
