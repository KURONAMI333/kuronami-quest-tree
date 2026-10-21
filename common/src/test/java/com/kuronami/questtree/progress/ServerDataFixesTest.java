package com.kuronami.questtree.progress;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kuronami.questtree.data.ProgressModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression checks for the 2026-10-20 server data review (one per finding). */
class ServerDataFixesTest {

    private static ProgressModel model(String json) {
        return ProgressModel.of(JsonParser.parseString(json).getAsJsonObject());
    }

    /** 1. Respec/reset a shared pool gives back only that chapter's nodes, not the whole pool's spent counter. */
    @Test
    void poolRefundCountsOnlyTheResetChapter() {
        ProgressModel m = model("""
                {"chapters": {
                  "s:a": {"points": {"per_level": 1, "start": 1, "pool": "shared"}, "nodes": {"n": {"cost": 2}}},
                  "s:b": {"points": {"per_level": 1, "start": 1, "pool": "shared"}, "nodes": {"m": {"cost": 3}}}}}
                """);
        ProgressModel.Chapter a = m.chapters.get("s:a");
        ProgressModel.Chapter b = m.chapters.get("s:b");
        PlayerProgress p = new PlayerProgress();
        p.taken.put(a.nodes.get("n").key, 1);
        p.spent.put(ProgressRules.key(a), 5);
        assertEquals(2, ProgressRules.refundChapterSpent(p, a));
        assertEquals(3, p.spent.get(ProgressRules.key(b)), "the other chapter's points stay spent");
    }

    /** 2. A full reset forgets the per-player chapter overrides as well as the skill values. */
    @Test
    void fullResetForgetsChapterOverrides() {
        PlayerProgress p = new PlayerProgress();
        p.xp.put("shared", 4.0);
        p.level.put("shared", 2);
        p.spent.put("shared", 1);
        p.bonus.put("shared", 1);
        p.exchange.put("shared", 1);
        p.chapterOpen.put("s:a", false);
        p.recent.add("unlock|s:a/1|0");
        ProgressRules.clearAll(p);
        assertTrue(p.xp.isEmpty());
        assertTrue(p.level.isEmpty());
        assertTrue(p.spent.isEmpty());
        assertTrue(p.bonus.isEmpty());
        assertTrue(p.exchange.isEmpty());
        assertTrue(p.chapterOpen.isEmpty(), "a later login must use unlocked_by_default again");
        assertTrue(p.recent.isEmpty());
    }

    /** 3. Anti-farming is counted per experience source, so one source's window does not swallow another's. */
    @Test
    void antiFarmingWindowsArePerSource() {
        AntiFarming a = new AntiFarming();
        long now = 1_000L;
        assertTrue(a.allowInChunk("s:c#0", 1, 60, "minecraft:overworld@0", now));
        assertFalse(a.allowInChunk("s:c#0", 1, 60, "minecraft:overworld@0", now + 1));
        assertTrue(a.allowInChunk("s:c#1", 1, 60, "minecraft:overworld@0", now + 1),
                "the second source of the same chapter has its own state");
    }

    /** 4. Expired anti-farming windows are dropped, not kept in the maps forever. */
    @Test
    void antiFarmingDropsExpiredWindows() {
        AntiFarming a = new AntiFarming();
        long now = 1_000L;
        assertTrue(a.allowInChunk("s:c#0", 1, 60, "minecraft:overworld@0", now));
        assertTrue(a.limitDamage("s:c#0", 10, 60, "entity", 4, now) > 0);
        assertEquals(2, a.trackedKeys());
        a.removeOutdated(now + 60_001);
        assertEquals(0, a.trackedKeys(), "windows and emptied keys are gone");
    }

