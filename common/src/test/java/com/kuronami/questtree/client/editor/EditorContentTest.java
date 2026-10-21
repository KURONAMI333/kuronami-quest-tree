package com.kuronami.questtree.client.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The content schema and its JSON patch (2026-10-21, phase 3). */
class EditorContentTest {

    @Test
    void everyTaskFieldRoundTripsAndKeepsUnknownFields() {
        for (String type : ContentSchema.TASK_TYPES) {
            JsonObject original = JsonParser.parseString("""
                    {"type":"%s","unknown":{"kept":7},"x":1}
                    """.formatted(type)).getAsJsonObject();
            JsonObject edited = original.deepCopy();
            List<FieldSpec> fields = ContentSchema.taskFields(type);
            for (FieldSpec field : fields) {
                writeSample(edited, field);
            }
            JsonObject patch = EditorDoc.patch(original, edited);
            JsonObject merged = original.deepCopy();
            EditorDoc.mergePatch(merged, patch);
            assertEquals(type, EditorDoc.text(merged, "type"), type);
            assertEquals(7, merged.getAsJsonObject("unknown").get("kept").getAsInt(), type);
            for (FieldSpec field : fields) {
                assertEquals(EditorDoc.show(edited, field), EditorDoc.show(merged, field),
                        type + " / " + field.path());
            }
        }
    }

    @Test
    void everyRewardFieldRoundTripsAndKeepsUnknownFields() {
        for (String type : ContentSchema.REWARD_TYPES) {
            JsonObject original = new JsonObject();
            ContentSchema.setRewardType(original, type);
            original.add("unknown", JsonParser.parseString("{\"kept\":7}"));
            JsonObject edited = original.deepCopy();
            List<FieldSpec> fields = ContentSchema.rewardFields(type);
            for (FieldSpec field : fields) {
                writeSample(edited, field);
            }
            JsonObject patch = EditorDoc.patch(original, edited);
            JsonObject merged = original.deepCopy();
            EditorDoc.mergePatch(merged, patch);
            assertEquals(type, ContentSchema.rewardType(merged), type);
            assertEquals(7, merged.getAsJsonObject("unknown").get("kept").getAsInt(), type);
            for (FieldSpec field : fields) {
                assertEquals(EditorDoc.show(edited, field), EditorDoc.show(merged, field),
                        type + " / " + field.path());
            }
        }
    }

    @Test
    void choosingChoiceWritesATableAndLeavingRemovesItsMode() {
        JsonObject reward = new JsonObject();
        ContentSchema.setRewardType(reward, "choice");
        assertEquals("table", reward.get("type").getAsString());
        assertEquals("choice", reward.get("mode").getAsString());
        assertEquals("choice", ContentSchema.rewardType(reward));
        ContentSchema.setRewardType(reward, "xp");
        assertEquals("xp", ContentSchema.rewardType(reward));
        assertFalse(reward.has("mode"));
    }

    @Test
    void aNonFiniteNumberIsNotWritten() {
        JsonObject o = new JsonObject();
        o.addProperty("x", 1.0);
        for (String text : java.util.List.of("Infinity", "-Infinity", "NaN", "1e999")) {
            EditorDoc.setNumber(o, "x", text, false);
            assertEquals(1.0, o.get("x").getAsDouble(), text);
        }
        EditorDoc.setNumber(o, "x", "2.5", false);
        assertEquals(2.5, o.get("x").getAsDouble());
    }

    @Test
    void patchKeepsMembersTheFormNeverTouched() {
        JsonObject original = JsonParser.parseString("""
                {"id":"q","display":{"icon":"minecraft:book","title":"Old"},"x":1,"tasks":[]}
                """).getAsJsonObject();
        JsonObject edited = original.deepCopy();
        EditorDoc.setString(edited, "display.title", "New");
        JsonObject patch = EditorDoc.patch(original, edited);
        assertEquals("New", patch.getAsJsonObject("display").get("title").getAsString());
        assertFalse(patch.getAsJsonObject("display").has("icon"));
        JsonObject merged = original.deepCopy();
        EditorDoc.mergePatch(merged, patch);
        assertEquals("minecraft:book", merged.getAsJsonObject("display").get("icon").getAsString());
        assertEquals(1, merged.get("x").getAsInt());
    }

    @Test
    void aBlankFieldRemovesItsMember() {
        JsonObject node = JsonParser.parseString("{\"title\":\"Old\",\"shape\":\"circle\"}").getAsJsonObject();
        EditorDoc.setString(node, "title", "  ");
        assertFalse(node.has("title"));
        assertEquals("circle", EditorDoc.text(node, "shape"));
    }

