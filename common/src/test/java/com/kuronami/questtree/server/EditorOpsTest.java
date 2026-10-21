package com.kuronami.questtree.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kuronami.questtree.network.EditorReplies;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The control buttons' one dangerous step: turning a request into a command line (2026-10-21). */
class EditorOpsTest {

    @Test
    void namespaceComesFromTheChapter() {
        assertEquals("mypack", EditorOps.namespaceOf("mypack:basics"));
        assertEquals("mypack", EditorOps.namespaceOf("mypack:group/basics"));
        assertNull(EditorOps.namespaceOf("mypack"));
        assertNull(EditorOps.namespaceOf("mypack:"));
        assertNull(EditorOps.namespaceOf("MyPack:basics"));
    }

    @Test
    void exportTakesOnlyResourceNamespaces() {
        assertEquals("quest_tree export mypack", EditorOps.exportCommand("MyPack"));
        assertNull(EditorOps.exportCommand("my pack"));
        assertNull(EditorOps.exportCommand("my/pack"));
        assertNull(EditorOps.exportCommand(""));
        JsonObject args = new JsonObject();
        args.addProperty("namespace", "other");
        assertEquals("other", EditorOps.exportNamespace(args, "mypack:basics"));
        assertEquals("mypack", EditorOps.exportNamespace(new JsonObject(), "mypack:basics"));
    }

    @Test
    void exportCanAskForTheExistingFolderToBeReplaced() {
        assertEquals("quest_tree export mypack", EditorOps.exportCommand("mypack", false));
        assertEquals("quest_tree export mypack force", EditorOps.exportCommand("mypack", true));
        assertNull(EditorOps.exportCommand("my pack", true));

        assertTrue(EditorOps.forceRequested(JsonParser.parseString("{\"force\":true}").getAsJsonObject()));
        assertFalse(EditorOps.forceRequested(new JsonObject()));

        assertNull(EditorOps.exportRefusal("mypack", false, false));
        assertEquals(EditorReplies.EXPORT_FOLDER_EXISTS + ": quest_tree_export_mypack",
                EditorOps.exportRefusal("mypack", false, true));
        assertNull(EditorOps.exportRefusal("mypack", true, true));
    }

    @Test
    void testCommandCarriesTheChapterId() {
        assertEquals("quest_tree reset @s mypack:group/basics", EditorOps.testCommand("mypack:group/basics"));
        assertNull(EditorOps.testCommand("mypack:group/../basics"));
        assertNull(EditorOps.testCommand("mypack:"));
        assertNull(EditorOps.testCommand(""));
    }

    @Test
    void testingAChapterIsRefusedOutsideASoloTeam() {
        UUID self = UUID.randomUUID();
        assertNull(EditorOps.testRefusal(List.of(self)));
        assertEquals("leave your party before testing a chapter",
                EditorOps.testRefusal(List.of(self, UUID.randomUUID())));
        assertEquals("leave your party before testing a chapter", EditorOps.testRefusal(List.of()));
    }

    @Test
    void aFailedCommandIsARefusalAndKeepsEditingOn() {
        EditorOps.ControlOutcome export = EditorOps.controlOutcome("export", 3, "quest_tree_export_t");
        assertTrue(export.ok());
        assertFalse(export.leaveEditing());
        assertEquals("quest_tree_export_t", export.message());

        EditorOps.ControlOutcome failedExport = EditorOps.controlOutcome("export", 0, "quest_tree_export_t");
        assertFalse(failedExport.ok());
        assertFalse(failedExport.leaveEditing());
        assertEquals("the export command failed", failedExport.message());

        EditorOps.ControlOutcome test = EditorOps.controlOutcome("test_chapter", 1, "");
        assertTrue(test.ok());
        assertTrue(test.leaveEditing());
        assertEquals("", test.message());

        EditorOps.ControlOutcome failedTest = EditorOps.controlOutcome("test_chapter", 0, "");
        assertFalse(failedTest.ok());
        assertFalse(failedTest.leaveEditing());
        assertEquals("the test command failed", failedTest.message());
    }
}