    /** 5. A custom task's button stops at its maximum, and a listening addon can complete it. */
    @Test
    void customTaskProgressIsBoundedAndCompletable() {
        assertEquals(3, CustomTaskResult.buttonProgress(2, 3));
        assertEquals(3, CustomTaskResult.buttonProgress(3, 3), "the button cannot pass the target");
        assertEquals(1, CustomTaskResult.buttonProgress(-1, 3), "a missing count starts at 1");
        CustomTaskResult done = new CustomTaskResult(1, 4);
        assertFalse(done.handled());
        done.markComplete();
        assertTrue(done.handled());
        assertTrue(done.complete());
        assertEquals(4, done.progress());
        CustomTaskResult set = new CustomTaskResult(0, 4);
        set.setProgress(9);
        assertEquals(4, set.progress(), "an addon's value is clamped too");
    }

    /** 6/8. A skill's scoreboard value is the number of unlocked skills of its definition, not the claim count. */
    @Test
    void scoreboardCountsTheDefinition() {
        ProgressModel m = model("""
                {"chapters": {"s:c": {"points": {"per_level": 1}, "nodes": {
                  "a": {"definition": "mining", "cost": 1},
                  "b": {"definition": "mining", "cost": 1},
                  "c": {"definition": "mining", "cost": 1},
                  "other": {"definition": "farming", "cost": 1}}}}}
                """);
        ProgressModel.Chapter c = m.chapters.get("s:c");
        PlayerProgress p = new PlayerProgress();
        p.taken.put(c.nodes.get("a").key, 1);
        p.taken.put(c.nodes.get("b").key, 2);
        p.taken.put(c.nodes.get("other").key, 1);
        assertEquals(2, ProgressRules.definitionCount(p, c.nodes.get("a")),
                "two mining skills are unlocked, ranks do not add to the count");
        assertEquals(1, ProgressRules.definitionCount(p, c.nodes.get("other")));
        assertEquals(2, ProgressRules.definitionCount(p, c.nodes.get("c")),
                "asking about a skill that is not taken still reports its definition's unlocked count");
    }

    /** 7. A one-chapter reset drops its exchange count, while the pool's experience and points stay. */
    @Test
    void chapterResetForgetsItsExchangeCountOnly() {
        ProgressModel m = model("""
                {"chapters": {
                  "s:a": {"points": {"per_level": 1, "start": 1, "pool": "shared",
                                     "exchange": {"xp_levels": 1, "level_limit": 2}},
                           "nodes": {"n": {"cost": 1}}},
                  "s:b": {"points": {"per_level": 1, "start": 1, "pool": "shared"}, "nodes": {"m": {"cost": 1}}}}}
                """);
        ProgressModel.Chapter a = m.chapters.get("s:a");
        PlayerProgress p = new PlayerProgress();
        String pooled = ProgressRules.key(a);
        p.xp.put(pooled, 9.0);
        p.level.put(pooled, 3);
        p.spent.put(pooled, 1);
        p.exchange.put(pooled, 2);
        ProgressRules.resetChapterData(p, a);
        assertEquals(3, p.level.get(pooled), "the shared level is not reset");
        assertEquals(9.0, p.xp.get(pooled));
        assertFalse(p.exchange.containsKey(pooled), "the reset chapter's exchange slot is forgotten");
    }

    /** 9. Pools merge imported per-chapter values once, including exchange counts. */
    @Test
    void migrationSumsPoolMembersOnce() {
        ProgressModel m = model("""
                {"chapters": {
                  "s:a": {"points": {"per_level": 1, "start": 1, "pool": "shared"}, "nodes": {"n": {"cost": 1}}},
                  "s:b": {"points": {"per_level": 1, "start": 1, "pool": "shared"}, "nodes": {"m": {"cost": 1}}}}}
                """);
        PlayerProgress p = new PlayerProgress();
        p.xp.put("s:a", 4.0);
        p.xp.put("s:b", 6.0);
        p.level.put("s:a", 1);
        p.level.put("s:b", 2);
        p.spent.put("s:a", 1);
        p.spent.put("s:b", 1);
        p.bonus.put("s:b", 1);
        p.exchange.put("s:a", 1);
        p.exchange.put("s:b", 2);
        String pooled = ProgressRules.key(m.chapters.get("s:a"));
        ProgressRules.migratePools(p, m);
        assertEquals(10.0, p.xp.get(pooled));
        assertEquals(3, p.level.get(pooled));
        assertEquals(2, p.spent.get(pooled));
        assertEquals(1, p.bonus.get(pooled));
        assertEquals(3, p.exchange.get(pooled));
        assertFalse(p.xp.containsKey("s:a"));
        ProgressRules.migratePools(p, m);
        assertEquals(10.0, p.xp.get(pooled), "a second migration must not double the values");
        assertEquals(3, p.exchange.get(pooled));
    }

