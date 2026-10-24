package com.kuronami.questtree.progress;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PityRulesTest {

    private static JsonObject table(String pity) {
        JsonObject table = new JsonObject();
        table.addProperty("rolls", 1);
        table.addProperty("empty_weight", 0);
        table.add("pity", com.google.gson.JsonParser.parseString(pity));
        return table;
    }

    @Test
    void theCeilingTakesTheEntryWithoutRollingAndStartsOver() {
        PityRules.Settings settings = new PityRules.Settings(3, 1);
        AtomicInteger calls = new AtomicInteger();
        PityRules.Result result = PityRules.roll(List.of(1.0, 1.0), 0, true, 1, 2, settings, () -> {
            calls.incrementAndGet();
            return 0.99;
        });
        assertEquals(0, calls.get(), "the ceiling must not use the random source");
        assertEquals(List.of(1), result.picks());
        assertEquals(0, result.misses());
    }

    @Test
    void aRollThatTakesTheEntryStartsTheCountOver() {
        PityRules.Settings settings = new PityRules.Settings(5, 1);
        // 0.9 of 2.0 lands on the second entry
        PityRules.Result result = PityRules.roll(List.of(1.0, 1.0), 0, true, 1, 3, settings, () -> 0.9);
        assertEquals(List.of(1), result.picks());
        assertEquals(0, result.misses());
    }

    @Test
    void aMissCountsAndAnEmptyPickCountsToo() {
        PityRules.Settings settings = new PityRules.Settings(5, 1);
        PityRules.Result miss = PityRules.roll(List.of(1.0, 1.0), 0, true, 1, 1, settings, () -> 0.1);
        assertEquals(List.of(0), miss.picks());
        assertEquals(2, miss.misses());
        PityRules.Result empty = PityRules.roll(List.of(1.0, 1.0), 3.0, true, 1, 1, settings, () -> 0.1);
        assertEquals(List.of(), empty.picks());
        assertEquals(2, empty.misses());
    }

    @Test
    void weightZeroEntriesAreGrantedOutsideTheRollAndDoNotCount() {
        PityRules.Settings settings = new PityRules.Settings(5, 2);
        PityRules.Result result = PityRules.roll(List.of(0.0, 1.0, 1.0), 0, true, 1, 0, settings, () -> 0.1);
        assertEquals(List.of(0, 1), result.picks());
        assertEquals(1, result.misses());
    }

    @Test
    void severalRollsCanCrossTheCeilingInTheMiddle() {
        PityRules.Settings settings = new PityRules.Settings(2, 1);
        PityRules.Result result = PityRules.roll(List.of(1.0, 1.0), 0, true, 3, 0, settings, () -> 0.1);
        // first roll misses, second reaches the ceiling, third rolls normally again
        assertEquals(List.of(0, 1, 0), result.picks());
        assertEquals(1, result.misses());
    }

    @Test
    void theCeilingOfOneTakesTheEntryEveryRoll() {
        PityRules.Settings settings = new PityRules.Settings(1, 1);
        PityRules.Result result = PityRules.roll(List.of(1.0, 1.0), 0, true, 3, 0, settings, () -> 0.1);
        assertEquals(List.of(1, 1, 1), result.picks());
        assertEquals(0, result.misses());
    }

    @Test
    void aStoredCountAtOrPastTheCeilingTakesTheEntryOnceThenStartsOver() {
        PityRules.Settings settings = new PityRules.Settings(3, 1);
        PityRules.Result result = PityRules.roll(List.of(1.0, 1.0), 0, true, 2, 9, settings, () -> 0.1);
        assertEquals(List.of(1, 0), result.picks());
        assertEquals(1, result.misses());
    }

    @Test
    void theEntryDefaultsToTheSmallestWeightAboveZero() {
        PityRules.Settings settings = PityRules.settings(table("{\"after\": 10}"), List.of(9.0, 1.0, 3.0));
        assertEquals(1, settings.entry());
        assertEquals(10, settings.after());
        assertEquals(0, PityRules.rarest(List.of(2.0, 2.0)), "a tie takes the first one");
    }

    @Test
    void anEntryThatIsAlwaysGrantedIsNotACeiling() {
        assertNull(PityRules.settings(table("{\"after\": 10, \"entry\": 0}"), List.of(0.0, 1.0)));
        assertNull(PityRules.settings(table("{\"after\": 10}"), List.of(0.0, 0.0)));
    }

    @Test
    void aShapeTheRollCannotUseMeansNoCeiling() {
        assertNull(PityRules.settings(table("{\"after\": 0}"), List.of(1.0, 1.0)));
        assertNull(PityRules.settings(table("{\"after\": 3, \"entry\": 2}"), List.of(1.0, 1.0)));
        assertNull(PityRules.settings(table("{\"after\": \"3\"}"), List.of(1.0, 1.0)));
        assertNull(PityRules.settings(table("{\"entry\": 1}"), List.of(1.0, 1.0)));
        assertNull(PityRules.settings(new JsonObject(), List.of(1.0)));
    }

    @Test
    void nothingToRollLeavesTheCountAlone() {
        PityRules.Result result = PityRules.roll(List.of(0.0), 0, true, 5, 4, new PityRules.Settings(5, 0),
                () -> 0.5);
        assertEquals(List.of(0), result.picks(), "a weight-0 entry is still granted");
        assertEquals(4, result.misses());
    }

    @Test
    void theCountSurvivesASaveAndOldSavesHaveNone() {
        PlayerPart part = new PlayerPart();
        part.pity.put("mypack:crate", 7);
        PlayerPart loaded = PlayerPart.fromJson(part.toJson());
        assertEquals(7, loaded.pity.get("mypack:crate"));
        assertTrue(PlayerPart.fromJson(new JsonObject()).pity.isEmpty());
    }
}
