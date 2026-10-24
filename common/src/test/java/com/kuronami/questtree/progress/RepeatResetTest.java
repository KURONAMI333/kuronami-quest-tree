package com.kuronami.questtree.progress;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kuronami.questtree.data.ProgressModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code repeat.reset}: a daily/weekly round opens at the next day or 7-day boundary instead of after a cooldown. */
class RepeatResetTest {

    private static ProgressModel model(String node) {
        return ProgressModel.of(JsonParser.parseString(
                "{\"chapters\": {\"t:a\": {\"nodes\": {\"n\": " + node + "}}}}").getAsJsonObject());
    }

    private static ProgressModel.Node node(ProgressModel model) {
        return model.chapters.get("t:a").nodes.get("n");
    }

    private static final String KILL = "\"tasks\": [{\"type\": \"kill\", \"entity\": \"minecraft:zombie\"}]";

    @Test
    void boundariesAreTheNextDayAndTheNextSevenDayBlock() {
        assertEquals(6L, ProgressRules.resetBoundary(5, "daily"));
        assertEquals(1L, ProgressRules.resetBoundary(0, "daily"));
        assertEquals(7L, ProgressRules.resetBoundary(5, "weekly"));
        assertEquals(7L, ProgressRules.resetBoundary(6, "weekly"));
        assertEquals(14L, ProgressRules.resetBoundary(8, "weekly"));
        assertEquals(14L, ProgressRules.resetBoundary(13, "weekly"));
        assertEquals(21L, ProgressRules.resetBoundary(14, "weekly"));
    }

    @Test
    void dailyRoundOpensOnlyAtTheNextDay() {
        ProgressModel model = model("{\"repeat\": {\"reset\": \"daily\"}, " + KILL + "}");
        ProgressModel.Node n = node(model);
        ProgressRules rules = new ProgressRules(model);
        PlayerProgress p = new PlayerProgress();
        p.completed.add(n.key);

        assertFalse(rules.repeatAfterClaim(p, n, 1_000L, 5), "the round is not cleared at once");
        assertEquals(6L, p.resetAt.get(n.key).longValue());
        assertFalse(p.repeats.containsKey(n.key));
        assertTrue(p.completed.contains(n.key));

        assertFalse(rules.sweepResets(p, model, 5), "nothing changes before the boundary");
        assertEquals(6L, p.resetAt.get(n.key).longValue());
        assertTrue(p.completed.contains(n.key));

        assertTrue(rules.sweepResets(p, model, 6), "the boundary clears the round");
        assertFalse(p.resetAt.containsKey(n.key));
        assertEquals(1, p.repeats.get(n.key).intValue());
        assertFalse(p.completed.contains(n.key), "the quest can be done again");
    }

    @Test
    void weeklyRoundWaitsForTheNextSevenDayBlock() {
        ProgressModel model = model("{\"repeat\": {\"reset\": \"weekly\"}, " + KILL + "}");
        ProgressModel.Node n = node(model);
        ProgressRules rules = new ProgressRules(model);
        PlayerProgress p = new PlayerProgress();
        p.completed.add(n.key);

        rules.repeatAfterClaim(p, n, 1_000L, 5);
        assertEquals(7L, p.resetAt.get(n.key).longValue());
        assertFalse(rules.sweepResets(p, model, 6));
        assertTrue(p.completed.contains(n.key));
        assertTrue(rules.sweepResets(p, model, 7));
        assertEquals(1, p.repeats.get(n.key).intValue());
        assertFalse(p.completed.contains(n.key));
    }

    @Test
    void maxStillCapsTheTotalCompletions() {
        ProgressModel model = model("{\"repeat\": {\"reset\": \"daily\", \"max\": 2}, " + KILL + "}");
        ProgressModel.Node n = node(model);
        ProgressRules rules = new ProgressRules(model);
        PlayerProgress p = new PlayerProgress();
        p.completed.add(n.key);

        assertFalse(rules.repeatAfterClaim(p, n, 1_000L, 5));
        assertTrue(rules.sweepResets(p, model, 6));
        assertEquals(1, p.repeats.get(n.key).intValue());

        p.completed.add(n.key);
        assertFalse(rules.repeatAfterClaim(p, n, 1_000L, 6), "max 2 refuses the third round");
        assertFalse(p.resetAt.containsKey(n.key));
        assertFalse(rules.sweepResets(p, model, 7));
        assertEquals(1, p.repeats.get(n.key).intValue());
        assertTrue(p.completed.contains(n.key), "the last round stays completed");
    }