    @Test
    void settingAnItemKeepsItsOtherMembersAndDropsATag() {
        JsonObject reward = JsonParser.parseString("""
                {"item":{"tag":"minecraft:logs","count":4,"components":{"a":1}}}
                """).getAsJsonObject();
        EditorDoc.setItem(reward, "item", "minecraft:oak_log");
        JsonObject item = reward.getAsJsonObject("item");
        assertEquals("minecraft:oak_log", item.get("id").getAsString());
        assertEquals(4, item.get("count").getAsInt());
        assertTrue(item.has("components"));
        assertFalse(item.has("tag"));
    }

    @Test
    void arrayPathsReadAndWriteByIndex() {
        JsonObject task = new JsonObject();
        EditorDoc.setNumber(task, "position[1]", "64", false);
        assertEquals(64.0, task.getAsJsonArray("position").get(1).getAsDouble());
        EditorDoc.setNumber(task, "position[0]", "-3", false);
        assertEquals(-3.0, EditorDoc.decimal(task, "position[0]"));
        assertEquals(64.0, EditorDoc.decimal(task, "position[1]"));
    }

    @Test
    void clearingAnArrayElementKeepsItsLengthUntilEverySlotIsZero() {
        JsonObject task = JsonParser.parseString("{\"position\":[1,2,3]}").getAsJsonObject();

        EditorDoc.setNumber(task, "position[1]", " ", true);
        JsonArray position = task.getAsJsonArray("position");
        assertEquals(3, position.size());
        assertEquals(1, position.get(0).getAsInt());
        assertEquals(0, position.get(1).getAsInt());
        assertEquals(3, position.get(2).getAsInt());

        EditorDoc.setNumber(task, "position[0]", "", true);
        assertEquals(3, task.getAsJsonArray("position").size());
        EditorDoc.setNumber(task, "position[2]", "", true);
        assertFalse(task.has("position"));
    }

    @Test
    void aFlagMemberBecomesAnObjectWhenAChildIsWrittenAndStaysOn() {
        JsonObject node = new JsonObject();
        EditorDoc.setFlag(node, "repeat", true);
        assertTrue(EditorDoc.flag(node, "repeat"));
        node.addProperty("repeat", true);
        EditorDoc.setNumber(node, "repeat.cooldown", "30", true);
        assertTrue(node.get("repeat").isJsonObject());
        assertTrue(EditorDoc.flag(node, "repeat"));
        assertEquals(30, node.getAsJsonObject("repeat").get("cooldown").getAsInt());
        EditorDoc.setFlag(node, "repeat", false);
        assertFalse(node.has("repeat"));
    }

    @Test
    void anItemWrittenAsABareIdKeepsItWhenACountIsAdded() {
        JsonObject reward = JsonParser.parseString("{\"type\":\"item\",\"item\":\"minecraft:apple\"}").getAsJsonObject();
        EditorDoc.setNumber(reward, "item.count", "3", true);
        assertEquals("minecraft:apple", EditorDoc.text(reward, "item"));
        assertEquals(3, EditorDoc.integer(reward, "item.count", 0));
    }

    @Test
    void changingATypeDropsOnlyTheFieldsOfTheOldOne() {
        JsonObject task = JsonParser.parseString("""
                {"type":"item","item":{"id":"minecraft:stone"},"count":4,"optional":true,"unknown":1}
                """).getAsJsonObject();
        ContentSchema.retypeTask(task, "kill");
        assertEquals("kill", EditorDoc.text(task, "type"));
        assertFalse(task.has("item"));
        assertTrue(task.has("count"), "count belongs to kill too");
        assertTrue(task.get("optional").getAsBoolean());
        assertEquals(1, task.get("unknown").getAsInt());

        JsonObject reward = ContentSchema.newReward("item");
        EditorDoc.setItem(reward, "item", "minecraft:apple");
        ContentSchema.retypeReward(reward, "choice");
        assertEquals("choice", ContentSchema.rewardType(reward));
        assertFalse(reward.has("item"));
        ContentSchema.retypeReward(reward, "xp");
        assertFalse(reward.has("mode"));
    }

    @Test
    void listOperationsAddRemoveAndReorder() {
        JsonObject node = new JsonObject();
        JsonArray tasks = EditorDoc.array(node, "tasks", true);
        tasks.add(ContentSchema.newTask("kill"));
        tasks.add(ContentSchema.newTask("item"));
        tasks.add(ContentSchema.newTask("stage"));
        assertTrue(EditorDoc.move(tasks, 2, -1));
        assertEquals("stage", EditorDoc.text(node, "tasks[1].type"));
        assertEquals("item", EditorDoc.text(node, "tasks[2].type"));
        assertFalse(EditorDoc.move(tasks, 0, -1));
        assertFalse(EditorDoc.move(tasks, 2, 1));
        EditorDoc.remove(node, "tasks[0]");
        assertEquals(2, tasks.size());
        assertEquals("stage", EditorDoc.text(node, "tasks[0].type"));
    }

