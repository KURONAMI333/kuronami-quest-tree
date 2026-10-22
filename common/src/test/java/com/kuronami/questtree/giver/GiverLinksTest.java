package com.kuronami.questtree.giver;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Giver links are keyed by entity UUID, survive their saved JSON form, and resolve a quest or chapter exactly. */
class GiverLinksTest {

    private static final UUID VILLAGER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID OTHER = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");

    @Test
    void linksSurviveTheSavedForm() {
        GiverLinks links = new GiverLinks();
        links.set(VILLAGER, GiverTarget.node("pack:basics/start"));
        links.set(OTHER, GiverTarget.chapter("pack:basics"));

        JsonObject saved = links.toJson();
        GiverLinks loaded = GiverLinks.fromJson(saved);

        assertEquals(GiverTarget.node("pack:basics/start"), loaded.get(VILLAGER));
        assertEquals(GiverTarget.chapter("pack:basics"), loaded.get(OTHER));
        assertEquals(2, loaded.size());
    }

    @Test
    void clearingOneEntityLeavesTheRest() {
        GiverLinks links = new GiverLinks();
        links.set(VILLAGER, GiverTarget.node("pack:basics/start"));
        links.set(OTHER, GiverTarget.node("pack:basics/next"));

        assertTrue(links.clear(VILLAGER));
        assertNull(links.get(VILLAGER));
        assertEquals(GiverTarget.node("pack:basics/next"), links.get(OTHER));
        assertFalse(links.clear(VILLAGER));
    }

    @Test
    void aBrokenEntryIsDroppedOnRead() {
        JsonObject saved = JsonParser.parseString("""
                {"not-a-uuid": "node|pack:basics/start",
                 "11111111-2222-3333-4444-555555555555": "bad form",
                 "66666666-7777-8888-9999-aaaaaaaaaaaa": "chapter|pack:basics"}
                """).getAsJsonObject();

        GiverLinks loaded = GiverLinks.fromJson(saved);

        assertEquals(1, loaded.size());
        assertEquals(GiverTarget.chapter("pack:basics"), loaded.get(OTHER));
    }

    @Test
    void aChapterAndAQuestAreBothFoundById() {
        Set<String> chapters = Set.of("pack:basics");
        Set<String> nodes = Set.of("pack:basics/start");
        assertEquals(GiverTarget.chapter("pack:basics"), GiverTarget.resolve("pack:basics", chapters, nodes));
        assertEquals(GiverTarget.node("pack:basics/start"), GiverTarget.resolve("pack:basics/start", chapters, nodes));
        assertNull(GiverTarget.resolve("pack:basics/missing", chapters, nodes));
    }

    @Test
    void theEncodedFormKeepsTheKind() {
        assertEquals("node|pack:basics/start", GiverTarget.node("pack:basics/start").encode());
        assertEquals("chapter|pack:basics", GiverTarget.chapter("pack:basics").encode());
        assertEquals(GiverTarget.node("pack:basics/start"), GiverTarget.decode("node|pack:basics/start"));
        assertEquals(GiverTarget.chapter("pack:basics"), GiverTarget.decode("chapter|pack:basics"));
        assertNull(GiverTarget.decode("pack:basics"));
        assertNull(GiverTarget.decode("chapter|"));
    }

    @Test
    void everySavedLinkKeepsItsOwnTarget() {
        GiverLinks links = new GiverLinks();
        links.set(VILLAGER, GiverTarget.node("pack:basics/start"));
        links.set(OTHER, GiverTarget.node("pack:basics/start"));

        Map<UUID, GiverTarget> all = links.all();
        assertEquals(2, all.size());
        all.forEach((id, target) -> assertEquals(GiverTarget.node("pack:basics/start"), target));
    }
}
