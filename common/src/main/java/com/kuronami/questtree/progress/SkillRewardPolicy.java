package com.kuronami.questtree.progress;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kuronami.questtree.data.ProgressModel;

import java.util.Set;

/**
 * Which skill rewards are paid once per rank. Respec keeps its mark so taking the same rank again cannot pay them
 * again (2026-10-21 review); pure so the rule is testable without a running game.
 */
public final class SkillRewardPolicy {

    private static final Set<String> ONE_OFF = Set.of("xp", "xp_levels", "item", "table", "advancement", "command",
            "currency", "custom");

    private SkillRewardPolicy() {
    }

    /** The old mark, kept for saves made before each reward was marked on its own (all one-off rewards of the rank). */
    public static String mark(ProgressModel.Node n, int rank) {
        return n.key + "#skill" + rank;
    }

    /** Mark of one reward of a rank: paid only when that reward itself was handed out or moved to pending. */
    public static String mark(ProgressModel.Node n, int rank, int index) {
        return mark(n, rank) + "#" + index;
    }

    /**
     * Whether this reward of the rank still owes its one-off pay-out. {@code claimed} answers for the whole team: a
     * mark on any member's record means the team already had it (2026-10-22 review).
     */
    public static boolean owed(java.util.function.Predicate<String> claimed, ProgressModel.Node n, int rank,
                               int index) {
        return !claimed.test(mark(n, rank, index)) && !claimed.test(mark(n, rank));
    }

    /**
     * Whether this reward of the rank still owes its one-off pay-out to this player (a record on its own).
     */
    public static boolean owed(PlayerProgress p, ProgressModel.Node n, int rank, int index) {
        return owed(p::rewardClaimed, n, rank, index);
    }

    /**
     * The marks of every member of a team, saved or online. A one-off skill reward is judged team-wide: taking the
     * same rank again as another member must not pay an item or command a second time (2026-10-22 review).
     */
    public static java.util.function.Predicate<String> teamClaimed(TeamProgress team) {
        return mark -> {
            if (team.teamClaimedRewards.contains(mark)) {
                return true;
            }
            for (PlayerPart part : team.players.values()) {
                if (part.claimedRewards.contains(mark)) {
                    return true;
                }
            }
            return false;
        };
    }

    /**
     * Records the mark of one reward once it was paid or moved to pending. A failed pay-out leaves no mark, so the
     * next take can pay it instead of losing it for good (2026-10-22 review).
     */
    public static void recordPaid(PlayerProgress p, ProgressModel.Node n, int rank, int index, boolean paid) {
        if (paid) {
            p.claimedRewards.add(mark(n, rank, index));
        }
    }

    /**
     * The indexes of a rank's rewards that were handed out: the ones whose conditions held. A reward whose conditions
     * did not hold was never paid, so a respec has nothing to undo and a login must not put it back (2026-10-22
     * review). Order follows the reward list.
     */
    public static java.util.List<Integer> grantedIndexes(ProgressModel.Node n, int rank,
                                                         java.util.function.Predicate<JsonObject> conditionsMet) {
        java.util.List<JsonObject> list = ProgressRules.rewardsOfRank(n, rank);
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            if (conditionsMet.test(list.get(i))) {
                out.add(i);
            }
        }
        return out;
    }

    public static boolean oneOff(String type) {
        return ONE_OFF.contains(type);
    }

    /** Whether this rank still pays the reward: the taker's first take, or a reward that is reapplied every take. */
    public static boolean pays(String type, boolean firstTake) {
        return !oneOff(type) || firstTake;
    }

    /**
     * Whether a reward object is paid on this take. A command with {@code lock_command} is not one-off: the respec runs
     * the lock half, so taking the rank again must run the unlock half again (2026-10-22 review). Commands without one
     * stay one-off, as before.
     */
    public static boolean pays(JsonObject reward, boolean firstTake) {
        String type = reward.has("type") && reward.get("type").isJsonPrimitive()
                ? reward.get("type").getAsString() : "";
        return pays(type, firstTake) || (type.equals("command") && hasLockCommand(reward));
    }

    /** Whether a command reward has a {@code lock_command} to undo it (the revoke half on respec/reset). */
    public static boolean hasLockCommand(JsonObject reward) {
        JsonElement lock = reward.get("lock_command");
        return lock != null && lock.isJsonPrimitive() && lock.getAsJsonPrimitive().isString()
                && !lock.getAsString().isBlank();
    }
}
