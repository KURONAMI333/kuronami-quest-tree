package com.kuronami.questtree.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The five condition types (DATA_MODEL.md "条件") without a running game. */
class QuestConditionsTest {

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static final class Fake implements QuestConditions.Context {
        String stage = "";
        long score;
        boolean item;
        boolean advancement;
        int party;

        @Override
        public boolean hasStage(String stage) {
            return this.stage.equals(stage);
        }

        @Override
        public long score(String objective) {
            return "points".equals(objective) ? score : 0;
        }

        @Override
        public boolean hasItem(JsonObject condition) {
            return item;
        }

        @Override
        public boolean advancementDone(String advancement, String criterion) {
            return this.advancement && "minecraft:story/root".equals(advancement) && criterion.isEmpty();
        }

        @Override
        public int partySize() {
            return party;
        }
    }

    @Test
    void noListMeansOpen() {
        assertTrue(QuestConditions.allMet(json("{}"), new Fake()));
    }

    @Test
    void everyEntryMustHold() {
        Fake f = new Fake();
        f.stage = "chapter_one";
        f.score = 3;
        f.item = true;
        f.advancement = true;
        f.party = 2;
        JsonObject all = json("""
                {"conditions": [
                  {"type": "stage", "stage": "chapter_one"},
                  {"type": "score", "objective": "points", "min": 3},
                  {"type": "item", "item": "minecraft:stone", "count": 4},
                  {"type": "advancement", "advancement": "minecraft:story/root"},
                  {"type": "party_size", "min": 2}]}
                """);
        assertTrue(QuestConditions.allMet(all, f));
        f.party = 1;
        assertFalse(QuestConditions.allMet(all, f));
    }

    @Test
    void namespacedTypesAreAccepted() {
        Fake f = new Fake();
        f.stage = "s";
        assertTrue(QuestConditions.met(json("{\"type\": \"kuronami_quest_tree:stage\", \"stage\": \"s\"}"), f));
    }

    @Test
    void aMissingScoreObjectiveReadsAsZero() {
        Fake f = new Fake();
        f.score = 10;
        assertFalse(QuestConditions.met(json("{\"type\": \"score\", \"objective\": \"other\", \"min\": 1}"), f));
        assertTrue(QuestConditions.met(json("{\"type\": \"score\", \"objective\": \"points\", \"min\": 10}"), f));
    }

    @Test
    void unknownOrMalformedConditionsFailClosed() {
        Fake f = new Fake();
        assertFalse(QuestConditions.met(json("{\"type\": \"nope\"}"), f));
        assertFalse(QuestConditions.allMet(json("{\"conditions\": {}}"), f));
        assertFalse(QuestConditions.allMet(json("{\"conditions\": [1]}"), f));
    }
}
