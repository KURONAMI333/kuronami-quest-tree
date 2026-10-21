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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /** An optional reward type that is not installed becomes Pufferfish's dummy; a required one stays reported. */
    @Test
    void puffishOptionalUnknownRewardBecomesDummy() throws Exception {
        Map<String, JsonElement> files = new LinkedHashMap<>();
        try (Stream<Path> s = Files.list(fixture("puffish/mining"))) {
            for (Path f : s.toList()) {
                files.put(f.getFileName().toString(), JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)));
            }
        }
        JsonArray rewards = files.get("definitions.json").getAsJsonObject().getAsJsonObject("s0").getAsJsonArray("rewards");
        JsonObject optional = new JsonObject();
        optional.addProperty("type", "puffish_skills:missing_mod:optional");
        optional.addProperty("required", false);
        rewards.add(optional);
        JsonObject required = new JsonObject();
        required.addProperty("type", "puffish_skills:missing_mod:required");
        required.addProperty("required", true);
        rewards.add(required);
        ImportOutput out = new ImportOutput("test");
        PuffishImporter.convert("ftb/mining", files, out);
        JsonArray converted = out.chapters.get("skill_ftb_mining").getAsJsonObject("nodes").getAsJsonObject("s0").getAsJsonArray("rewards");
        assertEquals("dummy", converted.get(converted.size() - 2).getAsJsonObject().get("type").getAsString());
        assertEquals("puffish_skills:missing_mod:required", converted.get(converted.size() - 1).getAsJsonObject().get("type").getAsString());
        assertTrue(out.unmapped.containsKey("skill_reward_type.puffish_skills:missing_mod:required"), out.unmapped.toString());
        assertFalse(out.unmapped.containsKey("skill_reward_type.puffish_skills:missing_mod:optional"), out.unmapped.toString());
    }

    /** A Pufferfish points reward names its amount and category differently; both are carried over. */
    @Test
    void puffishPointsRewardKeepsItsAmount() throws Exception {
        Map<String, JsonElement> files = new LinkedHashMap<>();
        try (Stream<Path> s = Files.list(fixture("puffish/mining"))) {
            for (Path f : s.toList()) {
                files.put(f.getFileName().toString(), JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)));
            }
        }
        JsonArray rewards = files.get("definitions.json").getAsJsonObject().getAsJsonObject("s0").getAsJsonArray("rewards");
        JsonObject points = new JsonObject();
        points.addProperty("type", "puffish_skills:points");
        JsonObject data = new JsonObject();
        data.addProperty("category", "puffish_skills:mining");
        data.addProperty("points", 3);
        points.add("data", data);
        rewards.add(points);
        ImportOutput out = new ImportOutput("test");
        PuffishImporter.convert("puffish/mining", files, out);
        JsonArray converted = out.chapters.get("skill_puffish_mining").getAsJsonObject("nodes").getAsJsonObject("s0").getAsJsonArray("rewards");
        JsonObject reward = converted.get(converted.size() - 1).getAsJsonObject();
        assertEquals("points", reward.get("type").getAsString());
        assertEquals(3, reward.get("amount").getAsInt());
        assertFalse(reward.has("points"), "the PS field name does not leak into our data");
        assertFalse(reward.has("category"), "the node's own chapter is the default target");
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

    /**
     * A chapter's {@code require_sequential_tasks} becomes the chapter default; a quest that sets its own false keeps
     * it, and a quest without a value inherits (FTB Quests' Tristate, 2026-10-20).
     */
    @Test
    void chapterSequentialDefaultIsImported(@TempDir Path tmp) throws Exception {
        Path chapters = tmp.resolve("chapters");
        Files.createDirectories(chapters);
        Files.writeString(chapters.resolve("seq.snbt"), """
                {
                  filename: "seq"
                  require_sequential_tasks: true
                  quests: [
                    {id: "1", tasks: [{id: "11", type: "checkmark"}, {id: "12", type: "checkmark"}]}
                    {id: "2", require_sequential_tasks: false, tasks: [{id: "21", type: "checkmark"}, {id: "22", type: "checkmark"}]}
                  ]
                }
                """);
        ImportOutput out = new ImportOutput("test");
        FtbQuestsImporter.convert(tmp, out);
        JsonObject ch = out.chapters.get("seq");
        assertTrue(ch.getAsJsonObject("defaults").get("sequential_tasks").getAsBoolean());
        JsonObject nodes = ch.getAsJsonObject("nodes");
        assertFalse(nodes.getAsJsonObject("1").has("sequential_tasks"), "no own value: inherits the chapter");
        assertFalse(nodes.getAsJsonObject("2").get("sequential_tasks").getAsBoolean(), "explicit false overrides");
    }

    /**
     * A 1.20.1 book (2026-10-20): dependencies may still be an int array, items use vanilla NBT ({@code Count},
     * {@code tag}) instead of {@code count}/{@code components}, and the two old NBT flags become one match mode.
     */
    @Test
    void olderFtbQuestsFormatConverts(@TempDir Path tmp) throws Exception {
        Path chapters = tmp.resolve("chapters");
        Files.createDirectories(chapters);
        Files.writeString(chapters.resolve("legacy.snbt"), """
                {
                  filename: "legacy"
                  title: "Legacy"
                  default_quest_shape: "default"
                  quests: [
                    {id: "1", shape: "default", title: "Old", description: ["first", "second"], tasks: [{id: "11", type: "item",
                      item: {id: "minecraft:diamond_sword", Count: 3, tag: {
                        Damage: 5,
                        display: {Name: "{\\"text\\":\\"Old Blade\\"}", Lore: ["{\\"text\\":\\"line\\"}"]},
                        Enchantments: [{id: "minecraft:sharpness", lvl: 2}]
                      }},
                      match_nbt: true, weak_nbt_match: true}]}
                    {id: "2", dependencies: [I; 1], tasks: [{id: "21", type: "item",
                      item: {id: "minecraft:stone", Count: 1}, match_nbt: true}]}
                  ]
                }
                """);
        ImportOutput out = new ImportOutput("test");
        FtbQuestsImporter.convert(tmp, out);
        JsonObject chapter = out.chapters.get("legacy");
        assertEquals("square", chapter.getAsJsonObject("defaults").get("shape").getAsString(),
                "a legacy \"default\" chapter shape must fall back to square");
        assertEquals("Legacy", out.lang.get("en_us").get("progression.test.legacy.title").getAsString());
        assertEquals("first\nsecond", out.lang.get("en_us").get("progression.test.legacy.1.desc").getAsString());
        JsonObject nodes = chapter.getAsJsonObject("nodes");
        assertFalse(nodes.getAsJsonObject("1").has("shape"), "a legacy \"default\" quest shape must use the chapter default");
        JsonArray first = nodes.getAsJsonObject("1").getAsJsonArray("tasks");
        JsonObject item = first.get(0).getAsJsonObject();
        assertEquals(3, item.getAsJsonObject("item").get("count").getAsInt(), "Count must become count");
        assertEquals("fuzzy", item.get("match_components").getAsString(), "weak_nbt_match must become fuzzy");
        JsonObject comps = item.getAsJsonObject("item").getAsJsonObject("components");
        assertEquals(5, comps.get("minecraft:damage").getAsInt());
        assertEquals("Old Blade", comps.getAsJsonObject("minecraft:custom_name").get("text").getAsString());
        assertTrue(comps.has("minecraft:enchantments"), "old Enchantments must become the enchantments component");
        JsonArray second = nodes.getAsJsonObject("2").getAsJsonArray("tasks");
        assertEquals("strict", second.get(0).getAsJsonObject().get("match_components").getAsString(),
                "match_nbt without weak_nbt_match must become strict");
        assertEquals("1", nodes.getAsJsonObject("2").getAsJsonObject("requires").getAsJsonArray("nodes").get(0).getAsString(),
                "an int-array dependency must resolve to the quest");
        assertTrue(out.unmapped.isEmpty(), "unmapped: " + out.unmapped);
    }

    /** FTB Quests points at reward tables by a decimal long; the table files carry the same id as 16 hex digits. */    @org.junit.jupiter.api.Test
    void tableIdMatchesTheTableFileName() {
        org.junit.jupiter.api.Assertions.assertEquals("611a165b35847a5a", FtbQuestsImporter.tableId("6996929552069196378"));
        org.junit.jupiter.api.Assertions.assertEquals("611a165b35847a5a", FtbQuestsImporter.tableId("6996929552069196378L"));
        org.junit.jupiter.api.Assertions.assertEquals("ffffffffffffffff", FtbQuestsImporter.tableId("-1"));
        org.junit.jupiter.api.Assertions.assertEquals("611a165b35847a5a", FtbQuestsImporter.tableId("611A165B35847A5A"));
    }
}
