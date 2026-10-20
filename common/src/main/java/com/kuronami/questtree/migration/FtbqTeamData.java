package com.kuronami.questtree.migration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * One FTB Quests team file ({@code <world>/ftbquests/<team uuid>.snbt}) as plain data (2026-10-20). Ids are the
 * unsigned numbers from {@link FtbqIds}; {@code completed} and {@code started} hold quest ids and task ids (and chapter
 * ids) mixed, because FTB Quests writes every completed object into the same map.
 */
public final class FtbqTeamData {

    /** A claimed reward: who claimed it ({@link FtbqIds#NIL} for a team reward), which reward, and when. */
    public record Claim(UUID player, long reward, long time) {
    }

    public final UUID id;
    public final String name;
    public final Map<Long, Long> taskProgress = new LinkedHashMap<>();
    public final Map<Long, Long> started = new LinkedHashMap<>();
    public final Map<Long, Long> completed = new LinkedHashMap<>();
    public final Map<Long, Long> repeatable = new LinkedHashMap<>();
    public final Map<Long, Integer> completionCount = new LinkedHashMap<>();
    public final List<Claim> claims = new ArrayList<>();
    /** Keys that did not parse as an id or claim; reported, never guessed. */
    public final List<String> badKeys = new ArrayList<>();

    private FtbqTeamData(UUID id, String name) {
        this.id = id;
        this.name = name;
    }

    /** @throws IllegalArgumentException when the file has no readable team uuid */
    public static FtbqTeamData parse(JsonObject o) {
        if (!o.has("uuid") || !o.get("uuid").isJsonPrimitive()) {
            throw new IllegalArgumentException("no uuid");
        }
        FtbqTeamData d = new FtbqTeamData(FtbqIds.uuid(o.get("uuid").getAsString()),
                o.has("name") && o.get("name").isJsonPrimitive() ? o.get("name").getAsString() : "");
        longs(o, "task_progress", d.taskProgress, d);
        longs(o, "started", d.started, d);
        longs(o, "completed", d.completed, d);
        longs(o, "repeatable", d.repeatable, d);
        JsonObject counts = obj(o, "completion_count");
        for (Map.Entry<String, JsonElement> e : counts.entrySet()) {
            OptionalLong id = FtbqIds.parse(e.getKey());
            if (id.isPresent() && e.getValue().isJsonPrimitive()) {
                d.completionCount.put(id.getAsLong(), e.getValue().getAsInt());
            } else {
                d.badKeys.add("completion_count." + e.getKey());
            }
        }
        for (Map.Entry<String, JsonElement> e : obj(o, "claimed_rewards").entrySet()) {
            String key = e.getKey();
            try {
                // "<undashed uuid>:<hex reward id>"
                int colon = key.indexOf(':');
                UUID player = FtbqIds.uuid(key.substring(0, colon));
                OptionalLong reward = FtbqIds.parse(key.substring(colon + 1));
                if (reward.isEmpty()) {
                    throw new IllegalArgumentException(key);
                }
                d.claims.add(new Claim(player, reward.getAsLong(), e.getValue().getAsLong()));
            } catch (RuntimeException ex) {
                d.badKeys.add("claimed_rewards." + key);
            }
        }
        return d;
    }

    private static void longs(JsonObject o, String field, Map<Long, Long> into, FtbqTeamData d) {
        for (Map.Entry<String, JsonElement> e : obj(o, field).entrySet()) {
            OptionalLong id = FtbqIds.parse(e.getKey());
            if (id.isPresent() && e.getValue().isJsonPrimitive()) {
                into.put(id.getAsLong(), e.getValue().getAsLong());
            } else {
                d.badKeys.add(field + "." + e.getKey());
            }
        }
    }

    private static JsonObject obj(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : new JsonObject();
    }
}
