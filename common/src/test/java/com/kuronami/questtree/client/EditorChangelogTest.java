package com.kuronami.questtree.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The map's changelog list: what stays, what fades, and the word a line starts with (2026-10-22, p6-editor). */
class EditorChangelogTest {

    @BeforeEach
    void clear() {
        EditorChangelog.clear();
    }

    @Test
    void entriesFadeOutAfterTheirLifetimeAndTheOldestGoFirst() {
        EditorChangelog.add("update_node", "t:basics", "a", 1, "", "", 0);
        EditorChangelog.add("update_node", "t:basics", "b", 1, "", "", 5_000);

        assertEquals(2, EditorChangelog.recent(5_000).size());
        List<EditorChangelog.Entry> left = EditorChangelog.recent(EditorChangelog.LIFE_MS + 1);
        assertEquals(1, left.size());
        assertEquals("b", left.get(0).node());
    }

    @Test
    void theListStopsAtItsLimitAndDropsTheOldestEntry() {
        for (int i = 0; i < EditorChangelog.LIMIT + 5; i++) {
            EditorChangelog.add("update_node", "t:basics", "n" + i, 1, "", "", 0);
        }

        List<EditorChangelog.Entry> all = EditorChangelog.recent(0);
        assertEquals(EditorChangelog.LIMIT, all.size());
        assertEquals("n5", all.get(0).node());
        assertEquals("n" + (EditorChangelog.LIMIT + 4), all.get(all.size() - 1).node());
    }

    @Test
    void aBatchKeepsItsCountAndAnEmptyCountMeansOne() {
        EditorChangelog.add("move_nodes", "t:basics", "a", 4, "move_nodes", "", 0);
        EditorChangelog.add("update_node", "t:basics", "b", 0, "", "", 0);

        List<EditorChangelog.Entry> all = EditorChangelog.recent(0);
        assertEquals(4, all.get(0).count());
        assertEquals("move_nodes", all.get(0).undoOp());
        assertEquals(1, all.get(1).count());
    }

    @Test
    void anOpFallsIntoOneOfTheSevenWords() {
        assertEquals("add", EditorChangelog.action("create_node"));
        assertEquals("add", EditorChangelog.action("restore_table"));
        assertEquals("delete", EditorChangelog.action("delete_nodes"));
        assertEquals("link", EditorChangelog.action("link"));
        assertEquals("unlink", EditorChangelog.action("unlink"));
        assertEquals("undo", EditorChangelog.action("undo"));
        assertEquals("redo", EditorChangelog.action("redo"));
        assertEquals("change", EditorChangelog.action("resize_nodes"));
        assertEquals("change", EditorChangelog.action("update_lang"));
        assertEquals("change", EditorChangelog.action("something_new"));
        assertEquals("change", EditorChangelog.action(null));
    }

    @Test
    void aFrameReadsOneSnapshotUntilTheNextTick() {
        EditorChangelog.add("update_node", "t:basics", "a", 1, "", "", 0);
        EditorChangelog.tick(0);

        List<EditorChangelog.Entry> first = EditorChangelog.recent();
        assertSame(first, EditorChangelog.recent(), "rendering must not copy the list");

        EditorChangelog.tick(1);
        assertNotSame(first, EditorChangelog.recent());
        assertEquals(1, EditorChangelog.recent().size());
    }

    @Test
    void expiryIsResolvedOnTheTick() {
        EditorChangelog.add("update_node", "t:basics", "a", 1, "", "", 0);
        EditorChangelog.tick(0);
        assertEquals(1, EditorChangelog.recent().size());

        EditorChangelog.tick(EditorChangelog.LIFE_MS + 1);
        assertTrue(EditorChangelog.recent().isEmpty());
    }

    @Test
    void aClearedListStartsEmpty() {
        EditorChangelog.add("update_node", "t:basics", "a", 1, "", "", 0);
        EditorChangelog.clear();
        assertTrue(EditorChangelog.recent(0).isEmpty());
    }
}
