package com.kuronami.questtree.migration;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.kuronami.questtree.importer.ImportOutput;
import com.kuronami.questtree.importer.PuffishImporter;
import com.kuronami.questtree.importer.Snbt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Broken, oversized and mistyped source files during a migration (2026-10-24): each one is reported or skipped on its
 * own, and the rest of the source still comes over.
 */
class MigrationRobustnessTest {

    private static final long AAA = 0xAAAL;

    @Test
    void aBrokenChapterFileIsReportedAndTheRestAreRead(@TempDir Path tmp) throws Exception {
        Path chapters = Files.createDirectories(tmp.resolve("chapters"));
        Files.writeString(chapters.resolve("good.snbt"), "{ id: \"0000000000000AAA\" filename: \"good\" quests: [] }");
        Files.writeString(chapters.resolve("bad.snbt"), "{ id: ");
        MigrationReport report = new MigrationReport();

        FtbqBook book = FtbqBook.read(tmp, report);

        assertTrue(book.isChapter(AAA));
        assertEquals(1, report.get("unresolved"));
        assertEquals(java.util.List.of("unreadable_file bad.snbt"), report.unresolvedList());
    }

    @Test
    void anOversizedChapterFileIsNotRead(@TempDir Path tmp) throws Exception {
        Path chapters = Files.createDirectories(tmp.resolve("chapters"));
        Files.writeString(chapters.resolve("good.snbt"), "{ id: \"0000000000000AAA\" }");
        Files.write(chapters.resolve("big.snbt"), new byte[(int) MigrationLimits.MAX_TEXT_FILE_BYTES + 1]);
        MigrationReport report = new MigrationReport();

        FtbqBook book = FtbqBook.read(tmp, report);

        assertTrue(book.isChapter(AAA));
        assertEquals(java.util.List.of("unreadable_file big.snbt"), report.unresolvedList());
    }

    @Test
    void anOversizedTeamFileIsCountedUnreadable(@TempDir Path tmp) throws Exception {
        Path players = Files.createDirectories(tmp.resolve("player"));
        Files.writeString(players.resolve("a.snbt"), "{ id: \"00000000-0000-0000-0000-00000000000a\" }");
        Files.write(players.resolve("big.snbt"), new byte[(int) MigrationLimits.MAX_TEXT_FILE_BYTES + 1]);
        int[] unreadable = {0};

        FtbTeamsMembers members = FtbTeamsMembers.read(tmp, unreadable);

        assertEquals(1, unreadable[0]);
        assertEquals(1, members.teams());
    }

    @Test
    void nonNumericKeysAreSkippedWithoutDroppingTheFile() {
        FtbqTeamData team = FtbqTeamData.parse(Snbt.parse("""
                {
                  uuid: "0000000000000000000000000000000a"
                  completion_count: { "0000000000000001": "many", "0000000000000005": {}, "0000000000000002": 3 }
                  repeatable: { "0000000000000003": "later", "0000000000000004": 5000L }
                }
                """).getAsJsonObject());

        assertEquals(Map.of(2L, 3), team.completionCount);
        assertEquals(Map.of(4L, 5000L), team.repeatable);
        assertTrue(team.badKeys.contains("completion_count.0000000000000001"));
        assertTrue(team.badKeys.contains("completion_count.0000000000000005"));
        assertTrue(team.badKeys.contains("repeatable.0000000000000003"));
    }

    @Test
    void aNonPrimitiveFilenameFallsBackToTheFileName() {
        FtbqBook book = FtbqBook.of(Map.of("good", Snbt.parse("{ filename: {}, quests: [] }").getAsJsonObject()));

        assertTrue(book.isChapter(1L));
    }

    @Test
    void aBrokenPuffishCategoryIsSkippedAndTheRestAreConverted() {
        Map<String, Map<String, JsonElement>> categories = new LinkedHashMap<>();
        categories.put("ftb/bad", files("{ \"mining\": \"not an object\" }"));
        categories.put("ftb/good", files("{ \"one\": {} }"));
        ImportOutput out = new ImportOutput("t");

        PuffishImporter.convertAll(categories, out);

        assertTrue(out.chapters.containsKey("skill_ftb_good"));
        assertTrue(out.notes.keySet().stream().anyMatch(n -> n.startsWith("category skipped (ftb/bad)")));
    }

    private static Map<String, JsonElement> files(String skills) {
        Map<String, JsonElement> files = new LinkedHashMap<>();
        files.put("category.json", JsonParser.parseString("{ \"title\": \"Category\" }"));
        files.put("skills.json", JsonParser.parseString(skills));
        return files;
    }
}
