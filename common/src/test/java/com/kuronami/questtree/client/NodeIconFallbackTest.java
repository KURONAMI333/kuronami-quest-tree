package com.kuronami.questtree.client;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeIconFallbackTest {

    @Test
    void aCheckmarkTaskStandsForACheck() {
        assertEquals("tick", NodeIconFallback.symbol(false, false, "checkmark"));
    }

    @Test
    void otherTasksWithoutAnItemGetTheGenericMark() {
        assertEquals("gear", NodeIconFallback.symbol(false, false, "custom"));
        assertEquals("gear", NodeIconFallback.symbol(false, false, "item"));
    }

    @Test
    void nothingIsAddedWhenSomethingElseAlreadyShows() {
        assertNull(NodeIconFallback.symbol(true, false, "checkmark"));
        assertNull(NodeIconFallback.symbol(false, true, "checkmark"));
        // a node with no tasks (a skill) keeps what it had
        assertNull(NodeIconFallback.symbol(false, false, null));
    }

    @Test
    void theSymbolsAreTexturesAResourcePackCanReplace() {
        for (String name : new String[]{NodeIconFallback.CHECK, NodeIconFallback.OTHER}) {
            Path file = Path.of("src/main/resources/assets/kuronami_quest_tree/textures/gui/icon/" + name + ".png");
            assertTrue(Files.exists(file), file.toString());
        }
    }
}
