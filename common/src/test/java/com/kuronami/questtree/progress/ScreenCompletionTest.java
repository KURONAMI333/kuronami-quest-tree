package com.kuronami.questtree.progress;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-23 review: an absent owner is completed by their login recheck, not by an online stand-in. */
class ScreenCompletionTest {

    @Test
    void onlyAnOnlineOwnerCompletesNow() {
        assertTrue(ScreenCompletion.shouldCompleteNow(true));
        assertFalse(ScreenCompletion.shouldCompleteNow(false));
    }
}
