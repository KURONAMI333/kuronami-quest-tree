package com.kuronami.questtree.progress;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rules {@code break} and {@code place_block} share. */
class BlockTaskRulesTest {

    private static final Predicate<String> TAGS = Set.of("minecraft:logs")::contains;

    @Test
    void aPlacementStopsAtTheTarget() {
        long count = 0;
        for (int i = 0; i < 25; i++) {
            count = BlockTaskRules.next(count, 10);
        }
        assertEquals(10, count);
        assertEquals(1, BlockTaskRules.next(0, 1));
        assertEquals(10, BlockTaskRules.next(10, 10), "a placement after the target does not move it");
        assertEquals(12, BlockTaskRules.next(12, 10), "a count already above the target is not lowered");
    }

    @Test
    void blockSpecsMatchTheSameWayForBreakAndPlaceBlock() {
        assertTrue(BlockTaskRules.matches("", "minecraft:stone", TAGS), "an empty spec is any block");
        assertTrue(BlockTaskRules.matches("minecraft:stone", "minecraft:stone", TAGS));
        assertFalse(BlockTaskRules.matches("minecraft:dirt", "minecraft:stone", TAGS));
        assertTrue(BlockTaskRules.matches("#minecraft:logs", "minecraft:oak_log", TAGS));
        assertFalse(BlockTaskRules.matches("#minecraft:ores", "minecraft:oak_log", TAGS));
        assertFalse(BlockTaskRules.matches("minecraft:logs", "minecraft:oak_log", TAGS),
                "a bare tag name is an id, not a tag");
    }
}
