package com.kuronami.questtree.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kuronami.questtree.importer.FtbQuestsImporter;
import com.kuronami.questtree.importer.ImportOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChapterOverlapTest {

    private static JsonObject chapter(String ftbId, String... nodeIds) {
        JsonObject c = new JsonObject();
        if (ftbId != null) {
            c.addProperty("ftbquests_id", ftbId);
        }
        JsonObject nodes = new JsonObject();
        for (String n : nodeIds) {
            nodes.add(n, JsonParser.parseString("{\"x\":0,\"y\":0}"));
        }
        c.add("nodes", nodes);
        return c;
    }

    @Test
    void importerKeepsTheFtbIdsOfChapterAndQuest(@TempDir Path tmp) throws Exception {
        Path chapters = Files.createDirectories(tmp.resolve("chapters"));
        Files.writeString(chapters.resolve("oritech.snbt"), """
                {
                    id: "ABCDEF0123456789"
                    filename: "oritech"
                    quests: [ { id: "1000000000000002", x: 0.0d, y: 0.0d, tasks: [ { id: "3", type: "checkmark" } ] } ]
                }
                """);
        ImportOutput out = new ImportOutput("ftbquests");
        FtbQuestsImporter.convert(tmp, out);
        JsonObject chapter = out.chapters.get("oritech");
        assertEquals("abcdef0123456789", chapter.get("ftbquests_id").getAsString());
        JsonObject node = chapter.getAsJsonObject("nodes").entrySet().iterator().next().getValue().getAsJsonObject();
        assertEquals("1000000000000002", node.get("ftbquests_id").getAsString());
    }

    @Test
    void sameSourceIdUnderTwoNamespacesOverlaps() {
        Map<String, JsonObject> all = new LinkedHashMap<>();
        all.put("atm10:oritech", chapter("aa", "q1", "q2"));
        all.put("ftbquests:renamed", chapter("aa", "q1", "q3"));
        all.put("ftbquests:other", chapter("bb", "z"));
        List<ChapterOverlap.Overlap> found = ChapterOverlap.find(all, null);
        assertEquals(2, found.size(), "one count per direction");
        ChapterOverlap.Overlap o = found.stream().filter(x -> x.namespace().equals("atm10")).findFirst().orElseThrow();
        assertEquals("ftbquests", o.other());
        assertEquals(1, o.chapters());
        assertEquals(1, o.quests());
    }

    @Test
    void sameFileNameWithoutSourceIdStillOverlaps() {
        Map<String, JsonObject> all = new LinkedHashMap<>();
        all.put("atm10:oritech", chapter(null, "q1"));
        all.put("ftbquests:oritech", chapter("aa", "q1"));
        assertEquals(2, ChapterOverlap.find(all, null).size());
        assertEquals(1, ChapterOverlap.find(all, "atm10").get(0).chapters());
    }

    @Test
    void differentBooksDoNotOverlapAndSameNamespaceIsIgnored() {
        Map<String, JsonObject> all = new LinkedHashMap<>();
        all.put("a:one", chapter("aa", "q"));
        all.put("b:two", chapter("bb", "q"));
        all.put("a:three", chapter("aa", "q"));
        assertTrue(ChapterOverlap.find(all, null).isEmpty());
    }

    @Test
    void onlyFilterKeepsPairsThatInvolveTheNamespace() {
        Map<String, JsonObject> all = new LinkedHashMap<>();
        all.put("a:x", chapter("1"));
        all.put("b:x", chapter("1"));
        all.put("c:y", chapter("2"));
        all.put("d:y", chapter("2"));
        assertEquals(2, ChapterOverlap.find(all, "a").size());
    }

    @Test
    void validatorWarnsAboutAnOverlapWithoutDroppingNodes() {
        JsonObject bundle = new JsonObject();
        JsonObject chapters = new JsonObject();
        chapters.add("atm10:oritech", JsonParser.parseString("{\"ftbquests_id\":\"aa\",\"nodes\":{\"q\":{\"x\":0,\"y\":0}}}"));
        chapters.add("ftbquests:oritech", JsonParser.parseString("{\"ftbquests_id\":\"aa\",\"nodes\":{\"q\":{\"x\":0,\"y\":0}}}"));
        bundle.add("chapters", chapters);
        bundle.add("reward_tables", new JsonObject());
        ProgressValidator.Result r = ProgressValidator.validate(bundle, Map.of());
        long overlapWarnings = r.problems.stream().filter(p -> p.severity() == ProgressValidator.Severity.WARNING
                && p.message().contains("are also in namespace")).count();
        assertEquals(1, overlapWarnings);
        assertTrue(r.droppedNodes.isEmpty());
    }
}
