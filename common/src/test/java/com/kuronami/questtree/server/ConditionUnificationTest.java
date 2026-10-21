package com.kuronami.questtree.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one condition gauge answers a built-in kind, a registered kind, a list mixing both, and an unknown kind the same
 * way wherever it is asked: the reward gate, the task gate and the observation write all read this verdict.
 */
class ConditionUnificationTest {

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static final class Fake implements QuestConditions.Context {
        boolean stage = true;
        long score = 10;
        boolean item = true;
        boolean advancement = true;
        int party = 2;

        @Override
        public boolean hasStage(String stage) {
            return this.stage;
        }

        @Override
        public long score(String objective) {
            return score;
        }

        @Override
        public boolean hasItem(JsonObject condition) {
            return item;
        }

        @Override
        public boolean advancementDone(String advancement, String criterion) {
            return this.advancement;
        }

        @Override
        public int partySize() {
            return party;
        }
    }

    private static final Map<String, Boolean> ADDON = Map.of("example:enabled", true);

    /** The same call {@code ProgressService.conditionsMet} makes: built-ins there, the registry for everything else. */
    private static boolean gate(JsonObject holder) {
        return QuestConditions.allMet(holder, new Fake(),
                condition -> ADDON.getOrDefault(typeOf(condition), false));
    }

    private static String typeOf(JsonObject condition) {
        return condition.has("type") && condition.get("type").isJsonPrimitive()
                ? condition.get("type").getAsString() : "";
    }

    private static boolean rewardTakes(JsonObject holder) {
        return gate(holder);
    }

    private static boolean taskCounts(JsonObject holder) {
        return gate(holder);
    }

    private static boolean observationWrites(JsonObject holder) {
        return ObservationWrite.writes(0, 1, gate(holder));
    }

    private static void assertEveryRoute(boolean expected, JsonObject holder) {
        assertTrue(expected == rewardTakes(holder), "reward: " + holder);
        assertTrue(expected == taskCounts(holder), "task: " + holder);
        assertTrue(expected == observationWrites(holder), "observation: " + holder);
    }

    @Test
    void builtInConditionsReachEveryRoute() {
        JsonObject met = json("""
                {"conditions": [
                  {"type": "stage", "stage": "chapter_one"},
                  {"type": "score", "objective": "points", "min": 10},
                  {"type": "item", "item": "minecraft:stone"},
                  {"type": "party_size", "min": 2}]}
                """);
        assertEveryRoute(true, met);
        JsonObject missed = json("{\"conditions\": [{\"type\": \"party_size\", \"min\": 3}]}");
        assertEveryRoute(false, missed);
    }

    @Test
    void registeredConditionsReachEveryRoute() {
        assertEveryRoute(true, json("{\"conditions\": [{\"type\": \"example:enabled\"}]}"));
        assertEveryRoute(false, json("{\"conditions\": [{\"type\": \"example:disabled\"}]}"));
    }

    @Test
    void aMixedListRequiresBothKinds() {
        JsonObject bothPass = json("""
                {"conditions": [
                  {"type": "stage", "stage": "chapter_one"},
                  {"type": "example:enabled"}]}
                """);
        assertEveryRoute(true, bothPass);
        JsonObject addonFails = json("""
                {"conditions": [
                  {"type": "stage", "stage": "chapter_one"},
                  {"type": "example:disabled"}]}
                """);
        assertEveryRoute(false, addonFails);
        JsonObject builtInFails = json("""
                {"conditions": [
                  {"type": "party_size", "min": 9},
                  {"type": "example:enabled"}]}
                """);
        assertEveryRoute(false, builtInFails);
    }

    @Test
    void anUnknownKindFailsClosedOnEveryRoute() {
        assertEveryRoute(false, json("{\"conditions\": [{\"type\": \"example:missing\"}]}"));
        assertFalse(QuestConditions.allMet(json("{\"conditions\": {}}"), new Fake()),
                "a malformed list cannot open a gate");
    }
}
