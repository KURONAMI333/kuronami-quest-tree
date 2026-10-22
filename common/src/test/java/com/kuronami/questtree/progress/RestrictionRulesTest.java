package com.kuronami.questtree.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Item and block restrictions (WP11 G6). */
class RestrictionRulesTest {

    private static final String IRON_SWORD = "minecraft:iron_sword";
    private static final String DIAMOND = "minecraft:diamond";
    private static final String SWORDS = "minecraft:swords";
    private static final String IRON_ORE = "minecraft:iron_ore";
    private static final String ORES = "minecraft:ores";

    private static RestrictionRules rules(String json) {
        return RestrictionRules.of(JsonParser.parseString(json).getAsJsonObject());
    }

    private static RestrictionRules.TagMatcher tags(Set<String> tags) {
        return tags::contains;
    }

    @Test
    void anItemIdMatchesOnlyTheNamedAction() {
        RestrictionRules r = rules("""
                {"restrictions": [{"id": "no_swords", "items": ["minecraft:iron_sword"],
                                   "actions": ["use", "attack"],
                                   "conditions": [{"type": "stage", "stage": "one"}]}]}""");
        assertEquals(1, r.forItem(RestrictionRules.USE, IRON_SWORD, tags(Set.of())).size());
        assertEquals(1, r.forItem(RestrictionRules.ATTACK, IRON_SWORD, tags(Set.of())).size());
        assertTrue(r.forItem(RestrictionRules.BREAK, IRON_SWORD, tags(Set.of())).isEmpty());
        assertTrue(r.forItem(RestrictionRules.USE, DIAMOND, tags(Set.of())).isEmpty());
        assertTrue(r.forBlock(RestrictionRules.USE, IRON_ORE, tags(Set.of())).isEmpty());
    }

    @Test
    void anOmittedActionListCoversEveryAction() {
        RestrictionRules r = rules("""
                {"restrictions": [{"id": "all", "items": ["minecraft:iron_sword"],
                                   "conditions": [{"type": "stage", "stage": "one"}]}]}""");
        for (String action : RestrictionRules.ACTIONS) {
            assertEquals(1, r.forItem(action, IRON_SWORD, tags(Set.of())).size(), action);
        }
    }

    @Test
    void itemAndBlockTagsMatchThroughTheCaller() {
        RestrictionRules r = rules("""
                {"restrictions": [
                  {"id": "tagged", "item_tags": ["minecraft:swords"],
                   "conditions": [{"type": "stage", "stage": "one"}]},
                  {"id": "ore", "block_tags": ["minecraft:ores"], "actions": ["break"],
                   "conditions": [{"type": "stage", "stage": "one"}]}]}""");
        assertEquals(1, r.forItem(RestrictionRules.USE, DIAMOND, tags(Set.of(SWORDS))).size());
        assertTrue(r.forItem(RestrictionRules.USE, DIAMOND, tags(Set.of())).isEmpty());
        assertEquals("ore", r.forBlock(RestrictionRules.BREAK, IRON_ORE, tags(Set.of(ORES))).get(0).id());
        assertTrue(r.forBlock(RestrictionRules.PLACE, IRON_ORE, tags(Set.of(ORES))).isEmpty());
    }

    @Test
    void oneRuleCanNameBothItemsAndBlocks() {
        RestrictionRules r = rules("""
                {"restrictions": [{"id": "both", "items": ["minecraft:iron_sword"],
                                   "blocks": ["minecraft:iron_ore"],
                                   "conditions": [{"type": "stage", "stage": "one"}]}]}""");
        assertEquals(1, r.forItem(RestrictionRules.USE, IRON_SWORD, tags(Set.of())).size());
        assertEquals(1, r.forBlock(RestrictionRules.BREAK, IRON_ORE, tags(Set.of())).size());
    }

    @Test
    void conditionsAndTheReasonAreKeptForTheEnforcement() {
        RestrictionRules r = rules("""
                {"restrictions": [{"id": "gate", "items": ["minecraft:iron_sword"],
                                   "message": "Finish the trial",
                                   "conditions": [{"type": "score", "objective": "trial", "min": 3}]}]}""");
        RestrictionRules.Rule rule = r.forItem(RestrictionRules.USE, IRON_SWORD, tags(Set.of())).get(0);
        assertEquals("Finish the trial", rule.message());
        assertTrue(rule.json().get("conditions").isJsonArray());
        assertEquals(1, rule.json().getAsJsonArray("conditions").size());
    }

    @Test
    void aRuleWithoutTargetsOrConditionsIsReportedAndDropped() {
        JsonArray array = JsonParser.parseString("""
                [{"id": "empty", "conditions": [{"type": "stage", "stage": "one"}]},
                 {"id": "no_condition", "items": ["minecraft:iron_sword"]},
                 {"id": "unknown_action", "items": ["minecraft:iron_sword"], "actions": ["smelt"],
                  "conditions": [{"type": "stage", "stage": "one"}]},
                 {"id": "bad_id", "items": ["not a valid id"], "conditions": []},
                 {"id": "empty"},
                 {"conditions": [{"type": "stage", "stage": "one"}], "items": ["minecraft:diamond"]}]
                """).getAsJsonArray();
        RestrictionRules.Result result = RestrictionRules.parse(array);
        // the broken shapes are dropped; a rule with no condition or no known action is kept but reported, because
        // the enforcement can still keep it shut and the author has to see the warning
        assertEquals(2, result.rules().size());
        assertTrue(result.rules().stream().noneMatch(rule -> rule.id().equals("bad_id")));
        assertTrue(result.rules().stream().filter(rule -> rule.id().equals("unknown_action"))
                .allMatch(rule -> rule.actions().isEmpty()));
        assertFalse(result.problems().isEmpty());
        assertTrue(result.problems().stream().anyMatch(p -> p.message().contains("applies to nothing")));
        assertTrue(result.problems().stream().anyMatch(p -> p.message().contains("would never open")));
        assertTrue(result.problems().stream().anyMatch(p -> p.message().contains("unknown action")));
        assertTrue(result.problems().stream().anyMatch(p -> p.message().contains("not an id")));
        assertTrue(result.problems().stream().anyMatch(p -> p.message().contains("needs an id")));
    }

    @Test
    void duplicateIdsAreReportedWithinOneFile() {
        JsonArray array = JsonParser.parseString("""
                [{"id": "same", "items": ["minecraft:iron_sword"], "conditions": [{"type": "stage", "stage": "one"}]},
                 {"id": "same", "items": ["minecraft:diamond"], "conditions": [{"type": "stage", "stage": "one"}]}]
                """).getAsJsonArray();
        RestrictionRules.Result result = RestrictionRules.parse(array);
        assertEquals(1, result.rules().size());
        assertEquals("minecraft:iron_sword",
                result.rules().get(0).json().getAsJsonArray("items").get(0).getAsString());
        assertTrue(result.problems().stream().anyMatch(p -> p.message().contains("duplicate rule id")));
    }

    @Test
    void anEmptyOrMissingListAllowsEverything() {
        assertTrue(RestrictionRules.of(new JsonObject()).isEmpty());
        assertTrue(rules("{\"restrictions\": []}").isEmpty());
        // the top-level shape being wrong is the caller's report, not a half-read rule
        assertTrue(rules("{\"restrictions\": {}}").isEmpty());
    }
}
