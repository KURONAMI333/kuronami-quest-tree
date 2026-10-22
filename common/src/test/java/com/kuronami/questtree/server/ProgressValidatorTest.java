package com.kuronami.questtree.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kuronami.questtree.importer.FtbQuestsImporter;
import com.kuronami.questtree.importer.ImportOutput;
import com.kuronami.questtree.importer.PuffishImporter;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The data check (DATA_MODEL.md "検査"): what is an ERROR, what is a WARNING, and what is dropped. */
class ProgressValidatorTest {

    private static ProgressValidator.Result validate(String json) {
        return validate(json, ProgressValidator.Registry.NONE);
    }

    private static ProgressValidator.Result validate(String json, ProgressValidator.Registry registry) {
        JsonObject bundle = JsonParser.parseString(json).getAsJsonObject();
        return ProgressValidator.validate(bundle, Map.of(), registry);
    }

    private static long errors(ProgressValidator.Result r) {
        return r.problems.stream().filter(p -> p.severity() == ProgressValidator.Severity.ERROR).count();
    }

    private static long warnings(ProgressValidator.Result r) {
        return r.problems.stream().filter(p -> p.severity() == ProgressValidator.Severity.WARNING).count();
    }

    private static boolean says(ProgressValidator.Result r, String fragment) {
        return r.problems.stream().anyMatch(p -> p.message().contains(fragment));
    }

    /** A chapter with one node whose single task and reward are written out as given. */
    private static String chapter(String tasks, String rewards) {
        return """
                {"chapters": {"t:basics": {"id": "basics", "nodes": {"a": {"id": "a", "tasks": [%s], "rewards": [%s]}}}},
                 "reward_tables": {}}""".formatted(tasks, rewards);
    }

    @Test
    void aNonFiniteNodeCoordinateIsAWarningAndTheNodeStays() {
        JsonObject bundle = JsonParser.parseString(chapter(
                "{\"type\": \"checkmark\", \"count\": 1}", "")).getAsJsonObject();
        bundle.getAsJsonObject("chapters").getAsJsonObject("t:basics")
                .getAsJsonObject("nodes").getAsJsonObject("a")
                .addProperty("x", Double.POSITIVE_INFINITY);
        ProgressValidator.Result r = ProgressValidator.validate(bundle, Map.of(), ProgressValidator.Registry.NONE);
        assertEquals(0, errors(r));
        assertTrue(says(r, "not a finite number"));
        assertTrue(r.droppedNodes.isEmpty());
    }

    @Test
    void anEnergyTaskWarnsOnALoaderWithoutEnergy() {
        String json = chapter("{\"type\": \"energy\", \"count\": 1000}", "{\"type\": \"xp\", \"amount\": 10}");
        ProgressValidator.Result neo = validate(json, ProgressValidator.Registry.NONE);
        assertFalse(says(neo, "energy tasks are counted only on NeoForge"));
        ProgressValidator.Registry fabric = new ProgressValidator.Registry() {
            @Override
            public boolean known(String kind, String id) {
                return true;
            }

            @Override
            public boolean energyAvailable() {
                return false;
            }
        };
        ProgressValidator.Result r = validate(json, fabric);
        assertEquals(0, errors(r));
        assertTrue(says(r, "energy tasks are counted only on NeoForge"));
    }

    @Test
    void aWellFormedNodeHasNoProblems() {
        ProgressValidator.Result r = validate(chapter(
                "{\"type\": \"item\", \"count\": 1, \"item\": {\"id\": \"minecraft:stone\"}}",
                "{\"type\": \"xp\", \"amount\": 10}"));
        assertEquals(List.of(), r.problems);
        assertTrue(r.droppedNodes.isEmpty());
    }

    @Test
    void anUnknownTaskTypeIsAnErrorAndDropsTheNode() {
        ProgressValidator.Result r = validate(chapter("{\"type\": \"nope\", \"count\": 1}", ""));
        assertEquals(1, errors(r));
        assertTrue(says(r, "unknown task type"));
        assertTrue(r.droppedNodes.contains("t:basics/a"));
    }

    @Test
    void anItemTaskWithoutAnItemIsAnError() {
        ProgressValidator.Result r = validate(chapter("{\"type\": \"item\", \"count\": 1}", ""));
        assertTrue(says(r, "item task has no item"));
        assertTrue(r.droppedNodes.contains("t:basics/a"));
    }

    @Test
    void aKillTaskWithoutAnEntityIsFine() {
        ProgressValidator.Result r = validate(chapter("{\"type\": \"kill\", \"count\": 5}", ""));
        assertEquals(List.of(), r.problems);
    }

    @Test
    void aFluidTaskNeedsAFluid() {
        assertEquals(1, errors(validate(chapter("{\"type\": \"fluid\", \"count\": 1, \"amount\": 100}", ""))));
        ProgressValidator.Result ok = validate(chapter(
                "{\"type\": \"fluid\", \"count\": 1, \"fluid\": \"minecraft:water\", \"amount\": 100}", ""));
        assertEquals(List.of(), ok.problems);
    }

    @Test
    void aLocationTaskNeedsAThreeNumberPosition() {
        assertEquals(1, errors(validate(chapter("{\"type\": \"location\", \"count\": 1}", ""))));
        ProgressValidator.Result ok = validate(chapter(
                "{\"type\": \"location\", \"count\": 1, \"position\": [1, 2, 3]}", ""));
        assertEquals(List.of(), ok.problems);
    }

    @Test
    void anObservationTaskNeedsATarget() {
        assertEquals(1, errors(validate(chapter("{\"type\": \"observation\", \"count\": 1}", ""))));
        ProgressValidator.Result ok = validate(chapter(
                "{\"type\": \"observation\", \"count\": 1, \"target\": \"minecraft:stone\"}", ""));
        assertEquals(List.of(), ok.problems);
    }

