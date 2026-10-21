package com.kuronami.questtree.progress;

import com.google.gson.JsonParser;
import com.kuronami.questtree.data.ProgressModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Skill rewards that must not be farmed by respec and taking the same rank again (2026-10-21 review). */
class SkillRewardPolicyTest {

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
    void theMarkIsPerNodeAndRank() {
        ProgressModel model = ProgressModel.of(JsonParser.parseString("""
                {"chapters": {"s:a": {"points": {}, "nodes": {"n": {"ranks": [{}, {}]}}}}}
                """).getAsJsonObject());
        ProgressModel.Node n = model.nodes.get("s:a/n");
        assertEquals("s:a/n#skill0", SkillRewardPolicy.mark(n, 0));
        assertNotEquals(SkillRewardPolicy.mark(n, 0), SkillRewardPolicy.mark(n, 1));
        assertEquals("s:a/n#skill0#1", SkillRewardPolicy.mark(n, 0, 1));
        assertNotEquals(SkillRewardPolicy.mark(n, 0, 0), SkillRewardPolicy.mark(n, 0, 1));
    }

    /** A reward is marked only when it was paid, so a failed or skipped one can still pay on the next take. */
    @Test
    void onlyPaidRewardsAreRecorded() {
        ProgressModel model = modelWithRewards();
        ProgressModel.Node n = model.nodes.get("s:a/n");
        PlayerProgress p = new PlayerProgress();
        assertTrue(SkillRewardPolicy.owed(p, n, 0, 0));
        assertTrue(SkillRewardPolicy.owed(p, n, 0, 1));

        SkillRewardPolicy.recordPaid(p, n, 0, 0, false);
        assertTrue(SkillRewardPolicy.owed(p, n, 0, 0), "a failed pay-out must stay owed");
        assertTrue(SkillRewardPolicy.owed(p, n, 0, 1), "another reward is not marked with it");

        SkillRewardPolicy.recordPaid(p, n, 0, 0, true);
        assertFalse(SkillRewardPolicy.owed(p, n, 0, 0));
        assertTrue(SkillRewardPolicy.owed(p, n, 0, 1));
    }

    /** Saves from before per-reward marks kept one mark per rank; it means every one-off reward of the rank was paid. */
    @Test
    void anOldPerRankMarkCountsEveryRewardOfThatRankAsPaid() {
        ProgressModel model = modelWithRewards();
        ProgressModel.Node n = model.nodes.get("s:a/n");
        PlayerProgress p = new PlayerProgress();
        p.claimedRewards.add("s:a/n#skill0");
        assertFalse(SkillRewardPolicy.owed(p, n, 0, 0));
        assertFalse(SkillRewardPolicy.owed(p, n, 0, 1), "the old mark paid the whole rank");
        assertTrue(SkillRewardPolicy.owed(p, n, 1, 0), "another rank is not covered");
    }

    /**
     * A reward whose conditions did not hold was never handed out, so a respec must not undo it and a login must not
     * re-apply it (2026-10-22 review).
     */
    @Test
    void onlyRewardsWhoseConditionsHeldAreUndoneOrReapplied() {
        ProgressModel model = ProgressModel.of(JsonParser.parseString("""
                {"chapters": {"s:a": {"points": {}, "nodes": {"n": {"ranks": [
                  {"rewards": [{"type": "attribute"}, {"type": "command"}, {"type": "attribute"}]}]}}}}}
                """).getAsJsonObject());
        ProgressModel.Node n = model.nodes.get("s:a/n");
        assertEquals(java.util.List.of(0, 2), SkillRewardPolicy.grantedIndexes(n, 0,
                rw -> !"command".equals(rw.get("type").getAsString())));
        assertEquals(java.util.List.of(0, 1, 2), SkillRewardPolicy.grantedIndexes(n, 0, rw -> true));
        assertEquals(java.util.List.of(), SkillRewardPolicy.grantedIndexes(n, 0, rw -> false));
    }

    private static ProgressModel modelWithRewards() {
        return ProgressModel.of(JsonParser.parseString("""
                {"chapters": {"s:a": {"points": {}, "nodes": {"n": {"ranks": [
                  {"rewards": [{"type": "item"}, {"type": "command", "command": "say hi"}]},
                  {"rewards": [{"type": "item"}]}]}}}}}
                """).getAsJsonObject());
    }
}
