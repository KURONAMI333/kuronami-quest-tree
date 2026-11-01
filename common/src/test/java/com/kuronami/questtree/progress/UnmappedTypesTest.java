package com.kuronami.questtree.progress;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kuronami.questtree.api.QuestTreeApi;
import com.kuronami.questtree.data.ProgressModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A task or reward an import kept although nothing here handles its type (2026-11-01): the quest stays playable while
 * the type is not registered, and the entry counts like any other once a mod registers it.
 */
class UnmappedTypesTest {

    private static final String MONEY = "example:money";

    private static ProgressModel model() {
        return ProgressModel.of(JsonParser.parseString("""
                {"chapters": {"t:c": {"nodes": {
                  "alone": {"tasks": [{"type": "example:money", "imported_unmapped": true, "count": 5}]},
                  "mixed": {"tasks": [
                      {"type": "example:money", "imported_unmapped": true, "count": 5},
                      {"type": "checkmark"}]},
                  "ordered": {"sequential_tasks": true, "tasks": [
                      {"type": "example:money", "imported_unmapped": true, "count": 5},
                      {"type": "checkmark"}]},
                  "synced": {"tasks": [
                      {"type": "example:money", "imported_unmapped": true, "skip": true, "count": 5}]},
                  "paid": {"rewards": [{"type": "example:money", "imported_unmapped": true}]}
                }}}}
                """).getAsJsonObject());
    }

    @Test
    void anUnregisteredTypeDoesNotHoldTheQuestBack() {
        ProgressRules r = new ProgressRules(model());
        PlayerProgress p = new PlayerProgress();
        ProgressModel.Node alone = r.model().nodes.get("t:c/alone");
        assertTrue(r.tasksDone(p, alone), "a quest whose only task waits for a missing mod still finishes");
        assertEquals(0, r.fraction(p, alone), 1e-9, "it adds nothing to the share done");
        assertFalse(r.taskOpen(p, r.model().nodes.get("t:c/ordered"), 0), "the skipped task itself never opens");
        assertTrue(r.taskOpen(p, r.model().nodes.get("t:c/ordered"), 1), "it does not hold the task after it back");
        assertFalse(r.tasksDone(p, r.model().nodes.get("t:c/mixed")), "a task next to it still has to be done");
    }

    @Test
    void aRegisteredTypeCountsLikeAnyOther() {
        assertTrue(QuestTreeApi.registerTaskTypeById(MONEY, context -> 0));
        try {
            ProgressRules r = new ProgressRules(model());
            PlayerProgress p = new PlayerProgress();
            ProgressModel.Node alone = r.model().nodes.get("t:c/alone");
            assertFalse(r.tasksDone(p, alone));
            assertTrue(r.taskOpen(p, r.model().nodes.get("t:c/ordered"), 0));
            p.setTask(alone.key, 0, 5);
            assertTrue(r.tasksDone(p, alone));
            assertEquals(1.0, r.fraction(p, alone), 1e-9);
        } finally {
            QuestTreeApi.unregisterTaskType(MONEY);
        }
    }

    @Test
    void anUnregisteredRewardDoesNotHoldTheClaim() {
        ProgressRules r = new ProgressRules(model());
        PlayerProgress p = new PlayerProgress();
        ProgressModel.Node paid = r.model().nodes.get("t:c/paid");
        assertTrue(ProgressRules.allRewardsClaimed(p, paid));
        assertTrue(QuestTreeApi.registerRewardTypeById(MONEY, context -> {
        }));
        try {
            assertFalse(ProgressRules.allRewardsClaimed(p, paid));
        } finally {
            QuestTreeApi.unregisterRewardType(MONEY);
        }
    }

    @Test
    void theServerWritesSkipIntoTheCopyItSends() {
        JsonObject bundle = JsonParser.parseString("""
                {"chapters": {"t:c": {"nodes": {
                  "a": {"tasks": [{"type": "example:money", "imported_unmapped": true}]},
                  "b": {"tasks": [{"type": "checkmark"}]}}}}}
                """).getAsJsonObject();
        UnmappedTypes.markSkipped(bundle);
        JsonObject nodes = bundle.getAsJsonObject("chapters").getAsJsonObject("t:c").getAsJsonObject("nodes");
        JsonObject task = nodes.getAsJsonObject("a").getAsJsonArray("tasks").get(0).getAsJsonObject();
        assertTrue(task.get("skip").getAsBoolean());
        assertFalse(nodes.getAsJsonObject("b").getAsJsonArray("tasks").get(0).getAsJsonObject().has("skip"),
                "a built-in task is not marked");
        // with the type registered on the server, the copy says so: a client without the mod must still show it
        assertTrue(QuestTreeApi.registerTaskTypeById(MONEY, context -> 0));
        try {
            UnmappedTypes.markSkipped(bundle);
            assertFalse(task.get("skip").getAsBoolean());
        } finally {
            QuestTreeApi.unregisterTaskType(MONEY);
        }
    }
}
