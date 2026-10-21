package com.kuronami.questtree.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackerRulesTest {

    @Test
    void autoPinOnlyShowsInTheAllMode() {
        assertTrue(TrackerRules.autoPin(true, true));
        assertFalse(TrackerRules.autoPin(true, false));
        assertFalse(TrackerRules.autoPin(false, true));
    }
}