    @Test
    void reorderingSendsTheWholeArrayAndKeepsEveryOtherMember() {
        JsonObject original = JsonParser.parseString("""
                {"x":1,"tasks":[{"type":"kill","u":1},{"type":"item"}],"rewards":[{"type":"xp","amount":5}]}
                """).getAsJsonObject();
        JsonObject edited = original.deepCopy();
        EditorDoc.move(edited.getAsJsonArray("tasks"), 0, 1);
        JsonObject patch = EditorDoc.patch(original, edited);
        assertEquals(List.of("tasks"), List.copyOf(patch.keySet()));
        JsonObject merged = original.deepCopy();
        EditorDoc.mergePatch(merged, patch);
        assertEquals(edited, merged);
    }

    @Test
    void anEmptyEditBuildsNoPatch() {
        JsonObject original = JsonParser.parseString("{\"title\":\"A\",\"tasks\":[]}").getAsJsonObject();
        assertTrue(EditorDoc.patch(original, original.deepCopy()).isEmpty());
    }

    @Test
    void showAndWriteAgreeForEveryKind() {
        JsonObject o = new JsonObject();
        for (FieldSpec f : ContentSchema.questFields()) {
            writeSample(o, f);
            assertFalse(EditorDoc.show(o, f).isEmpty() && f.kind() != FieldSpec.Kind.ENUM, f.path());
        }
        assertEquals("true", EditorDoc.show(o, ContentSchema.field("repeat", ContentSchema.questFields())));
        assertEquals("3", EditorDoc.show(o, ContentSchema.field("repeat.cooldown", ContentSchema.questFields())));
    }

    @Test
    void booleanRowsShowTheRuntimeDefaultWhileAbsent() {
        FieldSpec silent = ContentSchema.field("silent", ContentSchema.rewardFields("command"));
        assertEquals("true", EditorDoc.show(new JsonObject(), silent));
        JsonObject reward = new JsonObject();
        EditorDoc.write(reward, silent, "false");
        assertEquals("false", EditorDoc.show(reward, silent));
    }

    @Test
    void validatorLinesFindTheirRow() {
        List<String> fields = List.of("title", "tasks[1].item", "tasks[1].count", "visibility.hidden_until");
        String node = "data/t/progression/chapters/basics.json:12 chapters.basics.nodes.pick.tasks[1].item - no item";
        assertEquals("tasks[1].item", ValidationPaths.fieldFor(node, "t:basics", "pick", fields));
        assertEquals("tasks[1].count",
                ValidationPaths.fieldFor("chapters.basics.nodes.pick.tasks[1].count - not a number", "t:basics", "pick",
                        fields));
        assertEquals("tasks[1].item",
                ValidationPaths.fieldFor("f.json chapters.basics.nodes.pick.tasks[1] - task is wrong", "t:basics",
                        "pick", List.of("tasks[1].item")));
        assertEquals("decorations[2].x", ValidationPaths.fieldFor("chapters.basics.decorations[2].x - bad", "t:basics",
                null, List.of("decorations[2].x", "decorations[2].y")));
        String table = "reward_tables.loot.entries[0].reward.item - missing item";
        assertEquals("entries[0].reward.item", ValidationPaths.fieldFor(table, "t:loot", null,
                List.of("entries[0].reward.item", "entries[0].weight")));
        assertNull(ValidationPaths.fieldFor("chapters.basics.nodes.other.title - nope", "t:basics", "pick", fields));
        assertNull(ValidationPaths.fieldFor("no path here", "t:basics", "pick", fields));
        assertNull(ValidationPaths.fieldFor("progression - something general", "t:basics", "pick", fields));
        assertEquals("not a number", ValidationPaths.sentenceOf("a.json:1 chapters.x - not a number"));
    }

    @Test
    void tableEntriesAreARewardUnderReward() {
        List<String> paths = ContentSchema.entryFields("item").stream().map(FieldSpec::path).toList();
        assertTrue(paths.contains("weight"));
        assertTrue(paths.contains("reward.item"));
        assertTrue(paths.contains("reward.item.count"));
        JsonObject entry = ContentSchema.newEntry();
        EditorDoc.setItem(entry, "reward.item", "minecraft:diamond");
        EditorDoc.setNumber(entry, "reward.item.count", "2", true);
        assertEquals("minecraft:diamond", EditorDoc.text(entry, "reward.item"));
        assertEquals(2, EditorDoc.integer(entry, "reward.item.count", 0));
        JsonObject tables = JsonParser.parseString("{\"t:loot\":{\"rolls\":1},\"t:empty\":{},\"t:bad\":3}").getAsJsonObject();
        assertEquals(List.of("t:empty", "t:loot"), ContentSchema.tableIds(tables));
    }

