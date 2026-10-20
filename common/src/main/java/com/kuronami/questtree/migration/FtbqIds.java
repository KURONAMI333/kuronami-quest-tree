package com.kuronami.questtree.migration;

import com.google.gson.JsonElement;

import java.util.Locale;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * FTB Quests ids as they appear in book and progress files (2026-10-20). Every object id is a 64-bit number. Files
 * written by 1.20 and later spell it as 16 hex digits (progress files always do, as map keys); a book from 1.19 or
 * earlier may hold the same number in decimal. Everything is reduced to the unsigned number so a decimal chapter
 * entry and a hex progress key meet. The hex form is parsed unsigned: FTB Quests' own parser is signed and gives up
 * on ids with the top bit set, which ids made from random numbers regularly have.
 */
public final class FtbqIds {

    public static final UUID NIL = new UUID(0L, 0L);

    private FtbqIds() {
    }

    /** A JSON number is taken as written; a string is hex, with an optional leading {@code #}. */
    public static OptionalLong parse(JsonElement e) {
        if (e == null || !e.isJsonPrimitive()) {
            return OptionalLong.empty();
        }
        if (e.getAsJsonPrimitive().isNumber()) {
            return OptionalLong.of(e.getAsLong());
        }
        return parse(e.getAsString());
    }

    public static OptionalLong parse(String s) {
        String t = s == null ? "" : s.trim();
        if (t.startsWith("#")) {
            t = t.substring(1);
        }
        if (t.isEmpty() || t.length() > 16) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseUnsignedLong(t, 16));
        } catch (NumberFormatException ex) {
            return OptionalLong.empty();
        }
    }

    /** The spelling FTB Quests prints ({@code %016X}); used in reports so an id can be searched in the files. */
    public static String hex(long id) {
        return String.format(Locale.ROOT, "%016X", id);
    }

    /** A UUID with or without dashes. */
    public static UUID uuid(String s) {
        String t = s.trim();
        if (t.length() == 32) {
            t = t.substring(0, 8) + "-" + t.substring(8, 12) + "-" + t.substring(12, 16) + "-"
                    + t.substring(16, 20) + "-" + t.substring(20);
        }
        return UUID.fromString(t);
    }
}
