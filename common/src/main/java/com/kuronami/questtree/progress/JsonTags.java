package com.kuronami.questtree.progress;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;

/**
 * Converts between the saved JSON form and NBT without going through a string (2026-10-22 release review).
 * <p>
 * A {@code StringTag} holds a {@code writeUTF} string, capped at 65,535 bytes; a large book (a few hundred completed
 * quests for one team) overflows it and the save fails. A compound tag has no such cap, so each record is stored as
 * its own compound and read back through the same conversion.
 */
public final class JsonTags {

    private JsonTags() {
    }

    public static Tag toTag(JsonElement json) {
        return JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE, json);
    }

    public static JsonElement fromTag(Tag tag) {
        return NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, tag);
    }
}
