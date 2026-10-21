package com.kuronami.questtree.progress;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The previous build converted saves with {@code JsonOps.convertTo(NbtOps.INSTANCE, json)}, which has no boolean:
 * it wrote {@code true} as the byte 1 and read every byte back as a number, so a boolean flipped on the next load
 * (a book given on every login, a blocked reward unblocking itself, a closed chapter opening). These checks hold the
 * new conversion to the JSON type and hold it open to the shapes the old build wrote (2026-10-22 release review).
 */
class JsonTagsTest {

    @Test
    void booleansComeBackAsBooleans() {
        JsonObject json = JsonParser.parseString("{\"book_given\":true,\"rewards_blocked\":false}").getAsJsonObject();

        CompoundTag tag = (CompoundTag) JsonTags.toTag(json);

        assertInstanceOf(ByteTag.class, tag.get("book_given"), "a boolean is the only thing written as a byte");
        assertEquals(1, ((ByteTag) tag.get("book_given")).getAsByte());
        assertEquals(0, ((ByteTag) tag.get("rewards_blocked")).getAsByte());

        JsonObject back = (JsonObject) JsonTags.fromTag(tag);
        assertTrue(back.get("book_given").getAsJsonPrimitive().isBoolean());
        assertTrue(back.get("book_given").getAsBoolean());
        assertFalse(back.get("rewards_blocked").getAsBoolean());
        assertEquals(tag, JsonTags.toTag(back), "saving the loaded value again writes the same tag");
    }

    @Test
    void anIntegerIsNeverWrittenAsAByte() {
        for (String number : new String[] {"0", "1", "-1", "127", "300", "70000", "12345678901"}) {
            JsonElement json = JsonParser.parseString(number);
            Tag tag = JsonTags.toTag(json);

            assertFalse(tag.getId() == 1, number + " must not take the boolean form");
            JsonElement back = JsonTags.fromTag(tag);
            assertFalse(back.getAsJsonPrimitive().isBoolean(), number + " must not read back as a boolean");
            assertEquals(json.getAsLong(), back.getAsLong());
        }
    }

    @Test
    void aDoubleKeepsItsPrecisionAndALongItsWidth() {
        JsonObject json = new JsonObject();
        json.addProperty("int", 2);
        json.addProperty("long", 5_000_000_000L);
        json.addProperty("double", 0.1);

        CompoundTag tag = (CompoundTag) JsonTags.toTag(json);

        assertInstanceOf(IntTag.class, tag.get("int"));
        assertInstanceOf(LongTag.class, tag.get("long"));
        assertEquals(0.1, tag.getDouble("double"), 0.0, "a double is not narrowed to a float");

        JsonObject back = (JsonObject) JsonTags.fromTag(tag);
        assertInstanceOf(Integer.class, back.get("int").getAsNumber());
        assertInstanceOf(Long.class, back.get("long").getAsNumber());
        assertInstanceOf(Double.class, back.get("double").getAsNumber());
    }

    @Test
    void anEmptyArrayAndAnEmptyObjectSurvive() {
        JsonObject json = JsonParser.parseString("{\"nodes\":[],\"values\":{}}").getAsJsonObject();

        CompoundTag tag = (CompoundTag) JsonTags.toTag(json);
        JsonObject back = (JsonObject) JsonTags.fromTag(tag);

        assertEquals(0, back.getAsJsonArray("nodes").size());
        assertEquals(0, back.getAsJsonObject("values").size());
    }

    @Test
    void aMixedArraySurvivesAndIsReadBackUnwrapped() {
        JsonArray json = new JsonArray();
        json.add("test:book/node_0");
        json.add(1);
        json.add(true);
        json.add(JsonNull.INSTANCE);

        ListTag tag = (ListTag) JsonTags.toTag(json);

        assertEquals(4, tag.size(), "an NBT list holds one type, so each element carries its own");
        JsonArray back = (JsonArray) JsonTags.fromTag(tag);
        assertEquals("test:book/node_0", back.get(0).getAsString());
        assertEquals(1, back.get(1).getAsInt());
        assertTrue(back.get(2).getAsJsonPrimitive().isBoolean());
        assertTrue(back.get(2).getAsBoolean());
        assertTrue(back.get(3).isJsonNull());
    }

    @Test
    void aLongStringHasNoStringCap() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 40_000; i++) {
            text.append("test:book/node_").append(i).append(';');
        }
        JsonObject json = new JsonObject();
        json.addProperty("recent", text.toString());

        JsonObject back = (JsonObject) JsonTags.fromTag(JsonTags.toTag(json));

        assertEquals(text.toString(), back.get("recent").getAsString(),
                "a compound tag has no 65,535-byte string cap");
    }

    /** The previous build wrote booleans as bytes; the same bytes come back as booleans now. */
    @Test
    void aBooleanFromThePreviousBuildIsReadBackAsABoolean() {
        JsonObject json = JsonParser.parseString(
                "{\"book_given\":true,\"rewards_blocked\":false,\"chapter_open\":{\"test:chapter\":true}}")
                .getAsJsonObject();
        Tag legacy = JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE, json);

        JsonObject back = (JsonObject) JsonTags.fromTag(legacy);

        assertTrue(back.get("book_given").getAsJsonPrimitive().isBoolean());
        assertTrue(back.get("book_given").getAsBoolean());
        assertFalse(back.get("rewards_blocked").getAsBoolean());
        assertTrue(back.getAsJsonObject("chapter_open").get("test:chapter").getAsJsonPrimitive().isBoolean());
        assertTrue(back.getAsJsonObject("chapter_open").get("test:chapter").getAsBoolean());
    }

    /** The previous build narrowed numbers to bytes/shorts and wrote small-number arrays as byte arrays. */
    @Test
    void numbersFromThePreviousBuildAreReadBackAsNumbers() {
        JsonObject json = JsonParser.parseString(
                "{\"tasks\":{\"a\":1,\"b\":300,\"c\":70000},\"taken\":[1,2],\"xp\":{\"d\":1.5}}")
                .getAsJsonObject();
        Tag legacy = JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE, json);

        JsonObject back = (JsonObject) JsonTags.fromTag(legacy);

        assertEquals(1, PackNumbers.savedInt(back.getAsJsonObject("tasks").get("a"), -1),
                "a narrowed byte 1 reads as a boolean, which a saved counter still counts as 1");
        assertEquals(300, back.getAsJsonObject("tasks").get("b").getAsInt());
        assertEquals(70000, back.getAsJsonObject("tasks").get("c").getAsInt());
        assertEquals(2, back.getAsJsonArray("taken").size());
        assertEquals(2, back.getAsJsonArray("taken").get(1).getAsInt());
        assertEquals(1.5, back.getAsJsonObject("xp").get("d").getAsDouble(), 0.0001);
    }

    /** A malformed value is still a value: the conversion never throws on data it wrote. */
    @Test
    void aStringAndAnArrayOfStringsRoundTrip() {
        JsonObject json = JsonParser.parseString("{\"rewards\":[\"a#0\",\"b#1\"],\"kind\":\"item\"}")
                .getAsJsonObject();

        CompoundTag tag = (CompoundTag) JsonTags.toTag(json);

        assertInstanceOf(StringTag.class, tag.get("kind"));
        JsonObject back = (JsonObject) JsonTags.fromTag(tag);
        assertEquals(2, back.getAsJsonArray("rewards").size());
        assertEquals("b#1", back.getAsJsonArray("rewards").get(1).getAsString());
        assertEquals("item", back.get("kind").getAsString());
    }
}