    /** 11. The screen's respec charges points.respec.cost and only lets it happen when the points cover it. */
    @Test
    void respecCostIsChargedOnlyWhenAffordable() {
        ProgressModel m = model("""
                {"chapters": {"s:a": {"points": {"per_level": 1, "start": 2, "respec": {"cost": 3}},
                                      "nodes": {"n": {"cost": 1}}}}}
                """);
        ProgressModel.Chapter a = m.chapters.get("s:a");
        ProgressRules rules = new ProgressRules(m);
        PlayerProgress p = new PlayerProgress();
        assertEquals(3, ProgressRules.respecCost(a));
        assertFalse(rules.canRespec(p, a), "nothing taken: no respec");
        p.taken.put(a.nodes.get("n").key, 1);
        p.spent.put(ProgressRules.key(a), 1);
        assertEquals(1, ProgressRules.chapterRefund(p, a), "reading the refund changes nothing");
        assertEquals(1, p.spent.get(ProgressRules.key(a)));
        assertFalse(rules.canRespec(p, a), "1 point left + 1 refunded does not cover a cost of 3");
        p.bonus.put(ProgressRules.key(a), 2);
        assertTrue(rules.canRespec(p, a));
        p.spent.merge(ProgressRules.key(a), 3, Integer::sum);
        assertEquals(0, rules.availablePoints(p, a), "the fee stays spent after the respec");
    }

    /** 12. A pack without points.respec charges nothing, and a locked chapter cannot be respeced from the screen. */
    @Test
    void respecDefaultsToFreeAndWaitsForTheChapterToOpen() {
        ProgressModel m = model("""
                {"chapters": {
                  "s:free": {"points": {"per_level": 1, "start": 1}, "nodes": {"n": {"cost": 1}}},
                  "s:locked": {"points": {"per_level": 1, "start": 1, "unlocked_by_default": false,
                                           "respec": {"cost": 1}}, "nodes": {"m": {"cost": 1}}}}}
                """);
        ProgressRules rules = new ProgressRules(m);
        ProgressModel.Chapter free = m.chapters.get("s:free");
        ProgressModel.Chapter locked = m.chapters.get("s:locked");
        PlayerProgress p = new PlayerProgress();
        assertEquals(0, ProgressRules.respecCost(free));
        p.taken.put(free.nodes.get("n").key, 1);
        p.spent.put(ProgressRules.key(free), 1);
        assertTrue(rules.canRespec(p, free));
        p.taken.put(locked.nodes.get("m").key, 1);
        p.spent.merge(ProgressRules.key(locked), 1, Integer::sum);
        assertFalse(rules.canRespec(p, locked));
        p.chapterOpen.put(locked.id, true);
        assertTrue(rules.canRespec(p, locked));
    }

    /** 13. A chapter is locked by points.unlocked_by_default; visibility.lock_hint is only the client's hint. */
    @Test
    void chapterUnlockUsesUnlockedByDefault() {
        ProgressModel m = model("""
                {"chapters": {
                  "s:hinted": {"visibility": {"lock_hint": "progression.pack.locked_hint"},
                               "points": {"unlocked_by_default": false}, "nodes": {}},
                  "s:plain": {"visibility": {"lock_hint": ""}, "nodes": {}}}}
                """);
        assertFalse(ProgressRules.chapterUnlocked(new PlayerProgress(), m.chapters.get("s:hinted")));
        assertTrue(ProgressRules.chapterUnlocked(new PlayerProgress(), m.chapters.get("s:plain")));
    }

