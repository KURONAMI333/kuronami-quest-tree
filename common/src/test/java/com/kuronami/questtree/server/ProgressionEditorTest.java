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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

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
        c.addProperty("subtitle", "Pack subtitle");
        JsonObject display = new JsonObject();
        display.addProperty("icon", "minecraft:book");
        c.add("display", display);
        c.add("nodes", nodes);
        return c;
    }

    private static JsonObject pack() {
        JsonObject nodes = new JsonObject();
        nodes.add("a", node("{\"type\":\"checkmark\"}"));
        nodes.add("b", node("{\"type\":\"checkmark\"}"));
        nodes.add("c", node("{\"type\":\"checkmark\"}"));
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

    /** A real world root is "." (LevelResource.ROOT): the overlay must still be inside it (2026-10-22 review). */
    @Test
    void aWorldPathWithDotStillWritesInsideTheOverlay() throws Exception {
        Path dotted = temp.resolve(".").resolve("world");
        ProgressionEditor editor = new ProgressionEditor(dotted,
                () -> "minted", ProgressValidator.Registry.NONE);
        assertFalse(editor.overlayRoot().toString().contains("/./"),
                "the overlay root is normalized: " + editor.overlayRoot());

        ProgressionEditor.Outcome out = apply(editor, CHAPTER, 0, "update_node",
                "{\"id\":\"a\",\"patch\":{\"x\":7}}");

        assertTrue(out.ok(), out.messages().toString());
        Path written = temp.resolve("world").resolve(ProgressionOverlay.DIR).resolve("chapters")
                .resolve(CHAPTER.replace(':', '/') + ".json");
        assertTrue(Files.isRegularFile(written), "the chapter was written inside the world: " + written);
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
    void aMoveWithANonFiniteCoordinateIsRefusedAndWritesNothing() {
        ProgressionEditor editor = editor();
        JsonObject args = new JsonObject();
        args.addProperty("id", "a");
        args.addProperty("x", Double.POSITIVE_INFINITY);
        args.addProperty("y", 0);

        ProgressionEditor.Outcome out = editor.apply(pack(), new JsonObject(), Map.of(), CHAPTER, 0, "move_node",
                args, Set.of());

        assertFalse(out.ok());
        assertTrue(out.messages().toString().contains("numeric x and y"));
        assertFalse(Files.exists(overlayFile(CHAPTER)));
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
    void aSaveFromAScreenOpenedBeforeAnotherEditIsRefused() {
        ProgressionEditor editor = editor();
        long opened = 0;
        assertTrue(apply(editor, CHAPTER, opened, "update_chapter",
                "{\"patch\":{\"title\":\"First\"}}").ok());

        // the screen still holds the copy and revision it opened with
        ProgressionEditor.Outcome out = apply(editor, CHAPTER, opened, "update_node",
                "{\"id\":\"a\",\"patch\":{\"x\":5}}");

        assertFalse(out.ok());
        assertTrue(out.stale());
        assertEquals(1, out.revision());
        assertEquals(0, mergedX());
    }

    @Test
    void anEditThatBreaksAnotherChapterIsRefusedBeforeTheFileExists() {
        ProgressionEditor.Outcome out = apply(editor(), CHAPTER, 0, "delete_node", "{\"id\":\"a\"}");

        assertFalse(out.ok());
        assertTrue(out.messages().stream().anyMatch(m -> m.contains("basics/a")), out.messages().toString());
        assertFalse(Files.exists(overlayFile(CHAPTER)));
    }

    @Test
    void moveNodesIsOneRevisionAndOneUndoStep() {
        ProgressionEditor editor = editor();
        ProgressionEditor.Outcome out = apply(editor, CHAPTER, 0, "move_nodes",
                "{\"nodes\":[{\"id\":\"a\",\"x\":1,\"y\":2},{\"id\":\"b\",\"x\":3,\"y\":4}]}");

        assertTrue(out.ok(), out.messages().toString());
        assertEquals(1, out.revision());
        JsonObject merged = pack();
        ProgressionOverlay.read(temp.resolve(ProgressionOverlay.DIR)).applyTo(merged, new java.util.LinkedHashMap<>());
        JsonObject nodes = merged.getAsJsonObject(CHAPTER).getAsJsonObject("nodes");
        assertEquals(1, nodes.getAsJsonObject("a").get("x").getAsInt());
        assertEquals(4, nodes.getAsJsonObject("b").get("y").getAsInt());

        ProgressionEditor.Outcome undone = apply(editor, CHAPTER, 1, "undo", "{}");
        assertTrue(undone.ok(), undone.messages().toString());
        assertEquals(2, undone.revision());
        assertEquals(0, mergedX());
    }

    @Test
    void deleteNodesIsOneAtomicOp() {
        ProgressionEditor editor = editor();
        ProgressionEditor.Outcome out = apply(editor, CHAPTER, 0, "delete_nodes",
                "{\"ids\":[\"b\",\"c\"]}");

        assertTrue(out.ok(), out.messages().toString());
        assertEquals(1, out.revision());
        JsonObject merged = pack();
        ProgressionOverlay.read(temp.resolve(ProgressionOverlay.DIR)).applyTo(merged, new java.util.LinkedHashMap<>());
        JsonObject nodes = merged.getAsJsonObject(CHAPTER).getAsJsonObject("nodes");
        assertFalse(nodes.has("b"));
        assertFalse(nodes.has("c"));
        assertTrue(nodes.has("a"));

        ProgressionEditor.Outcome undone = apply(editor, CHAPTER, 1, "undo", "{}");
        assertTrue(undone.ok(), undone.messages().toString());
        assertEquals(2, undone.revision());
        JsonObject back = pack();
        ProgressionOverlay.read(temp.resolve(ProgressionOverlay.DIR)).applyTo(back, new java.util.LinkedHashMap<>());
        assertTrue(back.getAsJsonObject(CHAPTER).getAsJsonObject("nodes").has("b"));
        assertTrue(back.getAsJsonObject(CHAPTER).getAsJsonObject("nodes").has("c"));
    }

    @Test
    void aBrokenEntryRefusesTheWholeBatchAndWritesNothing() {
        ProgressionEditor editor = editor();
        ProgressionEditor.Outcome out = apply(editor, CHAPTER, 0, "delete_nodes",
                "{\"ids\":[\"b\",\"a\"]}");

        assertFalse(out.ok());
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
        // the file is gone, but the revision keeps counting: a late client must not be able to reuse number 1
        assertEquals(2, undone.revision());
        assertFalse(Files.exists(overlayFile(CHAPTER)));

        ProgressionEditor.Outcome redone = apply(editor, CHAPTER, 2, "redo", "{}");
        assertTrue(redone.ok());
        assertEquals(3, redone.revision());
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
    void createChapterIgnoresTheIncomingIdAndMintsOne() throws Exception {
        ProgressionEditor.Outcome out = apply(editor(), "", 0, "create_chapter",
                "{\"namespace\":\"t\",\"chapter\":{\"title\":\"New\"}}");

        assertTrue(out.ok(), out.messages().toString());
        assertEquals("t:minted", out.createdId());
        assertTrue(Files.exists(overlayFile("t:minted")));
        JsonObject written = JsonParser.parseString(Files.readString(overlayFile("t:minted"), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals("New", written.getAsJsonObject("chapter").get("title").getAsString());
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
    private int mergedX() {
        JsonObject merged = pack();
        ProgressionOverlay.read(temp.resolve(ProgressionOverlay.DIR)).applyTo(merged, new java.util.LinkedHashMap<>());
        return merged.getAsJsonObject(CHAPTER).getAsJsonObject("nodes").getAsJsonObject("a").get("x").getAsInt();
    }

    private JsonObject overlayJson(String chapterId) throws Exception {
        return JsonParser.parseString(Files.readString(overlayFile(chapterId), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    void undoAndRedoWalkTheStepsInOrder() {
        ProgressionEditor editor = editor();
        assertTrue(apply(editor, CHAPTER, 0, "update_node", "{\"id\":\"a\",\"patch\":{\"x\":1}}").ok());
        assertTrue(apply(editor, CHAPTER, 1, "update_node", "{\"id\":\"a\",\"patch\":{\"x\":2}}").ok());
        ProgressionEditor.Outcome last = apply(editor, CHAPTER, 2, "update_node", "{\"id\":\"a\",\"patch\":{\"x\":3}}");
        assertTrue(last.ok());
        assertEquals(3, mergedX());

        long revision = last.revision();
        for (int expected : new int[]{2, 1}) {
            ProgressionEditor.Outcome undone = apply(editor, CHAPTER, revision, "undo", "{}");
            assertTrue(undone.ok(), undone.messages().toString());
            revision = undone.revision();
            assertEquals(expected, mergedX());
        }
        ProgressionEditor.Outcome removed = apply(editor, CHAPTER, revision, "undo", "{}");
        assertTrue(removed.ok(), removed.messages().toString());
        revision = removed.revision();
        assertEquals(0, mergedX());
        assertFalse(Files.exists(overlayFile(CHAPTER)));

        for (int expected : new int[]{1, 2, 3}) {
            ProgressionEditor.Outcome redone = apply(editor, CHAPTER, revision, "redo", "{}");
            assertTrue(redone.ok(), redone.messages().toString());
            revision = redone.revision();
            assertEquals(expected, mergedX());
        }
    }

    @Test
    void updateChapterMergesNestedFieldsAndCanDeleteAPackField() throws Exception {
        ProgressionEditor editor = editor();
        ProgressionEditor.Outcome changed = apply(editor, CHAPTER, 0, "update_chapter",
                "{\"patch\":{\"display\":{\"title\":\"Edited\"}}}");
        assertTrue(changed.ok(), changed.messages().toString());

        JsonObject written = overlayJson(CHAPTER);
        JsonObject stored = written.getAsJsonObject("chapter").getAsJsonObject("display");
        assertEquals("Edited", stored.get("title").getAsString());
        assertEquals("minecraft:book", stored.get("icon").getAsString());

        JsonObject merged = pack();
        ProgressionOverlay.read(temp.resolve(ProgressionOverlay.DIR)).applyTo(merged, new java.util.LinkedHashMap<>());
        assertEquals("Pack subtitle", merged.getAsJsonObject(CHAPTER).get("subtitle").getAsString());

        ProgressionEditor.Outcome deleted = apply(editor, CHAPTER, changed.revision(), "update_chapter",
                "{\"patch\":{\"subtitle\":null}}");
        assertTrue(deleted.ok(), deleted.messages().toString());
        written = overlayJson(CHAPTER);
        assertFalse(written.getAsJsonObject("chapter").has("subtitle"));
        assertTrue(written.getAsJsonArray("removed").toString().contains("subtitle"));

        JsonObject after = pack();
        ProgressionOverlay.read(temp.resolve(ProgressionOverlay.DIR)).applyTo(after, new java.util.LinkedHashMap<>());
        assertFalse(after.getAsJsonObject(CHAPTER).has("subtitle"));
        assertEquals("Edited", after.getAsJsonObject(CHAPTER).getAsJsonObject("display").get("title").getAsString());
        assertEquals("minecraft:book", after.getAsJsonObject(CHAPTER).getAsJsonObject("display").get("icon").getAsString());
    }

    @Test
    void restoringAChapterPinsTheSnapshotWhenThePackNoLongerHasIt() {
        ProgressionEditor editor = editor();
        assertTrue(apply(editor, OTHER, 0, "delete_chapter", "{}").ok());

        JsonObject packWithoutIt = pack();
        packWithoutIt.remove(OTHER);
        ProgressionEditor.Outcome restored = editor.apply(packWithoutIt, new JsonObject(), Map.of(), OTHER, 1,
                "restore_chapter", new JsonObject(), Set.of());

        assertTrue(restored.ok(), restored.messages().toString());
        JsonObject merged = packWithoutIt.deepCopy();
        ProgressionOverlay.read(temp.resolve(ProgressionOverlay.DIR)).applyTo(merged, new java.util.LinkedHashMap<>());
        assertTrue(merged.has(OTHER));
        assertEquals("Basics", merged.getAsJsonObject(OTHER).get("title").getAsString());
    }

    @Test
    void revisionsKeepCountingAcrossAnUndoThatDeletesTheFile() {
        ProgressionEditor editor = editor();
        assertTrue(apply(editor, CHAPTER, 0, "update_node", "{\"id\":\"a\",\"patch\":{\"x\":1}}").ok());
        ProgressionEditor.Outcome undone = apply(editor, CHAPTER, 1, "undo", "{}");
        assertTrue(undone.ok());
        assertEquals(2, undone.revision());
        assertFalse(Files.exists(overlayFile(CHAPTER)));

        ProgressionEditor.Outcome stale = apply(editor, CHAPTER, 1, "update_node",
                "{\"id\":\"a\",\"patch\":{\"x\":2}}");
        assertFalse(stale.ok());
        assertTrue(stale.stale());

        // a restart loses the history but not the revision: the sidecar is what the next number comes from
        ProgressionEditor restarted = editor();
        ProgressionEditor.Outcome staleAfterRestart = apply(restarted, CHAPTER, 1, "update_node",
                "{\"id\":\"a\",\"patch\":{\"x\":2}}");
        assertFalse(staleAfterRestart.ok());
        assertTrue(staleAfterRestart.stale());

        ProgressionEditor.Outcome next = apply(restarted, CHAPTER, 2, "update_node",
                "{\"id\":\"a\",\"patch\":{\"x\":3}}");
        assertTrue(next.ok(), next.messages().toString());
        assertEquals(3, next.revision());
        JsonObject revisions = ProgressionOverlay.read(temp.resolve(ProgressionOverlay.DIR)).revisionsJson();
        assertEquals(3, revisions.get(CHAPTER).getAsLong());
    }

    @Test
    void writesAndUndoLeaveNoTemporaryFiles() throws Exception {
        ProgressionEditor editor = editor();
        assertTrue(apply(editor, CHAPTER, 0, "update_node", "{\"id\":\"a\",\"patch\":{\"x\":1}}").ok());
        assertTrue(apply(editor, CHAPTER, 1, "undo", "{}").ok());

        try (Stream<Path> files = Files.walk(temp.resolve(ProgressionOverlay.DIR))) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void createChapterMintsEveryInitialNodeIdAndRewritesTheirReferences() {
        AtomicInteger next = new AtomicInteger();
        ProgressionEditor editor = new ProgressionEditor(temp, () -> "c" + next.getAndIncrement(),
                ProgressValidator.Registry.NONE);
        ProgressionEditor.Outcome out = apply(editor, "", 0, "create_chapter",
                "{\"namespace\":\"t\",\"chapter\":{\"nodes\":{"
                        + "\"client\":{\"tasks\":[{\"type\":\"checkmark\"}],\"rewards\":[]},"
                        + "\"target\":{\"tasks\":[{\"type\":\"checkmark\"}],\"rewards\":[],"
                        + "\"requires\":{\"nodes\":[\"client\"]}}}}}");

        assertTrue(out.ok(), out.messages().toString());
        try {
            JsonObject nodes = overlayJson(out.createdId()).getAsJsonObject("nodes");
            assertFalse(nodes.has("client"));
            assertFalse(nodes.has("target"));
            assertEquals("c1", nodes.getAsJsonObject("c2").getAsJsonObject("node")
                    .getAsJsonObject("requires").getAsJsonArray("nodes").get(0).getAsString());
            assertTrue(nodes.has("c1"));
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    void nodeIdsWithCharactersOutsideTheRuleAreRefused() {
        ProgressionEditor.Outcome out = apply(editor(), CHAPTER, 0, "update_node",
                "{\"id\":\"bad-id\",\"patch\":{\"x\":1}}");

        assertFalse(out.ok());
        assertTrue(out.messages().get(0).contains("a-z"), out.messages().toString());
    }

    // ------------------------------------------------------------------ reward tables (2026-10-21, phase 3)

    private ProgressionEditor.Outcome table(ProgressionEditor editor, JsonObject packTables, String id, long revision,
                                            String args) {
        return editor.apply(pack(), packTables, Map.of(), id, revision, "update_table",
                JsonParser.parseString(args).getAsJsonObject(), Set.of());
    }

    private JsonObject tablesWithOverlay() {
        JsonObject tables = new JsonObject();
        ProgressionOverlay.read(temp.resolve(ProgressionOverlay.DIR)).applyTablesTo(tables, new java.util.LinkedHashMap<>());
        return tables;
    }

    private static final String LOOT = "{\"rolls\":2,\"entries\":[{\"weight\":3,\"reward\":"
            + "{\"type\":\"item\",\"item\":{\"id\":\"minecraft:diamond\"}}}],\"keep\":{\"x\":1}}";

    @Test
    void creatingATableWritesItWithRevisionOneAndNoChapterRevisionMoves() {
        ProgressionEditor.Outcome out = table(editor(), new JsonObject(), "t:loot", 0,
                "{\"create\":true,\"id\":\"t:loot\",\"table\":" + LOOT + "}");

        assertTrue(out.ok(), out.messages().toString());
        assertEquals(1, out.revision());
        JsonObject made = tablesWithOverlay();
        assertEquals(2, made.getAsJsonObject("t:loot").get("rolls").getAsInt());
        assertEquals(1, made.getAsJsonObject("t:loot").getAsJsonObject("keep").get("x").getAsInt());
        assertEquals(0, ProgressionOverlay.read(temp.resolve(ProgressionOverlay.DIR)).revision(CHAPTER));
    }

    @Test
    void updatingATableByPatchKeepsFieldsThePatchDoesNotName() {
        ProgressionEditor editor = editor();
        table(editor, new JsonObject(), "t:loot", 0, "{\"create\":true,\"id\":\"t:loot\",\"table\":" + LOOT + "}");
        ProgressionEditor.Outcome out = table(editor, tablesWithOverlay(), "t:loot", 1,
                "{\"patch\":{\"rolls\":5,\"loot_crate\":{}}}");

        assertTrue(out.ok(), out.messages().toString());
        assertEquals(2, out.revision());
        JsonObject loot = tablesWithOverlay().getAsJsonObject("t:loot");
        assertEquals(5, loot.get("rolls").getAsInt());
        assertTrue(loot.has("loot_crate"));
        assertEquals(1, loot.getAsJsonObject("keep").get("x").getAsInt());
        assertEquals(1, loot.getAsJsonArray("entries").size());
    }

    @Test
    void aStaleTableRevisionIsRefusedAndAnUnchangedTableWritesNothing() {
        ProgressionEditor editor = editor();
        table(editor, new JsonObject(), "t:loot", 0, "{\"create\":true,\"id\":\"t:loot\",\"table\":" + LOOT + "}");

        ProgressionEditor.Outcome stale = table(editor, tablesWithOverlay(), "t:loot", 0, "{\"patch\":{\"rolls\":9}}");
        assertFalse(stale.ok());
        assertTrue(stale.stale());
        assertEquals(1, stale.revision());

        ProgressionEditor.Outcome same = table(editor, tablesWithOverlay(), "t:loot", 1, "{\"patch\":{\"rolls\":2}}");
        assertTrue(same.ok());
        assertFalse(same.changed());
        assertEquals(1, same.revision());
    }

    @Test
    void aTableSaveFromAScreenOpenedBeforeAnotherEditIsRefused() {
        ProgressionEditor editor = editor();
        table(editor, new JsonObject(), "t:loot", 0, "{\"create\":true,\"id\":\"t:loot\",\"table\":" + LOOT + "}");
        long opened = 1;
        assertTrue(table(editor, tablesWithOverlay(), "t:loot", opened, "{\"patch\":{\"rolls\":4}}").ok());

        ProgressionEditor.Outcome out = table(editor, tablesWithOverlay(), "t:loot", opened,
                "{\"patch\":{\"rolls\":6}}");

        assertFalse(out.ok());
        assertTrue(out.stale());
        assertEquals(2, out.revision());
        assertEquals(4, tablesWithOverlay().getAsJsonObject("t:loot").get("rolls").getAsInt());
    }

    @Test
    void creatingATableThatExistsOrEditingOneThatDoesNotIsRefused() {
        ProgressionEditor editor = editor();
        JsonObject packTables = JsonParser.parseString("{\"t:loot\":" + LOOT + "}").getAsJsonObject();
        assertFalse(table(editor, packTables, "t:loot", 0, "{\"create\":true,\"table\":{}}").ok());
        assertFalse(table(editor, packTables, "t:missing", 0, "{\"patch\":{\"rolls\":1}}").ok());
        assertFalse(table(editor, packTables, "bad id", 0, "{\"create\":true,\"table\":{}}").ok());
        assertFalse(table(editor, packTables, "t:loot", 0, "{}").ok());
    }

    @Test
    void aPackTableCanBeEditedAndTheOverlayWinsOverThePack() {
        ProgressionEditor editor = editor();
        JsonObject packTables = JsonParser.parseString("{\"t:loot\":" + LOOT + "}").getAsJsonObject();
        ProgressionEditor.Outcome out = table(editor, packTables, "t:loot", 0, "{\"patch\":{\"rolls\":7}}");

        assertTrue(out.ok(), out.messages().toString());
        JsonObject merged = packTables.deepCopy();
        ProgressionOverlay.read(temp.resolve(ProgressionOverlay.DIR)).applyTablesTo(merged, new java.util.LinkedHashMap<>());
        assertEquals(7, merged.getAsJsonObject("t:loot").get("rolls").getAsInt());
        assertEquals(2, packTables.getAsJsonObject("t:loot").get("rolls").getAsInt());
    }

    private ProgressionEditor.Outcome tableOp(ProgressionEditor editor, JsonObject packTables, String id,
                                              long revision, String op, String args) {
        return editor.apply(pack(), packTables, Map.of(), id, revision, op,
                JsonParser.parseString(args).getAsJsonObject(), Set.of());
    }

    @Test
    void aTableUndoPutsBackTheWriteItReplacedAndRedoPutsItBackAgain() {
        ProgressionEditor editor = editor();
        table(editor, new JsonObject(), "t:loot", 0, "{\"create\":true,\"id\":\"t:loot\",\"table\":" + LOOT + "}");
        assertTrue(table(editor, tablesWithOverlay(), "t:loot", 1, "{\"patch\":{\"rolls\":5}}").ok());
        assertEquals(5, tablesWithOverlay().getAsJsonObject("t:loot").get("rolls").getAsInt());

        ProgressionEditor.Outcome undone = tableOp(editor, tablesWithOverlay(), "t:loot", 2, "undo", "{}");
        assertTrue(undone.ok(), undone.messages().toString());
        assertEquals(3, undone.revision());
        assertEquals(2, tablesWithOverlay().getAsJsonObject("t:loot").get("rolls").getAsInt());

        ProgressionEditor.Outcome redone = tableOp(editor, tablesWithOverlay(), "t:loot", 3, "redo", "{}");
        assertTrue(redone.ok(), redone.messages().toString());
        assertEquals(4, redone.revision());
        assertEquals(5, tablesWithOverlay().getAsJsonObject("t:loot").get("rolls").getAsInt());
    }

    @Test
    void deletingATableLeavesATombstoneForRestoreAndDoesNotTouchTheChapter() {
        ProgressionEditor editor = editor();
        table(editor, new JsonObject(), "t:loot", 0, "{\"create\":true,\"id\":\"t:loot\",\"table\":" + LOOT + "}");

        ProgressionEditor.Outcome deleted = tableOp(editor, tablesWithOverlay(), "t:loot", 1, "delete_table",
                "{\"id\":\"t:loot\"}");

        assertTrue(deleted.ok(), deleted.messages().toString());
        assertEquals(2, deleted.revision());
        assertFalse(tablesWithOverlay().has("t:loot"));
        JsonObject deletedJson = ProgressionOverlay.read(temp.resolve(ProgressionOverlay.DIR)).deletedJson();
        assertEquals(1, deletedJson.getAsJsonArray("tables").size());
        assertEquals("t:loot", deletedJson.getAsJsonArray("tables").get(0).getAsString());

        ProgressionEditor.Outcome restored = tableOp(editor, tablesWithOverlay(), "t:loot", 2, "restore_table",
                "{\"id\":\"t:loot\"}");

        assertTrue(restored.ok(), restored.messages().toString());
        assertEquals(3, restored.revision());
        assertEquals(2, tablesWithOverlay().getAsJsonObject("t:loot").get("rolls").getAsInt());
        assertTrue(ProgressionOverlay.read(temp.resolve(ProgressionOverlay.DIR)).deletedJson()
                .getAsJsonArray("tables").isEmpty());
        assertEquals(0, ProgressionOverlay.read(temp.resolve(ProgressionOverlay.DIR)).revision(CHAPTER));
    }

    @Test
    void deletingATableThatAChapterUsesAnswersWithTheNewWarnings() {
        ProgressionEditor editor = editor();
        JsonObject packTables = JsonParser.parseString("{\"t:loot\":" + LOOT + "}").getAsJsonObject();
        JsonObject nodes = new JsonObject();
        JsonObject n = node("{\"type\":\"checkmark\"}");
        n.add("rewards", JsonParser.parseString("[{\"type\":\"table\",\"table\":\"t:loot\"}]").getAsJsonArray());
        nodes.add("a", n);
        JsonObject chapters = new JsonObject();
        chapters.add(CHAPTER, chapter(nodes));

        ProgressionEditor.Outcome deleted = editor.apply(chapters, packTables, Map.of(), "t:loot", 0,
                "delete_table", JsonParser.parseString("{\"id\":\"t:loot\"}").getAsJsonObject(), Set.of());

        assertTrue(deleted.ok(), deleted.messages().toString());
        assertTrue(deleted.messages().stream().anyMatch(m -> m.contains("does not exist")),
                deleted.messages().toString());
    }

    @Test
    void restoringADeletedPackTableLetsThePackCopyComeBack() {
        ProgressionEditor editor = editor();
        JsonObject packTables = JsonParser.parseString("{\"t:loot\":" + LOOT + "}").getAsJsonObject();
        assertTrue(tableOp(editor, packTables, "t:loot", 0, "delete_table", "{\"id\":\"t:loot\"}").ok());
        assertFalse(tablesWithOverlay().has("t:loot"));

        ProgressionEditor.Outcome restored = tableOp(editor, packTables.deepCopy(), "t:loot", 1, "restore_table",
                "{\"id\":\"t:loot\"}");

        assertTrue(restored.ok(), restored.messages().toString());
        assertEquals(2, restored.revision());
        JsonObject merged = packTables.deepCopy();
        ProgressionOverlay.read(temp.resolve(ProgressionOverlay.DIR)).applyTablesTo(merged, new java.util.LinkedHashMap<>());
        assertEquals(2, merged.getAsJsonObject("t:loot").get("rolls").getAsInt());
        assertFalse(ProgressionOverlay.read(temp.resolve(ProgressionOverlay.DIR)).tables.containsKey("t:loot"));
    }


    // ---------------------------------------------------------------- command reward escalation (2026-10-22)

    private ProgressionEditor.Outcome applyAs(ProgressionEditor editor, int level, JsonObject pack, String op,
                                              String args) {
        return editor.apply(pack, new JsonObject(), Map.of(), CHAPTER, 0, op,
                JsonParser.parseString(args).getAsJsonObject(), Set.of(), level);
    }

    private static JsonObject packWithReward(String rewardJson) {
        JsonObject pack = pack();
        JsonArray rewards = new JsonArray();
        rewards.add(JsonParser.parseString(rewardJson));
        pack.getAsJsonObject(CHAPTER).getAsJsonObject("nodes").getAsJsonObject("a").add("rewards", rewards);
        return pack;
    }

    @Test
    void aLevelTwoEditorCannotAddACommandReward() {
        ProgressionEditor.Outcome out = applyAs(editor(), 2, pack(), "update_node",
                "{\"id\":\"a\",\"patch\":{\"rewards\":[{\"type\":\"command\",\"command\":\"op @s\"}]}}");

        assertFalse(out.ok());
        assertTrue(out.messages().stream().anyMatch(m -> m.contains("permission level 4")), out.messages().toString());
        assertFalse(Files.exists(overlayFile(CHAPTER)), "nothing was written");
    }

    @Test
    void aLevelFourEditorCanAddACommandReward() {
        ProgressionEditor.Outcome out = applyAs(editor(), 4, pack(), "update_node",
                "{\"id\":\"a\",\"patch\":{\"rewards\":[{\"type\":\"command\",\"command\":\"say hi\"}]}}");

        assertTrue(out.ok(), out.messages().toString());
    }

    @Test
    void aLevelTwoEditorCannotRaiseAPermissionLevelOrChangeTheCommand() {
        ProgressionEditor editor = editor();
        assertFalse(applyAs(editor, 2, packWithReward(
                        "{\"type\":\"command\",\"command\":\"say hi\",\"permission_level\":2}"),
                "update_node", "{\"id\":\"a\",\"patch\":{\"rewards\":["
                        + "{\"type\":\"command\",\"command\":\"say hi\",\"permission_level\":4}]}}").ok(),
                "raising the level is escalation");
        ProgressionEditor.Outcome changed = applyAs(editor, 2, packWithReward(
                        "{\"type\":\"command\",\"command\":\"say hi\"}"),
                "update_node", "{\"id\":\"a\",\"patch\":{\"rewards\":["
                        + "{\"type\":\"command\",\"command\":\"op @s\"}]}}");
        assertFalse(changed.ok(), "rewriting the command is escalation too");
    }

    /** A skill node pays one rank's rewards on each take, so a rank reward can run a command too. */
    private static JsonObject packWithRankReward(String rewardJson) {
        JsonArray rankRewards = new JsonArray();
        rankRewards.add(JsonParser.parseString(rewardJson));
        JsonObject rank = new JsonObject();
        rank.add("rewards", rankRewards);
        JsonArray ranks = new JsonArray();
        ranks.add(rank);
        JsonObject pack = pack();
        pack.getAsJsonObject(CHAPTER).getAsJsonObject("nodes").getAsJsonObject("a").add("ranks", ranks);
        return pack;
    }

    @Test
    void aLevelTwoEditorCannotAddACommandRewardToASkillRank() {
        ProgressionEditor.Outcome out = applyAs(editor(), 2, pack(), "update_node",
                "{\"id\":\"a\",\"patch\":{\"ranks\":[{\"rewards\":["
                        + "{\"type\":\"command\",\"unlock_command\":\"op @s\"}]}]}}");

        assertFalse(out.ok());
        assertTrue(out.messages().stream().anyMatch(m -> m.contains("permission level 4")), out.messages().toString());
        assertFalse(Files.exists(overlayFile(CHAPTER)), "nothing was written");
    }

    @Test
    void aLevelTwoEditorCannotChangeARankRewardsLockCommand() {
        ProgressionEditor.Outcome out = applyAs(editor(), 2, packWithRankReward(
                        "{\"type\":\"command\",\"unlock_command\":\"say hi\",\"lock_command\":\"say bye\"}"),
                "update_node", "{\"id\":\"a\",\"patch\":{\"ranks\":[{\"rewards\":["
                        + "{\"type\":\"command\",\"unlock_command\":\"say hi\",\"lock_command\":\"say bad\"}]}]}}");

        assertFalse(out.ok(), "rewriting a rank's lock command is escalation too");
        assertTrue(out.messages().stream().anyMatch(m -> m.contains("permission level 4")), out.messages().toString());
    }

    @Test
    void aLevelFourEditorCanAddACommandRewardToASkillRank() {
        ProgressionEditor.Outcome out = applyAs(editor(), 4, pack(), "update_node",
                "{\"id\":\"a\",\"patch\":{\"ranks\":[{\"rewards\":["
                        + "{\"type\":\"command\",\"unlock_command\":\"say hi\"}]}]}}");

        assertTrue(out.ok(), out.messages().toString());
    }

    @Test
    void aLevelTwoEditorCanAddAnOrdinaryRewardToASkillRank() {
        ProgressionEditor.Outcome out = applyAs(editor(), 2, pack(), "update_node",
                "{\"id\":\"a\",\"patch\":{\"ranks\":[{\"rewards\":["
                        + "{\"type\":\"item\",\"item\":\"minecraft:apple\"}]}]}}");

        assertTrue(out.ok(), out.messages().toString());
    }

    @Test
    void aLevelTwoEditorCanStillAddAnOrdinaryReward() {
        ProgressionEditor.Outcome out = applyAs(editor(), 2, pack(), "update_node",
                "{\"id\":\"a\",\"patch\":{\"rewards\":[{\"type\":\"item\",\"item\":\"minecraft:apple\"}]}}");

        assertTrue(out.ok(), out.messages().toString());
    }
}
