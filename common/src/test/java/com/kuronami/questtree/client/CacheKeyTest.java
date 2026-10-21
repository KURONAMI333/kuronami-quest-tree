package com.kuronami.questtree.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CacheKeyTest {

    @Test
    void theSameObjectsGiveTheSameKey() {
        Object progress = new Object();
        Object rules = new Object();

        assertEquals(CacheKey.of(progress, rules, "basics"), CacheKey.of(progress, rules, "basics"));
    }

    @Test
    void aNewSyncGivesADifferentKeyEvenWhenTheOldObjectsCompareEqual() {
        Object progress = new Object();
        Object oldRules = new Object() {
            @Override
            public boolean equals(Object other) {
                return true;
            }

            @Override
            public int hashCode() {
                return 1;
            }
        };
        Object newRules = new Object() {
            @Override
            public boolean equals(Object other) {
                return true;
            }

            @Override
            public int hashCode() {
                return 1;
            }
        };

        assertNotEquals(CacheKey.of(progress, oldRules), CacheKey.of(progress, newRules));
    }
}
