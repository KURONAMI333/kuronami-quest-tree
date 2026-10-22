package com.kuronami.questtree.client.editor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The reward table's "used by" list (2026-10-22, p6-editor). */
class TableReferencesTest {

    @Test
    void aNodeThatRollsTheTableAppearsOnceEvenWithTwoSuchRewards() {
        JsonObject chapters = JsonParser.parseString("""
                {"t:basics": {"nodes": {
                    "a": {"rewards": [{"type": "table", "table": "t:loot"},
                                       {"type": "table", "table": "t:loot"}]},
                    "b": {"rewards": [{"type": "table", "table": "t:other"}, {"type": "item"}]},
                    "c": {"tasks": [{"type": "checkmark"}]}
                }}}
                """).getAsJsonObject();

        assertEquals(List.of("t:basics/a"), TableReferences.usedIn(chapters, "t:loot"));
        assertEquals(List.of("t:basics/b"), TableReferences.usedIn(chapters, "t:other"));
    }

    @Test
    void anUnusedTableHasAnEmptyListAndBrokenShapesAreSkipped() {
        JsonObject chapters = JsonParser.parseString("""
                {"t:basics": {"nodes": {"a": {"rewards": [{"type": "table"}]}}},
                 "t:broken": {"nodes": [1, 2]},
                 "t:not-a-chapter": 3}
                """).getAsJsonObject();

        assertTrue(TableReferences.usedIn(chapters, "t:loot").isEmpty());
    }
}
