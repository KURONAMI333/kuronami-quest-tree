package com.kuronami.questtree.progress;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Converts between the saved JSON form and NBT without going through a string (2026-10-22 release review).
 * <p>
 * A {@code StringTag} holds a {@code writeUTF} string, capped at 65,535 bytes; a large book (a few hundred completed
 * quests for one team) overflows it and the save fails. A compound tag has no such cap, so each record is stored as
 * its own compound and read back through the same conversion.
 * <p>
 * The conversion is written here rather than taken from {@code JsonOps}/{@code NbtOps} because NBT has no boolean:
 * those ops write {@code true} as the byte 1 and read it back as the number 1, so every boolean in a save flipped on
 * the next start (a book given again on every login, a blocked reward unblocking itself, a closed chapter opening).
 * Here the tag type is the JSON type: a byte is a boolean (1/0), small integers are int tags, longs are long tags and
 * doubles keep their precision. A number is never written as a byte, so a byte can always be read back as a boolean.
 * <p>
 * Saves written by the previous build are still read: they hold booleans as bytes (the same encoding), numbers
 * narrowed to bytes and shorts, arrays of small numbers as byte arrays, and a mixed array as a list of single-entry
 * compounds under the empty key. Those shapes are accepted on read.
 */
public final class JsonTags {

    /**
     * JSON null has no NBT form. It is written as the only entry of a compound under this key, which no key of our
     * data produces (node ids, UUIDs, locales and migration names). Our own records never hold null; this is here so
     * that the conversion does not have to lie about a value it cannot express.
     */
    private static final String NULL = "\u0000null";
    /** The key the previous build's conversion used to wrap one element of a mixed array. */
    private static final String ELEMENT = "";

    private JsonTags() {
    }

    public static Tag toTag(JsonElement json) {
        if (json == null || json.isJsonNull()) {
            CompoundTag nullTag = new CompoundTag();
            nullTag.put(NULL, ByteTag.valueOf((byte) 0));
            return nullTag;
        }
        if (json.isJsonObject()) {
            CompoundTag out = new CompoundTag();
            for (Map.Entry<String, JsonElement> e : json.getAsJsonObject().entrySet()) {
                out.put(e.getKey(), toTag(e.getValue()));
            }
            return out;
        }
        if (json.isJsonArray()) {
            return listTag(json.getAsJsonArray());
        }
        JsonPrimitive p = json.getAsJsonPrimitive();
        if (p.isBoolean()) {
            return ByteTag.valueOf(p.getAsBoolean());
        }
        if (p.isString()) {
            return StringTag.valueOf(p.getAsString());
        }
        return numberTag(p);
    }

    public static JsonElement fromTag(Tag tag) {
        if (tag == null) {
            return JsonNull.INSTANCE;
        }
        return switch (tag.getId()) {
            // A byte is a boolean: both this build and the previous one wrote booleans as 1/0. Anything else in a
            // byte is a number a previous build narrowed; keep it as a number rather than inventing a boolean.
            case 1 -> {
                byte value = ((NumericTag) tag).getAsByte();
                yield value == 0 ? new JsonPrimitive(false)
                        : value == 1 ? new JsonPrimitive(true)
                        : new JsonPrimitive(value);
            }
            case 2 -> new JsonPrimitive(((NumericTag) tag).getAsShort());
            case 3 -> new JsonPrimitive(((NumericTag) tag).getAsInt());
            case 4 -> new JsonPrimitive(((NumericTag) tag).getAsLong());
            case 5 -> new JsonPrimitive(((NumericTag) tag).getAsFloat());
            case 6 -> new JsonPrimitive(((NumericTag) tag).getAsDouble());
            // the previous build turned an array of small numbers (or of booleans) into a byte array
            case 7 -> numbers(((ByteArrayTag) tag).getAsByteArray());
            case 8 -> new JsonPrimitive(tag.getAsString());
            case 9 -> array((ListTag) tag);
            case 10 -> object((CompoundTag) tag);
            case 11 -> numbers(((IntArrayTag) tag).getAsIntArray());
            case 12 -> numbers(((LongArrayTag) tag).getAsLongArray());
            default -> JsonNull.INSTANCE;
        };
    }

    private static Tag listTag(JsonArray array) {
        List<Tag> tags = new ArrayList<>(array.size());
        int type = -1;
        boolean mixed = false;
        for (JsonElement e : array) {
            Tag tag = toTag(e);
            tags.add(tag);
            if (type < 0) {
                type = tag.getId();
            } else if (type != tag.getId()) {
                mixed = true;
            }
        }
        ListTag out = new ListTag();
        if (!mixed) {
            out.addAll(tags);
            return out;
        }
        // an NBT list holds one type: wrap each element so the types can differ, as the previous build did
        for (Tag tag : tags) {
            CompoundTag wrapped = new CompoundTag();
            wrapped.put(ELEMENT, tag);
            out.add(wrapped);
        }
        return out;
    }

    private static JsonArray array(ListTag list) {
        JsonArray out = new JsonArray();
        for (Tag element : list) {
            if (element instanceof CompoundTag compound && compound.getAllKeys().size() == 1
                    && compound.contains(ELEMENT)) {
                out.add(fromTag(compound.get(ELEMENT)));
            } else {
                out.add(fromTag(element));
            }
        }
        return out;
    }

    private static JsonElement object(CompoundTag tag) {
        if (tag.getAllKeys().size() == 1 && tag.contains(NULL)) {
            return JsonNull.INSTANCE;
        }
        JsonObject out = new JsonObject();
        for (String key : tag.getAllKeys()) {
            out.add(key, fromTag(tag.get(key)));
        }
        return out;
    }

    private static JsonArray numbers(byte[] values) {
        JsonArray out = new JsonArray();
        for (byte value : values) {
            out.add(value);
        }
        return out;
    }

    private static JsonArray numbers(int[] values) {
        JsonArray out = new JsonArray();
        for (int value : values) {
            out.add(value);
        }
        return out;
    }

    private static JsonArray numbers(long[] values) {
        JsonArray out = new JsonArray();
        for (long value : values) {
            out.add(value);
        }
        return out;
    }

    /**
     * The tag for a JSON number. The number's own type is kept where it says something (an int, a long, a float, a
     * double); text parsed from JSON has no type, so an integer with no decimal point or exponent becomes a long and
     * everything else a double. A byte is never used: it means boolean.
     */
    private static Tag numberTag(JsonPrimitive p) {
        Number number = p.getAsNumber();
        if (number instanceof Byte || number instanceof Short || number instanceof Integer) {
            return IntTag.valueOf(number.intValue());
        }
        if (number instanceof Long) {
            return LongTag.valueOf(number.longValue());
        }
        if (number instanceof Float) {
            return FloatTag.valueOf(number.floatValue());
        }
        if (number instanceof Double) {
            return DoubleTag.valueOf(number.doubleValue());
        }
        String text = p.getAsString();
        if (text.indexOf('.') < 0 && text.indexOf('e') < 0 && text.indexOf('E') < 0) {
            try {
                return LongTag.valueOf(Long.parseLong(text));
            } catch (NumberFormatException ignored) {
                // too big for a long: fall through to a double, which is what the previous build did
            }
        }
        return DoubleTag.valueOf(p.getAsDouble());
    }
}
