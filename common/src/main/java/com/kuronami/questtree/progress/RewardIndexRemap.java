package com.kuronami.questtree.progress;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Moves reward claim records when the editor reorders, inserts or deletes rewards (2026-10-22 release review).
 * <p>
 * A claim is stored by index ({@code <node>#<index>}, or {@code <node>#<rank>#<index>} for pending, roll and skill
 * records), so changing the array would otherwise hand a claim to whatever reward took the old place. The mapping is
 * made from the reward JSON itself, which works for a move, an insert in the middle and a delete; identical rewards
 * are paired in order.
 */
public final class RewardIndexRemap {

    private RewardIndexRemap() {
    }

    /**
     * Old index to new index for every node whose reward list changed. A chapter or node missing from the new side is
     * left out: its records stay where they are, so restoring a deleted node finds them again.
     */
    public static Map<String, Map<Integer, Integer>> between(JsonObject oldChapters, JsonObject newChapters) {
        Map<String, Map<Integer, Integer>> out = new LinkedHashMap<>();
        if (oldChapters == null || newChapters == null) {
            return out;
        }
        for (Map.Entry<String, JsonElement> chapter : newChapters.entrySet()) {
            if (!chapter.getValue().isJsonObject() || !oldChapters.has(chapter.getKey())
                    || !oldChapters.get(chapter.getKey()).isJsonObject()) {
                continue;
            }
            JsonObject oldNodes = nodes(oldChapters.getAsJsonObject(chapter.getKey()));
            JsonObject newNodes = nodes(chapter.getValue().getAsJsonObject());
            for (Map.Entry<String, JsonElement> node : newNodes.entrySet()) {
                if (!node.getValue().isJsonObject() || !oldNodes.has(node.getKey())
                        || !oldNodes.get(node.getKey()).isJsonObject()) {
                    continue;
                }
                JsonArray before = rewards(oldNodes.getAsJsonObject(node.getKey()));
                JsonArray after = rewards(node.getValue().getAsJsonObject());
                if (before.equals(after)) {
                    continue;
                }
                out.put(chapter.getKey() + "/" + node.getKey(), pairing(before, after));
            }
        }
        return out;
    }

    /**
     * The key after the remap, or null when the reward it named was deleted. Keys of unchanged nodes pass through.
     */
    public static String apply(String key, Map<String, Map<Integer, Integer>> remaps) {
        if (key == null || remaps.isEmpty()) {
            return key;
        }
        int last = key.lastIndexOf('#');
        if (last < 0) {
            return key;
        }
        int first = key.indexOf('#');
        String node = key.substring(0, first);
        Map<Integer, Integer> mapping = remaps.get(node);
        if (mapping == null) {
            return key;
        }
        int index;
        try {
            index = Integer.parseInt(key.substring(last + 1));
        } catch (NumberFormatException ex) {
            return key;
        }
        Integer moved = mapping.get(index);
        return moved == null ? null : key.substring(0, last + 1) + moved;
    }

    /** Applies a remap to a set of claim keys in place; true when anything moved or was dropped. */
    public static boolean applyTo(Set<String> keys, Map<String, Map<Integer, Integer>> remaps) {
        if (remaps.isEmpty() || keys.isEmpty()) {
            return false;
        }
        List<String> replacements = new ArrayList<>(keys.size());
        for (String key : keys) {
            String moved = apply(key, remaps);
            if (moved != null) {
                replacements.add(moved);
            }
        }
        if (replacements.size() == keys.size() && replacements.containsAll(keys)) {
            return false;
        }
        keys.clear();
        keys.addAll(replacements);
        return true;
    }

    private static JsonObject nodes(JsonObject chapter) {
        return chapter.has("nodes") && chapter.get("nodes").isJsonObject()
                ? chapter.getAsJsonObject("nodes") : new JsonObject();
    }

    private static JsonArray rewards(JsonObject node) {
        return node.has("rewards") && node.get("rewards").isJsonArray()
                ? node.getAsJsonArray("rewards") : new JsonArray();
    }

    /** Pairs equal reward objects in order, so a duplicate at the end does not steal a claim from the front. */
    private static Map<Integer, Integer> pairing(JsonArray before, JsonArray after) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        boolean[] used = new boolean[before.size()];
        for (int to = 0; to < after.size(); to++) {
            for (int from = 0; from < before.size(); from++) {
                if (!used[from] && before.get(from).equals(after.get(to))) {
                    used[from] = true;
                    out.put(from, to);
                    break;
                }
            }
        }
        return out;
    }
}
