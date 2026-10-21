package com.kuronami.questtree.server;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
