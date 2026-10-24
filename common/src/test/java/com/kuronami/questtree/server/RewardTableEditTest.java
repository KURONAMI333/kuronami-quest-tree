package com.kuronami.questtree.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reward table items, empty tables and the export/import shape (2026-10-22). */
class RewardTableEditTest {

    private static JsonObject table(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void readsItemRewardsInOrderAndSkipsOtherKinds() {
        JsonObject table = table("""
                {"entries": [
                  {"weight": 2, "reward": {"type": "item", "item": {"id": "minecraft:stone", "count": 5}}},
                  {"weight": 1, "reward": {"type": "xp", "amount": 10}},
                  {"weight": 1, "reward": {"type": "item", "item": "minecraft:dirt"}}
                ]}""");
        assertEquals(List.of(new RewardTableEdit.Item("minecraft:stone", 5),
                new RewardTableEdit.Item("minecraft:dirt", 1)), RewardTableEdit.items(table));
    }

    @Test
    void aTableWithNoEntriesIsEmptyButAZeroWeightEntryIsNot() {
        assertTrue(RewardTableEdit.isEmpty(table("{}")));
        assertTrue(RewardTableEdit.isEmpty(table("{\"entries\": []}")));
        assertFalse(RewardTableEdit.isEmpty(table(
                "{\"entries\": [{\"weight\": 0, \"reward\": {\"type\": \"item\", \"item\": \"minecraft:stone\"}}]}")));
    }

    @Test
    void emptyIdsNamesOnlyEmptyTables() {
        JsonObject tables = table("""
                {"t:a": {"entries": []}, "t:b": {"entries": [{"reward": {"type": "item", "item": "minecraft:dirt"}}]},
                 "t:c": {}}""");
        assertEquals(List.of("t:a", "t:c"), RewardTableEdit.emptyIds(tables));
    }

    @Test
    void brokenEntriesKeepTheirPositionAndPayNothing() {
        JsonObject table = table("""
                {"entries": [
                  {"reward": {"type": "xp", "amount": 1}},
                  "not an object",
                  {"weight": "heavy", "reward": {"type": "xp", "amount": 2}},
                  {"weight": 2, "reward": "not an object"},
                  {"weight": 3, "reward": {"type": "xp", "amount": 3}}
                ]}""");
        List<RewardTableEdit.Entry> entries = RewardTableEdit.entries(table);
        assertEquals(5, entries.size(), "a choice keeps the index the client showed");
        assertEquals(1.0, entries.get(0).weight());
        assertNotNull(entries.get(0).reward());
        assertNull(entries.get(1).reward());
        assertNull(entries.get(2).reward(), "a weight that is not a number makes the entry unpayable");
        assertNull(entries.get(3).reward(), "a reward that is not an object makes the entry unpayable");
        assertEquals(3.0, entries.get(4).weight());
        assertNotNull(entries.get(4).reward());
        assertTrue(RewardTableEdit.entries(table("{\"entries\": 3}")).isEmpty(),
                "entries that are not an array yield no payable entries");
    }

    @Test
    void theWrittenTableIsTheExportShape() {
        JsonObject table = RewardTableEdit.table(List.of(new RewardTableEdit.Item("minecraft:stone", 3)));
        assertEquals(1, table.get("rolls").getAsInt());
        assertEquals(0, table.get("empty_weight").getAsInt());
        assertEquals(1.0, table.getAsJsonArray("entries").get(0).getAsJsonObject().get("weight").getAsDouble());
        JsonObject reward = table.getAsJsonArray("entries").get(0).getAsJsonObject()
                .getAsJsonObject("reward");
        assertEquals("item", reward.get("type").getAsString());
        assertEquals("minecraft:stone", reward.getAsJsonObject("item").get("id").getAsString());
        assertEquals(3, reward.getAsJsonObject("item").get("count").getAsInt());
        assertTrue(table.has("loot_crate"));
        // what was written reads back as the same items
        assertEquals(List.of(new RewardTableEdit.Item("minecraft:stone", 3)), RewardTableEdit.items(table));
    }
}
