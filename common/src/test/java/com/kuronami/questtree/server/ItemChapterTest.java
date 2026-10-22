package com.kuronami.questtree.server;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The chapter FTB Quests' generate makes from a list of items (2026-10-22). */
class ItemChapterTest {

    private static JsonObject node(JsonObject chapter, int index) {
        return chapter.getAsJsonObject("nodes").getAsJsonObject("n" + index);
    }

    private static String itemId(JsonObject node) {
        return node.getAsJsonArray("tasks").get(0).getAsJsonObject()
                .getAsJsonObject("item").get("id").getAsString();
    }

    @Test
    void oneNodePerDistinctItemSortedById() {
        JsonObject chapter = ItemChapter.chapter(List.of(
                "minecraft:stone", "minecraft:dirt", "minecraft:stone", "minecraft:apple"));
        assertEquals(3, chapter.getAsJsonObject("nodes").size());
        assertEquals("minecraft:apple", itemId(node(chapter, 0)));
        assertEquals("minecraft:dirt", itemId(node(chapter, 1)));
        assertEquals("minecraft:stone", itemId(node(chapter, 2)));
        assertEquals("Auto-generated chapter [3 items]", chapter.get("title").getAsString());
        assertEquals("minecraft:book", chapter.getAsJsonObject("icon").get("id").getAsString());
        assertEquals("rsquare", chapter.getAsJsonObject("defaults").get("shape").getAsString());
    }

    @Test
    void tasksAreOneItemEach() {
        JsonObject chapter = ItemChapter.chapter(List.of("minecraft:stone"));
        JsonObject task = node(chapter, 0).getAsJsonArray("tasks").get(0).getAsJsonObject();
        assertEquals("item", task.get("type").getAsString());
        assertEquals(1, task.getAsJsonObject("item").get("count").getAsInt());
    }

    @Test
    void columnsWrapAtFortyAndNamespacesStartOnANewRow() {
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            ids.add("minecraft:item_" + i);
        }
        ids.add("other:thing");
        ids.add("other:another");
        JsonObject chapter = ItemChapter.chapter(ids);
        assertEquals(39, node(chapter, 39).get("x").getAsInt());
        assertEquals(0, node(chapter, 39).get("y").getAsInt());
        // the namespace change leaves one blank row
        assertEquals(0, node(chapter, 40).get("x").getAsInt());
        assertEquals(2, node(chapter, 40).get("y").getAsInt());
        assertEquals(1, node(chapter, 41).get("x").getAsInt());
    }

    @Test
    void aFullRowWrapsWithoutANamespaceChange() {
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 41; i++) {
            ids.add(String.format("minecraft:item_%02d", i));
        }
        JsonObject chapter = ItemChapter.chapter(ids);
        assertEquals(39, node(chapter, 39).get("x").getAsInt());
        assertEquals(0, node(chapter, 40).get("x").getAsInt());
        assertEquals(1, node(chapter, 40).get("y").getAsInt());
    }

    @Test
    void anEmptyListMakesAnEmptyChapter() {
        JsonObject chapter = ItemChapter.chapter(List.of());
        assertTrue(chapter.getAsJsonObject("nodes").entrySet().isEmpty());
        assertEquals("Auto-generated chapter [0 items]", chapter.get("title").getAsString());
    }
}
