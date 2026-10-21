package com.kuronami.questtree.progress;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PackNumbersTest {

    @Test
    void brokenTypesFallBackAndCooldownsAreClamped() {
        JsonObject o = JsonParser.parseString("""
                {"cooldown_seconds":"soon"}
                """).getAsJsonObject();
        assertEquals(3600_000L, PackNumbers.cooldownMs(o, "cooldown_seconds", 3600));

        o.addProperty("cooldown_seconds", 7);
        assertEquals(7000L, PackNumbers.cooldownMs(o, "cooldown_seconds", 3600));

        o.addProperty("cooldown_seconds", Long.MAX_VALUE);
        assertEquals(PackNumbers.MAX_COOLDOWN_SECONDS * 1000L,
                PackNumbers.cooldownMs(o, "cooldown_seconds", 3600));

        o.addProperty("cooldown_seconds", -5);
        assertEquals(0L, PackNumbers.cooldownMs(o, "cooldown_seconds", 3600));
    }

    @Test
    void wrongNumberTypesUseTheFallback() {
        assertEquals(4, PackNumbers.intOr(JsonParser.parseString("\"3\""), 4));
        assertEquals(3, PackNumbers.intOr(JsonParser.parseString("3"), 4));
        assertEquals(8L, PackNumbers.longOr(JsonParser.parseString("{}"), 8L));
        assertEquals(1L, PackNumbers.longOr(JsonParser.parseString("false"), 1L));
        assertEquals(2L, PackNumbers.longOr(JsonParser.parseString("false"), 2L));
    }
}