    @Test
    void chapterAndDecorationFieldsRoundTrip() {
        JsonObject chapter = JsonParser.parseString("{\"icon\":{\"id\":\"minecraft:book\"},\"keep\":[1,2]}").getAsJsonObject();
        JsonObject edited = chapter.deepCopy();
        List<FieldSpec> all = new java.util.ArrayList<>(ContentSchema.chapterFields());
        all.addAll(ContentSchema.pointsFields());
        for (FieldSpec f : all) {
            writeSample(edited, f);
        }
        JsonObject merged = chapter.deepCopy();
        EditorDoc.mergePatch(merged, EditorDoc.patch(chapter, edited));
        assertEquals(2, merged.getAsJsonArray("keep").size());
        for (FieldSpec f : all) {
            assertEquals(EditorDoc.show(edited, f), EditorDoc.show(merged, f), f.path());
        }
        JsonObject deco = ContentSchema.newDecoration();
        for (FieldSpec f : ContentSchema.decorationFields()) {
            writeSample(deco, f);
            assertFalse(EditorDoc.show(deco, f).isEmpty(), f.path());
        }
    }

    @Test
    void everyLabelHasAnEnglishAndAJapaneseLine() throws Exception {
        List<FieldSpec> all = new java.util.ArrayList<>(ContentSchema.questFields());
        all.addAll(ContentSchema.chapterFields());
        all.addAll(ContentSchema.pointsFields());
        all.addAll(ContentSchema.decorationFields());
        all.addAll(ContentSchema.tableFields());
        all.addAll(ContentSchema.taskCommonFields());
        all.addAll(ContentSchema.rewardCommonFields());
        for (String t : ContentSchema.TASK_TYPES) {
            all.addAll(ContentSchema.taskOwnFields(t));
        }
        for (String t : ContentSchema.REWARD_TYPES) {
            all.addAll(ContentSchema.rewardOwnFields(t));
        }
        all.addAll(ContentSchema.entryFields("item"));
        for (String locale : List.of("en_us", "ja_jp")) {
            JsonObject lang;
            try (var in = getClass().getResourceAsStream("/assets/kuronami_quest_tree/lang/" + locale + ".json")) {
                lang = JsonParser.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))
                        .getAsJsonObject();
            }
            for (FieldSpec f : all) {
                assertTrue(lang.has(f.label()) || f.path().startsWith("reward."), locale + " lacks " + f.label());
            }
            for (String t : ContentSchema.TASK_TYPES) {
                assertTrue(lang.has("screen.kuronami_quest_tree.editor.task." + t), locale + " task " + t);
            }
            for (String t : ContentSchema.REWARD_TYPES) {
                assertTrue(lang.has("screen.kuronami_quest_tree.editor.reward." + t), locale + " reward " + t);
            }
        }
    }

    @Test
    void aRefusalSnapshotDiffersOnceTheDraftChanges() {
        JsonObject draft = JsonParser.parseString("""
                {"title":"Start","tasks":[{"type":"checkmark"}],"position":[1,2]}
                """).getAsJsonObject();
        JsonObject refused = draft.deepCopy();
        assertTrue(EditorDoc.sameContent(refused, draft));

        EditorDoc.setString(draft, "title", "Changed");
        assertFalse(EditorDoc.sameContent(refused, draft));

        draft.remove("title");
        EditorDoc.setString(draft, "title", "Start");
        assertTrue(EditorDoc.sameContent(refused, draft));

        draft.getAsJsonArray("tasks").add(JsonParser.parseString("{\"type\":\"item\"}"));
        assertFalse(EditorDoc.sameContent(refused, draft));

        draft.getAsJsonArray("tasks").remove(1);
        draft.getAsJsonArray("position").set(1, new com.google.gson.JsonPrimitive(3));
        assertFalse(EditorDoc.sameContent(refused, draft));
    }

    private static void writeSample(JsonObject object, FieldSpec field) {
        switch (field.kind()) {
            case TEXT, MULTILINE -> EditorDoc.setString(object, field.path(), "sample");
            case INT -> EditorDoc.setNumber(object, field.path(), "3", true);
            case DOUBLE -> EditorDoc.setNumber(object, field.path(), "2.5", false);
            case BOOL -> EditorDoc.setBool(object, field.path(), !field.defaultOn());
            case ENUM -> EditorDoc.setString(object, field.path(), field.options().get(field.options().size() - 1));
            case FLAG_OBJECT -> EditorDoc.setFlag(object, field.path(), true);
            case ITEM, ENTITY, DIMENSION, BIOME, STRUCTURE, ADVANCEMENT ->
                    EditorDoc.setItem(object, field.path(), "minecraft:stone");
        }
    }
}