    /** 10. A random reward never rolls empty, and weight-0 entries are granted outside the rolls. */
    @Test
    void randomRewardHonoursEmptyOnlyForLootAndZeroWeights() {
        RandomGenerator random = RandomGenerator.of("L64X128MixRandom");
        List<Integer> always = RewardRoll.roll(List.of(1.0), 1_000.0, false, 20, random::nextDouble);
        assertEquals(20, always.size());
        assertTrue(always.stream().allMatch(i -> i == 0), "empty_weight is not part of a random reward");
        List<Integer> loot = RewardRoll.roll(List.of(1.0), 1_000.0, true, 20, () -> 0.999);
        assertTrue(loot.isEmpty(), "a loot crate may roll nothing");
        assertEquals(List.of(1), RewardRoll.roll(List.of(1.0, 0.0), 0, false, 0, random::nextDouble),
                "a weight-0 entry is always granted");
        assertTrue(RewardRoll.roll(List.of(0.0, 0.0), 5, false, 1, random::nextDouble).isEmpty(),
                "no roll happens when every weight is 0");
    }

    /** 11. Erasing a pooled chapter erases the whole pool and every member's lock override. */
    @Test
    void pooledEraseTakesTheWholePool() {
        ProgressModel m = model("""
                {"chapters": {
                  "s:a": {"points": {"per_level": 1, "start": 1, "pool": "shared"}, "nodes": {"n": {"cost": 1}}},
                  "s:b": {"points": {"per_level": 1, "start": 1, "pool": "shared"}, "nodes": {"m": {"cost": 1}}}}}
                """);
        ProgressModel.Chapter a = m.chapters.get("s:a");
        String pooled = ProgressRules.key(a);
        PlayerProgress p = new PlayerProgress();
        p.xp.put(pooled, 5.0);
        p.level.put(pooled, 2);
        p.spent.put(pooled, 1);
        p.bonus.put(pooled, 1);
        p.exchange.put(pooled, 1);
        p.chapterOpen.put("s:a", false);
        p.chapterOpen.put("s:b", false);
        ProgressRules.eraseChapterData(p, m, a);
        assertTrue(p.xp.isEmpty());
        assertTrue(p.level.isEmpty());
        assertTrue(p.spent.isEmpty());
        assertTrue(p.bonus.isEmpty());
        assertTrue(p.exchange.isEmpty());
        assertTrue(p.chapterOpen.isEmpty(), "both chapters in the pool forget the override");
    }
    /** 11. Exchange costs come from level_limit and a values/expression cost_per_level curve at the purchase count. */
    @Test
    void exchangeCurvePricesEachPurchase() {
        ProgressModel m = model("""
                {"chapters": {"s:a": {"points": {"per_level": 1, "start": 0, "exchange": {
                  "level_limit": 2, "cost_per_level": {"type": "expression", "data": {"expression": "5 + level * 10"}}}},
                  "nodes": {}}}}
                """);
        ProgressModel.Chapter a = m.chapters.get("s:a");
        ProgressRules rules = new ProgressRules(m);
        PlayerProgress p = new PlayerProgress();
        assertEquals(5, ProgressRules.exchangeCost(p, a));
        p.exchange.put(ProgressRules.key(a), 1);
        assertEquals(15, ProgressRules.exchangeCost(p, a));
        assertFalse(ProgressRules.canExchange(p, a, 14));
        assertTrue(ProgressRules.canExchange(p, a, 15));
        p.exchange.put(ProgressRules.key(a), 2);
        assertFalse(ProgressRules.canExchange(p, a, 100), "the level limit is two purchases");
    }
    /** 10. Pufferfish's required points, spent points and exclusive count gate a skill without hiding its data. */
    @Test
    void pufferfishReturnRequirementsGateSkills() {
        ProgressModel m = model("""
                {"chapters": {"s:a": {"points": {"per_level": 1, "start": 2}, "nodes": {
                  "pts": {"requires": {"points": 3}},
                  "spent": {"requires": {"spent_points": 4}},
                  "a": {"exclusive_with": ["b", "c"]},
                  "b": {"requires": {"exclusions": 2}},
                  "c": {"exclusive_with": ["a"], "requires": {"exclusions": 1}}
                }}}}
                """);
        ProgressModel.Chapter a = m.chapters.get("s:a");
        ProgressRules rules = new ProgressRules(m);
        PlayerProgress p = new PlayerProgress();
        assertFalse(rules.unlocked(p, a.nodes.get("pts")));
        p.bonus.put(ProgressRules.key(a), 1);
        assertTrue(rules.unlocked(p, a.nodes.get("pts")), "three points left should pass a required_points of 3");
        assertFalse(rules.unlocked(p, a.nodes.get("spent")));
        p.spent.put(ProgressRules.key(a), 4);
        assertTrue(rules.unlocked(p, a.nodes.get("spent")));
        p.taken.put(a.nodes.get("a").key, 1);
        assertFalse(rules.excluded(p, a.nodes.get("b")), "one unlocked exclusive does not close a skill that wants two");
        assertTrue(rules.excluded(p, a.nodes.get("c")), "the default/explicit one closes it");
    }
    /** 7. A pack's malformed exchange/respec numbers fall back to the defaults instead of throwing. */
    @Test
    void malformedExchangeAndRespecNumbersUseDefaults() {
        ProgressModel m = model("""
                {"chapters": {"s:a": {"points": {
                  "exchange": {"xp_levels": "thirty", "level_limit": {}},
                  "respec": {"cost": "free"}}, "nodes": {}}}}
                """);
        ProgressModel.Chapter a = m.chapters.get("s:a");
        assertEquals(0, ProgressRules.exchangeOf(a).cost());
        assertEquals(Integer.MAX_VALUE, ProgressRules.exchangeOf(a).limit());
        assertEquals(0, ProgressRules.respecCost(a));
    }
    /** A points amount that is not a number pays and takes back nothing, the same reading on both sides (A5). */
    @Test
    void aPointsAmountThatIsNotANumberIsNotOwedBack() {
        ProgressModel m = model("""
                {"chapters": {
                  "s:a": {"points": {"start": 0}, "nodes": {"n": {"cost": 1,
                          "rewards": [{"type": "points", "amount": "lots", "chapter": "s:b"}]}}},
                  "s:b": {"points": {"start": 0}, "nodes": {}}}}
                """);
        ProgressModel.Chapter a = m.chapters.get("s:a");
        PlayerProgress p = new PlayerProgress();
        p.taken.put(a.nodes.get("n").key, 1);
        p.spent.put(ProgressRules.key(a), 1);
        assertTrue(new ProgressRules(m).canRespec(p, a), "a malformed amount owes nothing back");
    }

