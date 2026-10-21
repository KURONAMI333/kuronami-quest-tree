package com.kuronami.questtree.progress;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Progress of one owner (a team id, or a player's UUID when solo), keyed by global node keys
 * ({@code <ns>:<chapter>/<node>}). Plain data: saving to NBT and syncing live elsewhere so the rules can be tested
 * without the game.
 * <p>
 * Shared values are read and written directly on this object. A view for one member of a team is made with
 * {@link TeamProgress#view(java.util.UUID)}: it shares the team's collections but has the member's own claims,
 * recent list and book flag.
 */
public final class PlayerProgress {

    /** Nodes whose tasks are all done (quests) — rewards may still be unclaimed. */
    public Set<String> completed = new HashSet<>();
    /** Nodes whose rewards were claimed (all of them; kept for data from before per-reward claims). */
    public Set<String> claimed = new HashSet<>();
    /**
     * Individual rewards claimed, key {@code <node key>#<reward index>} (2026-10-20). In a team view this is the
     * player's own part; {@link #teamClaimedRewards} adds the rewards the team settled once.
     */
    public Set<String> claimedRewards = new HashSet<>();
    /** Rewards with {@code "team": true} already claimed by anyone in the team (FTBQ's team_reward, NIL UUID). */
    public Set<String> teamClaimedRewards = new HashSet<>();
    /** Task counters, key {@code <node key>#<task index>}. */
    public Map<String, Long> tasks = new HashMap<>();
    /** Skill nodes taken, with the number of ranks taken (1 for nodes without ranks). */
    public Map<String, Integer> taken = new HashMap<>();
    /** Per chapter: experience inside the current level, level reached, points spent, extra points (bought, commands). */
    public Map<String, Double> xp = new HashMap<>();
    public Map<String, Integer> level = new HashMap<>();
    public Map<String, Integer> spent = new HashMap<>();
    public Map<String, Integer> bonus = new HashMap<>();
    /** Per skill chapter: how many times experience was exchanged for a point (the exchange level limit counts these). */
    public Map<String, Integer> exchange = new HashMap<>();
    /** Repeatable quests: how often each was claimed, and until when (epoch millis) it rests before it opens again. */
    public Map<String, Integer> repeats = new HashMap<>();
    public Map<String, Long> cooldown = new HashMap<>();
    /**
     * Chapters whose open/locked state a command changed (2026-10-20). No entry means the chapter's
     * {@code points.unlocked_by_default}; storing only the override keeps the pack's default in charge.
     */
    public Map<String, Boolean> chapterOpen = new HashMap<>();
    /** Stages granted to the whole team ({@code "team": true} on a stage task or reward). */
    public Set<String> teamStages = new HashSet<>();
    /** Stages the quest tree granted to this player, as opposed to the shared {@link #teamStages}. */
    public Set<String> stages = new HashSet<>();
    /** Team stages this player's Pack Stages were last synced with, so a removal while offline is followed on login. */
    public Set<String> teamStagesApplied = new HashSet<>();
    /** Rewards whose pay-out was blocked; paid when the block is lifted (2026-10-21). */
    public Set<String> pendingRewards = new HashSet<>();
    /** Latest events for the "Recent" list, newest first: {@code kind|node key|epoch millis}. */
    public Deque<String> recent = new ArrayDeque<>();
    /** The quest book was handed out on first join (server config, 2026-10-20). */
    private boolean bookGiven;
    /** Set only on a view: writes of the book flag and reward flag go to this player's part. */
    private PlayerPart part;
    /** Set only when this is a shared record: an admin blocked every member's rewards. */
    private boolean rewardsBlocked;

    public static final int RECENT_MAX = 20;

    /** Node keys of {@code shared:false} nodes; their progress lives in each player's part. */
    private static volatile Set<String> personalNodes = Set.of();
    private static volatile int personalVersion;

    public static void setPersonalNodes(Set<String> keys) {
        Set<String> next = Set.copyOf(keys);
        if (!next.equals(personalNodes)) {
            personalNodes = next;
            personalVersion++;
        }
    }

    public static int personalVersion() {
        return personalVersion;
    }

    /** The node keys that are personal now, for migrating saved progress after {@code shared} changes. */
    public static Set<String> personalNodesSnapshot() {
        return personalNodes;
    }

    private static String nodeOf(String key) {
        int hash = key.lastIndexOf('#');
        return hash < 0 ? key : key.substring(0, hash);
    }

    static boolean isPersonal(String key) {
        return personalNodes.contains(key);
    }

    public PlayerProgress() {
    }

    /**
     * A member's view of a team record. Shared collections are the team's (writes reach every member); claims,
     * recent and the book flag are the member's own.
     */
    public static PlayerProgress view(PlayerProgress shared, PlayerPart part, Set<String> teamClaimedRewards) {
        PlayerProgress p = new PlayerProgress();
        p.completed = personalNodes.isEmpty() ? shared.completed : new RoutedSet(shared.completed, part.completed);
        p.tasks = personalNodes.isEmpty() ? shared.tasks : new RoutedMap<>(shared.tasks, part.tasks);
        p.taken = shared.taken;
        p.xp = shared.xp;
        p.level = shared.level;
        p.spent = shared.spent;
        p.bonus = shared.bonus;
        p.exchange = shared.exchange;
        p.repeats = personalNodes.isEmpty() ? shared.repeats : new RoutedMap<>(shared.repeats, part.repeats);
        p.cooldown = personalNodes.isEmpty() ? shared.cooldown : new RoutedMap<>(shared.cooldown, part.cooldown);
        p.chapterOpen = shared.chapterOpen;
        p.teamStages = shared.teamStages;
        p.stages = part.stages;
        p.teamStagesApplied = part.teamStagesApplied;
        p.pendingRewards = part.pendingRewards;
        p.claimed = part.claimed;
        p.claimedRewards = part.claimedRewards;
        p.teamClaimedRewards = teamClaimedRewards;
        p.recent = part.recent;
        p.bookGiven = part.bookGiven;
        p.rewardsBlocked = part.rewardsBlocked;
        p.part = part;
        return p;
    }

    public long task(String node, int index) {
        return tasks.getOrDefault(node + "#" + index, 0L);
    }

    public void setTask(String node, int index, long value) {
        tasks.put(node + "#" + index, value);
    }

    public boolean bookGiven() {
        return bookGiven;
    }

    public boolean rewardsBlocked() {
        return rewardsBlocked;
    }

    public void setRewardsBlocked(boolean blocked) {
        rewardsBlocked = blocked;
        if (part != null) {
            part.rewardsBlocked = blocked;
        }
    }

    public void setBookGiven(boolean given) {
        bookGiven = given;
        if (part != null) {
            part.bookGiven = given;
        }
    }

    /** Whether this reward is already claimed, either by this player or once by the team (team_reward). */
    public boolean rewardClaimed(String key) {
        return claimedRewards.contains(key) || teamClaimedRewards.contains(key);
    }

    /**
     * Claims a {@code "team": true} reward once for the whole team. Returns false when another member already took
     * it. The claimer keeps a personal mark as well, so their node reads as fully claimed on its own.
     */
    public boolean claimTeamReward(String key) {
        if (teamClaimedRewards.contains(key)) {
            return false;
        }
        teamClaimedRewards.add(key);
        claimedRewards.add(key);
        return true;
    }

    /** Forgets this member's {@code shared:false} progress; a shared reset runs once per team, so each part is cleared. */
    public void clearPersonalAll() {
        if (part != null) {
            part.completed.clear();
            part.tasks.clear();
            part.repeats.clear();
            part.cooldown.clear();
        }
    }

    /** Forgets this member's {@code shared:false} progress for one chapter or node. */
    public void clearPersonalNode(String id) {
        if (part == null) {
            return;
        }
        String prefix = id + "/";
        part.completed.removeIf(k -> k.equals(id) || k.startsWith(prefix));
        part.tasks.keySet().removeIf(k -> k.startsWith(id + "/") || k.startsWith(id + "#"));
        part.repeats.keySet().removeIf(k -> k.equals(id) || k.startsWith(prefix));
        part.cooldown.keySet().removeIf(k -> k.equals(id) || k.startsWith(prefix));
    }

    /** Forgets a node's own progress (tasks, completion, claim); used by repeats and resets. */
    public void clearNode(String node) {
        completed.remove(node);
        claimed.remove(node);
        tasks.keySet().removeIf(k -> k.startsWith(node + "#"));
        claimedRewards.removeIf(k -> k.startsWith(node + "#"));
        teamClaimedRewards.removeIf(k -> k.startsWith(node + "#"));
    }

    /** Copies every shared value. Claims, recent and the book flag are per player and are left alone. */
    public void copySharedFrom(PlayerProgress other) {
        completed.clear();
        completed.addAll(other.completed);
        tasks.clear();
        tasks.putAll(other.tasks);
        taken.clear();
        taken.putAll(other.taken);
        xp.clear();
        xp.putAll(other.xp);
        level.clear();
        level.putAll(other.level);
        spent.clear();
        spent.putAll(other.spent);
        bonus.clear();
        bonus.putAll(other.bonus);
        exchange.clear();
        exchange.putAll(other.exchange);
        repeats.clear();
        repeats.putAll(other.repeats);
        cooldown.clear();
        cooldown.putAll(other.cooldown);
        chapterOpen.clear();
        chapterOpen.putAll(other.chapterOpen);
        teamStages.clear();
        teamStages.addAll(other.teamStages);
    }

    public void event(String kind, String node, long time) {
        recent.addFirst(kind + "|" + node + "|" + time);
        while (recent.size() > RECENT_MAX) {
            recent.removeLast();
        }
    }
}
