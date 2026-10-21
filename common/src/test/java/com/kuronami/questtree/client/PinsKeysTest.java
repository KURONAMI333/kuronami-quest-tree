package com.kuronami.questtree.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PinsKeysTest {

    @Test
    void onlyStringEntriesFromTheRequestedWorldAreRead() {
        JsonArray list = new JsonArray();
        list.add("basics/a");
        list.add(7);
        list.add(new JsonObject());
        list.add("basics/b");
        JsonObject all = new JsonObject();
        all.add("world", list);
        all.add("other", new JsonArray());

        assertEquals(List.of("basics/a", "basics/b"), Pins.keysOf(all, "world"));
        assertEquals(List.of(), Pins.keysOf(all, "missing"));
    }
}
