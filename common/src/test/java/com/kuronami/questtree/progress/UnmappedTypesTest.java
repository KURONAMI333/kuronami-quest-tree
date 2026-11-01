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
                      {"type": "example:money", "imported_unmapped": true, "_kqt_unhandled": true, "count": 5}]},
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
    void theServerWritesTheSyncKeyIntoTheCopyItSends() {
        JsonObject bundle = JsonParser.parseString("""
                {"chapters": {"t:c": {"nodes": {
                  "a": {"tasks": [{"type": "example:money", "imported_unmapped": true}]},
                  "b": {"tasks": [{"type": "checkmark"}]}}}}}
                """).getAsJsonObject();
        UnmappedTypes.markSkipped(bundle);
        JsonObject nodes = bundle.getAsJsonObject("chapters").getAsJsonObject("t:c").getAsJsonObject("nodes");
        JsonObject task = nodes.getAsJsonObject("a").getAsJsonArray("tasks").get(0).getAsJsonObject();
        assertTrue(task.get(UnmappedTypes.SYNC).getAsBoolean());
        assertFalse(nodes.getAsJsonObject("b").getAsJsonArray("tasks").get(0).getAsJsonObject().has(UnmappedTypes.SYNC),
                "a built-in task is not marked");
        // with the type registered on the server, the copy says so: a client without the mod must still show it
        assertTrue(QuestTreeApi.registerTaskTypeById(MONEY, context -> 0));
        try {
            UnmappedTypes.markSkipped(bundle);
            assertFalse(task.get(UnmappedTypes.SYNC).getAsBoolean());
        } finally {
            QuestTreeApi.unregisterTaskType(MONEY);
        }
    }

    @Test
    void anAddOnsOwnSkipFieldIsLeftAlone() {
        JsonObject bundle = JsonParser.parseString("""
                {"chapters": {"t:c": {"nodes": {"a": {"tasks": [
                  {"type": "example:money", "imported_unmapped": true, "skip": 7}]}}}}}
                """).getAsJsonObject();
        UnmappedTypes.markSkipped(bundle);
        JsonObject task = bundle.getAsJsonObject("chapters").getAsJsonObject("t:c").getAsJsonObject("nodes")
                .getAsJsonObject("a").getAsJsonArray("tasks").get(0).getAsJsonObject();
        assertEquals(7, task.get("skip").getAsInt());
        assertTrue(task.get(UnmappedTypes.SYNC).getAsBoolean());
        assertTrue(UnmappedTypes.taskSkipped(JsonParser.parseString(
                "{\"type\":\"example:money\",\"imported_unmapped\":true,\"skip\":false}").getAsJsonObject()));
    }

    @Test
    void stripRemovesTheSyncKeyFromAnywhereAndOnlyThat() {
        JsonObject o = JsonParser.parseString("""
                {"_kqt_unhandled": true, "skip": 1, "nodes": {"a": {"tasks": [
                  {"type": "x", "_kqt_unhandled": false, "deep": [{"_kqt_unhandled": true, "k": 2}]}]}}}
                """).getAsJsonObject();
        assertTrue(UnmappedTypes.strip(o));
        assertFalse(o.toString().contains(UnmappedTypes.SYNC));
        assertTrue(o.toString().contains("\"skip\":1") && o.toString().contains("\"k\":2"));
        assertFalse(UnmappedTypes.strip(o));
    }

    @Test
    void aStoredSyncKeyDoesNotFreezeTheServersDecision() {
        JsonObject bundle = JsonParser.parseString("""
                {"chapters": {"t:c": {"nodes": {"a": {"tasks": [
                  {"type": "example:money", "imported_unmapped": true, "_kqt_unhandled": true}]}}}}}
                """).getAsJsonObject();
        // what the server does with everything it loads, before the validator and the model see it
        UnmappedTypes.strip(bundle);
        ProgressModel server = ProgressModel.of(bundle);
        JsonObject task = server.chapters.get("t:c").nodes.get("a").tasks.get(0);
        assertTrue(UnmappedTypes.taskSkipped(task));
        assertTrue(QuestTreeApi.registerTaskTypeById(MONEY, context -> 0));
        try {
            assertFalse(UnmappedTypes.taskSkipped(task), "registering the type opens it again");
        } finally {
            QuestTreeApi.unregisterTaskType(MONEY);
        }
    }

    @Test
    void aBuiltInTypeKeepingTheMarkerIsNotSkipped() {
        JsonObject task = JsonParser.parseString(
                "{\"type\": \"item\", \"imported_unmapped\": true}").getAsJsonObject();
        JsonObject reward = JsonParser.parseString(
                "{\"type\": \"xp\", \"imported_unmapped\": true}").getAsJsonObject();
        assertFalse(UnmappedTypes.taskSkipped(task), "an author retyped the task to a built-in one");
        assertFalse(UnmappedTypes.rewardSkipped(reward));
        JsonObject bundle = JsonParser.parseString("""
                {"chapters": {"t:c": {"nodes": {"n": {
                  "tasks": [{"type": "item", "imported_unmapped": true}],
                  "rewards": [{"type": "xp", "imported_unmapped": true}]}}}}}
                """).getAsJsonObject();
        UnmappedTypes.markSkipped(bundle);
        JsonObject node = bundle.getAsJsonObject("chapters").getAsJsonObject("t:c").getAsJsonObject("nodes")
                .getAsJsonObject("n");
        assertFalse(node.getAsJsonArray("tasks").get(0).getAsJsonObject().get(UnmappedTypes.SYNC).getAsBoolean());
        assertFalse(node.getAsJsonArray("rewards").get(0).getAsJsonObject().get(UnmappedTypes.SYNC).getAsBoolean());
    }

    @Test
    void aNodeWhoseTasksAreAllSkippedDoesNotStayHidden() {
        ProgressModel m = ProgressModel.of(JsonParser.parseString("""
                {"chapters": {"v:c": {"nodes": {
                  "gated": {"visibility": {"invisible_until_tasks": 2}, "tasks": [
                      {"type": "example:money", "imported_unmapped": true},
                      {"type": "checkmark"}]}
                }}}}
                """).getAsJsonObject());
        ProgressRules r = new ProgressRules(m);
        PlayerProgress p = new PlayerProgress();
        ProgressModel.Node gated = m.nodes.get("v:c/gated");
        assertFalse(r.visible(p, gated), "the one counted task is not done yet");
        p.setTask(gated.key, 1, 1);
        assertTrue(r.visible(p, gated), "two were asked for but only one task counts");
    }

    @Test
    void theRegistryVersionMovesWhenATypeComesOrGoes() {
        long before = QuestTreeApi.registryVersion();
        assertTrue(QuestTreeApi.registerTaskTypeById("example:versioned", context -> 0));
        long registered = QuestTreeApi.registryVersion();
        assertTrue(registered != before);
        QuestTreeApi.unregisterTaskType("example:versioned");
        assertTrue(QuestTreeApi.registryVersion() != registered);
    }

    @Test
    void aTableEntryNobodyPaysDoesNotTakeARoll() {
        JsonObject table = JsonParser.parseString("""
                {"entries": [
                  {"weight": 5, "reward": {"type": "example:money", "imported_unmapped": true}},
                  {"weight": 1, "reward": {"type": "item", "item": "minecraft:stone"}}]}
                """).getAsJsonObject();
        var entries = com.kuronami.questtree.server.RewardTableEdit.entries(table);
        assertEquals(2, entries.size(), "the entry keeps its place, so a choice keeps the index the client showed");
        assertEquals(null, entries.get(0).reward());
        assertEquals(0, entries.get(0).weight());
        assertTrue(entries.get(1).reward() != null);
    }
}
