package com.kuronami.questtree.progress;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kuronami.questtree.data.ProgressModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskScreenRulesTest {

    private static ProgressModel.Node node(String tasks) {
        JsonObject bundle = new JsonObject();
        JsonObject chapters = new JsonObject();
        JsonObject chapter = new JsonObject();
        chapter.addProperty("filename", "c");
        JsonObject nodes = new JsonObject();
        JsonObject n = new JsonObject();
        n.add("tasks", JsonParser.parseString(tasks));
        nodes.add("q", n);
        chapter.add("nodes", nodes);
        chapters.add("test:c", chapter);
        bundle.add("chapters", chapters);
        return ProgressModel.of(bundle).nodes.get("test:c/q");
    }

    @Test
    void onlyResourceTasksHaveAScreen() {
        assertTrue(ScreenRules.screenCapable(JsonParser.parseString("{\"type\":\"item\"}").getAsJsonObject()));
        assertTrue(ScreenRules.screenCapable(JsonParser.parseString("{\"type\":\"fluid\"}").getAsJsonObject()));
        assertTrue(ScreenRules.screenCapable(JsonParser.parseString("{\"type\":\"energy\"}").getAsJsonObject()));
        assertFalse(ScreenRules.screenCapable(JsonParser.parseString("{\"type\":\"xp\"}").getAsJsonObject()));
        assertFalse(ScreenRules.screenCapable(new JsonObject()));
    }

    @Test
    void screenOnlyIsFlaggedByFtbQuestsKey() {
        assertTrue(ScreenRules.screenOnly(JsonParser.parseString("{\"task_screen_only\":true}").getAsJsonObject()));
        assertFalse(ScreenRules.screenOnly(JsonParser.parseString("{\"task_screen_only\":false}").getAsJsonObject()));
        assertFalse(ScreenRules.screenOnly(new JsonObject()));
        assertFalse(ScreenRules.screenOnly(null));
    }

    @Test
    void screenTakesItemsOnlyWhenTheyAreConsumedOrScreenOnly() {
        ProgressModel.Node consumed = node("[{\"type\":\"item\",\"item\":\"minecraft:stone\",\"count\":2,\"consume\":true}]");
        ProgressModel.Node free = node("[{\"type\":\"item\",\"item\":\"minecraft:stone\",\"count\":2}]");
        ProgressModel.Node screenOnly = node(
                "[{\"type\":\"item\",\"item\":\"minecraft:stone\",\"count\":2,\"task_screen_only\":true}]");
        assertTrue(ScreenRules.takesItem(consumed, consumed.tasks.get(0)));
        assertFalse(ScreenRules.takesItem(free, free.tasks.get(0)));
        assertTrue(ScreenRules.takesItem(screenOnly, screenOnly.tasks.get(0)));
    }

    @Test
    void fluidSpecAcceptsIdForm() {
        JsonObject task = JsonParser.parseString("{\"type\":\"fluid\",\"fluid\":{\"id\":\"minecraft:water\"}}")
                .getAsJsonObject();
        assertTrue(ScreenRules.acceptsEnergy(JsonParser.parseString("{\"type\":\"energy\"}").getAsJsonObject()));
        assertFalse(ScreenRules.acceptsEnergy(task));
        assertTrue(ScreenRules.fluidSpec(task).equals("minecraft:water"));
    }
}
