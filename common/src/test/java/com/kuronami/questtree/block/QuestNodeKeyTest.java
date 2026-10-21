package com.kuronami.questtree.block;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The task block's saved key survives a save/load round trip and still reads the older key. */
class QuestNodeKeyTest {

    @Test
    void savedQuestSurvivesTheRoundTrip() {
        Map<String, String> saved = new HashMap<>();
        saved.put(QuestNodeKey.KEY, "t:q/node");
        assertEquals("t:q/node", QuestNodeKey.read(saved::containsKey, saved::get));
    }

    @Test
    void anOldBlockSavedUnderNodeStillReads() {
        Map<String, String> saved = new HashMap<>();
        saved.put(QuestNodeKey.LEGACY_KEY, "t:q/old");
        assertEquals("t:q/old", QuestNodeKey.read(saved::containsKey, saved::get));
    }

    @Test
    void theCurrentKeyWinsWhenBothArePresent() {
        Map<String, String> saved = new HashMap<>();
        saved.put(QuestNodeKey.KEY, "t:q/new");
        saved.put(QuestNodeKey.LEGACY_KEY, "t:q/old");
        assertEquals("t:q/new", QuestNodeKey.read(saved::containsKey, saved::get));
    }

    @Test
    void anEmptyBlockReadsAsEmpty() {
        Map<String, String> saved = new HashMap<>();
        assertEquals("", QuestNodeKey.read(saved::containsKey, saved::get));
    }
}
