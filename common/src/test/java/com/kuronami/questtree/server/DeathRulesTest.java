package com.kuronami.questtree.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeathRulesTest {

    @Test
    void keepInventoryAndCancelledDeathsDoNotDropAnotherBook() {
        assertTrue(DeathRules.dropBook(true, false, false));
        assertFalse(DeathRules.dropBook(true, true, false));
        assertFalse(DeathRules.dropBook(true, false, true));
        assertFalse(DeathRules.dropBook(false, false, false));
    }
}
