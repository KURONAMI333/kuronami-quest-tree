package com.kuronami.questtree.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kuronami.questtree.data.ProgressModel;
import com.kuronami.questtree.progress.PlayerProgress;
import com.kuronami.questtree.progress.ProgressRules;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The write behind a task bump judges no conditions (2026-10-22 review): a hand-in took the items before it writes,
 * so a condition that names them is already unmet and re-judging would drop the progress.
 */
class TaskWriteTest {

    /** The player has just handed the ten diamonds over, so the task's own item condition no longer holds. */
    private static final QuestConditions.Context EMPTY_HANDS = new QuestConditions.Context() {
        @Override
        public boolean hasStage(String stage) {
            return false;
        }

        @Override
        public long score(String objective) {
            return 0;
        }

        @Override
        public boolean hasItem(JsonObject condition) {
            return false;
        }

        @Override
        public boolean advancementDone(String advancement, String criterion) {
            return false;
        }

        @Override
        public int partySize() {
            return 1;
        }
    };

    private static ProgressModel.Node handIn() {
        JsonObject bundle = JsonParser.parseString("""
                {"chapters": {"t:q": {"id": "q", "nodes": {"a": {"id": "a",
                  "tasks": [{"type": "item", "count": 10, "consume": true,
                             "item": {"id": "minecraft:diamond", "count": 10},
                             "conditions": [{"type": "item", "item": {"id": "minecraft:diamond", "count": 10}}]}]}}}}}
                """).getAsJsonObject();
        return ProgressModel.of(bundle).nodes.get("t:q/a");
    }

    @Test
    void aHandInWhoseItemWasJustTakenStillRecordsProgress() {
        ProgressModel.Node n = handIn();
        JsonObject task = n.tasks.get(0);
        assertFalse(QuestConditions.allMet(task, EMPTY_HANDS), "the diamonds are gone, so the condition is closed");
        PlayerProgress p = new PlayerProgress();
        assertTrue(TaskWrite.records(p, n, 0, 10, ProgressRules.target(task)), "the write must not be gated again");
        assertEquals(10, p.task(n.key, 0));
    }

    @Test
    void theTargetIsAnnouncedOnlyOnce() {
        ProgressModel.Node n = handIn();
        long target = ProgressRules.target(n.tasks.get(0));
        PlayerProgress p = new PlayerProgress();
        assertFalse(TaskWrite.records(p, n, 0, 6, target), "a write below the target does not finish it");
        assertFalse(TaskWrite.records(p, n, 0, 8, target), "a later write below the target still does not finish it");
        assertTrue(TaskWrite.records(p, n, 0, target, target), "reaching the target announces it once");
        assertFalse(TaskWrite.records(p, n, 0, target, target), "a write that was already at the target does not");
    }
}
