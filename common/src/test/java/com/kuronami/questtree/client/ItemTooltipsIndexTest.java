package com.kuronami.questtree.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tooltip index must be rebuilt when an editor resync replaces the rules or the model (2026-10-22). */
class ItemTooltipsIndexTest {

    @Test
    void sameProgressRulesAndPinsReuseTheIndex() {
        Object progress = new Object();
        Object rules = new Object();
        assertTrue(IndexFreshness.same(progress, rules, 4, progress, rules, 4));
    }

    @Test
    void replacedRulesRebuildEvenWhenProgressIsTheSameObject() {
        Object progress = new Object();
        assertFalse(IndexFreshness.same(progress, new Object(), 4, progress, new Object(), 4),
                "a resync replaces the rules, so the old index may name a deleted task");
    }

    @Test
    void changedProgressOrPinsRebuild() {
        Object rules = new Object();
        assertFalse(IndexFreshness.same(new Object(), rules, 4, new Object(), rules, 4));
        assertFalse(IndexFreshness.same(new Object(), rules, 4, new Object(), rules, 5));
    }
}
