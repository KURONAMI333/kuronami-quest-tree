package com.kuronami.questtree.progress;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code reach_day}: the world day counter is {@code getDayTime / 24000}, and day 0 is the first day. */
class ReachDayTest {

    @Test
    void dayZeroIsReachedAtOnce() {
        assertEquals(0L, ProgressRules.worldDay(0L));
        assertTrue(ProgressRules.dayReached(0L, 0));
        assertTrue(ProgressRules.dayReached(23999L, 0));
    }

    @Test
    void theDayIsReachedAtItsFirstTick() {
        long day = 30;
        assertFalse(ProgressRules.dayReached(day * 24000L - 1, day));
        assertTrue(ProgressRules.dayReached(day * 24000L, day));
        assertTrue(ProgressRules.dayReached(day * 24000L + 1, day));
    }

    @Test
    void aLaterDayKeepsTheEarlierOneReached() {
        long later = 45 * 24000L + 500;
        assertEquals(45L, ProgressRules.worldDay(later));
        assertTrue(ProgressRules.dayReached(later, 30));
        assertFalse(ProgressRules.dayReached(later, 46));
    }
}
