package com.kuronami.questtree.progress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The screen-action buckets (2026-10-22 release review). */
class ActionRateLimiterTest {

    private final UUID player = UUID.randomUUID();

    @AfterEach
    void clear() {
        ActionRateLimiter.clear();
    }

    @Test
    void ordinaryActionsAllowTwentyPerSecond() {
        for (int i = 0; i < ActionRateLimiter.ACTIONS_PER_SECOND; i++) {
            assertTrue(ActionRateLimiter.allow(player, false, 1_000L), "action " + i);
        }
        assertFalse(ActionRateLimiter.allow(player, false, 1_000L));
    }

    @Test
    void ordinaryActionsRefillWithTime() {
        for (int i = 0; i < ActionRateLimiter.ACTIONS_PER_SECOND; i++) {
            ActionRateLimiter.allow(player, false, 1_000L);
        }
        assertFalse(ActionRateLimiter.allow(player, false, 1_000L));
        assertTrue(ActionRateLimiter.allow(player, false, 1_050L));
    }

    @Test
    void partyActionsAreSeparateAndSlower() {
        assertTrue(ActionRateLimiter.allow(player, true, 1_000L));
        assertTrue(ActionRateLimiter.allow(player, true, 1_000L));
        assertFalse(ActionRateLimiter.allow(player, true, 1_000L));
        // the party bucket being full must not stop ordinary actions
        for (int i = 0; i < ActionRateLimiter.ACTIONS_PER_SECOND; i++) {
            assertTrue(ActionRateLimiter.allow(player, false, 1_000L), "ordinary " + i);
        }
    }

    @Test
    void anotherPlayerHasTheirOwnBuckets() {
        for (int i = 0; i < ActionRateLimiter.ACTIONS_PER_SECOND; i++) {
            ActionRateLimiter.allow(player, false, 1_000L);
        }
        assertFalse(ActionRateLimiter.allow(player, false, 1_000L));
        assertTrue(ActionRateLimiter.allow(UUID.randomUUID(), false, 1_000L));
    }
}
