package com.kuronami.questtree.progress;

import com.google.gson.JsonObject;
import com.kuronami.questtree.data.ProgressModel;

import java.util.function.BooleanSupplier;

/**
 * Decides when a reward counts as taken. Kept free of the game so the claim rules can be tested without Minecraft.
 * <p>
 * A blocked quest reward (an admin stopped pay-outs) is still recorded as taken and kept in
 * {@link PlayerProgress#pendingRewards}, so the node can finish and repeat and the reward is paid when the block is
 * lifted (2026-10-21). Skill rewards never go through here: they are always paid on the spot (2026-10-22). A reward the
 * payment refused (for example a currency provider that failed) stays unclaimed, so the player can try again.
 */
public final class RewardLedger {

    /** Rank of a quest reward; skill rewards carry the rank they were taken at. */
    public static final int QUEST_RANK = -1;

    private RewardLedger() {
    }

    /** Key of a pending reward in the player's own part. Node keys contain no '#', so the parts stay readable. */
    public static String pendingKey(String node, int rank, int index) {
        return node + "#" + rank + "#" + index;
    }

    public static String pendingNode(String key) {
        int first = key.indexOf('#');
        return first < 0 ? key : key.substring(0, first);
    }

    public static int pendingRank(String key) {
        int first = key.indexOf('#');
        int second = first < 0 ? -1 : key.indexOf('#', first + 1);
        return second < 0 ? QUEST_RANK : Integer.parseInt(key.substring(first + 1, second));
    }

    public static int pendingIndex(String key) {
        int second = key.lastIndexOf('#');
        return second < 0 ? -1 : Integer.parseInt(key.substring(second + 1));
    }

    /**
     * Takes one quest reward. Returns false when the payment failed, in which case nothing is recorded.
     */
    public static boolean claim(PlayerProgress p, JsonObject reward, String node, int index, int rank,
                                boolean markClaimed, boolean blocked, BooleanSupplier pay) {
        String pending = pendingKey(node, rank, index);
        if (blocked) {
            if (blockedChoice(blocked, reward)) {
                // a choice table needs its pick to pay, and a pick is not stored; refuse instead of paying nothing later
                return false;
            }
            p.pendingRewards.merge(pending, 1, Integer::sum);
            if (markClaimed) {
                mark(p, reward, node, index);
            }
            return true;
        }
        if (!pay.getAsBoolean()) {
            return false;
        }
        if (markClaimed) {
            mark(p, reward, node, index);
        }
        return true;
    }

    /** Whether a pending key came from a skill reward (rank 0 or more); such keys are dropped when an old save loads. */
    public static boolean isSkillPending(String key) {
        try {
            return pendingRank(key) >= 0;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** A blocked choice table cannot be taken: the pick is not part of the pending record. */
    public static boolean blockedChoice(boolean blocked, JsonObject reward) {
        return blocked && "table".equals(reward.has("type") ? reward.get("type").getAsString() : "")
                && "choice".equals(reward.has("mode") ? reward.get("mode").getAsString() : "");
    }

    /**
     * Whether pending rewards can be looked up at all. A model with no nodes is an unreadable or empty data pack, not
     * proof that every node is gone; dropping the debt there would lose it (2026-10-22 review).
     */
    public static boolean canResolve(ProgressModel model) {
        return model != null && !model.nodes.isEmpty();
    }

    /** Records a reward as taken; a team reward settles once for the whole team. */
    public static void mark(PlayerProgress p, JsonObject reward, String node, int index) {
        String key = node + "#" + index;
        if (ProgressModel.boolOf(reward, "team")) {
            p.claimTeamReward(key);
        } else {
            p.claimedRewards.add(key);
        }
    }
}