    @Test
    void aBreakTaskWithoutABlockIsFine() {
        // an empty block means "any block" on purpose (ProgressService)
        assertEquals(List.of(), validate(chapter("{\"type\": \"break\", \"count\": 1}", "")).problems);
    }

    @Test
    void interactionAndDummyAreImplementedNow() {
        ProgressValidator.Result r = validate(chapter(
                "{\"type\": \"interaction\", \"action\": \"pickup\", \"item\": \"minecraft:stone\"}",
                "{\"type\": \"dummy\"}"));
        assertEquals(List.of(), r.problems);
        assertTrue(r.droppedNodes.isEmpty());
    }

    @Test
    void anInteractionWithoutAnActionIsAnError() {
        ProgressValidator.Result r = validate(chapter("{\"type\": \"interaction\", \"count\": 1}", ""));
        assertTrue(says(r, "has no \"action\""));
        assertTrue(r.droppedNodes.contains("t:basics/a"));
    }

    @Test
    void interactionTasksCheckTheirMatchingField() {
        assertEquals(List.of(), validate(chapter(
                "{\"type\": \"craft\", \"item\": \"minecraft:stone\"}", "")).problems);
        assertEquals(List.of(), validate(chapter(
                "{\"type\": \"right_click_block\", \"block\": \"minecraft:lever\"}", "")).problems);
        assertEquals(List.of(), validate(chapter(
                "{\"type\": \"tame\", \"entity\": \"minecraft:wolf\"}", "")).problems);
        assertTrue(says(validate(chapter(
                "{\"type\": \"interaction\", \"action\": \"nope\"}", "")), "unknown interaction action"));
    }

    @Test
    void killFiltersAreShapedText() {
        ProgressValidator.Result r = validate(chapter(
                "{\"type\": \"kill\", \"entity\": \"minecraft:zombie\", \"name\": \"Bob\", \"nbt\": \"{HasOnGround:1b}\"}", ""));
        assertEquals(List.of(), r.problems);
    }

    @Test
    void customTaskWarnsOnlyWithoutItsButtonAndScoreboardIsImplemented() {
        ProgressValidator.Result blocked = validate(chapter("{\"type\": \"custom\", \"count\": 1}", ""));
        assertEquals(0, errors(blocked));
        assertTrue(says(blocked, "never completes"));
        ProgressValidator.Result manual = validate(chapter(
                "{\"type\": \"custom\", \"count\": 1, \"enable_button\": true}", "{\"type\": \"scoreboard\", \"objective\": \"points\"}"));
        assertEquals(List.of(), manual.problems);
    }

    @Test
    void aCraftTaskCanNameItsRecipe() {
        ProgressValidator.Result ok = validate(chapter(
                "{\"type\": \"craft\", \"item\": \"minecraft:stick\", \"recipe\": \"minecraft:stick\"}", ""));
        assertEquals(0, errors(ok), ok.problems.toString());
    }

    @Test
    void aCurrencyRewardNeedsAPositiveAmount() {
        ProgressValidator.Result ok = validate(chapter("", "{\"type\": \"currency\", \"amount\": 50}"));
        assertEquals(0, errors(ok), ok.problems.toString());
        ProgressValidator.Result bad = validate(chapter("", "{\"type\": \"currency\"}"));
        assertTrue(says(bad, "amount"));
    }

    @Test
    void conditionsAreCheckedOnTasksAndRewards() {
        ProgressValidator.Result ok = validate(chapter(
                "{\"type\": \"item\", \"count\": 1, \"item\": \"minecraft:stone\", \"conditions\": ["
                        + "{\"type\": \"stage\", \"stage\": \"chapter_one\"},"
                        + "{\"type\": \"score\", \"objective\": \"points\", \"min\": 2},"
                        + "{\"type\": \"advancement\", \"advancement\": \"minecraft:story/root\"},"
                        + "{\"type\": \"party_size\", \"min\": 2}]}",
                "{\"type\": \"xp\", \"amount\": 1, \"conditions\": [{\"type\": \"item\", \"item\": \"minecraft:stone\"}]}"));
        assertEquals(List.of(), ok.problems, ok.problems.toString());
    }

    @Test
    void aConditionWithoutItsFieldIsAnError() {
        ProgressValidator.Result r = validate(chapter(
                "{\"type\": \"item\", \"count\": 1, \"item\": \"minecraft:stone\", \"conditions\": [{\"type\": \"stage\"}]}", ""));
        assertTrue(says(r, "has no stage"));
        assertTrue(r.droppedNodes.contains("t:basics/a"));
    }

    @Test
    void anUnknownConditionOnlyWarns() {
        ProgressValidator.Result r = validate(chapter(
                "{\"type\": \"item\", \"count\": 1, \"item\": \"minecraft:stone\", \"conditions\": [{\"type\": \"nope\"}]}", ""));
        assertEquals(0, errors(r));
        assertTrue(says(r, "unknown condition type"));
    }

    @Test
    void anotherModsNamespaceIsNeverABuiltInCondition() {
        ProgressValidator.Result other = validate(chapter(
                "{\"type\": \"item\", \"count\": 1, \"item\": \"minecraft:stone\", \"conditions\": ["
                        + "{\"type\": \"example:stage\", \"stage\": \"chapter_one\"}]}", ""));
        assertTrue(says(other, "unknown condition type"), "example:stage is not our built-in stage");
        ProgressValidator.Result ours = validate(chapter(
                "{\"type\": \"item\", \"count\": 1, \"item\": \"minecraft:stone\", \"conditions\": ["
                        + "{\"type\": \"kuronami_quest_tree:stage\", \"stage\": \"chapter_one\"}]}", ""));
        assertEquals(List.of(), ours.problems, ours.problems.toString());
    }

