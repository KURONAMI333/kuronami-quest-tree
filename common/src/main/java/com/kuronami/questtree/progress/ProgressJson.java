package com.kuronami.questtree.progress;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;

/** PlayerProgress to and from JSON (used for saving inside NBT and for syncing to the client). */
public final class ProgressJson {

    private ProgressJson() {
    }

    public static JsonObject write(PlayerProgress p) {
        JsonObject o = new JsonObject();
        o.add("completed", strings(p.completed));
        JsonObject completedAt = new JsonObject();
        p.completedAt.forEach(completedAt::addProperty);
        o.add("completed_at", completedAt);
        o.add("claimed", strings(p.claimed));
        // the client sees a team reward settled once by any member as claimed too, and the separate list lets the
        // details sheet say whether this player or a teammate took it (2026-10-21)
        java.util.Set<String> claimed = new java.util.LinkedHashSet<>(p.claimedRewards);
        claimed.addAll(p.teamClaimedRewards);
        o.add("claimed_rewards", strings(claimed));
        // the player's own claims stay separate from the team's, so a teammate's team reward is not "you claimed"
        o.add("own_claimed_rewards", strings(p.claimedRewards));
        o.add("team_claimed_rewards", strings(p.teamClaimedRewards));
        JsonObject tasks = new JsonObject();
        p.tasks.forEach(tasks::addProperty);
        o.add("tasks", tasks);
        o.add("taken", ints(p.taken));
        JsonObject xp = new JsonObject();
        p.xp.forEach(xp::addProperty);
        o.add("xp", xp);
        o.add("level", ints(p.level));
        o.add("spent", ints(p.spent));
        o.add("bonus", ints(p.bonus));
        o.add("exchange", ints(p.exchange));
        JsonObject chapterOpen = new JsonObject();
        p.chapterOpen.forEach(chapterOpen::addProperty);
        o.add("chapter_open", chapterOpen);
        o.add("recent", strings(p.recent));
        o.add("team_stages", strings(p.teamStages));
        o.add("stages", strings(p.stages));
        o.add("repeats", ints(p.repeats));
        JsonObject cd = new JsonObject();
        p.cooldown.forEach(cd::addProperty);
        o.add("cooldown", cd);
        o.addProperty("book_given", p.bookGiven());
        o.addProperty("rewards_blocked", p.rewardsBlocked());
        return o;
    }

    public static PlayerProgress read(JsonObject o) {
        PlayerProgress p = new PlayerProgress();
        for (JsonElement e : arr(o, "completed")) {
            p.completed.add(e.getAsString());
        }
        for (Map.Entry<String, JsonElement> e : obj(o, "completed_at").entrySet()) {
            if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isNumber()) {
                p.completedAt.put(e.getKey(), e.getValue().getAsLong());
            }
        }
        for (JsonElement e : arr(o, "claimed")) {
            p.claimed.add(e.getAsString());
        }
        // own_claimed_rewards is the current shape; claimed_rewards is the merged list older saves and clients have
        String ownKey = o.has("own_claimed_rewards") ? "own_claimed_rewards" : "claimed_rewards";
        for (JsonElement e : arr(o, ownKey)) {
            p.claimedRewards.add(e.getAsString());
        }
        for (JsonElement e : arr(o, "team_claimed_rewards")) {
            p.teamClaimedRewards.add(e.getAsString());
        }
        for (Map.Entry<String, JsonElement> e : obj(o, "tasks").entrySet()) {
            p.tasks.put(e.getKey(), e.getValue().getAsLong());
        }
        readInts(obj(o, "taken"), p.taken);
        for (Map.Entry<String, JsonElement> e : obj(o, "xp").entrySet()) {
            p.xp.put(e.getKey(), e.getValue().getAsDouble());
        }
        readInts(obj(o, "level"), p.level);
        readInts(obj(o, "spent"), p.spent);
        readInts(obj(o, "bonus"), p.bonus);
        readInts(obj(o, "exchange"), p.exchange);
        for (Map.Entry<String, JsonElement> e : obj(o, "chapter_open").entrySet()) {
            p.chapterOpen.put(e.getKey(), e.getValue().getAsBoolean());
        }
        for (JsonElement e : arr(o, "team_stages")) {
            p.teamStages.add(e.getAsString());
        }
        for (JsonElement e : arr(o, "stages")) {
            p.stages.add(e.getAsString());
        }
        readInts(obj(o, "repeats"), p.repeats);
        for (Map.Entry<String, JsonElement> e : obj(o, "cooldown").entrySet()) {
            p.cooldown.put(e.getKey(), e.getValue().getAsLong());
        }
        for (JsonElement e : arr(o, "recent")) {
            p.recent.addLast(e.getAsString());
        }
        p.setRewardsBlocked(o.has("rewards_blocked") && o.get("rewards_blocked").isJsonPrimitive()
                && o.get("rewards_blocked").getAsBoolean());
        p.setBookGiven(o.has("book_given") && o.get("book_given").isJsonPrimitive() && o.get("book_given").getAsBoolean());
        return p;
    }

    private static JsonArray strings(Iterable<String> it) {
        JsonArray a = new JsonArray();
        it.forEach(a::add);
        return a;
    }

    private static JsonObject ints(Map<String, Integer> m) {
        JsonObject o = new JsonObject();
        m.forEach(o::addProperty);
        return o;
    }

    private static void readInts(JsonObject o, Map<String, Integer> into) {
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            into.put(e.getKey(), e.getValue().getAsInt());
        }
    }

    private static JsonObject obj(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : new JsonObject();
    }

    private static JsonArray arr(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray();
    }
}
