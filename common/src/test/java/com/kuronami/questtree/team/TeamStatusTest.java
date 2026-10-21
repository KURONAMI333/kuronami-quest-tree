package com.kuronami.questtree.team;

import com.kuronami.questtree.progress.PlayerProgress;
import com.kuronami.questtree.progress.ProgressRules;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The details-sheet line: solo says nothing, and a team distinguishes waiting, own and team claims. */
class TeamStatusTest {

    private static final String NODE = "t:q/one";

    @Test
    void soloPlayShowsNoTeamLine() {
        PlayerProgress p = new PlayerProgress();
        assertEquals(TeamStatus.Mark.NONE,
                TeamStatus.mark(false, ProgressRules.State.CLAIM, p, NODE));
    }

    @Test
    void aCompletedTeamQuestWithRewardsWaitingSaysSo() {
        assertEquals(TeamStatus.Mark.REWARDS_WAITING,
                TeamStatus.mark(true, ProgressRules.State.CLAIM, new PlayerProgress(), NODE));
    }

    @Test
    void ownClaimAndTeamClaimAreDifferent() {
        PlayerProgress mine = new PlayerProgress();
        mine.claimed.add(NODE);
        assertEquals(TeamStatus.Mark.YOU_CLAIMED,
                TeamStatus.mark(true, ProgressRules.State.DONE, mine, NODE));

        PlayerProgress theirs = new PlayerProgress();
        theirs.teamClaimedRewards.add(NODE + "#0");
        assertEquals(TeamStatus.Mark.TEAM_CLAIMED,
                TeamStatus.mark(true, ProgressRules.State.DONE, theirs, NODE));
    }

    @Test
    void completedWithNoClaimShowsTheTeam() {
        assertEquals(TeamStatus.Mark.TEAM_COMPLETED,
                TeamStatus.mark(true, ProgressRules.State.DONE, new PlayerProgress(), NODE));
    }
}
