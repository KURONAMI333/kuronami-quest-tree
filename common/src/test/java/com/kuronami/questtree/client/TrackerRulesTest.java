package com.kuronami.questtree.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackerRulesTest {

    @Test
    void autoPinOnlyShowsInTheAllMode() {
        assertTrue(TrackerRules.autoPin(true, true));
        assertFalse(TrackerRules.autoPin(true, false));
        assertFalse(TrackerRules.autoPin(false, true));
    }

    @Test
    void aCardForOneRowIsNeverEmpty() {
        TrackerRules.Size one = TrackerRules.card(10, new int[]{40}, new int[]{0});
        assertEquals(TrackerRules.ICON + TrackerRules.GAP + 40 + 2 * TrackerRules.PAD, one.width());
        assertEquals(10 * 2 + 2 * TrackerRules.PAD, one.height());
        assertTrue(one.width() > 0);
        assertTrue(one.height() > 0);
    }

    @Test
    void itemLinesAndRowGapsMakeTheCardTaller() {
        int one = TrackerRules.card(10, new int[]{40}, new int[]{0}).height();
        int withMaterials = TrackerRules.card(10, new int[]{40}, new int[]{2}).height();
        int twoRows = TrackerRules.card(10, new int[]{40, 80}, new int[]{0, 0}).height();
        assertEquals(one + 2 * (10 + 2), withMaterials);
        assertEquals(one + 10 * 2 + 2, twoRows);
        assertTrue(twoRows > one);
    }

    @Test
    void theLinePitchFollowsTheTextScaleAndNoRowsMakeAnEmptyCard() {
        assertEquals(10, TrackerRules.lineH(1f));
        assertEquals(12, TrackerRules.lineH(1.2f));
        assertEquals(10, TrackerRules.lineH(0.8f));
        TrackerRules.Size none = TrackerRules.card(TrackerRules.lineH(1f), new int[0], new int[0]);
        assertEquals(2 * TrackerRules.PAD, none.width());
        assertEquals(2 * TrackerRules.PAD, none.height());
    }
}