    /** 1. A respec takes back the points a skill paid out, and refuses when that would overdraw the pool. */
    @Test
    void respecRevokesPointsRewardsAndRefusesOverdraft() {
        ProgressModel m = model("""
                {"chapters": {"s:a": {"points": {"per_level": 1, "start": 1},
                                      "nodes": {"n": {"cost": 1, "rewards": [{"type": "points", "amount": 2}]}}}}}
                """);
        ProgressModel.Chapter a = m.chapters.get("s:a");
        ProgressModel.Node n = a.nodes.get("n");
        ProgressRules rules = new ProgressRules(m);
        PlayerProgress p = new PlayerProgress();
        p.taken.put(n.key, 1);
        p.spent.put(ProgressRules.key(a), 3);
        p.bonus.put(ProgressRules.key(a), 2);
        assertEquals(2, ProgressRules.chapterPointsReward(p, a));
        assertFalse(rules.canRespec(p, a), "the refund plus what is left does not cover the points the skill paid");
        p.spent.put(ProgressRules.key(a), 1);
        assertTrue(rules.canRespec(p, a));
        rules.revokePoints(p, n, 1);
        assertEquals(0, p.bonus.getOrDefault(ProgressRules.key(a), 0), "the paid points came back out once");
        assertEquals(0, rules.availablePoints(p, a));
        assertEquals(1, ProgressRules.refundChapterSpent(p, a));
        assertEquals(1, rules.availablePoints(p, a), "the cost comes back separately from the points reward");
    }
}
