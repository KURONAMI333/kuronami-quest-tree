package com.kuronami.questtree.client.viewer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewerRefreshTest {

    @Test
    void aChangeWaitsForTheQuietWindow() {
        ViewerRefresh refresh = new ViewerRefresh();
        refresh.mark(1_000);

        assertFalse(refresh.due(1_000 + ViewerRefresh.QUIET_MS - 1));
        assertTrue(refresh.due(1_000 + ViewerRefresh.QUIET_MS));
    }

    @Test
    void anotherChangeRestartsTheWindow() {
        ViewerRefresh refresh = new ViewerRefresh();
        refresh.mark(1_000);
        refresh.mark(2_500);

        assertFalse(refresh.due(3_000));
        assertTrue(refresh.due(2_500 + ViewerRefresh.QUIET_MS));
    }

    @Test
    void aDoneReloadDoesNotFireAgain() {
        ViewerRefresh refresh = new ViewerRefresh();
        refresh.mark(0);
        assertTrue(refresh.due(ViewerRefresh.QUIET_MS));
        refresh.done();

        assertFalse(refresh.dirty());
        assertFalse(refresh.due(ViewerRefresh.QUIET_MS * 10));
    }

    @Test
    void resetForgetsAPendingChange() {
        ViewerRefresh refresh = new ViewerRefresh();
        refresh.mark(0);
        refresh.reset();

        assertFalse(refresh.dirty());
        assertFalse(refresh.due(ViewerRefresh.QUIET_MS));
    }
}
