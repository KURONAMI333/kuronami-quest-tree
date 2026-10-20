package com.kuronami.questtree.client.edit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorSelectionTest {

    private static final EditorSelection.Hit A = new EditorSelection.Hit("a", 0, 0, 0.5, 0.5);
    private static final EditorSelection.Hit B = new EditorSelection.Hit("b", 4, 0, 0.5, 0.5);
    private static final EditorSelection.Hit C = new EditorSelection.Hit("c", 2, 3, 0.5, 0.5);

    @Test
    void plainClickReplacesAndCtrlToggles() {
        EditorSelection s = new EditorSelection();
        s.click("a", false);
        s.click("b", false);
        assertEquals(List.of("b"), s.ids());
        s.click("a", true);
        s.click("b", true);
        assertEquals(List.of("a"), s.ids(), "Ctrl on an already picked node takes it out");
    }

    @Test
    void boxTakesTouchedNodesAndKeepsAdditiveOnes() {
        EditorSelection s = new EditorSelection();
        s.add("outside");
        s.box(EditorSelection.Rect.of(-1, -1, 1, 1), List.of(A, B, C), true);
        assertEquals(List.of("outside", "a"), s.ids());
        s.box(EditorSelection.Rect.of(-1, -1, 1, 1), List.of(A, B, C), false);
        assertEquals(List.of("a"), s.ids(), "a plain box replaces the selection");
    }

    @Test
    void boxTouchesANodeItOnlyGrazes() {
        EditorSelection s = new EditorSelection();
        s.box(EditorSelection.Rect.of(-1, -1, 0.1, 0.1), List.of(A), false);
        assertTrue(s.contains("a"));
        s.box(EditorSelection.Rect.of(1, 1, 2, 2), List.of(A), false);
        assertFalse(s.contains("a"), "a box clear of the tile picks nothing");
    }

    @Test
    void selectAllAndRetainFollowWhatTheChapterHas() {
        EditorSelection s = new EditorSelection();
        s.selectAll(List.of("a", "b"));
        s.retain(List.of("b", "c"));
        assertEquals(List.of("b"), s.ids());
    }
}
