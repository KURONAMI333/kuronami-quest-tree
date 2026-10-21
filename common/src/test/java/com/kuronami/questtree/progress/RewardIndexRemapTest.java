package com.kuronami.questtree.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Reward claim records follow a reorder, an insert and a delete (2026-10-22 release review). */
class RewardIndexRemapTest {

    private static JsonObject chapters(String rewards) {
        return JsonParser.parseString("{\"t:c\": {\"nodes\": {\"n\": {\"rewards\": " + rewards + "}}}}")
                .getAsJsonObject();
    }

    @Test
    void reorderMovesEveryClaimToItsReward() {
        Map<String, Map<Integer, Integer>> remaps = RewardIndexRemap.between(
                chapters("[{\"type\": \"item\", \"item\": \"a\"}, {\"type\": \"item\", \"item\": \"b\"}, {\"type\": \"item\", \"item\": \"c\"}]"),
                chapters("[{\"type\": \"item\", \"item\": \"c\"}, {\"type\": \"item\", \"item\": \"a\"}, {\"type\": \"item\", \"item\": \"b\"}]"));
        assertEquals("t:c/n#1", RewardIndexRemap.apply("t:c/n#0", remaps));
        assertEquals("t:c/n#2", RewardIndexRemap.apply("t:c/n#1", remaps));
        assertEquals("t:c/n#0", RewardIndexRemap.apply("t:c/n#2", remaps));
    }

    @Test
    void insertAtTheFrontKeepsExistingClaims() {
        Map<String, Map<Integer, Integer>> remaps = RewardIndexRemap.between(
                chapters("[{\"type\": \"item\", \"item\": \"a\"}, {\"type\": \"item\", \"item\": \"b\"}]"),
                chapters("[{\"type\": \"item\", \"item\": \"new\"}, {\"type\": \"item\", \"item\": \"a\"}, {\"type\": \"item\", \"item\": \"b\"}]"));
        assertEquals("t:c/n#1", RewardIndexRemap.apply("t:c/n#0", remaps));
        assertEquals("t:c/n#2", RewardIndexRemap.apply("t:c/n#1", remaps));
    }

    @Test
    void deleteDropsTheClaimOfTheRemovedReward() {
        Map<String, Map<Integer, Integer>> remaps = RewardIndexRemap.between(
                chapters("[{\"type\": \"item\", \"item\": \"a\"}, {\"type\": \"item\", \"item\": \"b\"}, {\"type\": \"item\", \"item\": \"c\"}]"),
                chapters("[{\"type\": \"item\", \"item\": \"a\"}, {\"type\": \"item\", \"item\": \"c\"}]"));
        assertEquals("t:c/n#1", RewardIndexRemap.apply("t:c/n#2", remaps));
        assertNull(RewardIndexRemap.apply("t:c/n#1", remaps));

        Set<String> claims = new LinkedHashSet<>(Set.of("t:c/n#0", "t:c/n#1", "t:c/n#2"));
        assertTrue(RewardIndexRemap.applyTo(claims, remaps));
        assertEquals(Set.of("t:c/n#0", "t:c/n#1"), claims);
    }

    @Test
    void threePartRecordsUseTheSameMappingAndUnchangedNodesAreLeftAlone() {
        JsonObject before = JsonParser.parseString("{\"t:c\": {\"nodes\": {"
                + "\"n\": {\"rewards\": [{\"type\": \"skill\", \"skill\": \"a\"}, {\"type\": \"skill\", \"skill\": \"b\"}]},"
                + "\"other\": {\"rewards\": [{\"type\": \"item\", \"item\": \"a\"}]}}}}").getAsJsonObject();
        JsonObject after = JsonParser.parseString("{\"t:c\": {\"nodes\": {"
                + "\"n\": {\"rewards\": [{\"type\": \"skill\", \"skill\": \"b\"}, {\"type\": \"skill\", \"skill\": \"a\"}]},"
                + "\"other\": {\"rewards\": [{\"type\": \"item\", \"item\": \"a\"}]}}}}").getAsJsonObject();
        Map<String, Map<Integer, Integer>> remaps = RewardIndexRemap.between(before, after);
        assertEquals("t:c/n#-1#0", RewardIndexRemap.apply("t:c/n#-1#1", remaps));
        assertEquals("t:c/n#-1#1", RewardIndexRemap.apply("t:c/n#-1#0", remaps));
        // a node whose rewards did not change is not in the map, so its keys stay as they are
        assertFalse(remaps.containsKey("t:c/other"));
        assertEquals("t:c/other#0", RewardIndexRemap.apply("t:c/other#0", remaps));
    }

    @Test
    void identicalRewardsArePairedInOrder() {
        Map<String, Map<Integer, Integer>> remaps = RewardIndexRemap.between(
                chapters("[{\"type\": \"item\", \"item\": \"a\"}, {\"type\": \"item\", \"item\": \"a\"}]"),
                chapters("[{\"type\": \"item\", \"item\": \"a\"}]"));
        assertEquals("t:c/n#0", RewardIndexRemap.apply("t:c/n#0", remaps));
        assertNull(RewardIndexRemap.apply("t:c/n#1", remaps));
    }
}
