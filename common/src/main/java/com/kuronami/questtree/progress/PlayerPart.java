package com.kuronami.questtree.progress;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * The part of a team record that belongs to one player: what they claimed, the book flag, and the recent list.
 * Shared values (completed nodes, task counters, skills, experience, repeats) live in the team's
 * {@link PlayerProgress} and are read through {@link TeamProgress#view(java.util.UUID)}.
 */
public final class PlayerPart {

    public final Set<String> claimed = new HashSet<>();
    public final Set<String> claimedRewards = new HashSet<>();
    public final Deque<String> recent = new ArrayDeque<>();
    public boolean bookGiven;

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
        bookGiven |= other.bookGiven;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.add("claimed", strings(claimed));
        o.add("claimed_rewards", strings(claimedRewards));
        o.add("recent", strings(recent));
        o.addProperty("book_given", bookGiven);
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
}