    @Test
    void aZeroCountIsAWarning() {
        ProgressValidator.Result r = validate(chapter(
                "{\"type\": \"item\", \"count\": 0, \"item\": {\"id\": \"minecraft:stone\"}}", ""));
        assertEquals(0, errors(r));
        assertTrue(says(r, "treated as 1"));
    }

    @Test
    void anOmittedCountIsTheDocumentedDefaultAndIsNotReported() {
        // omitting count means 1 (DATA_MODEL); warning on it would flood the log of every pack
        ProgressValidator.Result r = validate(chapter(
                "{\"type\": \"item\", \"item\": {\"id\": \"minecraft:stone\"}}", ""));
        assertEquals(List.of(), r.problems);
    }

    @Test
    void anIdThatTheGameDoesNotKnowIsAWarning() {
        ProgressValidator.Registry registry = (kind, id) -> !id.endsWith("notreal");
        ProgressValidator.Result r = validate(chapter(
                "{\"type\": \"item\", \"count\": 1, \"item\": {\"id\": \"minecraft:notreal\"}}", ""), registry);
        assertEquals(0, errors(r));
        assertTrue(says(r, "does not exist"));
    }

    @Test
    void anItemIdWithoutANamespaceIsNotReported() {
        // ResourceLocation.tryParse supplies "minecraft" when the namespace is left out, so "stone" is the same id
        ProgressValidator.Registry registry = (kind, id) -> true;
        ProgressValidator.Result r = validate(chapter(
                "{\"type\": \"item\", \"count\": 1, \"item\": {\"id\": \"stone\"}}", ""), registry);
        assertEquals(List.of(), r.problems);
    }

    @Test
    void aBlockIdWithoutANamespaceIsAWarning() {
        // blockMatches compares the raw text against the block's full id, so "stone" never matches "minecraft:stone"
        ProgressValidator.Registry registry = (kind, id) -> true;
        ProgressValidator.Result r = validate(chapter(
                "{\"type\": \"break\", \"count\": 1, \"block\": \"stone\"}", ""), registry);
        assertEquals(0, errors(r));
        assertTrue(says(r, "has no namespace, so it never matches"));
    }

    @Test
    void contentTheLoadedWorldDoesNotHaveIsAWarningAndTheNodeStays() {
        ProgressValidator.Registry registry = (kind, id) -> !id.endsWith("notreal");
        ProgressValidator.Result r = validate(chapter("""
                {"type": "biome", "biome": "minecraft:notreal"},
                {"type": "structure", "structure": "minecraft:notreal"},
                {"type": "dimension", "dimension": "minecraft:notreal"},
                {"type": "advancement", "advancement": "minecraft:notreal"},
                {"type": "craft", "item": "minecraft:stone", "recipe": "minecraft:notreal"}
                """, ""), registry);
        assertEquals(0, errors(r), r.problems.toString());
        assertEquals(5, warnings(r), r.problems.toString());
        assertTrue(says(r, "biome \"minecraft:notreal\" does not exist"));
        assertTrue(says(r, "structure \"minecraft:notreal\" does not exist"));
        assertTrue(says(r, "dimension \"minecraft:notreal\" does not exist"));
        assertTrue(says(r, "advancement \"minecraft:notreal\" does not exist"));
        assertTrue(says(r, "recipe \"minecraft:notreal\" does not exist"));
        assertTrue(r.droppedNodes.isEmpty());
    }

    @Test
    void anAdvancementConditionAndRewardAreChecked() {
        ProgressValidator.Registry registry = (kind, id) -> !id.endsWith("notreal");
        ProgressValidator.Result r = validate(chapter(
                "{\"type\": \"item\", \"count\": 1, \"item\": \"minecraft:stone\", \"conditions\": ["
                        + "{\"type\": \"advancement\", \"advancement\": \"minecraft:notreal\"}]}",
                "{\"type\": \"advancement\", \"advancement\": \"minecraft:notreal\"}"), registry);
        assertEquals(0, errors(r));
        assertTrue(says(r, "advancement \"minecraft:notreal\" does not exist"));
        assertEquals(2, warnings(r));
    }

    @Test
    void aMissingOrEmptyTagIsAWarning() {
        ProgressValidator.Registry registry = new ProgressValidator.Registry() {
            @Override
            public boolean known(String kind, String id) {
                return true;
            }

            @Override
            public ProgressValidator.TagStatus tag(String kind, String id) {
                if (id.equals("minecraft:missing_tag")) {
                    return ProgressValidator.TagStatus.MISSING;
                }
                if (id.equals("minecraft:empty_tag")) {
                    return ProgressValidator.TagStatus.EMPTY;
                }
                return ProgressValidator.TagStatus.OK;
            }
        };
        ProgressValidator.Result r = validate(chapter("""
                {"type": "break", "count": 1, "block": "#missing_tag"},
                {"type": "break", "count": 1, "block": "#empty_tag"},
                {"type": "item", "count": 1, "item": {"tag": "missing_tag"}}
                """, ""), registry);
        assertEquals(0, errors(r), r.problems.toString());
        assertEquals(3, warnings(r), r.problems.toString());
        assertTrue(says(r, "tag \"#missing_tag\" does not exist in this pack"));
        assertTrue(says(r, "tag \"#empty_tag\" is empty, so it never matches"));
        assertTrue(r.droppedNodes.isEmpty());
    }

