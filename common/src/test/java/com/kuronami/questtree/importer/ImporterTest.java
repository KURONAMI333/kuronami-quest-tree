package com.kuronami.questtree.importer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImporterTest {

    private static Path fixture(String p) throws Exception {
        return Path.of(ImporterTest.class.getResource("/fixtures/" + p).toURI());
    }

    @Test
    void snbtReadsPrettyFormat() {
        JsonObject o = Snbt.parse("{\n\tid: \"A\"\n\tx: 1.5d\n\tn: 5L\n\tlist: [I; 1, 2]\n\tflag: true\n\tnested: { k: \"v\" }\n}").getAsJsonObject();
        assertEquals("A", o.get("id").getAsString());
        assertEquals(1.5, o.get("x").getAsDouble());
        assertEquals(5, o.get("n").getAsLong());
        assertEquals(2, o.getAsJsonArray("list").size());
        assertTrue(o.get("flag").getAsBoolean());
        assertEquals("v", o.getAsJsonObject("nested").get("k").getAsString());
    }

    @Test
    void ftbQuestsBookConvertsCompletely(@TempDir Path tmp) throws Exception {
        ImportOutput out = new ImportOutput("test");
        FtbQuestsImporter.convert(fixture("ftbquests/quests"), out);
        assertEquals(2, out.counts.get("chapters"));
        // imported chapters keep their shapes instead of taking the shape of each quest's role
        for (Map.Entry<String, JsonObject> ch : out.chapters.entrySet()) {
            assertTrue(ch.getValue().getAsJsonObject("defaults").has("shape"), ch.getKey() + " has no default shape");
        }
        assertEquals(196, out.counts.get("nodes"));
        assertEquals(1, out.counts.get("reward_tables"));
        assertEquals(1, out.counts.get("cross_chapter_dependencies"));
        assertTrue(out.unmapped.isEmpty(), "unmapped: " + out.unmapped);
        // translations from per-chapter lang files, in both locales
        assertEquals("Basics", out.lang.get("en_us").get("progression.test.basics.title").getAsString());
        assertEquals("基本", out.lang.get("ja_jp").get("progression.test.basics.title").getAsString());
        // an image that appears after a quest keeps that link
        JsonObject api = out.chapters.get("apiculture");
        assertTrue(api.getAsJsonArray("decorations").get(1).getAsJsonObject().has("visible_after"));
        // random reward points at the converted table
        out.writeTo(tmp);
        assertTrue(Files.exists(tmp.resolve("data/test/progression/chapters/apiculture.json")));
        assertTrue(Files.exists(tmp.resolve("data/test/progression/lang/ja_jp.json")));
        assertTrue(Files.exists(tmp.resolve("pack.mcmeta")));
    }

    @Test
    void reimportRemovesStaleChapters(@TempDir Path tmp) throws Exception {
        ImportOutput out = new ImportOutput("test");
        FtbQuestsImporter.convert(fixture("ftbquests/quests"), out);
        out.writeTo(tmp);
        Path stale = tmp.resolve("data/test/progression/chapters/deleted_in_ftb.json");
        Files.writeString(stale, "{}");
        out.writeTo(tmp);
        assertTrue(Files.notExists(stale));
        assertTrue(out.settings != null && out.settings.has("version"));
    }

    /** Writes both fixtures as one data pack under build/ for the in-game check (copied into the dev world by hand). */
    @Test
    void writesFixturePack() throws Exception {
        ImportOutput out = new ImportOutput("fixture");
        FtbQuestsImporter.convert(fixture("ftbquests/quests"), out);
        Map<String, JsonElement> files = new LinkedHashMap<>();
        try (Stream<Path> s = Files.list(fixture("puffish/mining"))) {
            for (Path f : s.toList()) {
                files.put(f.getFileName().toString(), JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)));
            }
        }
        PuffishImporter.convert("ftb/mining", files, out);
        Path dir = Path.of("build/fixture-pack");
        out.writeTo(dir);
        assertTrue(Files.exists(dir.resolve("data/fixture/progression/chapters/skill_ftb_mining.json")));
    }

    /**
     * Dev tool: converts a real pack's FTB Quests folder into a data pack without starting the game. Runs only when
     * -Dqt.importDir=<.../config/ftbquests/quests> is given; writes to build/import-pack with namespace qt.importNs.
     */
    @Test
    void importsExternalPackWhenAsked() throws Exception {
        String dir = System.getProperty("qt.importDir");
        if (dir == null || dir.isBlank()) {
            return;
        }
        ImportOutput out = new ImportOutput(System.getProperty("qt.importNs", "pack"));
        FtbQuestsImporter.convert(Path.of(dir), out);
        out.writeTo(Path.of("build/import-pack"));
        System.out.println("IMPORT counts=" + out.counts + " unmapped=" + out.unmapped);
    }

    @Test
    void puffishCategoryConverts() throws Exception {
        Map<String, JsonElement> files = new LinkedHashMap<>();
        try (Stream<Path> s = Files.list(fixture("puffish/mining"))) {
            for (Path f : s.toList()) {
                files.put(f.getFileName().toString(), JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)));
            }
        }
        ImportOutput out = new ImportOutput("test");
        PuffishImporter.convert("ftb/mining", files, out);
        JsonObject ch = out.chapters.get("skill_ftb_mining");
        assertEquals(7, ch.getAsJsonObject("nodes").size());
        assertTrue(ch.getAsJsonObject("points").has("per_level"));
        assertEquals(1, out.counts.get("skill_exclusive_pairs"));
        assertTrue(out.unmapped.isEmpty(), "unmapped: " + out.unmapped);
        assertEquals("any", ch.getAsJsonObject("nodes").getAsJsonObject("s0").getAsJsonObject("requires").get("mode").getAsString());
    }

    /** The block, fluid, forge_energy and observation tasks each keep their own counter field after conversion. */
    @Test
    void taskTypesConvertToTheirOwnCounters() throws Exception {
        ImportOutput out = new ImportOutput("test");
        FtbQuestsImporter.convert(fixture("ftbquests_tasks/quests"), out);
        JsonArray tasks = out.chapters.get("tasks").getAsJsonObject("nodes").getAsJsonObject("2000000000000001").getAsJsonArray("tasks");
        JsonObject brk = tasks.get(0).getAsJsonObject();
        assertEquals("break", brk.get("type").getAsString(), "FTB block task should become break");
        assertEquals("minecraft:stone", brk.get("block").getAsString());
        assertEquals(4, brk.get("count").getAsInt());
        JsonObject fluid = tasks.get(1).getAsJsonObject();
        assertEquals("fluid", fluid.get("type").getAsString());
        assertEquals("minecraft:water", fluid.get("fluid").getAsString());
        assertEquals(1000, fluid.get("amount").getAsInt());
        JsonObject energy = tasks.get(2).getAsJsonObject();
        assertEquals("energy", energy.get("type").getAsString(), "FTB forge_energy should become energy");
        assertEquals(5000, energy.get("amount").getAsInt(), "FTB value should become amount");
        JsonObject obs = tasks.get(3).getAsJsonObject();
        assertEquals("observation", obs.get("type").getAsString());
        assertEquals("minecraft:stone", obs.get("target").getAsString(), "FTB to_observe should become target");
        assertEquals(20, obs.get("ticks").getAsInt(), "FTB timer should become ticks");
        assertTrue(out.unmapped.isEmpty(), "unmapped: " + out.unmapped);
    }

    /** FTB Quests points at reward tables by a decimal long; the table files carry the same id as 16 hex digits. */
    @org.junit.jupiter.api.Test
    void tableIdMatchesTheTableFileName() {
        org.junit.jupiter.api.Assertions.assertEquals("611a165b35847a5a", FtbQuestsImporter.tableId("6996929552069196378"));
        org.junit.jupiter.api.Assertions.assertEquals("611a165b35847a5a", FtbQuestsImporter.tableId("6996929552069196378L"));
        org.junit.jupiter.api.Assertions.assertEquals("ffffffffffffffff", FtbQuestsImporter.tableId("-1"));
        org.junit.jupiter.api.Assertions.assertEquals("611a165b35847a5a", FtbQuestsImporter.tableId("611A165B35847A5A"));
    }
}
