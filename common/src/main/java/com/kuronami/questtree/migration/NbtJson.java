package com.kuronami.questtree.migration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.nbt.CollectionTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/**
 * Vanilla NBT as Gson values (2026-10-20), so the Pufferfish's Skills mapping can be written and tested on plain JSON.
 * Numbers keep their value (a byte flag becomes 0/1), strings stay strings, lists and compounds nest. Only what the
 * migration reads needs to survive; typed arrays become number arrays.
 */
public final class NbtJson {

    private NbtJson() {
    }

    public static JsonElement of(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            JsonObject o = new JsonObject();
            for (String key : compound.getAllKeys()) {
                Tag child = compound.get(key);
                if (child != null) {
                    o.add(key, of(child));
                }
            }
            return o;
        }
        if (tag instanceof CollectionTag<?> list) {
            JsonArray a = new JsonArray();
            for (Tag child : list) {
                a.add(of(child));
            }
            return a;
        }
        if (tag instanceof NumericTag n) {
            return new JsonPrimitive(n.getAsNumber());
        }
        if (tag instanceof StringTag s) {
            return new JsonPrimitive(s.getAsString());
        }
        return new JsonPrimitive(tag.getAsString());
    }

    public static JsonObject of(CompoundTag tag) {
        return of((Tag) tag).getAsJsonObject();
    }
}
