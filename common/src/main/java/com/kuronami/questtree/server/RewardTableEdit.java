package com.kuronami.questtree.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The JSON side of FTB Quests' reward-table chest commands (2026-10-22): which tables are empty, what a table puts in
 * a chest, and the table made from a chest's items. The container and the editor stay in the command; this part runs
 * without the game.
 */
public final class RewardTableEdit {

    /** One item reward as a chest sees it: an id and a count (KQT item rewards carry no components). */
    public record Item(String id, int count) {
    }

    private RewardTableEdit() {
    }

    /** A table is empty when it has no entries at all; an entry with weight 0 still counts, as in FTB Quests. */
    public static boolean isEmpty(JsonObject table) {
        JsonElement entries = table.get("entries");
        return entries == null || !entries.isJsonArray() || entries.getAsJsonArray().isEmpty();
    }

    /** The item rewards of a table, in order. Other reward kinds are left out, as FTB Quests' export does. */
    public static List<Item> items(JsonObject table) {
        List<Item> items = new ArrayList<>();
        JsonElement entries = table.get("entries");
        if (entries == null || !entries.isJsonArray()) {
            return items;
        }
        for (JsonElement e : entries.getAsJsonArray()) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonElement reward = e.getAsJsonObject().get("reward");
            if (reward == null || !reward.isJsonObject()) {
                continue;
            }
            JsonObject rw = reward.getAsJsonObject();
            if (!"item".equals(str(rw, "type"))) {
                continue;
            }
            JsonElement it = rw.get("item");
            String id = "";
            int count = 1;
            if (it != null && it.isJsonPrimitive() && it.getAsJsonPrimitive().isString()) {
                id = it.getAsString();
            } else if (it != null && it.isJsonObject()) {
                JsonObject o = it.getAsJsonObject();
                id = str(o, "id");
                count = intOf(o, "count", 1);
            }
            if (!id.isEmpty() && count > 0) {
                items.add(new Item(id, count));
            }
        }
        return items;
    }

    /** A table whose entries are the items, each with weight 1.0 and the usual single roll. */
    public static JsonObject table(List<Item> items) {
        JsonObject table = new JsonObject();
        table.addProperty("rolls", 1);
        table.addProperty("empty_weight", 0);
        JsonArray entries = new JsonArray();
        for (Item item : items) {
            JsonObject entry = new JsonObject();
            entry.addProperty("weight", 1.0);
            JsonObject reward = new JsonObject();
            reward.addProperty("type", "item");
            JsonObject stack = new JsonObject();
            stack.addProperty("id", item.id());
            stack.addProperty("count", Math.max(1, item.count()));
            reward.add("item", stack);
            entry.add("reward", reward);
            entries.add(entry);
        }
        table.add("entries", entries);
        table.add("loot_crate", com.google.gson.JsonNull.INSTANCE);
        return table;
    }

    /** The ids of the empty tables in a bundle's {@code reward_tables}. */
    public static List<String> emptyIds(JsonObject tables) {
        List<String> ids = new ArrayList<>();
        for (String id : tables.keySet()) {
            if (tables.get(id).isJsonObject() && isEmpty(tables.getAsJsonObject(id))) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : "";
    }

    private static int intOf(JsonObject o, String key, int fallback) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsInt() : fallback;
    }
}