    @Test
    void observationTargetsAreCheckedIncludingBlockStatesAndTags() {
        ProgressValidator.Registry registry = new ProgressValidator.Registry() {
            @Override
            public boolean known(String kind, String id) {
                return !id.endsWith("notreal");
            }

            @Override
            public ProgressValidator.TagStatus tag(String kind, String id) {
                return id.endsWith("missing_tag")
                        ? ProgressValidator.TagStatus.MISSING : ProgressValidator.TagStatus.OK;
            }
        };
        ProgressValidator.Result r = validate(chapter("""
                {"type": "observation", "observe_type": "block_state", "target": "minecraft:notreal[lit=true]"},
                {"type": "observation", "observe_type": "block_tag", "target": "missing_tag"},
                {"type": "observation", "observe_type": "entity_type_tag", "target": "#missing_tag"}
                """, ""), registry);
        assertEquals(0, errors(r), r.problems.toString());
        assertEquals(3, warnings(r), r.problems.toString());
        assertTrue(says(r, "block \"minecraft:notreal\" does not exist"));
        assertTrue(says(r, "tag \"#missing_tag\" does not exist in this pack"));
        assertTrue(r.droppedNodes.isEmpty());
    }

    @Test
    void problemsAreGroupedByChapterOrTable() {
        assertEquals("chapters.basics",
                ProgressValidator.chapterOfPath("chapters.basics.nodes.a.tasks[0].item"));
        assertEquals("chapters.dimensions",
                ProgressValidator.chapterOfPath("chapters.dimensions.points.exchange"));
        assertEquals("reward_tables", ProgressValidator.chapterOfPath("reward_tables.t:loot.rolls[0]"));
        assertEquals("settings", ProgressValidator.chapterOfPath("settings.emergency.items[0]"));
        assertEquals("progression", ProgressValidator.chapterOfPath(""));
    }

    @Test
    void anUnknownRequireIsAnError() {
        String json = """
                {"chapters": {"t:basics": {"nodes": {
                    "a": {"id": "a", "requires": {"nodes": ["ghost"]}, "tasks": []}}}},
                 "reward_tables": {}}""";
        ProgressValidator.Result r = validate(json);
        assertEquals(1, errors(r));
        assertTrue(says(r, "does not exist"));
        assertTrue(r.droppedNodes.contains("t:basics/a"));
    }

    @Test
    void aRequirementCycleThatNothingReachesIsAnErrorAndDropsEveryNodeInIt() {
        // neither node can open from anywhere, whatever the player does
        String json = """
                {"chapters": {"t:basics": {"nodes": {
                    "a": {"id": "a", "requires": {"nodes": ["b"]}},
                    "b": {"id": "b", "requires": {"nodes": ["a"]}}}}},
                 "reward_tables": {}}""";
        ProgressValidator.Result r = validate(json);
        assertEquals(2, errors(r));
        assertTrue(says(r, "can never be unlocked"));
        assertTrue(r.droppedNodes.contains("t:basics/a"));
        assertTrue(r.droppedNodes.contains("t:basics/b"));
    }

    @Test
    void aCycleThroughARootNodeIsNotReported() {
        // the Puffish root is open from the start even though the connections point back at it (ProgressRules.unlocked)
        String json = """
                {"chapters": {"t:skills": {"nodes": {
                    "root": {"id": "root", "root": true, "requires": {"nodes": ["s0"], "mode": "any"}},
                    "s0": {"id": "s0", "requires": {"nodes": ["root"], "mode": "any"}}}}},
                 "reward_tables": {}}""";
        ProgressValidator.Result r = validate(json);
        assertEquals(List.of(), r.problems);
    }

    @Test
    void aPuffishChainOfBothWayAnyLinksHasNoProblems() {
        // PuffishImporter writes both directions of a link with mode "any" (PuffishImporter), so a chain like this is
        // normal: each node opens from the one before it. A cycle search alone would call this a problem
        String json = """
                {"chapters": {"t:skills": {"nodes": {
                    "root": {"id": "root", "root": true, "requires": {"nodes": ["s0"], "mode": "any"}},
                    "s0": {"id": "s0", "requires": {"nodes": ["root", "s1"], "mode": "any"}},
                    "s1": {"id": "s1", "requires": {"nodes": ["s0", "s2"], "mode": "any"}},
                    "s2": {"id": "s2", "requires": {"nodes": ["s1"], "mode": "any"}}}}},
                 "reward_tables": {}}""";
        ProgressValidator.Result r = validate(json);
        assertEquals(List.of(), r.problems);
    }

    @Test
    void anAnyLoopThatNothingElseReachesIsStillAnError() {
        // "any" does not save a loop that no open node ever enters
        String json = """
                {"chapters": {"t:basics": {"nodes": {
                    "a": {"id": "a", "requires": {"nodes": ["b"], "mode": "any"}},
                    "b": {"id": "b", "requires": {"nodes": ["a"], "mode": "any"}}}}},
                 "reward_tables": {}}""";
        ProgressValidator.Result r = validate(json);
        assertEquals(2, errors(r));
        assertTrue(says(r, "can never be unlocked"));
        assertTrue(r.droppedNodes.contains("t:basics/a"));
        assertTrue(r.droppedNodes.contains("t:basics/b"));
    }

    @Test
    void aRequireOnADroppedNodeIsOnlyAWarning() {
        // b is broken (unknown task type) and gets dropped; a still points at it but must survive
        String json = """
                {"chapters": {"t:basics": {"nodes": {
                    "a": {"id": "a", "requires": {"nodes": ["b"]}},
                    "b": {"id": "b", "tasks": [{"type": "nope"}]}}}},
                 "reward_tables": {}}""";
        ProgressValidator.Result r = validate(json);
        assertTrue(says(r, "which was dropped"));
        assertTrue(r.droppedNodes.contains("t:basics/b"));
        assertFalse(r.droppedNodes.contains("t:basics/a"));
    }

