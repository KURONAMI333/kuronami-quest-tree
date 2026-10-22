package com.kuronami.questtree.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The restrictions file the editor writes (2026-10-22, G6). It replaces the pack's rule array whole, and its revision
 * is one number for the file; the pack's array is untouched while no overlay file exists.
 */
class RestrictionsOverlayTest {

    @TempDir
    Path root;

    @Test
    void noFileLeavesThePackArrayInTheBundle() {
        JsonObject bundle = new JsonObject();
        JsonArray packRules = new JsonArray();
        packRules.add(JsonParser.parseString("{\"id\":\"pack\"}"));
        bundle.add("restrictions", packRules);

        ProgressionOverlay.Overlay overlay = ProgressionOverlay.read(root);
        assertNull(overlay.restrictions);
        overlay.applyRestrictionsTo(bundle);

        assertEquals(packRules, bundle.getAsJsonArray("restrictions"));
        assertFalse(bundle.has("restrictions_revision"));
    }

    @Test
    void theFileReplacesTheArrayAndCarriesItsRevision() {
        JsonObject file = new JsonObject();
        file.addProperty("revision", 4);
        JsonArray rules = new JsonArray();
        rules.add(JsonParser.parseString("{\"id\":\"edited\",\"items\":[\"minecraft:iron_sword\"],"
                + "\"conditions\":[{\"type\":\"stage\",\"stage\":\"edited\"}]}"));
        file.add("rules", rules);
        write(file);

        ProgressionOverlay.Overlay overlay = ProgressionOverlay.read(root);
        assertTrue(overlay.problems.isEmpty());
        assertEquals(4, overlay.restrictions.revision);

        JsonObject bundle = new JsonObject();
        JsonArray packRules = new JsonArray();
        packRules.add(JsonParser.parseString("{\"id\":\"pack\"}"));
        bundle.add("restrictions", packRules);
        overlay.applyRestrictionsTo(bundle);

        assertEquals(1, bundle.getAsJsonArray("restrictions").size());
        assertEquals("edited", bundle.getAsJsonArray("restrictions").get(0).getAsJsonObject()
                .get("id").getAsString());
        assertEquals(4, bundle.get("restrictions_revision").getAsLong());
    }

    @Test
    void aWrittenFileReadsBackWithTheNextRevision() throws Exception {
        JsonArray rules = new JsonArray();
        rules.add(JsonParser.parseString("{\"id\":\"rule\",\"items\":[\"minecraft:stone\"],"
                + "\"conditions\":[{\"type\":\"stage\",\"stage\":\"x\"}]}"));

        ProgressionOverlay.writeRestrictions(root, 7, rules);

        ProgressionOverlay.Overlay overlay = ProgressionOverlay.read(root);
        assertTrue(overlay.problems.isEmpty());
        assertEquals(7, overlay.restrictions.revision);
        assertEquals(1, overlay.restrictions.rules.size());
    }

    @Test
    void aFileWithoutARulesArrayIsAnErrorAndThePackStays() {
        JsonObject file = new JsonObject();
        file.addProperty("revision", 2);
        file.add("rules", new JsonObject());
        write(file);

        ProgressionOverlay.Overlay overlay = ProgressionOverlay.read(root);
        assertNull(overlay.restrictions);
        assertFalse(overlay.problems.isEmpty());
    }

    private void write(JsonObject file) {
        try {
            Files.createDirectories(root);
            Files.writeString(root.resolve("restrictions.json"), file.toString(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
