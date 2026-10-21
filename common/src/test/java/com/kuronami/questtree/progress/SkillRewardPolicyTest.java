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
        for (String type : new String[]{"xp", "xp_levels", "item", "table", "advancement", "command"}) {
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
    }
}
