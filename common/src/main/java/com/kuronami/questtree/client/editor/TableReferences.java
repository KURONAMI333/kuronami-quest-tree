package com.kuronami.questtree.client.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Which quests roll a reward table (2026-10-22, p6-editor). FTB Quests shows this list on the table's screen so an
 * author can see what a table is for before retitling or deleting it; the answers come from the book the client
 * already holds, so the server sends nothing extra.
 */
public final class TableReferences {

    private TableReferences() {
    }

    /** Node keys ({@code chapter/node}) whose rewards name {@code tableId}, in chapter and node order. */
    public static List<String> usedIn(JsonObject chapters, String tableId) {
        List<String> out = new ArrayList<>();
        if (chapters == null || tableId == null) {
            return out;
        }
        for (Map.Entry<String, JsonElement> chapter : chapters.entrySet()) {
            if (!chapter.getValue().isJsonObject()) {
                continue;
            }
            JsonElement nodes = chapter.getValue().getAsJsonObject().get("nodes");
            if (nodes == null || !nodes.isJsonObject()) {
                continue;
            }
            for (Map.Entry<String, JsonElement> node : nodes.getAsJsonObject().entrySet()) {
                if (!node.getValue().isJsonObject()) {
                    continue;
                }
                JsonElement rewards = node.getValue().getAsJsonObject().get("rewards");
                if (rewards == null || !rewards.isJsonArray()) {
                    continue;
                }
                for (JsonElement reward : rewards.getAsJsonArray()) {
                    if (reward.isJsonObject() && reward.getAsJsonObject().has("table")
                            && reward.getAsJsonObject().get("table").isJsonPrimitive()
                            && tableId.equals(reward.getAsJsonObject().get("table").getAsString())) {
                        out.add(chapter.getKey() + "/" + node.getKey());
                        break;
                    }
                }
            }
        }
        return out;
    }
}
