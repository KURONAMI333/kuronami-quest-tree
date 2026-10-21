package com.kuronami.questtree.progress;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Team ownership, the join/leave rules and the FTBQ team_reward mark, checked without a running game. */
class TeamProgressTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID TEAM = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    @AfterEach
    void clearPersonalNodes() {
        PlayerProgress.setPersonalNodes(Set.of());
    }

    @Test
    void legacyPlayerBecomesSoloTeamWithoutLosingAnything() {
        PlayerProgress old = new PlayerProgress();
        old.completed.add("t:q/one");
        old.setTask("t:q/two", 0, 3);
        old.xp.put("t:mining", 12.5);
        old.claimed.add("t:q/one");
        old.claimedRewards.add("t:q/one#0");
        old.event("claim", "t:q/one", 1000);
        old.setBookGiven(true);

        TeamProgress solo = TeamProgress.fromLegacy(A, old);
        PlayerProgress view = solo.view(A);

        assertEquals(A, solo.id);
        assertTrue(view.completed.contains("t:q/one"));
        assertEquals(3, view.task("t:q/two", 0));
        assertEquals(12.5, view.xp.get("t:mining"));
        assertTrue(view.claimed.contains("t:q/one"));
        assertTrue(view.claimedRewards.contains("t:q/one#0"));
        assertTrue(view.bookGiven());
        assertEquals(1, view.recent.size());
    }

    @Test
    void teamRewardIsClaimedOnceForEveryoneButOtherClaimsStayPerPlayer() {
        TeamProgress team = new TeamProgress(TEAM);
        PlayerProgress a = team.view(A);
        PlayerProgress b = team.view(B);

        assertTrue(a.claimTeamReward("t:q/shared#0"));
        assertFalse(b.claimTeamReward("t:q/shared#0"));
        assertTrue(b.rewardClaimed("t:q/shared#0"));

        a.claimedRewards.add("t:q/shared#1");
        assertFalse(b.rewardClaimed("t:q/shared#1"));
    }

    @Test
    void joiningFoldsProgressInWithMaxPerKey() {
        TeamProgress solo = new TeamProgress(A);
        solo.shared.completed.add("t:q/a");
        solo.shared.setTask("t:q/x", 0, 5);
        solo.shared.xp.put("t:mining", 10.0);
        solo.part(A).claimedRewards.add("t:q/a#0");
        solo.teamClaimedRewards.add("t:q/team#0");

        TeamProgress team = new TeamProgress(TEAM);
        team.shared.completed.add("t:q/b");
        team.shared.setTask("t:q/x", 0, 7);
        team.shared.xp.put("t:mining", 4.0);
        team.mergeJoiner(A, solo);

        assertEquals(2, team.shared.completed.size());
        assertEquals(7, team.shared.task("t:q/x", 0));
        assertEquals(10.0, team.shared.xp.get("t:mining"));
        assertTrue(team.view(A).claimedRewards.contains("t:q/a#0"));
        assertTrue(team.view(A).rewardClaimed("t:q/team#0"));
    }

    @Test
    void personalNodesStayWithTheirPlayer() {
        PlayerProgress.setPersonalNodes(Set.of("t:q/private"));
        TeamProgress team = new TeamProgress(TEAM);

        PlayerProgress a = team.view(A);
        a.completed.add("t:q/private");
        a.setTask("t:q/private", 0, 4);
        a.repeats.put("t:q/private", 2);
        a.cooldown.put("t:q/private", 99L);
        a.completed.add("t:q/shared");

        PlayerProgress b = team.view(B);
        assertFalse(b.completed.contains("t:q/private"));
        assertEquals(0, b.task("t:q/private", 0));
        assertFalse(b.repeats.containsKey("t:q/private"));
        assertFalse(team.shared.completed.contains("t:q/private"));
        assertTrue(team.shared.completed.contains("t:q/shared"));
        assertTrue(team.part(A).completed.contains("t:q/private"));
        assertEquals(4, team.part(A).tasks.get("t:q/private#0"));

        // the saved and synced form keeps the personal part, so a login restores it
        TeamProgress read = TeamProgress.parse(team.toJson().toString());
        assertTrue(read.view(A).completed.contains("t:q/private"));
        assertFalse(read.view(B).completed.contains("t:q/private"));
        assertEquals(4, read.view(A).task("t:q/private", 0));
        assertEquals(2, read.view(A).repeats.get("t:q/private"));
        assertEquals(99L, read.view(A).cooldown.get("t:q/private"));
    }

    @Test
    void rewardBlockingBelongsToOnePlayerAndSurvivesReload() {
        TeamProgress team = new TeamProgress(TEAM);
        team.view(A).setRewardsBlocked(true);

        assertTrue(team.view(A).rewardsBlocked());
        assertFalse(team.view(B).rewardsBlocked());
        assertTrue(TeamProgress.parse(team.toJson().toString()).view(A).rewardsBlocked());
        assertFalse(TeamProgress.parse(team.toJson().toString()).view(B).rewardsBlocked());

        JsonObject synced = ProgressJson.write(team.view(A));
        assertTrue(ProgressJson.read(synced).rewardsBlocked());
    }

    @Test
    void teamStagesAreSharedAndSurviveJoinLeaveAndReload() {
        TeamProgress team = new TeamProgress(TEAM);
        team.view(A).teamStages.add("chapter/two");

        assertTrue(team.view(B).teamStages.contains("chapter/two"));
        TeamProgress solo = team.leaveCopy(A);
        assertTrue(solo.view(A).teamStages.contains("chapter/two"));

        TeamProgress joiner = new TeamProgress(B);
        joiner.shared.teamStages.add("chapter/one");
        team.mergeJoiner(B, joiner);
        assertTrue(team.shared.teamStages.containsAll(Set.of("chapter/one", "chapter/two")));
        assertTrue(TeamProgress.parse(team.toJson().toString()).shared.teamStages.contains("chapter/one"));
    }

    @Test
    void leavingTakesACopyAndKeepsOwnClaims() {
        TeamProgress team = new TeamProgress(TEAM);
        team.shared.completed.add("t:q/a");
        team.part(A).claimedRewards.add("t:q/a#0");

        TeamProgress solo = team.leaveCopy(A);

        assertEquals(A, solo.id);
        assertTrue(solo.shared.completed.contains("t:q/a"));
        assertTrue(solo.view(A).claimedRewards.contains("t:q/a#0"));
    }

    @Test
    void syncedJsonShowsTeamRewardAsClaimed() {
        TeamProgress team = new TeamProgress(TEAM);
        team.view(A).claimTeamReward("t:q/a#0");

        // a teammate's synced view: the team settled it, but this player did not
        JsonObject json = ProgressJson.write(team.view(B));
        PlayerProgress read = ProgressJson.read(json);

        assertTrue(read.rewardClaimed("t:q/a#0"));
        // the separate lists let the details sheet say the team, not this player, took it
        assertFalse(read.claimedRewards.contains("t:q/a#0"));
        assertTrue(read.teamClaimedRewards.contains("t:q/a#0"));
    }

    @Test
    void storeMovesAPlayerBetweenSoloAndTeam() {
        TeamStoreData store = new TeamStoreData();
        PlayerProgress solo = store.team(A).view(A);
        solo.completed.add("t:q/solo");
        store.owners.put(A, A);

        assertTrue(store.changedTeam(A, TEAM));
        assertTrue(store.team(TEAM).shared.completed.contains("t:q/solo"));
        assertFalse(store.teams.containsKey(A));

        assertTrue(store.changedTeam(A, A));
        TeamProgress back = store.team(A);
        assertNotNull(back);
        assertTrue(back.shared.completed.contains("t:q/solo"));
        assertEquals(A, store.teamOf(A));
    }

    @Test
    void leavingCannotBeUsedToTakeATeamRewardASecondTime() {
        TeamStoreData store = new TeamStoreData();
        store.team(TEAM).shared.completed.add("t:q/a");
        store.owners.put(A, TEAM);

        // the caller names the team rewards of completed nodes; the leaver's copy treats them as taken
        assertTrue(store.changedTeam(A, A, (team, player) -> java.util.Set.of("t:q/a#0")));
        assertTrue(store.team(A).view(A).rewardClaimed("t:q/a#0"));
        assertFalse(store.team(A).view(A).rewardClaimed("t:q/a#1"));
    }
}
