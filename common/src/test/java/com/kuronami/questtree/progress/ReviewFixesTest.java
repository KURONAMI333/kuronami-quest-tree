package com.kuronami.questtree.progress;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kuronami.questtree.data.ProgressModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure parts of the 2026-10-21 task/reward review fixes. */
class ReviewFixesTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID TEAM = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    @AfterEach
    void clearPersonalNodes() {
        PlayerProgress.setPersonalNodes(Set.of());
    }

    @Test
    void failedPaymentLeavesTheRewardUnclaimedForARetry() {
        PlayerProgress p = new PlayerProgress();
        JsonObject reward = reward("item");
        assertFalse(RewardLedger.claim(p, reward, "t:q/n", 0, RewardLedger.QUEST_RANK, true, false, () -> false));
        assertFalse(p.rewardClaimed("t:q/n#0"));
        assertTrue(RewardLedger.claim(p, reward, "t:q/n", 0, RewardLedger.QUEST_RANK, true, false, () -> true));
        assertTrue(p.rewardClaimed("t:q/n#0"));
    }

    @Test
    void blockedRewardCompletesTheNodeAndWaitsToBePaid() {
        PlayerProgress p = new PlayerProgress();
        JsonObject reward = reward("item");
        assertTrue(RewardLedger.claim(p, reward, "t:q/n", 0, RewardLedger.QUEST_RANK, true, true, () -> {
            throw new AssertionError("a blocked reward must not be paid");
        }));
        assertTrue(p.rewardClaimed("t:q/n#0"));
        assertTrue(p.pendingRewards.contains("t:q/n#-1#0"));
    }

    @Test
    void conditionUnmetRewardDoesNotHoldTheNodeOpen() {
        ProgressModel.Node n = nodeWithRewards(2);
        PlayerProgress p = new PlayerProgress();
        p.claimedRewards.add(n.key + "#1");
        assertFalse(ProgressRules.allRewardsClaimed(p, n));
        assertTrue(ProgressRules.allRewardsClaimed(p, n, i -> i == 0));
    }

    @Test
    void barrierUsesCompletionAndBothKindsOfStage() {
        assertTrue(BarrierRule.allows("node", "t:q/n", Set.of("t:q/n"), Set.of()));
        assertFalse(BarrierRule.allows("node", "t:q/n", Set.of(), Set.of()));
        assertTrue(BarrierRule.allows("stage", "s", Set.of(), Set.of("s")));
        assertFalse(BarrierRule.allows("stage", "", Set.of(), Set.of("")));
    }

    @Test
    void pickupIgnoresTheThrowersOwnItem() {
        assertFalse(PickupRule.counts(A, A));
        assertTrue(PickupRule.counts(A, B));
        assertTrue(PickupRule.counts(null, B));
    }

    @Test
    void teamStageSyncOnlyRemovesWhatTheTeamLost() {
        var plan = StageSync.plan(Set.of("old", "kept"), Set.of("kept", "new"));
        assertEquals(Set.of("old"), plan.remove());
        assertEquals(Set.of("new"), plan.add());
    }

    @Test
    void stalePersonalNodeSetIsRebuiltBeforeTheFirstView() {
        PlayerProgress.setPersonalNodes(Set.of());
        ProgressModel model = ProgressModel.of(JsonParser.parseString("""
                {"chapters": {"t:q": {"nodes": {"p": {"shared": false}}}}}
                """).getAsJsonObject());
        new ProgressRules(model);
        TeamProgress team = new TeamProgress(TEAM);
        team.view(A).completed.add("t:q/p");
        assertTrue(team.part(A).completed.contains("t:q/p"));
        assertFalse(team.shared.completed.contains("t:q/p"));
    }

    @Test
    void personalRoutingMigratesSavedValuesBothWays() {
        TeamProgress team = new TeamProgress(TEAM);
        team.shared.completed.add("t:q/became_personal");
        team.shared.setTask("t:q/became_personal", 0, 4);
        team.part(A).completed.add("t:q/became_shared");
        team.part(B).completed.add("t:q/became_shared");
        team.part(A).tasks.put("t:q/became_shared#0", 2L);
        team.part(B).tasks.put("t:q/became_shared#0", 3L);

        team.migratePersonal(Set.of("t:q/became_shared"), Set.of("t:q/became_personal"));

        assertTrue(team.part(A).completed.contains("t:q/became_personal"));
        assertTrue(team.part(B).completed.contains("t:q/became_personal"));
        assertEquals(4L, team.part(B).tasks.get("t:q/became_personal#0"));
        assertFalse(team.shared.completed.contains("t:q/became_personal"));
        assertTrue(team.shared.completed.contains("t:q/became_shared"));
        assertEquals(3, team.shared.task("t:q/became_shared", 0));
        assertFalse(team.part(A).completed.contains("t:q/became_shared"));
    }

    @Test
    void leavingUsesOnlyTheLeaversCompletionForSettledRewards() {
        TeamProgress team = new TeamProgress(TEAM);
        team.shared.completed.add("t:q/team");
        team.part(A).completed.add("t:q/a");
        team.part(B).completed.add("t:q/b");
        assertTrue(team.completedFor(A).contains("t:q/a"));
        assertFalse(team.completedFor(A).contains("t:q/b"));
        assertTrue(team.completedFor(A).contains("t:q/team"));
    }

    private static JsonObject reward(String type) {
        JsonObject r = new JsonObject();
        r.addProperty("type", type);
        return r;
    }

    private static ProgressModel.Node nodeWithRewards(int count) {
        StringBuilder rewards = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                rewards.append(',');
            }
            rewards.append("{\"type\":\"item\"}");
        }
        JsonObject bundle = JsonParser.parseString("{\"chapters\":{\"t:q\":{\"nodes\":{\"n\":{\"rewards\":["
                + rewards + "]}}}}}").getAsJsonObject();
        return ProgressModel.of(bundle).chapters.get("t:q").nodes.get("n");
    }
}
