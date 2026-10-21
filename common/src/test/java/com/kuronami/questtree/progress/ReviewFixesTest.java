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
    void failedTableRewardStaysUnclaimed() {
        PlayerProgress p = new PlayerProgress();
        JsonObject reward = reward("table");
        assertFalse(RewardLedger.claim(p, reward, "t:q/n", 0, RewardLedger.QUEST_RANK, true, false, () -> false));
        assertFalse(p.rewardClaimed("t:q/n#0"), "the table's pay-out failed, so the node stays claimable");
        assertTrue(RewardLedger.claim(p, reward, "t:q/n", 0, RewardLedger.QUEST_RANK, true, false, () -> true));
        assertTrue(p.rewardClaimed("t:q/n#0"));
    }

    @Test
    void aCurrencyRewardWithoutAnEconomyModIsTakenAndOneThatFailsIsNot() {
        assertEquals(CurrencyClaim.Outcome.NO_PROVIDER, CurrencyClaim.resolve(false, false));
        assertTrue(CurrencyClaim.completes(CurrencyClaim.Outcome.NO_PROVIDER));
        assertEquals(CurrencyClaim.Outcome.FAILED, CurrencyClaim.resolve(true, false));
        assertFalse(CurrencyClaim.completes(CurrencyClaim.Outcome.FAILED));
        assertEquals(CurrencyClaim.Outcome.PAID, CurrencyClaim.resolve(true, true));
        assertTrue(CurrencyClaim.completes(CurrencyClaim.Outcome.PAID));
    }

    @Test
    void aRandomRollIsRecordedSoARetryUsesTheSameEntries() {
        java.util.List<Integer> picks = java.util.List.of(2, 0, 2);
        String saved = RewardRoll.encode(picks);
        assertEquals(picks, RewardRoll.decode(saved));
        assertEquals(java.util.List.of(), RewardRoll.decode(""));
        assertEquals(null, RewardRoll.decode("x"));
    }

    @Test
    void blockedRewardCompletesTheNodeAndWaitsToBePaid() {
        PlayerProgress p = new PlayerProgress();
        JsonObject reward = reward("item");
        assertTrue(RewardLedger.claim(p, reward, "t:q/n", 0, RewardLedger.QUEST_RANK, true, true, () -> {
            throw new AssertionError("a blocked reward must not be paid");
        }));
        assertTrue(p.rewardClaimed("t:q/n#0"));
        assertEquals(1, p.pendingRewards.get("t:q/n#-1#0"));
    }

    @Test
    void aResetForgetsThePendingRewardsOfTheNode() {
        PlayerProgress p = new PlayerProgress();
        JsonObject reward = reward("item");
        RewardLedger.claim(p, reward, "t:q/n", 0, RewardLedger.QUEST_RANK, true, true, () -> true);
        p.claimedRewards.clear();
        RewardLedger.claim(p, reward, "t:q/n", 0, RewardLedger.QUEST_RANK, true, true, () -> true);
        assertEquals(2, p.pendingRewards.get("t:q/n#-1#0"));
        p.clearNode("t:q/n");
        assertTrue(p.pendingRewards.isEmpty(), "a reset node must not pay a reward from the old round");
    }

    @Test
    void aRepeatKeepsThePendingRewardsOfTheClaimedRound() {
        PlayerProgress p = new PlayerProgress();
        JsonObject reward = reward("item");
        RewardLedger.claim(p, reward, "t:q/n", 0, RewardLedger.QUEST_RANK, true, true, () -> true);
        p.claimedRewards.clear();
        RewardLedger.claim(p, reward, "t:q/n", 0, RewardLedger.QUEST_RANK, true, true, () -> true);
        assertEquals(2, p.pendingRewards.get("t:q/n#-1#0"));

        ProgressModel model = ProgressModel.of(JsonParser.parseString("""
                {"chapters": {"t:q": {"nodes": {"n": {"repeat": {"cooldown": 60}}}}}}
                """).getAsJsonObject());
        ProgressModel.Node n = model.chapters.get("t:q").nodes.get("n");
        assertTrue(new ProgressRules(model).repeatAfterClaim(p, n, 1_000L));
        assertEquals(2, p.pendingRewards.get("t:q/n#-1#0"),
                "the next round must not forget the two pay-outs the last one owes");
    }

    @Test
    void aBlockedMemberSkillRewardIsOwedToThatMemberOnly() {
        // Rewards.giveSkillOne writes a member's own command/tag pay-out into that member's progress
        PlayerProgress taker = new PlayerProgress();
        PlayerProgress member = new PlayerProgress();
        JsonObject reward = reward("command");
        assertTrue(RewardLedger.claim(member, reward, "s:q/skill", 0, 0, false, true, () -> true));
        assertEquals(1, member.pendingRewards.get("s:q/skill#0#0"));
        assertTrue(taker.pendingRewards.isEmpty(), "the taker is not owed another member's pay-out");
    }

    @Test
    void aBlockedChoiceTableIsRefusedInsteadOfPaidWithNoChoice() {
        PlayerProgress p = new PlayerProgress();
        JsonObject reward = reward("table");
        reward.addProperty("mode", "choice");
        assertFalse(RewardLedger.claim(p, reward, "t:q/n", 0, RewardLedger.QUEST_RANK, true, true, () -> true));
        assertFalse(p.rewardClaimed("t:q/n#0"));
        assertTrue(p.pendingRewards.isEmpty());
    }

    @Test
    void pendingCountsSurviveASaveAndOldSavesStillLoad() {
        PlayerPart part = new PlayerPart();
        part.rewardsBlocked = true;
        part.pendingRewards.put("t:q/n#-1#0", 3);
        part.pendingRewards.put("t:q/m#-1#1", 1);
        PlayerPart loaded = PlayerPart.fromJson(part.toJson());
        assertTrue(loaded.rewardsBlocked);
        assertEquals(3, loaded.pendingRewards.get("t:q/n#-1#0"));
        assertEquals(1, loaded.pendingRewards.get("t:q/m#-1#1"));

        com.google.gson.JsonObject old = part.toJson();
        com.google.gson.JsonArray legacy = new com.google.gson.JsonArray();
        legacy.add("t:q/n#-1#0");
        old.add("pending_rewards", legacy);
        assertEquals(1, PlayerPart.fromJson(old).pendingRewards.get("t:q/n#-1#0"));
    }

    @Test
    void barrierAnswersAreKeptPerQuestionAndExpire() {
        BarrierCache<Boolean> cache = new BarrierCache<>(4, 8);
        java.util.UUID player = java.util.UUID.randomUUID();
        cache.put(player, "stage", "a", 10, true);
        cache.put(player, "stage", "b", 10, false);
        assertEquals(true, cache.get(player, "stage", "a", 12));
        assertEquals(false, cache.get(player, "stage", "b", 12));
        assertEquals(null, cache.get(player, "stage", "a", 15));
        assertEquals(null, cache.get(player, "node", "a", 10));
    }

    @Test
    void barrierAnswersForOnePlayerAreBounded() {
        BarrierCache<Boolean> cache = new BarrierCache<>(100, 2);
        java.util.UUID player = java.util.UUID.randomUUID();
        cache.put(player, "node", "a", 1, true);
        cache.put(player, "node", "b", 1, true);
        cache.put(player, "node", "c", 1, true);
        assertEquals(null, cache.get(player, "node", "a", 1), "the oldest answer is dropped");
        assertEquals(true, cache.get(player, "node", "c", 1));
        cache.clear(player);
        assertEquals(null, cache.get(player, "node", "c", 1));
    }

    @Test
    void anEmptyModelDoesNotMigratePersonalNodes() {
        TeamStoreData data = new TeamStoreData();
        data.personalNodes.add("t:q/n");
        TeamProgress team = data.team(java.util.UUID.randomUUID());
        team.shared.completed.add("t:q/n");
        assertFalse(data.migratePersonalNodes(java.util.Set.of()));
        assertFalse(team.shared.completed.isEmpty(), "an empty model must leave the shared record alone");
        assertTrue(data.personalNodes.contains("t:q/n"));
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
