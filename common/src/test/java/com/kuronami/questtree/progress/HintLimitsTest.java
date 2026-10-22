package com.kuronami.questtree.progress;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The 2026-10-23 review fix: restriction hints must fit in one progress packet. */
class HintLimitsTest {

    private static final int PAYLOAD_MAX = 1000 * 1024;

    private static List<String> hints(int count, String text) {
        List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add("minecraft:item_" + i + "|" + text);
        }
        return out;
    }

    @Test
    void moreHintsThanThePacketAllowsAreDropped() {
        HintLimits.Result result = HintLimits.limit(hints(5000, "reason"), 1024, 0, PAYLOAD_MAX);
        assertEquals(HintLimits.MAX_HINTS, result.hints().size());
        assertEquals(5000 - HintLimits.MAX_HINTS, result.dropped());
        assertTrue(result.shortened() == 0);
    }

    @Test
    void oneHintLongerThanTheLimitIsCutInsteadOfDropped() {
        String longHint = "minecraft:stone|" + "あ".repeat(10_000);
        HintLimits.Result result = HintLimits.limit(List.of(longHint), 1024, 0, PAYLOAD_MAX);
        assertEquals(1, result.hints().size());
        assertEquals(1, result.shortened());
        assertTrue(result.hints().get(0).codePointCount(0, result.hints().get(0).length())
                <= HintLimits.MAX_HINT_CHARS);
    }

    @Test
    void hintsStopBeforeThePayloadBudgetIsUsedUp() {
        int dataBytes = 950 * 1024;
        int eventBytes = 20 * 1024;
        HintLimits.Result result = HintLimits.limit(hints(5000, "reason"), dataBytes, eventBytes, PAYLOAD_MAX);
        long budget = PAYLOAD_MAX - dataBytes - eventBytes - HintLimits.RESERVED_BYTES;
        long used = 0;
        for (String hint : result.hints()) {
            used += hint.getBytes(StandardCharsets.UTF_8).length + 5;
        }
        assertTrue(used <= budget, "the hints must leave room for the rest of the packet");
        assertTrue(result.dropped() > 0);
    }

    @Test
    void aFullDataSectionLeavesNoHints() {
        HintLimits.Result result = HintLimits.limit(hints(10, "reason"), PAYLOAD_MAX, 0, PAYLOAD_MAX);
        assertTrue(result.hints().isEmpty());
        assertEquals(10, result.dropped());
    }

    @Test
    void anEmptyListStaysEmpty() {
        assertEquals(List.of(), HintLimits.limit(List.of(), 0, 0, PAYLOAD_MAX).hints());
        assertEquals(List.of(), HintLimits.limit(null, 0, 0, PAYLOAD_MAX).hints());
    }
}
