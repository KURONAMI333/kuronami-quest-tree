package com.kuronami.questtree.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kuronami.questtree.importer.ImportOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What {@code /quest_tree export} picks out of the bundle (2026-10-20). */
class ProgressExportTest {

    @TempDir
    Path temp;

    private static JsonObject bundle() {
        JsonObject chapter = JsonParser.parseString(
                "{\"title\":\"Start\",\"nodes\":{\"a\":{\"x\":0},\"b\":{\"x\":1}}}").getAsJsonObject();
        JsonObject chapters = new JsonObject();
        chapters.add("my-pack:start", chapter.deepCopy());
        chapters.add("my.pack:start", chapter.deepCopy());
        chapters.add("other:start", chapter.deepCopy());
        JsonObject tables = new JsonObject();
        tables.add("my-pack:reward", JsonParser.parseString("{\"rolls\":1}"));
        JsonObject groups = new JsonObject();
        JsonObject bundle = new JsonObject();
        bundle.add("chapters", chapters);
        bundle.add("reward_tables", tables);
        bundle.add("groups", groups);
        return bundle;
    }

    @Test
    void aNamespaceWithAHyphenOrDotStillMatchesItsChapters() {
        assertEquals("my-pack", ProgressExport.validNamespace("my-pack"));
        assertEquals("my.pack", ProgressExport.validNamespace("my.pack"));

        ImportOutput hyphen = ProgressExport.collect("my-pack", bundle(), Map.of(), Set.of());
        assertEquals(Set.of("start"), hyphen.chapters.keySet());
        assertEquals(2, hyphen.counts.get("nodes"));
        assertEquals(Set.of("reward"), hyphen.tables.keySet());

        ImportOutput dot = ProgressExport.collect("my.pack", bundle(), Map.of(), Set.of());
        assertEquals(Set.of("start"), dot.chapters.keySet());
    }

    @Test
    void anImpossibleNamespaceIsRefused() {
        assertNull(ProgressExport.validNamespace(".."));
        assertNull(ProgressExport.validNamespace("has space"));
        assertNull(ProgressExport.validNamespace("Upper/Case"));
        assertNull(ProgressExport.validNamespace(""));
    }

    @Test
    void translationsOfTheNamespaceAreExported() {
        JsonObject en = new JsonObject();
        en.addProperty("progression.my-pack.start.title", "Start");
        en.addProperty("progression.my-pack.start.nodes.a.title", "A");
        en.addProperty("progression.other.start.title", "Other");
        en.addProperty("my-pack.extra", "Extra");
        Map<String, JsonObject> langs = new LinkedHashMap<>();
        langs.put("en_us", en);

        ImportOutput out = ProgressExport.collect("my-pack", bundle(), langs, Set.of());

        JsonObject selected = out.lang.get("en_us");
        assertEquals(3, selected.size());
        assertTrue(selected.has("progression.my-pack.start.title"));
        assertFalse(selected.has("progression.other.start.title"));
        assertEquals(3, out.counts.get("lang"));
    }

    @Test
    void droppedNodesAreCountedInsteadOfDisappearingSilently() {
        ImportOutput out = ProgressExport.collect("my-pack", bundle(), Map.of(),
                Set.of("my-pack:start/a", "my-pack:start/b", "other:start/a"));

        assertEquals(2, out.counts.get("dropped_nodes"));
    }

    @Test
    void anExistingPackFolderIsOnlyReplacedWithForce() throws Exception {
        ImportOutput out = ProgressExport.collect("my-pack", bundle(), Map.of(), Set.of());
        Path root = temp.resolve("datapacks").resolve(ProgressExport.packFolder("my-pack"));
        Files.createDirectories(root);
        Files.writeString(root.resolve("keep.txt"), "keep", StandardCharsets.UTF_8);

        assertThrows(FileAlreadyExistsException.class, () -> ProgressExport.write(out, root, false));
        assertTrue(Files.exists(root.resolve("keep.txt")));

        ProgressExport.write(out, root, true);
        Path chapter = root.resolve("data/my-pack/progression/chapters/start.json");
        assertTrue(Files.exists(chapter));
        assertEquals("Start", JsonParser.parseString(Files.readString(chapter, StandardCharsets.UTF_8))
                .getAsJsonObject().get("title").getAsString());
        assertTrue(Files.exists(root.resolve("data/my-pack/progression/reward_tables/reward.json")));
    }
}