    @Test
    void aRewardThatPointsAtAMissingTableIsOnlyAWarning() {
        // Rewards.give catches what its readers throw, so a reward that cannot be given only skips itself
        ProgressValidator.Result r = validate(chapter("", "{\"type\": \"table\", \"table\": \"t:nope\"}"));
        assertEquals(0, errors(r));
        assertTrue(says(r, "does not exist"));
        assertFalse(r.droppedNodes.contains("t:basics/a"));
    }

    @Test
    void aRewardTableIsCheckedThroughItsEntries() {
        String json = """
                {"chapters": {},
                 "reward_tables": {"t:gems": {"rolls": 1, "entries": [
                    {"weight": 1.0, "reward": {"type": "item", "item": {"id": "minecraft:diamond"}}},
                    {"weight": 1.0}]}}}""";
        ProgressValidator.Result r = validate(json);
        assertTrue(says(r, "entry has no \"reward\""));
    }

    @Test
    void aSyntaxErrorIsReportedWithItsLine() {
        String bad = "{\n  \"chapters\": {\n    \"a\": ,\n  }\n}";
        ProgressValidator.Problem p;
        try {
            JsonParser.parseString(bad);
            throw new AssertionError("expected a syntax error");
        } catch (com.google.gson.JsonSyntaxException ex) {
            p = ProgressValidator.parseError("data/t/progression/chapters/a.json", bad, ex);
        }
        assertEquals(ProgressValidator.Severity.ERROR, p.severity());
        assertEquals(3, p.line());
    }

    @Test
    void aDuplicateKeyInTheTextIsAnError() {
        String text = """
                {"id": "basics", "nodes": {"a": {"id": "a", "count": 1, "count": 2}}}""";
        Map<String, ProgressValidator.Source> sources = Map.of(
                "chapters/t:basics", new ProgressValidator.Source("data/t/progression/chapters/basics.json", text));
        JsonObject bundle = new JsonObject();
        bundle.add("chapters", new JsonObject());
        bundle.add("reward_tables", new JsonObject());
        ProgressValidator.Result r = ProgressValidator.validate(bundle, sources);
        assertTrue(says(r, "declared more than once"));
    }

    @Test
    void aCountThatIsNotANumberIsAnError() {
        // ProgressRules.target reads count with getAsLong on the server tick and nothing above it catches that
        ProgressValidator.Result r = validate(chapter(
                "{\"type\": \"item\", \"count\": \"many\", \"item\": {\"id\": \"minecraft:stone\"}}", ""));
        assertTrue(says(r, "\"count\" is not a whole number"), () -> "problems: " + r.problems);
        assertTrue(r.droppedNodes.contains("t:basics/a"));
    }

    @Test
    void aCountWrittenAsAStringIsFine() {
        // Gson parses a string that holds a number, and so do the readers
        assertEquals(List.of(), validate(chapter(
                "{\"type\": \"item\", \"count\": \"16\", \"item\": {\"id\": \"minecraft:stone\"}}", "")).problems);
    }

    @Test
    void aRequirementThatIsAnObjectIsAnError() {
        // ProgressModel reads each name with getAsString, which throws on an object, an array or null
        String json = """
                {"chapters": {"t:basics": {"nodes": {
                    "a": {"id": "a", "requires": {"nodes": [{"a": 1}]}, "tasks": []}}}},
                 "reward_tables": {}}""";
        ProgressValidator.Result r = validate(json);
        assertTrue(says(r, "is not a node name"));
        assertTrue(r.droppedNodes.contains("t:basics/a"));
    }

    @Test
    void aRequirementWrittenAsANumberIsResolvedAsItsText() {
        // getAsString turns 7 into "7", so it is a name like any other: this one is missing, not malformed
        String json = """
                {"chapters": {"t:basics": {"nodes": {
                    "a": {"id": "a", "requires": {"nodes": [7]}, "tasks": []}}}},
                 "reward_tables": {}}""";
        ProgressValidator.Result r = validate(json);
        assertTrue(says(r, "requires node \"7\", which does not exist"), () -> "problems: " + r.problems);
        assertTrue(r.droppedNodes.contains("t:basics/a"));
    }

    @Test
    void aRequirementWrittenAsANumberCanStillResolve() {
        String json = """
                {"chapters": {"t:basics": {"nodes": {
                    "7": {"id": "7", "tasks": []},
                    "a": {"id": "a", "requires": {"nodes": [7]}, "tasks": []}}}},
                 "reward_tables": {}}""";
        ProgressValidator.Result r = validate(json);
        assertEquals(List.of(), r.problems);
    }

    @Test
    void aNonNumericRewardAmountIsOnlyAWarning() {
        ProgressValidator.Result r = validate(chapter(
                "{\"type\": \"checkmark\", \"count\": 1}",
                "{\"type\": \"xp\", \"amount\": \"lots\"}"));
        assertEquals(0, errors(r));
        assertTrue(says(r, "\"amount\" is not a number"));
        assertFalse(r.droppedNodes.contains("t:basics/a"));
    }

