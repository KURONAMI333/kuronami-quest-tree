package com.kuronami.questtree.progress;

import com.google.gson.JsonParser;
import com.kuronami.questtree.data.ProgressModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Skill rewards that must not be farmed by respec and taking the same rank again (2026-10-22 review). */
class SkillRewardPolicyTest {

    private static final java.util.UUID A = java.util.UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final java.util.UUID B = java.util.UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void oneOffRewardsPayOnlyOnTheFirstTake() {
        for (String type : new String[]{"xp", "xp_levels", "item", "table", "advancement", "command",
                "currency", "custom"}) {
            assertFalse(SkillRewardPolicy.pays(type, false), type + " paid again after a respec");
            assertTrue(SkillRewardPolicy.pays(type, true), type + " was skipped on the first take");
        }
        // these come back with the skill or are shared, so they are paid every time
        assertTrue(SkillRewardPolicy.pays("attribute", false));
        assertTrue(SkillRewardPolicy.pays("points", false));
        assertTrue(SkillRewardPolicy.pays("tag", false));
        assertTrue(SkillRewardPolicy.pays("scoreboard", false));
    }

    @Test
    void aCommandWithALockCommandPaysOnEveryTake() {
        var plain = JsonParser.parseString("""
                {"type": "command", "command": "say hi"}
                """).getAsJsonObject();
        var paired = JsonParser.parseString("""
                {"type": "command", "command": "say hi", "lock_command": "say bye"}
                """).getAsJsonObject();
        assertFalse(SkillRewardPolicy.pays(plain, false), "a command with no undo stays one-off");
        assertFalse(SkillRewardPolicy.hasLockCommand(plain));
        assertTrue(SkillRewardPolicy.hasLockCommand(paired));
        assertTrue(SkillRewardPolicy.pays(paired, false), "the unlock half must run again after a respec locked it");
        assertFalse(SkillRewardPolicy.pays(JsonParser.parseString("""
                {"type": "item", "id": "minecraft:stone"}
                """).getAsJsonObject(), false), "a lock_command does not loosen other one-off rewards");
    }

    @Test
    void thePaidRecordIsPerNodeRankAndReward() {
        ProgressModel model = ProgressModel.of(JsonParser.parseString("""
                {"chapters": {"s:a": {"points": {}, "nodes": {"n": {"ranks": [{}, {}]}}}}}
                """).getAsJsonObject());
        ProgressModel.Node n = model.nodes.get("s:a/n");
        assertEquals("s:a/n#0#0", SkillRewardPolicy.paidKey(n, 0, 0));
        assertNotEquals(SkillRewardPolicy.paidKey(n, 0, 0), SkillRewardPolicy.paidKey(n, 1, 0));
        assertNotEquals(SkillRewardPolicy.paidKey(n, 0, 0), SkillRewardPolicy.paidKey(n, 0, 1));
    }

    /** A reward is recorded only when it was paid, so a failed or skipped one can still pay on the next take. */
    @Test
    void onlyPaidRewardsAreRecorded() {
        ProgressModel model = modelWithRewards();
        ProgressModel.Node n = model.nodes.get("s:a/n");
        PlayerProgress p = new PlayerProgress();
        assertTrue(SkillRewardPolicy.owed(p, n, 0, 0));
        assertTrue(SkillRewardPolicy.owed(p, n, 0, 1));

        SkillRewardPolicy.recordPaid(p, n, 0, 0, false);
        assertTrue(SkillRewardPolicy.owed(p, n, 0, 0), "a failed pay-out must stay owed");
        assertTrue(SkillRewardPolicy.owed(p, n, 0, 1), "another reward is not recorded with it");

        SkillRewardPolicy.recordPaid(p, n, 0, 0, true);
        assertFalse(SkillRewardPolicy.owed(p, n, 0, 0));
        assertTrue(SkillRewardPolicy.owed(p, n, 0, 1));
    }

    /** A one-off skill reward paid to any member is settled for the whole team (2026-10-22 review). */
    @Test
    void aRecordOnAnyMemberCountsForTheWholeTeam() {
        ProgressModel model = modelWithRewards();
        ProgressModel.Node n = model.nodes.get("s:a/n");
        TeamProgress team = new TeamProgress(java.util.UUID.randomUUID());
        PlayerProgress a = team.view(A);
        PlayerProgress b = team.view(B);

        assertTrue(SkillRewardPolicy.owed(a, n, 0, 0));
        SkillRewardPolicy.recordPaid(a, n, 0, 0, true);

        assertFalse(SkillRewardPolicy.owed(b, n, 0, 0), "another member's record already paid the item");
        assertTrue(SkillRewardPolicy.owed(b, n, 0, 1), "only the reward that was paid is settled");
        assertTrue(team.paidSkillRewards.contains("s:a/n#0#0"));
    }

    /**
     * Skill rewards are paid on the spot, even while quest rewards are blocked: only the paid record is written,
     * never a pending entry (2026-10-22 review).
     */
    @Test
    void aSkillRewardLeavesNoPendingEntryWhenQuestRewardsAreBlocked() {
        ProgressModel model = modelWithRewards();
        ProgressModel.Node n = model.nodes.get("s:a/n");
        PlayerProgress p = new PlayerProgress();
        p.setRewardsBlocked(true);

        SkillRewardPolicy.recordPaid(p, n, 0, 0, true);

        assertTrue(p.paidSkillRewards.contains(SkillRewardPolicy.paidKey(n, 0, 0)));
        assertTrue(p.pendingRewards.isEmpty(), "skill rewards never go to the pending ledger");
    }

