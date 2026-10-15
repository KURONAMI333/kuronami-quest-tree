package com.kuronami.packprogress.importer;

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
        assertTrue(Files.exists(dir.resolve("data/fixture/progression/chapters/skill_mining.json")));
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
        JsonObject ch = out.chapters.get("skill_mining");
        assertEquals(7, ch.getAsJsonObject("nodes").size());
        assertTrue(ch.getAsJsonObject("points").has("per_level"));
        assertEquals(1, out.counts.get("skill_exclusive_pairs"));
        assertTrue(out.unmapped.isEmpty(), "unmapped: " + out.unmapped);
        assertEquals("any", ch.getAsJsonObject("nodes").getAsJsonObject("s0").getAsJsonObject("requires").get("mode").getAsString());
    }
}
