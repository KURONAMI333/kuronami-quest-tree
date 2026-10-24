package com.kuronami.questtree.progress;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A {@code build} task's layout: every written offset must hold its block. */
class BuildTaskRulesTest {

    private static final Predicate<String> TAGS = Set.of("minecraft:stone_bricks")::contains;

    /** A layout of three blocks around the anchor, as the running game would answer it. */
    private static final Map<String, String> WORLD = Map.of(
            "0,0,0", "minecraft:gold_block",
            "1,0,0", "minecraft:stone_bricks",
            "0,1,0", "minecraft:torch");

    private static JsonObject task(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static BuildTaskRules.BlockAt at(Map<String, String> world) {
        return new BuildTaskRules.BlockAt() {
            @Override
            public String id(int dx, int dy, int dz) {
                return world.getOrDefault(dx + "," + dy + "," + dz, "minecraft:air");
            }

            @Override
            public boolean tag(int dx, int dy, int dz, String tag) {
                String id = id(dx, dy, dz);
                // the test world has one stone-brick block; the tag covers it by id
                return tag.equals("minecraft:stone_bricks") && id.equals("minecraft:stone_bricks");
            }
        };
    }

    private static final String LAYOUT = """
            {"type": "build", "anchor": "minecraft:gold_block",
             "blocks": [{"pos": [0,0,0], "block": "minecraft:gold_block"},
                        {"pos": [1,0,0], "block": "#minecraft:stone_bricks"},
                        {"pos": [0,1,0], "block": "minecraft:torch"}]}
            """;

    @Test
    void everyWrittenBlockMustMatch() {
        assertTrue(BuildTaskRules.matches(task(LAYOUT), at(WORLD)));
    }

    @Test
    void oneWrongBlockStopsTheBuild() {
        Map<String, String> world = new HashMap<>(WORLD);
        world.put("0,1,0", "minecraft:redstone_torch");
        assertFalse(BuildTaskRules.matches(task(LAYOUT), at(world)));
    }

    @Test
    void aTagEntryMatchesTheBlockInIt() {
        Map<String, String> world = new HashMap<>(WORLD);
        world.put("1,0,0", "minecraft:stone_bricks");
        assertTrue(BuildTaskRules.matches(task(LAYOUT), at(world)));

        world.put("1,0,0", "minecraft:dirt");
        assertFalse(BuildTaskRules.matches(task(LAYOUT), at(world)));
    }

    @Test
    void anAnchorOnlyLimitsWhichPlacementChecksTheLayout() {
        // no anchor: any placement runs the check, and the layout still holds its gold block at the anchor offset
        assertTrue(BuildTaskRules.matchesAnchor(task("""
                {"type": "build", "blocks": [{"pos": [0,0,0], "block": "minecraft:gold_block"}]}
                """), "minecraft:iron_block", TAGS));

        assertTrue(BuildTaskRules.matchesAnchor(task(LAYOUT), "minecraft:gold_block", TAGS));
        assertFalse(BuildTaskRules.matchesAnchor(task(LAYOUT), "minecraft:iron_block", TAGS));
        assertTrue(BuildTaskRules.matchesAnchor(task("""
                {"type": "build", "anchor": "#minecraft:stone_bricks", "blocks": [{"pos": [0,0,0], "block": "air"}]}
                """), "minecraft:stone_bricks", TAGS));
        assertFalse(BuildTaskRules.matchesAnchor(task("""
                {"type": "build", "anchor": 5, "blocks": [{"pos": [0,0,0], "block": "air"}]}
                """), "minecraft:stone", TAGS), "a non-string anchor never triggers");
    }

    @Test
    void brokenLayoutsCannotComplete() {
        assertFalse(BuildTaskRules.matches(task("{\"type\": \"build\"}"), at(WORLD)));
        assertFalse(BuildTaskRules.matches(task("{\"type\": \"build\", \"blocks\": {}}"), at(WORLD)));
        assertFalse(BuildTaskRules.matches(task("{\"type\": \"build\", \"blocks\": []}"), at(WORLD)));
        assertFalse(BuildTaskRules.matches(task("{\"type\": \"build\", \"blocks\": [5]}"), at(WORLD)));
        assertFalse(BuildTaskRules.matches(task("""
                {"type": "build", "blocks": [{"pos": [0,0], "block": "minecraft:gold_block"}]}
                """), at(WORLD)));
        assertFalse(BuildTaskRules.matches(task("""
                {"type": "build", "blocks": [{"pos": [0,0,0], "block": ""}]}
                """), at(WORLD)));
        assertFalse(BuildTaskRules.matches(task("""
                {"type": "build", "blocks": [{"pos": [0,0,0]}]}
                """), at(WORLD)));
    }

    @Test
    void sizeCountsOnlyReadableEntries() {
        assertEquals(3, BuildTaskRules.size(task(LAYOUT)));
        assertEquals(0, BuildTaskRules.size(task("{\"type\": \"build\"}")));
        assertEquals(1, BuildTaskRules.size(task("""
                {"type": "build", "blocks": [{"pos": [0,0,0], "block": "minecraft:gold_block"}, {"pos": [0,0]}, 7]}
                """)));
    }

    @Test
    void layoutsPastTheLimitsNeverComplete() {
        StringBuilder big = new StringBuilder("{\"type\": \"build\", \"blocks\": [");
        for (int i = 0; i < BuildTaskRules.MAX_BLOCKS + 1; i++) {
            if (i > 0) {
                big.append(',');
            }
            big.append("{\"pos\": [0,0,0], \"block\": \"minecraft:air\"}");
        }
        big.append("]}");
        assertFalse(BuildTaskRules.matches(task(big.toString()), at(Map.of())), "too many entries");

        assertFalse(BuildTaskRules.matches(task("""
                {"type": "build", "blocks": [{"pos": [33,0,0], "block": "minecraft:air"}]}
                """), at(Map.of("33,0,0", "minecraft:air"))), "an offset past the limit");
    }
}