    /** Only the revocable rewards lose their record, so a respec pays them again but keeps one-off gifts settled. */
    @Test
    void takingBackForgetsOnlyRevocableRewards() {
        ProgressModel model = ProgressModel.of(JsonParser.parseString("""
                {"chapters": {"s:a": {"points": {}, "nodes": {"n": {"ranks": [
                  {"rewards": [{"type": "points", "amount": 2}, {"type": "item"},
                               {"type": "tag", "tag": "x"}, {"type": "command", "command": "say hi",
                                "lock_command": "say bye"}]}]}}}}}
                """).getAsJsonObject());
        ProgressModel.Node n = model.nodes.get("s:a/n");
        PlayerProgress p = new PlayerProgress();
        for (int i = 0; i < 4; i++) {
            SkillRewardPolicy.recordPaid(p, n, 0, i, true);
        }

        SkillRewardPolicy.clearRevoked(p, n, 1);

        assertEquals(java.util.Set.of("s:a/n#0#1"), p.paidSkillRewards, "the item stays settled");
    }

    /** A save from before the record has only old marks: they say which rank was paid, and every reward of it. */
    @Test
    void oldMarksBecomePaidRecords() {
        ProgressModel model = modelWithRewards();
        ProgressModel.Node n = model.nodes.get("s:a/n");
        TeamProgress team = new TeamProgress(java.util.UUID.randomUUID());
        team.part(A).claimedRewards.add("s:a/n#skill0");
        team.part(A).claimedRewards.add("s:a/n#skill1#0");

        assertTrue(SkillRewardPolicy.migratePaidSkillRewards(team, model));

        assertTrue(team.paidSkillRewards.contains("s:a/n#0#0"));
        assertTrue(team.paidSkillRewards.contains("s:a/n#0#1"), "the old rank mark paid the whole rank");
        assertTrue(team.paidSkillRewards.contains("s:a/n#1#0"));
        assertFalse(team.paidSkillRewards.contains("s:a/n#1#1"));
        assertFalse(SkillRewardPolicy.migratePaidSkillRewards(team, model), "running it again changes nothing");
    }

    /** Without any old mark, a taken skill node is filled as fully paid: conditions were not judged back then. */
    @Test
    void aTakenSkillNodeWithNoMarkIsFilledIn() {
        ProgressModel model = modelWithRewards();
        ProgressModel.Node n = model.nodes.get("s:a/n");
        TeamProgress team = new TeamProgress(java.util.UUID.randomUUID());
        team.shared.taken.put(n.key, 1);

        assertTrue(SkillRewardPolicy.migratePaidSkillRewards(team, model));

        assertTrue(team.paidSkillRewards.contains("s:a/n#0#0"));
        assertTrue(team.paidSkillRewards.contains("s:a/n#0#1"));
        assertFalse(team.paidSkillRewards.contains("s:a/n#1#0"), "a rank that was not taken is not paid");
    }

    /** A leaver keeps the team's one-off marks and paid records: taking the rank alone again must not pay again. */
    @Test
    void aLeaverKeepsTheTeamsOneOffRecords() {
        ProgressModel model = modelWithRewards();
        ProgressModel.Node n = model.nodes.get("s:a/n");
        TeamProgress team = new TeamProgress(java.util.UUID.randomUUID());
        SkillRewardPolicy.recordPaid(team.view(A), n, 0, 0, true);
        team.part(A).claimedRewards.add("s:a/n#skill0#1");

        TeamProgress solo = team.leaveCopy(B);

        assertTrue(solo.paidSkillRewards.contains("s:a/n#0#0"));
        assertTrue(solo.part(B).claimedRewards.contains("s:a/n#skill0#1"));
        assertFalse(SkillRewardPolicy.owed(solo.view(B), n, 0, 0), "the item was already paid for the team");
    }

    @Test
    void thePaidRecordSurvivesASaveRoundTrip() {
        ProgressModel model = modelWithRewards();
        ProgressModel.Node n = model.nodes.get("s:a/n");
        TeamProgress team = new TeamProgress(java.util.UUID.randomUUID());
        SkillRewardPolicy.recordPaid(team.view(A), n, 0, 0, true);

        TeamProgress back = TeamProgress.fromJson(team.toJson());

        assertEquals(java.util.Set.of("s:a/n#0#0"), back.paidSkillRewards);
        assertFalse(SkillRewardPolicy.owed(back.view(B), n, 0, 0));
    }

    private static ProgressModel modelWithRewards() {
        return ProgressModel.of(JsonParser.parseString("""
                {"chapters": {"s:a": {"points": {}, "nodes": {"n": {"ranks": [
                  {"rewards": [{"type": "item"}, {"type": "command", "command": "say hi"}]},
                  {"rewards": [{"type": "item"}]}]}}}}}
                """).getAsJsonObject());
    }
}
