package com.kuronami.questtree.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The writable layer's own rules, without the editor on top (2026-10-20). */
class ProgressionOverlayTest {

    @TempDir
    Path temp;

    private static JsonObject chapter(String title, String nodes) {
        return JsonParser.parseString("{\"title\":\"" + title + "\",\"nodes\":" + nodes + "}").getAsJsonObject();
    }

    private void writeOverlay(String id, String json) throws Exception {
        Path file = temp.resolve(ProgressionOverlay.DIR).resolve("chapters")
                .resolve(id.replace(':', '/') + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    @Test
    void fieldsMergeAndNodeEntriesReplaceRemoveOrAdd() throws Exception {
        writeOverlay("t:basics", """
                {"revision": 3, "base": true,
                 "chapter": {"title": "Edited"},
                 "nodes": {
                   "a": {"action": "remove", "node": {"x": 1}, "edited": true},
                   "b": {"action": "replace", "node": {"x": 9}},
                   "c": {"action": "add", "node": {"x": 5}}
                 }}
                """);
        JsonObject chapters = new JsonObject();
        chapters.add("t:basics", chapter("Pack", "{\"a\":{\"x\":1},\"b\":{\"x\":2}}"));

        ProgressionOverlay.Overlay overlay = ProgressionOverlay.read(temp.resolve(ProgressionOverlay.DIR));
        assertTrue(overlay.problems.isEmpty(), overlay.problems.toString());
        assertEquals(3, overlay.revision("t:basics"));
        overlay.applyTo(chapters, new LinkedHashMap<>());

        JsonObject merged = chapters.getAsJsonObject("t:basics");
        assertEquals("Edited", merged.get("title").getAsString());
        JsonObject nodes = merged.getAsJsonObject("nodes");
        assertFalse(nodes.has("a"));
        assertEquals(9, nodes.getAsJsonObject("b").get("x").getAsInt());
        assertEquals(5, nodes.getAsJsonObject("c").get("x").getAsInt());
    }

    @Test
    void aChapterTombstoneHidesThePackChapter() throws Exception {
        writeOverlay("t:basics", """
                {"revision": 1, "base": true, "remove": true, "edited": false,
                 "chapter": {"title": "Pack"}, "nodes": {}}
                """);
        JsonObject chapters = new JsonObject();
        chapters.add("t:basics", chapter("Pack", "{}"));

        ProgressionOverlay.read(temp.resolve(ProgressionOverlay.DIR)).applyTo(chapters, new LinkedHashMap<>());

        assertFalse(chapters.has("t:basics"));
    }

    @Test
    void deletedJsonListsChapterAndLiveNodeTombstones() throws Exception {
        writeOverlay("t:gone", """
                {"revision": 2, "base": true, "remove": true, "edited": true,
                 "chapter": {"title": "Gone"}, "nodes": {"x": {"action": "remove", "node": {"x": 1}}}}
                """);
        writeOverlay("t:basics", """
                {"revision": 4, "base": true,
                 "nodes": {
                   "a": {"action": "remove", "node": {"x": 1}, "edited": true},
                   "b": {"action": "add", "node": {"x": 5}}
                 }}
                """);

        ProgressionOverlay.Overlay overlay = ProgressionOverlay.read(temp.resolve(ProgressionOverlay.DIR));
        JsonObject deleted = overlay.deletedJson();

        JsonArray chapters = deleted.getAsJsonArray("chapters");
        assertEquals(1, chapters.size());
        assertEquals("t:gone", chapters.get(0).getAsString());
        JsonObject nodes = deleted.getAsJsonObject("nodes");
        assertEquals(1, nodes.size());
        assertEquals(1, nodes.getAsJsonArray("t:basics").size());
        assertEquals("a", nodes.getAsJsonArray("t:basics").get(0).getAsString());
    }

    @Test
    void anUnreadableFileIsReportedAndNotApplied() throws Exception {
        writeOverlay("t:basics", "{\"nodes\": {\"a\": 4}}");

        ProgressionOverlay.Overlay overlay = ProgressionOverlay.read(temp.resolve(ProgressionOverlay.DIR));

        assertEquals(1, overlay.problems.stream()
                .filter(p -> p.severity() == ProgressValidator.Severity.ERROR).count());
        assertTrue(overlay.problems.get(0).message().contains("node entry is not an object"));
    }

    @Test
    void aChapterIdLongerThanTheEditorMintsIsStillReadable() throws Exception {
        // packs may already ship a long id; rejecting it on read would turn the whole layer into errors, which
        // blocks every editor write in the world (2026-10-23 review)
        writeOverlay("t:" + "a".repeat(60), """
                {"revision": 1, "chapter": {"title": "Long"}}
                """);

        ProgressionOverlay.Overlay overlay = ProgressionOverlay.read(temp.resolve(ProgressionOverlay.DIR));

        assertTrue(overlay.problems.stream().noneMatch(p -> p.severity() == ProgressValidator.Severity.ERROR),
                overlay.problems.toString());
    }

    @Test
    void aPathThatCannotBeAChapterIdIsReported() throws Exception {
        writeOverlay("single", "{}");

        ProgressionOverlay.Overlay overlay = ProgressionOverlay.read(temp.resolve(ProgressionOverlay.DIR));

        assertEquals(1, overlay.problems.size());
        assertTrue(overlay.problems.get(0).message().contains("chapters/<namespace>"));
    }
    @Test
    void aRemovalMarkerDeletesAPackFieldTheOverlayDoesNotReplace() throws Exception {
        writeOverlay("t:basics", """
                {"revision": 4, "base": true,
                 "chapter": {"title": "Edited"},
                 "removed": ["subtitle"]}
                """);
        JsonObject chapters = new JsonObject();
        JsonObject pack = JsonParser.parseString(
                "{\"title\":\"Pack\",\"subtitle\":\"Gone\",\"nodes\":{}}").getAsJsonObject();
        chapters.add("t:basics", pack);

        ProgressionOverlay.read(temp.resolve(ProgressionOverlay.DIR)).applyTo(chapters, new LinkedHashMap<>());

        JsonObject merged = chapters.getAsJsonObject("t:basics");
        assertEquals("Edited", merged.get("title").getAsString());
        assertFalse(merged.has("subtitle"));
    }

    @Test
    void aSidecarKeepsTheRevisionOfAChapterWhoseFileIsGone() throws Exception {
        Path root = temp.resolve(ProgressionOverlay.DIR);
        Files.createDirectories(root);
        Files.writeString(root.resolve("revisions.json"), "{\"t:basics\": 9}", StandardCharsets.UTF_8);

        ProgressionOverlay.Overlay overlay = ProgressionOverlay.read(root);

        assertEquals(9, overlay.revision("t:basics"));
        assertEquals(9, overlay.revisionsJson().get("t:basics").getAsLong());
    }

    /** A bad rule saved by the restrictions editor reaches /quest_tree validate, not just the runtime parse. */
    @Test
    void overlayRestrictionProblemsAreReportedAsWarnings() throws Exception {
        Path root = temp.resolve(ProgressionOverlay.DIR);
        Files.createDirectories(root);
        Files.writeString(root.resolve("restrictions.json"), """
                {"revision": 1, "rules": [
                  {"id": "bad_action", "items": ["minecraft:stone"], "actions": ["wield"],
                   "conditions": [{"type": "stage", "stage": "x"}]},
                  {"id": "no_target"}
                ]}
                """, StandardCharsets.UTF_8);

        ProgressionOverlay.Overlay overlay = ProgressionOverlay.read(root);

        assertEquals(2, overlay.restrictions.rules.size());
        assertTrue(overlay.problems.stream().anyMatch(p -> p.severity() == ProgressValidator.Severity.WARNING
                && p.path().startsWith("rules[0].actions") && p.message().contains("unknown action")),
                overlay.problems.toString());
        assertTrue(overlay.problems.stream().anyMatch(p -> p.path().equals("rules[1]")
                && p.message().contains("no items or blocks")), overlay.problems.toString());
    }


    /** Translations are a writable overlay per locale (2026-10-22, p6-editor): read, revised and merged. */
    @Test
    void translationFilesAreReadPerLocaleAndWinOverThePacksText() throws Exception {
        Path dir = temp.resolve(ProgressionOverlay.DIR).resolve("lang");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("ja_jp.json"), """
                {"revision": 4, "entries": {"a.key": "A", "b.key": "B"}}
                """, StandardCharsets.UTF_8);

        ProgressionOverlay.Overlay overlay = ProgressionOverlay.read(temp.resolve(ProgressionOverlay.DIR));

        assertTrue(overlay.problems.isEmpty(), overlay.problems.toString());
        assertEquals(4, overlay.langRevision("ja_jp"));
        assertEquals(0, overlay.langRevision("de_de"));
        assertEquals(4, overlay.langRevisionsJson().get("ja_jp").getAsLong());
        Map<String, JsonObject> into = new LinkedHashMap<>();
        into.put("en_us", JsonParser.parseString("{\"a.key\":\"English\"}").getAsJsonObject());
        overlay.applyLangsTo(into);
        assertEquals("A", into.get("ja_jp").get("a.key").getAsString());
        assertEquals("English", into.get("en_us").get("a.key").getAsString());
    }

    @Test
    void writeLangWritesAFileTheNextReadFinds() throws Exception {
        JsonObject entries = JsonParser.parseString("{\"a.key\":\"A\"}").getAsJsonObject();

        ProgressionOverlay.writeLang(temp.resolve(ProgressionOverlay.DIR), "ja_jp", 2, entries);
        ProgressionOverlay.Overlay overlay = ProgressionOverlay.read(temp.resolve(ProgressionOverlay.DIR));

        assertTrue(overlay.problems.isEmpty(), overlay.problems.toString());
        assertEquals(2, overlay.langRevision("ja_jp"));
        Map<String, JsonObject> into = new LinkedHashMap<>();
        overlay.applyLangsTo(into);
        assertEquals("A", into.get("ja_jp").get("a.key").getAsString());
    }

    @Test
    void aTranslationFileWithABadNameIsReportedAndIgnored() throws Exception {
        Path dir = temp.resolve(ProgressionOverlay.DIR).resolve("lang");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("ja-JP.json"), """
                {"revision": 1, "entries": {"a.key": "A"}}
                """, StandardCharsets.UTF_8);

        ProgressionOverlay.Overlay overlay = ProgressionOverlay.read(temp.resolve(ProgressionOverlay.DIR));

        assertTrue(!overlay.problems.isEmpty(), "a file the game cannot address is a problem to show");
        assertEquals(0, overlay.langRevision("japanese"));
    }
}
