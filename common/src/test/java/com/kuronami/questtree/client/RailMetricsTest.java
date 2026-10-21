package com.kuronami.questtree.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RailMetricsTest {

    @Test
    void wrappedRowsKeepTheClickBelowThemOnTheSameChapter() {
        int[] heights = {14, 26, 14, 26};
        assertEquals(0, RailMetrics.rowAt(heights, 13));
        assertEquals(1, RailMetrics.rowAt(heights, 14));
        assertEquals(1, RailMetrics.rowAt(heights, 39));
        assertEquals(2, RailMetrics.rowAt(heights, 40));
        assertEquals(3, RailMetrics.rowAt(heights, 54));
        assertEquals(-1, RailMetrics.rowAt(heights, 80));
    }
}