    @Test
    void nonNumericRewardTableNumbersAreOnlyWarnings() {
        // rolls, weight and empty_weight are read with getAsDouble inside Rewards.give, which catches
        String json = """
                {"chapters": {},
                 "reward_tables": {"t:gems": {"rolls": "two", "empty_weight": {}, "entries": [
                    {"weight": "heavy", "reward": {"type": "item", "item": {"id": "minecraft:diamond"}}}]}}}""";
        ProgressValidator.Result r = validate(json);
        assertEquals(0, errors(r));
        assertTrue(says(r, "\"rolls\" is not a number"));
        assertTrue(says(r, "\"weight\" is not a number"));
        assertTrue(says(r, "\"empty_weight\" is not a number"));
    }

    @Test
    void aNonNumericMinIsAWarningAndTheNodeStillOpens() {
        // ProgressModel.intOf falls back to its default, which for min means "not used"
        String json = """
                {"chapters": {"t:basics": {"nodes": {
                    "a": {"id": "a", "requires": {"nodes": ["b"], "min": "two"}, "tasks": []},
                    "b": {"id": "b", "tasks": []}}}},
                 "reward_tables": {}}""";
        ProgressValidator.Result r = validate(json);
        assertTrue(says(r, "\"min\" is not a number"));
        assertEquals(0, errors(r));
    }

    @Test
    void anExclusiveWithThatNamesAMissingNodeIsOnlyAWarning() {
        // the node still opens and completes; only the rivalry the author meant is lost (ProgressRules.excluded)
        String json = """
                {"chapters": {"t:basics": {"nodes": {
                    "a": {"id": "a", "exclusive_with": ["ghost"], "tasks": []}}}},
                 "reward_tables": {}}""";
        ProgressValidator.Result r = validate(json);
        assertEquals(0, errors(r));
        assertTrue(says(r, "exclusive_with node \"ghost\", which does not exist"));
        assertFalse(r.droppedNodes.contains("t:basics/a"));
    }

    @Test
    void aRewardTableThatContainsItselfIsAnErrorAndDropsTheNodeThatGivesIt() {
        // the readers recurse until the stack runs out, and that Error is not caught; the table cannot be dropped,
        // so the node that hands it out is
        String json = """
                {"chapters": {"t:basics": {"nodes": {
                    "a": {"id": "a", "tasks": [], "rewards": [{"type": "table", "table": "t:loop"}]}}}},
                 "reward_tables": {"t:loop": {"rolls": 1, "entries": [
                    {"weight": 1.0, "reward": {"type": "table", "table": "t:loop"}}]}}}""";
        ProgressValidator.Result r = validate(json);
        assertEquals(2, errors(r));
        assertTrue(says(r, "contains itself"));
        assertTrue(r.droppedNodes.contains("t:basics/a"));
    }

    @Test
    void aReferenceIntoAChapterThatWouldNotParseIsOnlyAWarning() {
        // the chapter file failed to parse, so whether the node is there cannot be known
        String json = """
                {"chapters": {"t:basics": {"nodes": {
                    "a": {"id": "a", "requires": {"nodes": ["broken/x"]}, "tasks": []}}}},
                 "reward_tables": {}}""";
        JsonObject bundle = JsonParser.parseString(json).getAsJsonObject();
        ProgressValidator.Result r = ProgressValidator.validate(
                bundle, Map.of(), ProgressValidator.Registry.NONE, java.util.Set.of("t:broken"));
        assertEquals(0, errors(r));
        assertTrue(says(r, "could not be read"));
        assertFalse(r.droppedNodes.contains("t:basics/a"));
    }

    @Test
    void aCountThatIsNotEvenANumberShapeIsOnlyAWarning() {
        // ProgressRules.target guards count and ticks with isJsonPrimitive and skips what it cannot read, so the node
        // still works and only means something different from what was written
        ProgressValidator.Result r = validate(chapter(
                "{\"type\": \"item\", \"count\": [], \"item\": {\"id\": \"minecraft:stone\"}}", ""));
        assertEquals(0, errors(r));
        assertTrue(says(r, "\"count\" is not a number, so it is not read"), () -> "problems: " + r.problems);
        assertFalse(r.droppedNodes.contains("t:basics/a"));
    }

    @Test
    void anOptionalFlagThatIsNotABooleanIsAWarning() {
        // ProgressRules.tasksDone reads optional with getAsBoolean, which reads a number or a word as false
        ProgressValidator.Result r = validate(chapter(
                "{\"type\": \"item\", \"count\": 1, \"optional\": \"yes\", \"item\": {\"id\": \"minecraft:stone\"}}", ""));
        assertEquals(0, errors(r));
        assertTrue(says(r, "\"optional\" is not true or false, so it is read as false"), () -> "problems: " + r.problems);
        assertFalse(r.droppedNodes.contains("t:basics/a"));
    }

    @Test
    void anOptionalFlagThatIsNotPrimitiveIsAnError() {
        // getAsBoolean on an object throws on the tick, and nothing above it catches that
        ProgressValidator.Result r = validate(chapter(
                "{\"type\": \"item\", \"count\": 1, \"optional\": {}, \"item\": {\"id\": \"minecraft:stone\"}}", ""));
        assertEquals(1, errors(r));
        assertTrue(says(r, "so the server would stop reading this node"), () -> "problems: " + r.problems);
        assertTrue(r.droppedNodes.contains("t:basics/a"));
    }

    @Test
    void aRepeatWithAMaxBelowTwoNeverRepeats() {
        // ProgressRules.repeatAfterClaim asks "done + 1 >= max", so max 1 never comes round again
        String json = """
                {"chapters": {"t:basics": {"nodes": {
                    "a": {"id": "a", "repeat": {"max": 1},
                          "tasks": [{"type": "item", "count": 1, "item": {"id": "minecraft:stone"}}]}}}},
                 "reward_tables": {}}""";
        ProgressValidator.Result r = validate(json);
        assertEquals(0, errors(r));
        assertTrue(says(r, "\"max\" is 1, so the node never repeats"), () -> "problems: " + r.problems);
    }

