package com.kuronami.questtree.client;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class SyncStateTest {

    @Test
    void aNewSyncReplacesEveryPartAtOnce() {
        JsonObject chapters = new JsonObject();
        JsonObject tables = new JsonObject();
        JsonObject deleted = new JsonObject();
        Object model = new Object();
        Object rules = new Object();

        SyncState<Object, Object> state = new SyncState<>(model, rules, chapters, tables, deleted);

        assertSame(model, state.model());
        assertSame(rules, state.rules());
        assertSame(chapters, state.chapters());
        assertSame(tables, state.tables());
        assertSame(deleted, state.deleted());
    }

    @Test
    void emptyClearsTheModelAndRulesButKeepsEmptyJsonObjects() {
        SyncState<Object, Object> empty = SyncState.empty();

        assertNull(empty.model());
        assertNull(empty.rules());
        assertNull(empty.chapters());
        assertEquals(0, empty.tables().size());
        assertEquals(0, empty.deleted().size());
    }
}
