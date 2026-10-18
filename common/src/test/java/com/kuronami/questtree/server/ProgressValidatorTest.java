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
    void anUnknownTaskOrRewardTypeEmitsAWarningNotAnError() {
        ProgressValidator.Result r = validate(chapter(
                "{\"type\": \"custom\", \"count\": 1}",
                "{\"type\": \"scoreboard\"}"));
        assertEquals(0, errors(r));
        assertTrue(says(r, "is not implemented yet"));
        assertTrue(r.droppedNodes.isEmpty());
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
    void anIdWithoutANamespaceIsAWarning() {
        ProgressValidator.Registry registry = (kind, id) -> true;
        ProgressValidator.Result r = validate(chapter(
                "{\"type\": \"item\", \"count\": 1, \"item\": {\"id\": \"stone\"}}", ""), registry);
        assertTrue(says(r, "has no namespace"));
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
    void aDependencyCycleIsAnErrorAndDropsEveryNodeInIt() {
        String json = """
                {"chapters": {"t:basics": {"nodes": {
                    "a": {"id": "a", "requires": {"nodes": ["b"]}},
                    "b": {"id": "b", "requires": {"nodes": ["a"]}}}}},
                 "reward_tables": {}}""";
        ProgressValidator.Result r = validate(json);
        assertTrue(says(r, "dependency cycle"));
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
        assertFalse(says(r, "dependency cycle"));
        assertTrue(r.droppedNodes.isEmpty());
    }

    @Test
    void aCycleWithAnyRequirementsIsOnlyAWarning() {
        String json = """
                {"chapters": {"t:basics": {"nodes": {
                    "a": {"id": "a", "requires": {"nodes": ["b"], "mode": "any"}},
                    "b": {"id": "b", "requires": {"nodes": ["a"], "mode": "any"}}}}},
                 "reward_tables": {}}""";
        ProgressValidator.Result r = validate(json);
        assertEquals(0, errors(r));
        assertTrue(says(r, "possible dependency cycle"));
        assertTrue(r.droppedNodes.isEmpty());
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
    void aRewardThatPointsAtAMissingTableIsAnError() {
        ProgressValidator.Result r = validate(chapter("", "{\"type\": \"table\", \"table\": \"t:nope\"}"));
        assertEquals(1, errors(r));
        assertTrue(says(r, "does not exist"));
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
}
