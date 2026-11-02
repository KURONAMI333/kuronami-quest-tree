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

    @Test
    void aSingleTaskQuestDoesNotSayItsCountTwice() {
        assertFalse(TrackerRules.questCountShown(1, "0/1", java.util.List.of("0/1")));
    }

    @Test
    void theQuestCountStaysWhenTheNumbersDifferOrThereIsNoItemLine() {
        // the quest counts what was handed in, the item line what the inventory holds
        assertTrue(TrackerRules.questCountShown(1, "0/8", java.util.List.of("3/8")));
        assertTrue(TrackerRules.questCountShown(1, "0/1", java.util.List.of()));
        // several tasks: the quest line is the share done, not one task's count
        assertTrue(TrackerRules.questCountShown(2, "0/1", java.util.List.of("0/1")));
        assertTrue(TrackerRules.questCountShown(2, "0/1", java.util.List.of("0/1", "0/4")));
    }

    @Test
    void aRowWithoutItsCountLineIsAsTallAsTheIcon() {
        assertEquals(20, TrackerRules.rowHeight(10, true));
        assertEquals(TrackerRules.ICON, TrackerRules.rowHeight(10, false));
        assertEquals(20, TrackerRules.rowHeight(20, false));
        int full = TrackerRules.card(10, new int[]{40}, new int[]{1}).height();
        int trimmed = TrackerRules.card(10, new int[]{40}, new int[]{1}, new boolean[]{false}).height();
        assertEquals(full - 4, trimmed);
    }
}