    @Test
    void aRepeatCooldownThatIsNotANumberIsAnError() {
        // ProgressRules.repeatAfterClaim reads cooldown with getAsLong while the claim settles, on the tick
        String json = """
                {"chapters": {"t:basics": {"nodes": {
                    "a": {"id": "a", "repeat": {"cooldown": "soon"},
                          "tasks": [{"type": "item", "count": 1, "item": {"id": "minecraft:stone"}}]}}}},
                 "reward_tables": {}}""";
        ProgressValidator.Result r = validate(json);
        assertEquals(1, errors(r), () -> "problems: " + r.problems);
        assertTrue(says(r, "\"cooldown\" is not a whole number"), () -> "problems: " + r.problems);
        assertTrue(r.droppedNodes.contains("t:basics/a"));
    }

    @Test
    void aBreakTaskOnAnEmptyTagIsAnError() {
        // blockTagMatches parses the text after "#" with tryParse, which is null for "", so the task never counts
        ProgressValidator.Result r = validate(chapter("{\"type\": \"break\", \"count\": 1, \"block\": \"#\"}", ""));
        assertEquals(1, errors(r));
        assertTrue(says(r, "is empty, so it never matches"), () -> "problems: " + r.problems);
    }

    @Test
    void aBreakTaskOnATagIsNotReported() {
        ProgressValidator.Result r = validate(chapter(
                "{\"type\": \"break\", \"count\": 1, \"block\": \"#minecraft:logs\"}", ""));
        assertEquals(List.of(), r.problems);
    }

    @Test
    void anItemRewardThatNamesAFluidGivesNothing() {
        // Rewards.item reads only "id" (ProgressService.itemSpec), so a fluid-only object has no item to give
        ProgressValidator.Result r = validate(chapter(
                "{\"type\": \"item\", \"count\": 1, \"item\": {\"id\": \"minecraft:stone\"}}",
                "{\"type\": \"item\", \"item\": {\"fluid\": \"minecraft:water\"}}"));
        assertEquals(0, errors(r));
        assertTrue(says(r, "item reward has no item id, so nothing is given"), () -> "problems: " + r.problems);
    }

    @Test
    void aTableRewardWithAModeThatIsNotAStringIsAWarning() {
        // Rewards.table reads mode with getAsString inside give, which catches it and loses the reward
        String json = """
                {"chapters": {"t:basics": {"nodes": {
                    "a": {"id": "a", "count": 1, "rewards": [{"type": "table", "table": "t:gems", "mode": 2}]}}}},
                 "reward_tables": {"t:gems": {"rolls": 1, "entries": [
                    {"weight": 1.0, "reward": {"type": "item", "item": {"id": "minecraft:diamond"}}}]}}}""";
        ProgressValidator.Result r = validate(json);
        assertEquals(0, errors(r));
        assertTrue(says(r, "\"mode\" is not a string"), () -> "problems: " + r.problems);
    }

    @Test
    void anUnresolvedRequireOnlyDropsTheNodeWhenItLosesTheGate() {
        // mode "any" asks for one of its names either way, so losing one leaves a gate that still asks for something
        String json = """
                {"chapters": {"t:basics": {"nodes": {
                    "a": {"id": "a", "requires": {"nodes": ["ghost"], "mode": "any"}, "tasks": []}}}},
                 "reward_tables": {}}""";
        ProgressValidator.Result r = validate(json);
        assertEquals(0, errors(r));
        assertTrue(says(r, "does not exist"), () -> "problems: " + r.problems);
        assertFalse(r.droppedNodes.contains("t:basics/a"));
    }

    @Test
    void aRequireOfADroppedNodeFollowsWhatItCostsTheGate() {
        // "b" is dropped for its own error, and "a" then names a node that is not there any more: with mode "any" the
        // gate still asks for one of two names, so it only warns and both nodes stay in the data
        String json = """
                {"chapters": {"t:basics": {"nodes": {
                    "a": {"id": "a", "requires": {"nodes": ["b"], "mode": "any"}, "tasks": []},
                    "b": {"id": "b", "tasks": [{"type": "nope"}]}}}},
                 "reward_tables": {}}""";
        ProgressValidator.Result r = validate(json);
        assertTrue(r.droppedNodes.contains("t:basics/b"));
        assertTrue(says(r, "was dropped, so this node opens earlier than written"), () -> "problems: " + r.problems);
        assertEquals(1, errors(r), () -> "problems: " + r.problems);
        assertFalse(r.droppedNodes.contains("t:basics/a"));
    }

    @Test
    void aRequireOfADroppedNodeThatLosesTheGateIsAWarning() {
        // the node that named the dropped one is kept, so it is reported as a warning: an error would say "dropped"
        // (2026-10-19 review)
        String json = """
                {"chapters": {"t:basics": {"nodes": {
                    "a": {"id": "a", "requires": {"nodes": ["b"]}, "tasks": []},
                    "b": {"id": "b", "tasks": [{"type": "nope"}]}}}},
                 "reward_tables": {}}""";
        ProgressValidator.Result r = validate(json);
        assertEquals(1, errors(r), () -> "problems: " + r.problems);
        assertTrue(says(r, "was dropped, so this node opens earlier than written"), () -> "problems: " + r.problems);
        assertFalse(r.droppedNodes.contains("t:basics/a"));
    }

    @Test
    void aChapterWhoseNodesAreNotAnObjectIsAnError() {
        // the readers take "nodes" with obj(...), which hands back an empty object, so the chapter is empty
        String json = """
                {"chapters": {"t:basics": {"nodes": []}},
                 "reward_tables": {}}""";
        ProgressValidator.Result r = validate(json);
        assertEquals(1, errors(r));
        assertTrue(says(r, "the chapter has no nodes"), () -> "problems: " + r.problems);
    }

