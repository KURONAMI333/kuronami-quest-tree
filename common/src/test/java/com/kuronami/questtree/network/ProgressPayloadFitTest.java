package com.kuronami.questtree.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The 2026-10-24 fix: a progress packet over the limit is fitted by cutting the oldest recent entries. */
class ProgressPayloadFitTest {

    private static String text(Random random, int chars) {
        StringBuilder out = new StringBuilder(chars);
        for (int i = 0; i < chars; i++) {
            out.append((char) ('a' + random.nextInt(26)));
        }
        return out.toString();
    }

    private static JsonObject progressWithRecent(int count, int chars, long seed) {
        Random random = new Random(seed);
        JsonObject progress = new JsonObject();
        JsonArray completed = new JsonArray();
        completed.add("pack:chapter/first");
        completed.add("pack:chapter/second");
        progress.add("completed", completed);
        JsonObject tasks = new JsonObject();
        tasks.addProperty("pack:chapter/first#0", 5);
        progress.add("tasks", tasks);
        progress.addProperty("level", 3);
        JsonArray recent = new JsonArray();
        // newest first, like PlayerProgress.recent
        for (int i = 0; i < count; i++) {
            recent.add("complete|pack:chapter/node_" + i + "|" + text(random, chars));
        }
        progress.add("recent", recent);
        return progress;
    }

    @Test
    void recentIsCutOldestFirstUntilThePacketFits() {
        JsonObject progress = progressWithRecent(20, 200, 1);
        int payloadMax = 2048;
        ProgressPayloadFit.Result result = ProgressPayloadFit.fit(progress, payloadMax);

        assertFalse(result.tooLarge(payloadMax));
        assertTrue(result.data().length <= payloadMax);
        assertTrue(result.droppedRecent() > 0);
        JsonArray kept = result.json().getAsJsonArray("recent");
        assertEquals(20 - result.droppedRecent(), kept.size());
        for (int i = 0; i < kept.size(); i++) {
            assertEquals(progress.getAsJsonArray("recent").get(i).toString(), kept.get(i).toString(),
                "newest entries stay");
        }
        // the caller's JSON is not changed
        assertEquals(20, progress.getAsJsonArray("recent").size());
    }

    @Test
    void everyKeyOutsideRecentIsKeptAtItsValue() {
        JsonObject progress = progressWithRecent(20, 200, 2);
        ProgressPayloadFit.Result result = ProgressPayloadFit.fit(progress, 2048);

        assertTrue(result.droppedRecent() > 0);
        for (Map.Entry<String, JsonElement> entry : progress.entrySet()) {
            if (entry.getKey().equals("recent")) {
                continue;
            }
            assertEquals(entry.getValue().toString(), result.json().get(entry.getKey()).toString(),
                    entry.getKey());
        }
    }

    @Test
    void anEmptyRecentListIsReturnedAsItIsAndStillTooLarge() {
        Random random = new Random(3);
        JsonObject progress = new JsonObject();
        JsonArray recent = new JsonArray();
        progress.add("recent", recent);
        JsonArray completed = new JsonArray();
        for (int i = 0; i < 3000; i++) {
            completed.add("pack:chapter/node_" + i + "_" + text(random, 30));
        }
        progress.add("completed", completed);

        ProgressPayloadFit.Result result = ProgressPayloadFit.fit(progress, 1024);

        assertEquals(0, result.droppedRecent());
        assertTrue(result.tooLarge(1024));
        assertSame(progress, result.json());
    }

    @Test
    void aPacketThatAlreadyFitsIsLeftAlone() {
        JsonObject progress = progressWithRecent(3, 10, 4);
        ProgressPayloadFit.Result result = ProgressPayloadFit.fit(progress, ProgressPayload.MAX);

        assertEquals(0, result.droppedRecent());
        assertSame(progress, result.json());
        assertEquals(ProgressPayloadFit.compress(progress.toString()).length, result.data().length);
    }
}
