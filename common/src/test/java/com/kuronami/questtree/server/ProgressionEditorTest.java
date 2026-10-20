package com.kuronami.questtree.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The op engine: what it writes, what it refuses, and what undo puts back (2026-10-20). */
class ProgressionEditorTest {

    private static final String CHAPTER = "t:basics";
    private static final String OTHER = "t:other";

    @TempDir
    Path temp;

    private static JsonObject node(String tasks) {
        JsonObject n = new JsonObject();
        n.addProperty("x", 0);
        n.addProperty("y", 0);
        JsonArray t = new JsonArray();
        t.add(JsonParser.parseString(tasks));
        n.add("tasks", t);
        n.add("rewards", new JsonArray());
        return n;
    }

    private static JsonObject chapter(JsonObject nodes) {
        JsonObject c = new JsonObject();
        c.addProperty("title", "Basics");
        c.add("nodes", nodes);
        return c;
    }

    private static JsonObject pack() {
        JsonObject nodes = new JsonObject();
        nodes.add("a", node("{\"type\":\"checkmark\"}"));
        nodes.add("b", node("{\"type\":\"checkmark\"}"));
        JsonObject chapters = new JsonObject();
        chapters.add(CHAPTER, chapter(nodes));
        JsonObject other = new JsonObject();
        other.add("x", node("{\"type\":\"checkmark\"}"));
        other.getAsJsonObject("x").add("requires", requires("basics/a"));
        chapters.add(OTHER, chapter(other));
        JsonObject bundle = new JsonObject();
        bundle.add("chapters", chapters);
        return chapters;
    }

    private static JsonObject requires(String id) {
        JsonArray names = new JsonArray();
        names.add(id);
        JsonObject r = new JsonObject();
        r.add("nodes", names);
        return r;
    }

    private ProgressionEditor editor() {
        return new ProgressionEditor(temp, () -> "minted", ProgressValidator.Registry.NONE);
    }

    private static ProgressionEditor.Outcome apply(ProgressionEditor editor, String chapterId, long revision,
                                                   String op, String args) {
        return editor.apply(pack(), new JsonObject(), Map.of(), chapterId, revision, op,
                JsonParser.parseString(args).getAsJsonObject(), Set.of());
    }

    private Path overlayFile(String chapterId) {
        return temp.resolve(ProgressionOverlay.DIR).resolve("chapters")
                .resolve(chapterId.replace(':', '/') + ".json");
    }