    @Test
    void aDeeplyNestedFileDoesNotTakeTheLineScanDown() {
        // the scan recurses once per level, and a stack overflow is an Error that its catch cannot see
        String deep = "{\"a\":".repeat(400) + "1" + "}".repeat(400);
        assertEquals(1, ProgressValidator.lineOf(deep, "a.a"));
    }

    @Test
    void theImportedFixturesHaveNoErrors() throws Exception {
        ImportOutput out = new ImportOutput("t");
        FtbQuestsImporter.convert(PathOf.resource("/fixtures/ftbquests/quests"), out);
        Map<String, JsonElement> files = new LinkedHashMap<>();
        try (Stream<Path> s = Files.list(PathOf.resource("/fixtures/puffish/mining"))) {
            for (Path f : s.toList()) {
                files.put(f.getFileName().toString(), JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)));
            }
        }
        PuffishImporter.convert("ftb/mining", files, out);

        JsonObject chapters = new JsonObject();
        out.chapters.forEach((k, v) -> chapters.add("t:" + k, v));
        JsonObject tables = new JsonObject();
        out.tables.forEach((k, v) -> tables.add("t:" + k, v));
        JsonObject bundle = new JsonObject();
        bundle.add("chapters", chapters);
        bundle.add("reward_tables", tables);

        ProgressValidator.Result r = ProgressValidator.validate(bundle, Map.of(), (kind, id) -> true);
        assertEquals(List.of(), r.problems.stream().filter(p -> p.severity() == ProgressValidator.Severity.ERROR).toList(),
                () -> "imported fixtures produced errors: " + r.problems);
    }


    /** The editor checks only the chapter it edited, but against the whole book (2026-10-20). */
    @Test
    void validateChapterReturnsOnlyThatChaptersProblems() {
        JsonObject chapters = JsonParser.parseString("""
                {"t:basics": {"nodes": {"a": {"tasks": [{"type": "checkmark"}]}}},
                 "t:other": {"nodes": {"b": {"requires": {"nodes": ["basics/a"]}, "tasks": [{"type": "checkmark"}]}}}}
                """).getAsJsonObject();
        JsonObject candidate = JsonParser.parseString("""
                {"nodes": {"a": {"tasks": [{"type": "nope"}]}}}
                """).getAsJsonObject();

        ProgressValidator.Result r = ProgressValidator.validateChapter("t:basics", candidate, chapters,
                new JsonObject(), Map.of(), ProgressValidator.Registry.NONE);

        assertEquals(1, errors(r));
        assertTrue(says(r, "unknown task type"));
        assertTrue(r.droppedNodes.contains("t:basics/a"));
        assertTrue(r.problems.stream().allMatch(p -> p.path().startsWith("chapters.basics")), r.problems.toString());
    }

    /** A cost curve the exchange reader cannot use falls back to the fixed cost, so it only warns (2026-10-22 review). */
    @Test
    void aBrokenExchangeCurveIsAWarning() {
        ProgressValidator.Result missing = validate("""
                {"chapters": {"t:a": {"points": {"exchange": {
                  "xp_levels": 5, "cost_per_level": {"type": "expression"}}}, "nodes": {}}}}
                """);
        assertEquals(0, errors(missing));
        assertTrue(says(missing, "has no \"data\" object"));

        ProgressValidator.Result tooLong = validate("""
                {"chapters": {"t:a": {"points": {"exchange": {
                  "xp_levels": 5, "cost_per_level": {"type": "expression",
                    "data": {"expression": "%s"}}}}, "nodes": {}}}}
                """.formatted("1+".repeat(200) + "1"));
        assertEquals(0, errors(tooLong));
        assertTrue(says(tooLong, "longer than 256"));

        ProgressValidator.Result good = validate("""
                {"chapters": {"t:a": {"points": {"exchange": {
                  "xp_levels": 5, "cost_per_level": {"type": "values", "data": {"values": [5, 10]}}}}, "nodes": {}}}}
                """);
        assertEquals(List.of(), good.problems);
    }

    /** Small helper so the fixture paths read like the other tests. */
    private static final class PathOf {
        static Path resource(String name) {
            try {
                return Path.of(ProgressValidatorTest.class.getResource(name).toURI());
            } catch (Exception ex) {
                throw new AssertionError(ex);
            }
        }
    }

    /** The emergency button's items are handed out as written; a shape it cannot read only warns (2026-10-22 review). */
    @Test
    void emergencyItemsAreCheckedWithoutDroppingNodes() {
        ProgressValidator.Result r = validate("""
                {"chapters": {}, "settings": {"emergency": {
                  "items": ["minecraft:bread", {"item": "minecraft:stone", "count": 3},
                            {"id": "minecraft:dirt", "count": "many"}, 7],
                  "cooldown_seconds": "soon"}}}
                """);
        assertEquals(0, errors(r));
        assertTrue(says(r, "cooldown_seconds"));
        assertTrue(says(r, "count"));
        assertTrue(says(r, "cannot be read as an item id"));
        assertEquals(3, warnings(r));
    }

    @Test
    void anEmergencyItemThatDoesNotExistWarns() {
        ProgressValidator.Result r = validate("""
                {"chapters": {}, "settings": {"items": ["minecraft:nope"], "cooldown_seconds": 60}}
                """, (kind, id) -> !id.equals("minecraft:nope"));
        assertEquals(0, errors(r));
        assertTrue(says(r, "does not exist"));
    }
}