    @Test
    void aCooldownBesideResetIsIgnored() {
        ProgressModel model = model("{\"repeat\": {\"reset\": \"daily\", \"cooldown\": 3600}, " + KILL + "}");
        ProgressModel.Node n = node(model);
        ProgressRules rules = new ProgressRules(model);
        PlayerProgress p = new PlayerProgress();

        assertFalse(rules.repeatAfterClaim(p, n, 1_000L, 5));
        assertEquals(6L, p.resetAt.get(n.key).longValue());
        assertFalse(p.cooldown.containsKey(n.key), "a cooldown is not recorded when reset is set");
        assertFalse(p.repeats.containsKey(n.key));
    }

    @Test
    void aCooldownOnlyRepeatIsUnchanged() {
        ProgressModel model = model("{\"repeat\": {\"cooldown\": 30}, " + KILL + "}");
        ProgressModel.Node n = node(model);
        ProgressRules rules = new ProgressRules(model);
        PlayerProgress p = new PlayerProgress();
        p.completed.add(n.key);

        assertTrue(rules.repeatAfterClaim(p, n, 1_000L, 5));
        assertEquals(31_000L, p.cooldown.get(n.key).longValue());
        assertEquals(1, p.repeats.get(n.key).intValue());
        assertFalse(p.resetAt.containsKey(n.key));
        assertFalse(p.completed.contains(n.key));
    }

    @Test
    void resetIsReadOnlyFromARepeatObject() {
        JsonObject plain = JsonParser.parseString("{\"repeat\": true, " + KILL + "}").getAsJsonObject();
        assertEquals("", ProgressRules.reset(plain));
        assertEquals("", ProgressRules.reset(JsonParser.parseString(
                "{\"repeat\": {\"reset\": \"monthly\"}}").getAsJsonObject()));
        assertEquals("", ProgressRules.reset(JsonParser.parseString(
                "{\"repeat\": {\"reset\": 3}}").getAsJsonObject()));
        assertEquals("daily", ProgressRules.reset(JsonParser.parseString(
                "{\"repeat\": {\"reset\": \"daily\"}}").getAsJsonObject()));

        ProgressModel model = model("{\"repeat\": true, " + KILL + "}");
        ProgressModel.Node n = node(model);
        PlayerProgress p = new PlayerProgress();
        assertTrue(new ProgressRules(model).repeatAfterClaim(p, n, 1_000L, 5));
        assertFalse(p.resetAt.containsKey(n.key));
    }

    @Test
    void aCompletionWithoutRewardsSchedulesTheReset() {
        ProgressModel model = model("{\"repeat\": {\"reset\": \"weekly\"}, " + KILL + "}");
        ProgressModel.Node n = node(model);
        PlayerProgress p = new PlayerProgress();
        p.completed.add(n.key);

        assertFalse(new ProgressRules(model).repeatAfterCompletion(p, n, 1_000L, 8));
        assertEquals(14L, p.resetAt.get(n.key).longValue());
        assertTrue(p.completed.contains(n.key));
    }

    @Test
    void sweepDropsNodesThatLeftTheModel() {
        ProgressModel model = model("{\"repeat\": {\"reset\": \"daily\"}, " + KILL + "}");
        ProgressRules rules = new ProgressRules(model);
        PlayerProgress p = new PlayerProgress();
        p.resetAt.put("t:a/gone", 0L);

        assertTrue(rules.sweepResets(p, model, 0));
        assertTrue(p.resetAt.isEmpty());
    }

    @Test
    void sweepDoesNotReopenANodeWhoseRepeatWasRemoved() {
        ProgressModel model = model("{\"repeat\": true, " + KILL + "}");
        ProgressRules rules = new ProgressRules(model);
        PlayerProgress p = new PlayerProgress();
        p.completed.add("t:a/n");
        p.resetAt.put("t:a/n", 0L);

        assertTrue(rules.sweepResets(p, model, 5));
        assertTrue(p.resetAt.isEmpty());
        assertTrue(p.completed.contains("t:a/n"), "the completed round stays");
        assertFalse(p.repeats.containsKey("t:a/n"));
    }

    @Test
    void anUnreadableMaxCountsAsUnlimited() {
        ProgressModel model = model("{\"repeat\": {\"reset\": \"daily\", \"max\": \"lots\"}, " + KILL + "}");
        ProgressModel.Node n = node(model);
        ProgressRules rules = new ProgressRules(model);
        PlayerProgress p = new PlayerProgress();
        p.completed.add(n.key);

        assertFalse(rules.repeatAfterClaim(p, n, 1_000L, 5));
        assertTrue(rules.sweepResets(p, model, 6));
        assertEquals(1, p.repeats.get(n.key).intValue());
    }
}
