package com.kuronami.questtree.progress;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The part of a team record that belongs to one player: claimed rewards, the book flag, the recent list, and the
 * progress of {@code shared:false} nodes (completion, task counters, repeats, cooldowns). Shared values live in the
 * team's {@link PlayerProgress} and are read through {@link TeamProgress#view(java.util.UUID)}.
 */
public final class PlayerPart {

    public final Set<String> claimed = new HashSet<>();
    public final Set<String> claimedRewards = new HashSet<>();
    public final Deque<String> recent = new ArrayDeque<>();
    public final Set<String> completed = new HashSet<>();
    public final Map<String, Long> tasks = new HashMap<>();
    public final Map<String, Integer> repeats = new HashMap<>();
    public final Map<String, Long> cooldown = new HashMap<>();
    public boolean bookGiven;
    /** An admin stopped this player from taking rewards; tasks still count and completions still happen. */
    public boolean rewardsBlocked;

    public void event(String kind, String node, long time) {
        recent.addFirst(kind + "|" + node + "|" + time);
        while (recent.size() > PlayerProgress.RECENT_MAX) {
            recent.removeLast();
        }
    }

    public void copyFrom(PlayerPart other) {
        claimed.addAll(other.claimed);
        claimedRewards.addAll(other.claimedRewards);
        recent.clear();
        recent.addAll(other.recent);
        completed.addAll(other.completed);
        tasks.putAll(other.tasks);
        repeats.putAll(other.repeats);
        cooldown.putAll(other.cooldown);
        bookGiven |= other.bookGiven;
        rewardsBlocked |= other.rewardsBlocked;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.add("claimed", strings(claimed));
        o.add("claimed_rewards", strings(claimedRewards));
        o.add("recent", strings(recent));
        o.add("completed", strings(completed));
        o.add("tasks", longs(tasks));
        o.add("repeats", ints(repeats));
        o.add("cooldown", longs(cooldown));
        o.addProperty("book_given", bookGiven);
        o.addProperty("rewards_blocked", rewardsBlocked);
        return o;
    }

    public static PlayerPart fromJson(JsonObject o) {
        PlayerPart part = new PlayerPart();
        for (JsonElement e : arr(o, "claimed")) {
            part.claimed.add(e.getAsString());
        }
        for (JsonElement e : arr(o, "claimed_rewards")) {
            part.claimedRewards.add(e.getAsString());
        }
        for (JsonElement e : arr(o, "recent")) {
            part.recent.addLast(e.getAsString());
        }
        for (JsonElement e : arr(o, "completed")) {
            part.completed.add(e.getAsString());
        }
        for (Map.Entry<String, JsonElement> e : obj(o, "tasks").entrySet()) {
            part.tasks.put(e.getKey(), e.getValue().getAsLong());
        }
        for (Map.Entry<String, JsonElement> e : obj(o, "repeats").entrySet()) {
            part.repeats.put(e.getKey(), e.getValue().getAsInt());
        }
        for (Map.Entry<String, JsonElement> e : obj(o, "cooldown").entrySet()) {
            part.cooldown.put(e.getKey(), e.getValue().getAsLong());
        }
        part.rewardsBlocked = o.has("rewards_blocked") && o.get("rewards_blocked").isJsonPrimitive()
                && o.get("rewards_blocked").getAsBoolean();
        part.bookGiven = o.has("book_given") && o.get("book_given").isJsonPrimitive()
                && o.get("book_given").getAsBoolean();
        return part;
    }

    private static JsonArray strings(Iterable<String> it) {
        JsonArray a = new JsonArray();
        it.forEach(a::add);
        return a;
    }

    private static JsonArray arr(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray();
    }

    private static JsonObject obj(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : new JsonObject();
    }

    private static JsonObject longs(Map<String, Long> m) {
        JsonObject o = new JsonObject();
        m.forEach(o::addProperty);
        return o;
    }

    private static JsonObject ints(Map<String, Integer> m) {
        JsonObject o = new JsonObject();
        m.forEach(o::addProperty);
        return o;
    }
}