    @Test
    void updateNodeWritesAReplaceEntryWithTheNextRevision() throws Exception {
        ProgressionEditor.Outcome out = apply(editor(), CHAPTER, 0, "update_node",
                "{\"id\":\"a\",\"patch\":{\"x\":7,\"hide\":true}}");

        assertTrue(out.ok());
        assertEquals(1, out.revision());
        JsonObject written = JsonParser.parseString(Files.readString(overlayFile(CHAPTER), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals(1, written.get("revision").getAsInt());
        assertEquals("replace", written.getAsJsonObject("nodes").getAsJsonObject("a").get("action").getAsString());

        JsonObject merged = pack();
        ProgressionOverlay.read(temp.resolve(ProgressionOverlay.DIR)).applyTo(merged, new java.util.LinkedHashMap<>());
        JsonObject a = merged.getAsJsonObject(CHAPTER).getAsJsonObject("nodes").getAsJsonObject("a");
        assertEquals(7, a.get("x").getAsInt());
        assertTrue(a.get("hide").getAsBoolean());
    }

    @Test
    void aStaleRevisionIsRefusedAndWritesNothing() {
        apply(editor(), CHAPTER, 0, "update_node", "{\"id\":\"a\",\"patch\":{\"x\":7}}");

        ProgressionEditor.Outcome out = apply(editor(), CHAPTER, 0, "update_node",
                "{\"id\":\"b\",\"patch\":{\"x\":9}}");

        assertFalse(out.ok());
        assertTrue(out.stale());
        assertEquals(1, out.revision());
    }

    @Test
    void anEditThatBreaksAnotherChapterIsRefusedBeforeTheFileExists() {
        ProgressionEditor.Outcome out = apply(editor(), CHAPTER, 0, "delete_node", "{\"id\":\"a\"}");

        assertFalse(out.ok());
        assertTrue(out.messages().stream().anyMatch(m -> m.contains("basics/a")), out.messages().toString());
        assertFalse(Files.exists(overlayFile(CHAPTER)));
    }

    @Test
    void deletingAndRestoringAnUntouchedPackNodeLetsThePackCopyReappear() {
        ProgressionEditor editor = editor();
        assertTrue(apply(editor, CHAPTER, 0, "delete_node", "{\"id\":\"b\"}").ok());

        ProgressionEditor.Outcome restored = apply(editor, CHAPTER, 1, "restore_node", "{\"id\":\"b\"}");

        assertTrue(restored.ok());
        JsonObject merged = pack();
        ProgressionOverlay.read(temp.resolve(ProgressionOverlay.DIR)).applyTo(merged, new java.util.LinkedHashMap<>());
        assertTrue(merged.getAsJsonObject(CHAPTER).getAsJsonObject("nodes").has("b"));
    }

    @Test
    void undoRemovesAFileThatDidNotExistBeforeAndRedoPutsItBack() {
        ProgressionEditor editor = editor();
        assertTrue(apply(editor, CHAPTER, 0, "update_node", "{\"id\":\"a\",\"patch\":{\"x\":7}}").ok());

        ProgressionEditor.Outcome undone = apply(editor, CHAPTER, 1, "undo", "{}");
        assertTrue(undone.ok());
        assertEquals(0, undone.revision());
        assertFalse(Files.exists(overlayFile(CHAPTER)));

        ProgressionEditor.Outcome redone = apply(editor, CHAPTER, 0, "redo", "{}");
        assertTrue(redone.ok());
        assertEquals(1, redone.revision());
        assertTrue(Files.exists(overlayFile(CHAPTER)));
    }

    @Test
    void moveNodeWritesTheGridPosition() {
        ProgressionEditor.Outcome out = apply(editor(), CHAPTER, 0, "move_node",
                "{\"id\":\"b\",\"x\":4.5,\"y\":-2}");

        assertTrue(out.ok(), out.messages().toString());
        JsonObject merged = pack();
        ProgressionOverlay.read(temp.resolve(ProgressionOverlay.DIR)).applyTo(merged, new java.util.LinkedHashMap<>());
        JsonObject b = merged.getAsJsonObject(CHAPTER).getAsJsonObject("nodes").getAsJsonObject("b");
        assertEquals(4.5, b.get("x").getAsDouble());
        assertEquals(-2, b.get("y").getAsInt());
    }

    @Test
    void updateChapterChangesFieldsButRefusesNodes() {
        ProgressionEditor editor = editor();
        ProgressionEditor.Outcome out = apply(editor, CHAPTER, 0, "update_chapter",
                "{\"patch\":{\"title\":\"Renamed\",\"icon\":{\"id\":\"minecraft:stone\"}}}");
        assertTrue(out.ok(), out.messages().toString());

        ProgressionEditor.Outcome refused = apply(editor, CHAPTER, 1, "update_chapter",
                "{\"patch\":{\"nodes\":{}}}");
        assertFalse(refused.ok());

        JsonObject merged = pack();
        ProgressionOverlay.read(temp.resolve(ProgressionOverlay.DIR)).applyTo(merged, new java.util.LinkedHashMap<>());
        assertEquals("Renamed", merged.getAsJsonObject(CHAPTER).get("title").getAsString());
    }

    @Test
    void deleteAndRestoreChapterBringsBackThePackCopy() {
        ProgressionEditor editor = editor();
        assertTrue(apply(editor, OTHER, 0, "delete_chapter", "{}").ok());

        JsonObject hidden = pack();
        ProgressionOverlay.read(temp.resolve(ProgressionOverlay.DIR)).applyTo(hidden, new java.util.LinkedHashMap<>());
        assertFalse(hidden.has(OTHER));

        assertTrue(apply(editor, OTHER, 1, "restore_chapter", "{}").ok());
        JsonObject back = pack();
        ProgressionOverlay.read(temp.resolve(ProgressionOverlay.DIR)).applyTo(back, new java.util.LinkedHashMap<>());
        assertEquals("Basics", back.getAsJsonObject(OTHER).get("title").getAsString());
    }

    @Test
    void deletingAChapterThatAnotherChapterDependsOnIsRefused() {
        ProgressionEditor.Outcome out = apply(editor(), CHAPTER, 0, "delete_chapter", "{}");

        assertFalse(out.ok());
        assertTrue(out.messages().stream().anyMatch(m -> m.contains("basics/a")), out.messages().toString());
    }

    @Test
    void linkAndUnlinkEditTheRequiresList() {
        ProgressionEditor editor = editor();
        assertTrue(apply(editor, CHAPTER, 0, "link", "{\"from\":\"b\",\"to\":\"a\"}").ok());

        JsonObject linked = pack();
        ProgressionOverlay.read(temp.resolve(ProgressionOverlay.DIR)).applyTo(linked, new java.util.LinkedHashMap<>());
        JsonArray names = linked.getAsJsonObject(CHAPTER).getAsJsonObject("nodes").getAsJsonObject("b")
                .getAsJsonObject("requires").getAsJsonArray("nodes");
        assertTrue(names.toString().contains("\"a\""));

        assertTrue(apply(editor, CHAPTER, 1, "unlink", "{\"from\":\"b\",\"to\":\"a\"}").ok());
        JsonObject unlinked = pack();
        ProgressionOverlay.read(temp.resolve(ProgressionOverlay.DIR)).applyTo(unlinked, new java.util.LinkedHashMap<>());
        JsonArray gone = unlinked.getAsJsonObject(CHAPTER).getAsJsonObject("nodes").getAsJsonObject("b")
                .getAsJsonObject("requires").getAsJsonArray("nodes");
        assertFalse(gone.toString().contains("\"a\""));
    }

    @Test
    void anExistingErrorElsewhereDoesNotBlockAnUnrelatedEdit() {
        JsonObject chapters = pack();
        chapters.getAsJsonObject(CHAPTER).getAsJsonObject("nodes").getAsJsonObject("a")
                .getAsJsonArray("tasks").get(0).getAsJsonObject().addProperty("type", "nope");
        ProgressionEditor editor = editor();

        ProgressionEditor.Outcome out = editor.apply(chapters, new JsonObject(), Map.of(), CHAPTER, 0, "delete_node",
                JsonParser.parseString("{\"id\":\"b\"}").getAsJsonObject(), Set.of());

        assertTrue(out.ok(), out.messages().toString());
    }

    @Test
    void createChapterIgnoresTheIncomingIdAndMintsOne() {
        ProgressionEditor.Outcome out = apply(editor(), "", 0, "create_chapter",
                "{\"namespace\":\"t\",\"chapter\":{\"nodes\":{\"a\":{\"tasks\":[{\"type\":\"checkmark\"}]}}}}");

        assertTrue(out.ok(), out.messages().toString());
        assertEquals("t:minted", out.createdId());
        assertTrue(Files.exists(overlayFile("t:minted")));
    }

    @Test
    void createNodeMintsAnIdUniqueInTheChapter() throws Exception {
        ProgressionEditor.Outcome out = apply(editor(), CHAPTER, 0, "create_node",
                "{\"node\":{\"tasks\":[{\"type\":\"checkmark\"}],\"rewards\":[]}}");

        assertTrue(out.ok(), out.messages().toString());
        assertEquals("minted", out.createdId());
        JsonObject written = JsonParser.parseString(Files.readString(overlayFile(CHAPTER), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertTrue(written.getAsJsonObject("nodes").has("minted"));
    }
}
