package com.kuronami.questtree.progress;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * One player's progress, keyed by global node keys ({@code <ns>:<chapter>/<node>}). Plain data: saving to NBT and
 * syncing live elsewhere so the rules can be tested without the game.
 */
public final class PlayerProgress {

    /** Nodes whose tasks are all done (quests) — rewards may still be unclaimed. */
    public final Set<String> completed = new HashSet<>();
    /** Nodes whose rewards were claimed. */
    public final Set<String> claimed = new HashSet<>();
    /** Task counters, key {@code <node key>#<task index>}. */
    public final Map<String, Long> tasks = new HashMap<>();
    /** Skill nodes taken, with the number of ranks taken (1 for nodes without ranks). */
    public final Map<String, Integer> taken = new HashMap<>();
    /** Per chapter: experience inside the current level, level reached, points spent, extra points (bought, commands). */
    public final Map<String, Double> xp = new HashMap<>();
    public final Map<String, Integer> level = new HashMap<>();
    public final Map<String, Integer> spent = new HashMap<>();
    public final Map<String, Integer> bonus = new HashMap<>();
    /** Per skill chapter: how many times experience was exchanged for a point (the exchange level limit counts these). */
    public final Map<String, Integer> exchange = new HashMap<>();
    /** Repeatable quests: how often each was claimed, and until when (epoch millis) it rests before it opens again. */
    public final Map<String, Integer> repeats = new HashMap<>();
    public final Map<String, Long> cooldown = new HashMap<>();
    /** Latest events for the "Recent" list, newest first: {@code kind|node key|epoch millis}. */
    public final Deque<String> recent = new ArrayDeque<>();

    public static final int RECENT_MAX = 20;

    public long task(String node, int index) {
        return tasks.getOrDefault(node + "#" + index, 0L);
    }

    public void setTask(String node, int index, long value) {
        tasks.put(node + "#" + index, value);
    }

    /** Forgets a node's own progress (tasks, completion, claim); used by repeats and resets. */
    public void clearNode(String node) {
        completed.remove(node);
        claimed.remove(node);
        tasks.keySet().removeIf(k -> k.startsWith(node + "#"));
    }

    public void event(String kind, String node, long time) {
        recent.addFirst(kind + "|" + node + "|" + time);
        while (recent.size() > RECENT_MAX) {
            recent.removeLast();
        }
    }
}
