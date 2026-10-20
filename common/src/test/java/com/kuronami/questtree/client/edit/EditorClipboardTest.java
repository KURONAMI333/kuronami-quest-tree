package com.kuronami.questtree.client.edit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorClipboardTest {

    private static JsonObject node(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void pastesRelativePositionsAndDropsOnlyInternalLinks() {
        JsonObject a = node("""
                {"x": 2, "y": 3, "title": "A",
                 "requires": {"nodes": ["b", "outside"], "mode": "any"},
                 "exclusive_with": ["b"]}""");
        JsonObject b = node("""
                {"x": 3, "y": 4, "title": "B", "requires": {"nodes": ["outside"]}}""");
        EditorClipboard clip = new EditorClipboard(List.of(
                new EditorClipboard.Entry("a", 2, 3, a),
                new EditorClipboard.Entry("b", 3, 4, b)));

        List<JsonObject> made = clip.nodesAt(10, 20, true);
        assertEquals(10.0, made.get(0).get("x").getAsDouble());
        assertEquals(20.0, made.get(0).get("y").getAsDouble());
        assertEquals(11.0, made.get(1).get("x").getAsDouble());
        assertEquals(21.0, made.get(1).get("y").getAsDouble());
        assertEquals(List.of("outside"), java.util.stream.StreamSupport
                .stream(made.get(0).getAsJsonObject("requires").getAsJsonArray("nodes").spliterator(), false)
                .map(e -> e.getAsString()).toList());
        assertEquals(0, made.get(0).getAsJsonArray("exclusive_with").size());
        assertEquals("A", made.get(0).get("title").getAsString(), "the rest of the node is copied verbatim");
        assertFalse(made.get(0).has("id"));

        assertEquals(List.of(new EditorClipboard.Link("a", "b")), clip.internalLinks());
        assertEquals("b", a.getAsJsonObject("requires").getAsJsonArray("nodes").get(0).getAsString(),
                "copying does not change the source");
    }

    @Test
    void pasteJobWaitsForEveryCreateAndRewritesLinks() {
        JsonObject a = node("""
                {"x": 0, "y": 0, "requires": {"nodes": ["b"]}}""");
        JsonObject b = node("""
                {"x": 1, "y": 0}""");
        EditorClipboard clip = new EditorClipboard(List.of(
                new EditorClipboard.Entry("a", 0, 0, a),
                new EditorClipboard.Entry("b", 1, 0, b)));
        PasteJob job = new PasteJob(clip);

        job.created("a", "node_7");
        assertFalse(job.complete());
        assertTrue(job.resolvedLinks().isEmpty(), "a link waits for both ends");
        job.created("b", "node_8");
        assertTrue(job.complete());
        assertEquals(List.of(new EditorClipboard.Link("node_7", "node_8")), job.resolvedLinks());
    }

    @Test
    void refusedCreateDoesNotBlockTheRest() {
        JsonObject a = node("""
                {"x": 0, "y": 0, "requires": {"nodes": ["b"]}}""");
        JsonObject b = node("""
                {"x": 1, "y": 0}""");
        PasteJob job = new PasteJob(new EditorClipboard(List.of(
                new EditorClipboard.Entry("a", 0, 0, a),
                new EditorClipboard.Entry("b", 1, 0, b))));
        job.refused("a");
        job.created("b", "node_8");
        assertTrue(job.complete());
        assertEquals(1, job.refusedCount());
        assertTrue(job.resolvedLinks().isEmpty(), "no link is sent to a node that was not created");
    }
}
