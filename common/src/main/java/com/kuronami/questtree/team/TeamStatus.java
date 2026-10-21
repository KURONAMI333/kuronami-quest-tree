package com.kuronami.questtree.team;

import com.kuronami.questtree.progress.PlayerProgress;
import com.kuronami.questtree.progress.ProgressRules;

/**
 * The one-line state a shared quest shows in the details sheet. Kept pure so the difference between "you claimed"
 * and "the team claimed" is testable without a client.
 */
public final class TeamStatus {

    public enum Mark {
        /** Solo play, or nothing to say about this node. */
        NONE,
        REWARDS_WAITING,
        YOU_CLAIMED,
        TEAM_CLAIMED,
        TEAM_COMPLETED
    }

    private TeamStatus() {
    }

    public static Mark mark(boolean team, ProgressRules.State state, PlayerProgress progress, String node) {
        if (!team) {
            return Mark.NONE;
        }
        if (state == ProgressRules.State.CLAIM) {
            return Mark.REWARDS_WAITING;
        }
        if (state != ProgressRules.State.DONE) {
            return Mark.NONE;
        }
        if (progress.claimed.contains(node) || hasClaim(progress.claimedRewards, node)) {
            return Mark.YOU_CLAIMED;
        }
        if (hasClaim(progress.teamClaimedRewards, node)) {
            return Mark.TEAM_CLAIMED;
        }
        return Mark.TEAM_COMPLETED;
    }

    private static boolean hasClaim(Iterable<String> claims, String node) {
        String prefix = node + "#";
        for (String claim : claims) {
            if (claim.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
