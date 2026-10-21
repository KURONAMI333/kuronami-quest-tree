package com.kuronami.questtree.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SafeJsonTest {

    @Test
    void oneMalformedEntryDoesNotStopTheRest() {
        JsonArray entries = new JsonArray();
        entries.add(new JsonPrimitive(1));
        entries.add(new JsonPrimitive(2));
        entries.add(new JsonPrimitive(3));
        List<Integer> seen = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        SafeJson.each(entries, e -> {
            int value = e.getAsInt();
            if (value == 2) {
                throw new IllegalStateException("bad entry");
            }
            seen.add(value);
        }, ex -> failed.add(ex.getMessage()));

        assertEquals(List.of(1, 3), seen);
        assertEquals(List.of("bad entry"), failed);
    }
}
