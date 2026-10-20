package com.kuronami.questtree.network;

import com.kuronami.questtree.network.EditorOpChunks.Part;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The C2S size ceiling and the chunk path that keeps a large edit inside it (2026-10-20). */
class EditorOpChunksTest {

    @Test
    void aChunkFitsTheVanillaServerboundPayloadLimit() {
        // a char is at most 4 UTF-8 bytes; leave room for the payload id and the other fields
        assertTrue(EditorOpChunks.MAX_PART_CHARS * 4 < 32767);
    }

    @Test
    void oneSmallRequestIsStillOnePayload() {
        List<Part> parts = EditorOpChunks.split(0, "t:basics", 3, "move_node", "{\"id\":\"a\"}");

        assertEquals(1, parts.size());
        assertEquals(0, parts.get(0).partIndex());
        assertEquals(1, parts.get(0).partCount());
        assertEquals(3, parts.get(0).chapterRevision());
    }

    @Test
    void aLargeRequestIsSplitAndPutBackTogether() {
        String args = "{\"id\":\"a\",\"text\":\"" + "x".repeat(EditorOpChunks.MAX_PART_CHARS * 2 + 5) + "\"}";
        List<Part> parts = EditorOpChunks.split(7, "t:basics", 4, "update_node", args);

        assertEquals(3, parts.size());
        EditorOpChunks.Assembler assembler = new EditorOpChunks.Assembler();
        assertNull(assembler.accept(parts.get(0)));
        assertNull(assembler.accept(parts.get(1)));
        Part complete = assembler.accept(parts.get(2));

        assertNotNull(complete);
        assertEquals(7, complete.requestId());
        assertEquals("t:basics", complete.chapterId());
        assertEquals(4, complete.chapterRevision());
        assertEquals("update_node", complete.op());
        assertEquals(args, complete.args());
        assertEquals(0, complete.partIndex());
        assertEquals(1, complete.partCount());
    }

    @Test
    void partsArrivingInAnotherOrderStillJoin() {
        String args = "y".repeat(EditorOpChunks.MAX_PART_CHARS + 1);
        List<Part> parts = EditorOpChunks.split(9, "t:basics", 1, "update_node", args);
        EditorOpChunks.Assembler assembler = new EditorOpChunks.Assembler();
        assertNull(assembler.accept(parts.get(1)));
        Part complete = assembler.accept(parts.get(0));

        assertNotNull(complete);
        assertEquals(args, complete.args());
    }

    @Test
    void argsTooLargeForAnyRequestAreRefusedBeforeSend() {
        assertThrows(IllegalArgumentException.class, () -> EditorOpChunks.split(3, "t:basics", 0, "create_node",
                "x".repeat(EditorOpChunks.MAX_ARGS_CHARS + 1)));
    }

    @Test
    void aChunkedRequestWithoutARequestIdCannotBeReassembled() {
        String args = "z".repeat(EditorOpChunks.MAX_PART_CHARS + 1);
        assertThrows(IllegalArgumentException.class,
                () -> EditorOpChunks.split(0, "t:basics", 0, "update_node", args));
    }

    @Test
    void anInvalidPartCountIsRefusedWithASentence() {
        EditorOpChunks.Assembler assembler = new EditorOpChunks.Assembler();
        assertThrows(IllegalArgumentException.class,
                () -> assembler.accept(new Part(1, "t:basics", 0, "undo", "{}", 3, 2)));
    }
}
