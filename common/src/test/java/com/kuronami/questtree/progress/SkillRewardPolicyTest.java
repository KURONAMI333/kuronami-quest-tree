package com.kuronami.questtree.progress;

import com.google.gson.JsonObject;
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

    /**
     * The condition of a skill reward is judged once, with the taker's progress, and the same answer decides for
     * every member: attributes, tags and a lock_command command reach all of them (2026-10-22 review).
     */
    @Test
    void oneJudgementDecidesTheWholeTeamsEffects() {
        java.util.List<JsonObject> rewards = java.util.List.of(
                JsonParser.parseString("""
                        {"type": "attribute", "attribute": "minecraft:generic.max_health", "value": 2}
                        """).getAsJsonObject(),
                JsonParser.parseString("""
                        {"type": "tag", "tag": "x"}
                        """).getAsJsonObject(),
                JsonParser.parseString("""
                        {"type": "command", "unlock_command": "say hi", "lock_command": "say bye"}
                        """).getAsJsonObject(),
                JsonParser.parseString("""
                        {"type": "item", "id": "minecraft:stone"}
                        """).getAsJsonObject());
        java.util.concurrent.atomic.AtomicInteger judged = new java.util.concurrent.atomic.AtomicInteger();

        var actions = SkillRewardPolicy.actions(rewards, rw -> {
            judged.incrementAndGet();
            return true;
        }, index -> index == 3);

        assertEquals(4, judged.get(), "each reward is judged once for the taker, not once per member");
        assertEquals(3, actions.size(), "the one-off item was already paid for the team");
        assertTrue(actions.get(0).teamWide(), "an attribute belongs to every member");
        assertTrue(actions.get(1).teamWide(), "a tag belongs to every member");
        assertTrue(actions.get(2).teamWide(), "the unlock half of a command belongs to every member");
        assertFalse(actions.get(2).reward().has("command"), "the unlock command is what runs again");

        assertTrue(SkillRewardPolicy.actions(rewards, rw -> false, index -> false).isEmpty(),
                "a condition that failed for the taker pays nothing to any member");
    }

    /** A plain command and a one-off gift stay with the taker; a command with a lock half does not. */
    @Test
    void onlyTeamEffectsReachTheOtherMembers() {
        assertFalse(SkillRewardPolicy.teamWide(JsonParser.parseString("""
                {"type": "command", "command": "say hi"}
                """).getAsJsonObject()));
        assertFalse(SkillRewardPolicy.teamWide(JsonParser.parseString("""
                {"type": "item", "id": "minecraft:stone"}
                """).getAsJsonObject()));
        assertFalse(SkillRewardPolicy.teamWide(JsonParser.parseString("""
                {"type": "points", "amount": 2}
                """).getAsJsonObject()));
        assertTrue(SkillRewardPolicy.teamWide(JsonParser.parseString("""
                {"type": "command", "command": "say hi", "lock_command": "say bye"}
                """).getAsJsonObject()));
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

    /**
     * A chapter lock takes the tag and the unlock command back but keeps their record, as it keeps the attribute and
     * points records: the unlock puts back exactly what was paid, and nothing that never was (2026-10-22 review).
     */
    @Test
    void aChapterLockKeepsTheRecordAndUnlockPutsTheEffectsBack() {
        ProgressModel model = ProgressModel.of(JsonParser.parseString("""
                {"chapters": {"s:a": {"points": {}, "nodes": {"n": {"ranks": [
                  {"rewards": [{"type": "attribute", "attribute": "minecraft:generic.max_health", "amount": 2},
                               {"type": "tag", "tag": "x"},
                               {"type": "points", "amount": 2},
                               {"type": "command", "unlock_command": "say hi", "lock_command": "say bye"},
                               {"type": "tag", "tag": "y"}]}]}}}}}
                """).getAsJsonObject());
        ProgressModel.Node n = model.nodes.get("s:a/n");
        PlayerProgress p = new PlayerProgress();
        // the last tag's condition did not hold, so it was never paid and must not come back with the unlock
        for (int i = 0; i < 4; i++) {
            SkillRewardPolicy.recordPaid(p, n, 0, i, true);
        }

        java.util.List<JsonObject> back = SkillRewardPolicy.reapplyOnUnlock(n, p.paidSkillRewards, 1);

        assertEquals(2, back.size(), "only the tag and the unlock command come back");
        assertEquals("x", back.get(0).get("tag").getAsString());
        assertEquals("say hi", back.get(1).get("unlock_command").getAsString(),
                "the unlock half runs again, not the lock half");
        assertTrue(p.paidSkillRewards.contains("s:a/n#0#0"),
                "a lock keeps the attribute record that login and unlock reapply from");
        assertTrue(p.paidSkillRewards.contains("s:a/n#0#1"), "a lock keeps the tag record");
        assertTrue(p.paidSkillRewards.contains("s:a/n#0#2"), "a lock keeps the points record");
        assertTrue(p.paidSkillRewards.contains("s:a/n#0#3"), "a lock keeps the lock_command record");
    }

    /** A save from before the record has only old marks: they say which rank was paid, and every reward of it. */
    @Test
    void oldMarksBecomePaidRecords() {
        ProgressModel model = modelWithRewards();
        ProgressModel.Node n = model.nodes.get("s:a/n");
        TeamProgress team = new TeamProgress(java.util.UUID.randomUUID());
        team.needsPaidMigration = true;
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
        team.needsPaidMigration = true;
        team.shared.taken.put(n.key, 1);

        assertTrue(SkillRewardPolicy.migratePaidSkillRewards(team, model));

        assertTrue(team.paidSkillRewards.contains("s:a/n#0#0"));
        assertTrue(team.paidSkillRewards.contains("s:a/n#0#1"));
        assertFalse(team.paidSkillRewards.contains("s:a/n#1#0"), "a rank that was not taken is not paid");
    }

    /**
     * A new save writes the record even when it is empty, so a node whose conditions did not hold is never filled as
     * paid on a later load (2026-10-22 review).
     */
    @Test
    void aNewSaveIsNotFilledAgainOnEveryLoad() {
        ProgressModel model = modelWithRewards();
        ProgressModel.Node n = model.nodes.get("s:a/n");
        TeamProgress team = new TeamProgress(java.util.UUID.randomUUID());
        team.shared.taken.put(n.key, 1);

        JsonObject saved = team.toJson();
        assertTrue(saved.has("paid_skill_rewards"), "an empty record must still be written");

        TeamProgress loaded = TeamProgress.fromJson(saved);
        assertFalse(loaded.needsPaidMigration);
        assertFalse(SkillRewardPolicy.migratePaidSkillRewards(loaded, model));
        assertTrue(loaded.paidSkillRewards.isEmpty(), "nothing was paid, so nothing may be recorded");

        TeamProgress again = TeamProgress.fromJson(loaded.toJson());
        assertFalse(SkillRewardPolicy.migratePaidSkillRewards(again, model));
        assertTrue(again.paidSkillRewards.isEmpty(), "a second load must not fill it either");
    }

    /** A save without the record is filled once; the next load already has the key and is left alone. */
    @Test
    void anOldSaveIsFilledOnceAndRemembersIt() {
        ProgressModel model = modelWithRewards();
        ProgressModel.Node n = model.nodes.get("s:a/n");
        TeamProgress team = new TeamProgress(java.util.UUID.randomUUID());
        team.shared.taken.put(n.key, 1);
        JsonObject saved = team.toJson();
        saved.remove("paid_skill_rewards");

        TeamProgress loaded = TeamProgress.fromJson(saved);
        assertTrue(loaded.needsPaidMigration, "a save with no record is the old form");
        assertTrue(SkillRewardPolicy.migratePaidSkillRewards(loaded, model));
        assertFalse(loaded.needsPaidMigration, "the migration runs only once");
        assertTrue(loaded.paidSkillRewards.contains("s:a/n#0#0"));

        TeamProgress next = TeamProgress.fromJson(loaded.toJson());
        assertFalse(SkillRewardPolicy.migratePaidSkillRewards(next, model));
        assertEquals(java.util.Set.of("s:a/n#0#0", "s:a/n#0#1"), next.paidSkillRewards,
                "the filled record is the same after a reload");
    }

    /**
     * A team saved before the migration must not write the new key: an empty one would read as migrated, so the taken
     * skills would never be filled and a retake could pay them again (2026-10-22 review).
     */
    @Test
    void aTeamWaitingForTheMigrationWritesNoKey() {
        ProgressModel model = modelWithRewards();
        ProgressModel.Node n = model.nodes.get("s:a/n");
        TeamProgress team = new TeamProgress(java.util.UUID.randomUUID());
        team.needsPaidMigration = true;
        team.shared.taken.put(n.key, 1);

        JsonObject saved = team.toJson();
        assertFalse(saved.has("paid_skill_rewards"), "the key is what marks a save as migrated");

        TeamProgress loaded = TeamProgress.fromJson(saved);
        assertTrue(loaded.needsPaidMigration, "a save made before the migration still needs it");
        assertTrue(SkillRewardPolicy.migratePaidSkillRewards(loaded, model));
        assertTrue(loaded.paidSkillRewards.contains("s:a/n#0#0"));
    }

    /**
     * An old mark can name one reward of a node. The migration must still record every revocable reward of the taken
     * rank, or a respec takes back no points and a retake stacks them (2026-10-22 review).
     */
    @Test
    void anOldMarkDoesNotHideTheRevocableRewardsOfTheNode() {
        ProgressModel model = ProgressModel.of(JsonParser.parseString("""
                {"chapters": {"s:a": {"points": {"start": 0}, "nodes": {"n": {"cost": 1, "ranks": [
                  {"rewards": [{"type": "points", "amount": 2}, {"type": "item"}]}]}}}}}
                """).getAsJsonObject());
        ProgressModel.Node n = model.nodes.get("s:a/n");
        TeamProgress team = new TeamProgress(java.util.UUID.randomUUID());
        team.needsPaidMigration = true;
        team.shared.taken.put(n.key, 1);
        team.part(A).claimedRewards.add("s:a/n#skill0#1");
        team.view(A).bonus.put(ProgressRules.key(model.chapters.get("s:a")), 2);

        assertTrue(SkillRewardPolicy.migratePaidSkillRewards(team, model));

        assertTrue(team.paidSkillRewards.contains("s:a/n#0#0"),
                "the points reward is recorded even though the item mark filled the node");
        assertTrue(team.paidSkillRewards.contains("s:a/n#0#1"));

        new ProgressRules(model).revokePoints(team.view(A), n, 1);
        assertEquals(0, team.view(A).bonus.getOrDefault(ProgressRules.key(model.chapters.get("s:a")), 0),
                "the respec takes the points back once");
        assertFalse(team.paidSkillRewards.contains("s:a/n#0#0"));
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

    /** A locked chapter already undid these effects, so a reset or respec must not run them a second time. */
    @Test
    void aLockedChapterDoesNotUndoItsEffectsTwice() {
        var tag = JsonParser.parseString("""
                {"type": "tag", "tag": "x"}
                """).getAsJsonObject();
        var command = JsonParser.parseString("""
                {"type": "command", "command": "say hi", "lock_command": "say bye"}
                """).getAsJsonObject();
        var points = JsonParser.parseString("""
                {"type": "points", "amount": 2}
                """).getAsJsonObject();
        assertTrue(SkillRewardPolicy.undoesEffect(tag, false));
        assertTrue(SkillRewardPolicy.undoesEffect(command, false));
        assertFalse(SkillRewardPolicy.undoesEffect(tag, true), "the lock already removed the tag");
        assertFalse(SkillRewardPolicy.undoesEffect(command, true), "the lock already ran lock_command");
        assertFalse(SkillRewardPolicy.undoesEffect(points, false));
    }

    private static ProgressModel modelWithRewards() {
        return ProgressModel.of(JsonParser.parseString("""
                {"chapters": {"s:a": {"points": {}, "nodes": {"n": {"ranks": [
                  {"rewards": [{"type": "item"}, {"type": "command", "command": "say hi"}]},
                  {"rewards": [{"type": "item"}]}]}}}}}
                """).getAsJsonObject());
    }
}
