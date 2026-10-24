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
    /** When each completed node was finished (epoch millis), for the debug details sheet (2026-10-22). */
    public Map<String, Long> completedAt = new HashMap<>();
    /** Nodes whose rewards were claimed (all of them; kept for data from before per-reward claims). */
    public Set<String> claimed = new HashSet<>();
    /**
     * Individual rewards claimed, key {@code <node key>#<reward index>} (2026-10-20). In a team view this is the
     * player's own part; {@link #teamClaimedRewards} adds the rewards the team settled once.
     */
    public Set<String> claimedRewards = new HashSet<>();
    /** Rewards with {@code "team": true} already claimed by anyone in the team (FTBQ's team_reward, NIL UUID). */
    public Set<String> teamClaimedRewards = new HashSet<>();
    /**
     * Skill rewards the team actually paid, key {@code <node>#<rank>#<index>} (2026-10-22). Revocation, re-application
     * and the respec judgement read only this record, so a condition that stops holding later cannot undo or repay
     * something. In a team view this is the team's shared set.
     */
    public Set<String> paidSkillRewards = new HashSet<>();
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
    /** Repeatable quests with a daily/weekly reset: the world day their next round may start. */
    public Map<String, Long> resetAt = new HashMap<>();
    /**
     * Chapters whose open/locked state a command changed. The state belongs to the whole team: every member's view
     * reads the same map. No entry means the chapter's {@code points.unlocked_by_default}; storing only the override
     * keeps the pack's default in charge.
     */
    public Map<String, Boolean> chapterOpen = new HashMap<>();
    /** Stages granted to the whole team ({@code "team": true} on a stage task or reward). */
    public Set<String> teamStages = new HashSet<>();
    /** Stages the quest tree granted to this player, as opposed to the shared {@link #teamStages}. */
    public Set<String> stages = new HashSet<>();
    /** Team stages this player's Pack Stages were last synced with, so a removal while offline is followed on login. */
    public Set<String> teamStagesApplied = new HashSet<>();
    /** Rewards whose pay-out was blocked, with counts; paid when the block is lifted (2026-10-21). */
    public Map<String, Integer> pendingRewards = new HashMap<>();
    /** Rolls already made for a reward table, kept until its pay-out succeeds (2026-10-21 review). */
    public Map<String, String> rewardRolls = new HashMap<>();
    /** Loot crate rolls since each table last took its ceiling entry: table id → count. Per player, never the team's. */
    public Map<String, Integer> pity = new HashMap<>();
    /** Latest events for the "Recent" list, newest first: {@code kind|node key|epoch millis}. */
    public Deque<String> recent = new ArrayDeque<>();
    /** Emergency items: when this player may use them again, and whether the wait message was already shown. */
    private long emergencyReadyAt;
    private boolean emergencyWaitNotice;
    /** The quest book was handed out on first join (server config, 2026-10-20). */
    private boolean bookGiven;
    /**
     * The team's quest book is closed for everyone (FTB Quests' {@code locked}, 2026-10-22). A team value: every
     * member's view reads the same flag, and an admin command changes it once per team.
     */
    private boolean bookLocked;
    /** Set only on a view: the shared record the lock lives in, so a write reaches the team. */
    private PlayerProgress sharedRecord;
    /** Set only on a view: writes of the book flag and reward flag go to this player's part. */
    private PlayerPart part;
    /** Set only when this is a shared record: an admin blocked every member's rewards. */
    private boolean rewardsBlocked;

    public static final int RECENT_MAX = 20;
    /** Old shared cooldown key; kept so saves from before the per-player change can be migrated. */
    public static final String EMERGENCY_COOLDOWN = "#emergency";

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
    public static PlayerProgress view(PlayerProgress shared, PlayerPart part, Set<String> teamClaimedRewards,
                                      Set<String> paidSkillRewards) {
        PlayerProgress p = new PlayerProgress();
        p.completed = personalNodes.isEmpty() ? shared.completed : new RoutedSet(shared.completed, part.completed);
        p.completedAt = personalNodes.isEmpty() ? shared.completedAt
                : new RoutedMap<>(shared.completedAt, part.completedAt);
        p.tasks = personalNodes.isEmpty() ? shared.tasks : new RoutedMap<>(shared.tasks, part.tasks);
        p.taken = shared.taken;
        p.xp = shared.xp;
        p.level = shared.level;
        p.spent = shared.spent;
        p.bonus = shared.bonus;
        p.exchange = shared.exchange;
        p.repeats = personalNodes.isEmpty() ? shared.repeats : new RoutedMap<>(shared.repeats, part.repeats);
        p.cooldown = personalNodes.isEmpty() ? shared.cooldown : new RoutedMap<>(shared.cooldown, part.cooldown);
        p.resetAt = personalNodes.isEmpty() ? shared.resetAt : new RoutedMap<>(shared.resetAt, part.resetAt);
        p.chapterOpen = shared.chapterOpen;
        p.teamStages = shared.teamStages;
        p.stages = part.stages;
        p.teamStagesApplied = part.teamStagesApplied;
        p.pendingRewards = part.pendingRewards;
        p.rewardRolls = part.rewardRolls;
        p.pity = part.pity;
        p.claimed = part.claimed;
        p.claimedRewards = part.claimedRewards;
        p.teamClaimedRewards = teamClaimedRewards;
        p.paidSkillRewards = paidSkillRewards;
        p.recent = part.recent;
        p.bookGiven = part.bookGiven;
        p.rewardsBlocked = part.rewardsBlocked;
        p.bookLocked = shared.bookLocked;
        p.sharedRecord = shared;
        p.emergencyReadyAt = part.emergencyReadyAt;
        p.emergencyWaitNotice = part.emergencyWaitNotice;
        p.part = part;
        return p;
    }

    /** When this player may use the emergency items again (epoch millis). Per player, never the team's. */
    public long emergencyReadyAt() {
        return emergencyReadyAt;
    }

    public void setEmergencyReadyAt(long at) {
        emergencyReadyAt = at;
        if (part != null) {
            part.emergencyReadyAt = at;
        }
    }

    public boolean emergencyWaitNotice() {
        return emergencyWaitNotice;
    }

    public void setEmergencyWaitNotice(boolean shown) {
        emergencyWaitNotice = shown;
        if (part != null) {
            part.emergencyWaitNotice = shown;
        }
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

    /** Whether the team's book is closed. Shared, so a view reads the team record's flag. */
    public boolean bookLocked() {
        return sharedRecord != null ? sharedRecord.bookLocked : bookLocked;
    }

    public void setBookLocked(boolean locked) {
        if (sharedRecord != null) {
            sharedRecord.bookLocked = locked;
        } else {
            bookLocked = locked;
        }
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
            part.completedAt.clear();
            part.tasks.clear();
            part.repeats.clear();
            part.cooldown.clear();
            part.resetAt.clear();
        }
    }

    /** Forgets this member's {@code shared:false} progress for one chapter or node. */
    public void clearPersonalNode(String id) {
        if (part == null) {
            return;
        }
        String prefix = id + "/";
        part.completed.removeIf(k -> k.equals(id) || k.startsWith(prefix));
        part.completedAt.keySet().removeIf(k -> k.equals(id) || k.startsWith(prefix));
        part.tasks.keySet().removeIf(k -> k.startsWith(id + "/") || k.startsWith(id + "#"));
        part.repeats.keySet().removeIf(k -> k.equals(id) || k.startsWith(prefix));
        part.cooldown.keySet().removeIf(k -> k.equals(id) || k.startsWith(prefix));
        part.resetAt.keySet().removeIf(k -> k.equals(id) || k.startsWith(prefix));
    }

    /**
     * Forgets a node's own progress for its next round. Rewards already taken while pay-outs were blocked stay in
     * {@link #pendingRewards}: they are owed from the round that was claimed (2026-10-22 review).
     */
    public void clearRound(String node) {
        completed.remove(node);
        completedAt.remove(node);
        claimed.remove(node);
        tasks.keySet().removeIf(k -> k.startsWith(node + "#"));
        clearRewardMarks(node);
    }

    /**
     * Forgets a node's reward marks for its next round, keeping the marks of a table pay-out that is still owed
     * ({@code <pendingKey>>paid>...} and {@code <pendingKey>>rolls}). The pending table resumes after the block lifts,
     * so its finished entries must not be paid again; a mark whose table is no longer owed goes with the round
     * (2026-10-22 review). The team's repeat cleanup uses this too, so every member's rolls go the same way.
     */
    public void clearRewardMarks(String node) {
        String prefix = node + "#";
        claimedRewards.removeIf(k -> k.startsWith(prefix));
        teamClaimedRewards.removeIf(k -> k.startsWith(prefix));
        rewardRolls.keySet().removeIf(k -> k.startsWith(prefix) && !owedTableMark(k));
    }

    /** Whether a reward-roll mark belongs to the pay-out of a table still listed as owed. */
    private boolean owedTableMark(String mark) {
        int sep = mark.indexOf('>');
        return sep > 0 && pendingRewards.containsKey(mark.substring(0, sep));
    }

    /** Forgets a node's own progress and everything it still owes; used by resets (2026-10-22 review). */
    public void clearNode(String node) {
        // pending first: once it is gone the table marks no longer count as owed and clearRound drops them too
        pendingRewards.keySet().removeIf(k -> k.startsWith(node + "#"));
        paidSkillRewards.removeIf(k -> k.startsWith(node + "#"));
        clearRound(node);
    }

    /** Copies every shared value. Claims, recent and the book flag are per player and are left alone. */
    public void copySharedFrom(PlayerProgress other) {
        completed.clear();
        completed.addAll(other.completed);
        completedAt.clear();
        completedAt.putAll(other.completedAt);
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
        resetAt.clear();
        resetAt.putAll(other.resetAt);
        chapterOpen.clear();
        chapterOpen.putAll(other.chapterOpen);
        teamStages.clear();
        teamStages.addAll(other.teamStages);
        paidSkillRewards.clear();
        paidSkillRewards.addAll(other.paidSkillRewards);
        bookLocked = other.bookLocked;
    }

    public void event(String kind, String node, long time) {
        recent.addFirst(kind + "|" + node + "|" + time);
        while (recent.size() > RECENT_MAX) {
            recent.removeLast();
        }
    }
}
