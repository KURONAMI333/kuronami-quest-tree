package com.kuronami.questtree.progress;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.Collection;
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
    public final Map<String, Long> completedAt = new HashMap<>();
    public final Map<String, Long> tasks = new HashMap<>();
    public final Map<String, Integer> repeats = new HashMap<>();
    public final Map<String, Long> cooldown = new HashMap<>();
    public final Map<String, Long> resetAt = new HashMap<>();
    /** Stages this player holds, including Pack Stages granted outside the quest tree. */
    public final Set<String> stages = new HashSet<>();
    /** Team stages already mirrored into this player's Pack Stages. */
    public final Set<String> teamStagesApplied = new HashSet<>();
    /** Rewards blocked by an admin, with how many times each is owed; paid when the block is lifted. */
    public final Map<String, Integer> pendingRewards = new HashMap<>();
    /** Rolls already made for a reward table, so a failed pay-out does not roll again: reward key → picked indexes. */
    public final Map<String, String> rewardRolls = new HashMap<>();
    /** Emergency items: when this player may use them again, and whether the wait message was already shown. */
    public long emergencyReadyAt;
    public boolean emergencyWaitNotice;
    public boolean bookGiven;
    /** An admin stopped this player from taking rewards; tasks still count and completions still happen. */
    public boolean rewardsBlocked;
    /** Skill pending keys dropped while loading an old save, for the loader to log (never saved). */
    public int droppedSkillPending;

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
        completedAt.putAll(other.completedAt);
        tasks.putAll(other.tasks);
        repeats.putAll(other.repeats);
        cooldown.putAll(other.cooldown);
        resetAt.putAll(other.resetAt);
        stages.addAll(other.stages);
        teamStagesApplied.addAll(other.teamStagesApplied);
        other.pendingRewards.forEach((k, v) -> pendingRewards.merge(k, v, Integer::sum));
        rewardRolls.putAll(other.rewardRolls);
        emergencyReadyAt = Math.max(emergencyReadyAt, other.emergencyReadyAt);
        emergencyWaitNotice |= other.emergencyWaitNotice;
        bookGiven |= other.bookGiven;
        rewardsBlocked |= other.rewardsBlocked;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.add("claimed", strings(claimed));
        o.add("claimed_rewards", strings(claimedRewards));
        o.add("recent", strings(recent));
        o.add("completed", strings(completed));
        o.add("completed_at", longs(completedAt));
        o.add("tasks", longs(tasks));
        o.add("repeats", ints(repeats));
        o.add("cooldown", longs(cooldown));
        o.add("reset_at", longs(resetAt));
        o.add("stages", strings(stages));
        o.add("team_stages_applied", strings(teamStagesApplied));
        o.add("pending_rewards", ints(pendingRewards));
        o.add("reward_rolls", textMap(rewardRolls));
        o.addProperty("emergency_ready_at", emergencyReadyAt);
        o.addProperty("emergency_wait_notice", emergencyWaitNotice);
        o.addProperty("book_given", bookGiven);
        o.addProperty("rewards_blocked", rewardsBlocked);
        return o;
    }

    public static PlayerPart fromJson(JsonObject o) {
        PlayerPart part = new PlayerPart();
        readStrings(arr(o, "claimed"), part.claimed);
        readStrings(arr(o, "claimed_rewards"), part.claimedRewards);
        readRecent(arr(o, "recent"), part.recent);
        readStrings(arr(o, "completed"), part.completed);
        for (Map.Entry<String, JsonElement> e : obj(o, "completed_at").entrySet()) {
            long at = PackNumbers.savedLong(e.getValue(), Long.MIN_VALUE);
            if (at >= 0) {
                part.completedAt.put(e.getKey(), at);
            }
        }
        for (Map.Entry<String, JsonElement> e : obj(o, "tasks").entrySet()) {
            part.tasks.put(e.getKey(), PackNumbers.savedLong(e.getValue(), 0L));
        }
        for (Map.Entry<String, JsonElement> e : obj(o, "repeats").entrySet()) {
            part.repeats.put(e.getKey(), PackNumbers.savedInt(e.getValue(), 0));
        }
        for (Map.Entry<String, JsonElement> e : obj(o, "cooldown").entrySet()) {
            part.cooldown.put(e.getKey(), PackNumbers.savedLong(e.getValue(), 0L));
        }
        for (Map.Entry<String, JsonElement> e : obj(o, "reset_at").entrySet()) {
            part.resetAt.put(e.getKey(), PackNumbers.savedLong(e.getValue(), 0L));
        }
        readStrings(arr(o, "stages"), part.stages);
        readStrings(arr(o, "team_stages_applied"), part.teamStagesApplied);
        if (o.has("pending_rewards") && o.get("pending_rewards").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : obj(o, "pending_rewards").entrySet()) {
                part.pendingRewards.put(e.getKey(), PackNumbers.savedInt(e.getValue(), 0));
            }
        } else {
            // saves before 2026-10-21 stored each pending reward once
            for (JsonElement e : arr(o, "pending_rewards")) {
                if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
                    part.pendingRewards.merge(e.getAsString(), 1, Integer::sum);
                }
            }
        }
        // skill rewards are never blocked any more; keys an older save kept are dropped instead of paid at login
        var pendingKeys = part.pendingRewards.keySet().iterator();
        while (pendingKeys.hasNext()) {
            if (RewardLedger.isSkillPending(pendingKeys.next())) {
                pendingKeys.remove();
                part.droppedSkillPending++;
            }
        }
        for (Map.Entry<String, JsonElement> e : obj(o, "reward_rolls").entrySet()) {
            JsonElement value = e.getValue();
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                part.rewardRolls.put(e.getKey(), value.getAsString());
            }
        }
        part.emergencyReadyAt = PackNumbers.savedLong(o.get("emergency_ready_at"), 0L);
        part.emergencyWaitNotice = PackNumbers.booleanOr(o.get("emergency_wait_notice"), false);
        part.rewardsBlocked = PackNumbers.booleanOr(o.get("rewards_blocked"), false);
        part.bookGiven = PackNumbers.booleanOr(o.get("book_given"), false);
        return part;
    }

    private static void readStrings(JsonArray a, Collection<String> into) {
        for (JsonElement e : a) {
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
                into.add(e.getAsString());
            }
        }
    }

    private static void readRecent(JsonArray a, Deque<String> into) {
        for (JsonElement e : a) {
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
                into.addLast(e.getAsString());
            }
        }
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

    private static JsonObject textMap(Map<String, String> m) {
        JsonObject o = new JsonObject();
        m.forEach(o::addProperty);
        return o;
    }
}
