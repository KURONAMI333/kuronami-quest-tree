package com.kuronami.questtree.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The claimable count is recomputed only when the progress or the rules change (2026-10-22). */
class ProgressCountCacheTest {

    @Test
    void onePairIsCountedOnce() {
        ProgressCountCache cache = new ProgressCountCache();
        Object progress = new Object();
        Object rules = new Object();
        assertFalse(cache.valid(progress, rules));
        cache.store(progress, rules, 3);
        assertTrue(cache.valid(progress, rules));
        assertEquals(3, cache.value());
    }

    @Test
    void aNewProgressOrRulesInvalidatesTheCount() {
        ProgressCountCache cache = new ProgressCountCache();
        Object progress = new Object();
        Object rules = new Object();
        cache.store(progress, rules, 2);
        assertFalse(cache.valid(new Object(), rules));
        assertFalse(cache.valid(progress, new Object()));
    }

    @Test
    void loggingOutForgetsTheCount() {
        ProgressCountCache cache = new ProgressCountCache();
        Object progress = new Object();
        Object rules = new Object();
        cache.store(progress, rules, 5);
        cache.clear();
        assertFalse(cache.valid(progress, rules));
    }
}
