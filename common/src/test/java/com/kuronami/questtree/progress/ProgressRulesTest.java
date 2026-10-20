package com.kuronami.questtree.progress;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kuronami.questtree.data.ProgressModel;
import com.kuronami.questtree.importer.FtbQuestsImporter;
import com.kuronami.questtree.importer.ImportOutput;
import com.kuronami.questtree.importer.PuffishImporter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rules checked on the imported fixtures (the same data the in-game check uses). */
class ProgressRulesTest {

    private static ProgressModel model;

    @BeforeAll
    static void load() throws Exception {
        ImportOutput out = new ImportOutput("t");
        FtbQuestsImporter.convert(Path.of(ProgressRulesTest.class.getResource("/fixtures/ftbquests/quests").toURI()), out);
        Map<String, JsonElement> files = new LinkedHashMap<>();
        try (Stream<Path> s = Files.list(Path.of(ProgressRulesTest.class.getResource("/fixtures/puffish/mining").toURI()))) {
            for (Path f : s.toList()) {
                files.put(f.getFileName().toString(), JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)));
            }
        }
        PuffishImporter.convert("ftb/mining", files, out);
        JsonObject bundle = new JsonObject();
        JsonObject chapters = new JsonObject();
        out.chapters.forEach((k, v) -> chapters.add("t:" + k, v));
        bundle.add("chapters", chapters);
        model = ProgressModel.of(bundle);
    }

    private static ProgressModel.Node basics(int i) {
        return model.chapters.get("t:basics").nodes.values().stream().skip(i).findFirst().orElseThrow();
    }

    @Test
    void questsOpenInOrder() {
        ProgressRules rules = new ProgressRules(model);
        PlayerProgress p = new PlayerProgress();
        assertEquals(ProgressRules.State.OPEN, rules.state(p, basics(0)));
        assertEquals(ProgressRules.State.LOCKED, rules.state(p, basics(1)));
        // first task of the first quest needs 1 item: done -> completed -> rewards to claim
        p.setTask(basics(0).key, 0, 1);
        assertTrue(rules.tasksDone(p, basics(0)));
        p.completed.add(basics(0).key);
        assertEquals(ProgressRules.State.CLAIM, rules.state(p, basics(0)));
        assertEquals(ProgressRules.State.OPEN, rules.state(p, basics(1)));
        p.claimed.add(basics(0).key);
        assertEquals(ProgressRules.State.DONE, rules.state(p, basics(0)));
        // second quest needs 2: half way is PROGRESS
        p.setTask(basics(1).key, 0, 1);
        assertEquals(ProgressRules.State.PROGRESS, rules.state(p, basics(1)));
        assertEquals(0.5, rules.fraction(p, basics(1)), 1e-9);
    }

    @Test
    void crossChapterRequirementResolves() {
        ProgressModel.Chapter api = model.chapters.get("t:apiculture");
        ProgressModel.Node first = api.nodes.values().iterator().next();
        assertEquals(1, first.requires.size());
        assertTrue(first.requires.get(0).startsWith("t:basics/"));
    }

    @Test
    void skillPointsAndExclusives() {
        ProgressRules rules = new ProgressRules(model);
        ProgressModel.Chapter mining = model.chapters.get("t:skill_ftb_mining");
        PlayerProgress p = new PlayerProgress();
        ProgressModel.Node root = mining.nodes.get("root");
        ProgressModel.Node s0 = mining.nodes.get("s0");
        ProgressModel.Node s1 = mining.nodes.get("s1");
        assertEquals(0, rules.availablePoints(p, mining));
        assertEquals(ProgressRules.State.OPEN, rules.state(p, root));
        // curve level^1.5 * 100: level 0 -> 1 costs max(1, 0) = 1, level 1 -> 2 costs 100
        assertEquals(1, rules.addExperience(p, mining, 1));
        assertEquals(1, rules.availablePoints(p, mining));
        assertTrue(rules.take(p, root));
        assertEquals(ProgressRules.State.DONE, rules.state(p, root));
        assertEquals(ProgressRules.State.OPEN, rules.state(p, s0));
        rules.addExperience(p, mining, 100);
        assertEquals(ProgressRules.State.CLAIM, rules.state(p, s0));
        assertTrue(rules.take(p, s0));
        assertEquals(ProgressRules.State.EXCLUDED, rules.state(p, s1));
        assertFalse(rules.take(p, s1));
    }

    /**
     * Sequential tasks (2026-10-20): with require_sequential_tasks the second task waits until the first reaches its
     * target, so a later step can neither be counted nor submitted early.
     */
    @Test
    void sequentialTasksWaitForTheOneBefore() {
        JsonObject bundle = JsonParser.parseString("""
                {"chapters": {"s:c": {"nodes": {
                  "q": {"sequential_tasks": true, "tasks": [
                    {"type": "checkmark"}, {"type": "checkmark", "count": 2}, {"type": "checkmark"}]}
                }}}}""").getAsJsonObject();
        ProgressRules r = new ProgressRules(ProgressModel.of(bundle));
        ProgressModel.Node q = r.model().nodes.get("s:c/q");
        PlayerProgress p = new PlayerProgress();
        assertTrue(r.taskOpen(p, q, 0));
        assertFalse(r.taskOpen(p, q, 1), "the second task waits while the first is incomplete");
        assertFalse(r.taskOpen(p, q, 2));
        p.setTask(q.key, 0, 1);
        assertTrue(r.taskOpen(p, q, 1), "the first task done opens the second");
        assertFalse(r.taskOpen(p, q, 2), "the third still waits for the second");
        p.setTask(q.key, 1, 2);
        assertTrue(r.taskOpen(p, q, 2));
    }

    /** Hidden quests: secret until done, until requirements are done, or until requirements are visible (recursively). */
    @Test
    void visibility() {
        JsonObject bundle = JsonParser.parseString("""
                {"chapters": {"v:c": {"defaults": {"hidden_until": "deps_visible"}, "nodes": {
                  "a": {"visibility": {"hidden_until": "deps_complete"}, "requires": {"nodes": ["root"]}},
                  "root": {"visibility": {"hidden_until": "none"}},
                  "b": {"requires": {"nodes": ["a"]}},
                  "secret": {"visibility": {"hidden_until": "complete"}},
                  "after3": {"visibility": {"invisible_until_tasks": 1}, "tasks": [{"type": "checkmark"}]}
                }}}}""").getAsJsonObject();
        ProgressModel m = ProgressModel.of(bundle);
        ProgressRules r = new ProgressRules(m);
        PlayerProgress p = new PlayerProgress();
        assertTrue(r.visible(p, m.nodes.get("v:c/root")));
        assertFalse(r.visible(p, m.nodes.get("v:c/a")), "a waits for root to be done");
        assertFalse(r.visible(p, m.nodes.get("v:c/b")), "b (chapter default deps_visible) waits for a to be visible");
        assertFalse(r.visible(p, m.nodes.get("v:c/secret")));
        assertFalse(r.visible(p, m.nodes.get("v:c/after3")));
        p.completed.add("v:c/root");
        assertTrue(r.visible(p, m.nodes.get("v:c/a")));
        assertTrue(r.visible(p, m.nodes.get("v:c/b")));
        p.setTask("v:c/after3", 0, 1);
        assertTrue(r.visible(p, m.nodes.get("v:c/after3")));
        p.completed.add("v:c/secret");
        assertTrue(r.visible(p, m.nodes.get("v:c/secret")), "done nodes are always visible");
    }
}
