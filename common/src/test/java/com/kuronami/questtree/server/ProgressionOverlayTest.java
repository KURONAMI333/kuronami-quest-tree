package com.kuronami.questtree.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

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
    void anUnreadableFileIsReportedAndNotApplied() throws Exception {
        writeOverlay("t:basics", "{\"nodes\": {\"a\": 4}}");

        ProgressionOverlay.Overlay overlay = ProgressionOverlay.read(temp.resolve(ProgressionOverlay.DIR));

        assertEquals(1, overlay.problems.stream()
                .filter(p -> p.severity() == ProgressValidator.Severity.ERROR).count());
        assertTrue(overlay.problems.get(0).message().contains("node entry is not an object"));
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

}
