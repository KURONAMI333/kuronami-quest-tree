package com.kuronami.questtree.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A failing reward is logged on its first claim only. */
class RewardAttemptsTest {

    @Test
    void logsOncePerReward() {
        RewardAttempts attempts = new RewardAttempts();
        assertTrue(attempts.first("chapter:quest#0"));
        assertFalse(attempts.first("chapter:quest#0"));
        assertTrue(attempts.first("chapter:quest#1"), "another reward of the same quest still gets its own line");
    }
}
