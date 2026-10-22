package com.kuronami.questtree.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A reward claim is stored by index ({@code <node>#<index>}), so when a reward moves the saved claims must move with
 * it. Only an editor publish is such a move: a data pack reload, starting a world, and opening another world all
 * rebuild the bundle, and charging their difference to the claims would shift them onto a different reward (a reward
 * the overlay deleted would become claimable again) — 2026-10-22 release review.
 */
class ProgressDataRemapTest {

    private static final String CHAPTER = "t:basics";

    @TempDir
    Path temp;

    @BeforeEach
    void useTheTestRegistry() {
        ProgressData.registry = ProgressValidator.Registry.NONE;
    }

    @AfterEach
    void reset() {
        ProgressData.clearOverlayRoot();
        ProgressData.registry = GameRegistry.INSTANCE;
    }

    private static JsonObject node(String... items) {
        JsonObject n = new JsonObject();
        n.addProperty("x", 0);
        n.addProperty("y", 0);
        JsonArray tasks = new JsonArray();
        tasks.add(JsonParser.parseString("{\"type\":\"checkmark\"}"));
        n.add("tasks", tasks);
        JsonArray rewards = new JsonArray();
        for (String item : items) {
            rewards.add(JsonParser.parseString("{\"type\":\"item\",\"item\":\"" + item + "\"}"));
        }
        n.add("rewards", rewards);
        return n;
    }

    /** A pack whose one chapter has one node with the given rewards, in that order. */
    private static JsonObject pack(String... items) {
        JsonObject nodes = new JsonObject();
        nodes.add("a", node(items));
        JsonObject chapter = new JsonObject();
        chapter.addProperty("title", "Basics");
        chapter.add("nodes", nodes);
        JsonObject chapters = new JsonObject();
        chapters.add(CHAPTER, chapter);
        return ProgressData.packOf(chapters, new JsonObject(), new JsonArray(), new JsonObject(), new JsonArray());
    }

    private void installPack(String... items) {
        ProgressData.installPack(pack(items), Map.of(), Set.of(), List.of(), Map.of());
    }

    /** The editor's own write: replace the node with one holding these rewards. */
    private void publish(String... items) throws Exception {
        Path file = temp.resolve(ProgressionOverlay.DIR).resolve("chapters").resolve("t/basics.json");
        Files.createDirectories(file.getParent());
        JsonObject entry = new JsonObject();
        entry.addProperty("action", "replace");
        entry.add("node", node(items));
        JsonObject nodes = new JsonObject();
        nodes.add("a", entry);
        JsonObject root = new JsonObject();
        root.addProperty("revision", 1);
        root.addProperty("base", true);
        root.add("nodes", nodes);
        Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
    }

    @Test
    void aWorldStartDoesNotMoveASavedReward() throws Exception {
        installPack("minecraft:apple", "minecraft:stick");
        publish("minecraft:apple");   // the overlay deletes the second reward

        ProgressData.setOverlayRoot(temp);

        assertTrue(ProgressData.takeRewardRemap().isEmpty(),
                "starting a world reads the overlay for the first time; that is not an edit and must not move claims");
    }

    @Test
    void aDataPackReloadDoesNotMoveASavedReward() throws Exception {
        installPack("minecraft:apple", "minecraft:stick");
        ProgressData.setOverlayRoot(temp);
        publish("minecraft:apple");
        ProgressData.takeRewardRemap();

        installPack("minecraft:apple", "minecraft:stick");

        assertTrue(ProgressData.takeRewardRemap().isEmpty(), "a data pack reload is not an edit");
    }

    @Test
    void anEditorPublishMovesTheSavedReward() throws Exception {
        installPack("minecraft:apple", "minecraft:stick");
        ProgressData.setOverlayRoot(temp);

        publish("minecraft:stick");   // the editor deletes the first reward
        ProgressData.rebuildOverlay();

        Map<String, Map<Integer, Integer>> moved = ProgressData.takeRewardRemap();
        assertEquals(Map.of(1, 0), moved.get(CHAPTER + "/a"),
                "a claim on the second reward moves to the first");
    }

    @Test
    void anotherWorldDoesNotCompareWithTheLastOne() throws Exception {
        installPack("minecraft:apple", "minecraft:stick");
        ProgressData.setOverlayRoot(temp);
        ProgressData.clearOverlayRoot();

        publish("minecraft:stick");
        ProgressData.setOverlayRoot(temp);

        assertTrue(ProgressData.takeRewardRemap().isEmpty(),
                "the previous world's chapters must not be the baseline for this one");
    }
}
