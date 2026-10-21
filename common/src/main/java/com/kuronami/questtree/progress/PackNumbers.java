package com.kuronami.questtree.progress;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Reads numbers from pack and save JSON without trusting their type. A string or object where a number belongs falls
 * back to the default instead of throwing while a world is joining.
 */
public final class PackNumbers {

    /** 30 days: long enough for any emergency cooldown, short enough that seconds * 1000 cannot overflow. */
    public static final long MAX_COOLDOWN_SECONDS = 30L * 24 * 60 * 60;

    /** A map coordinate outside this range is a broken pack or a typo, not a place to draw. */
    public static final double MAX_COORDINATE = 1_000_000;

    private PackNumbers() {
    }

    public static long longOr(JsonElement e, long fallback) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()
                ? e.getAsLong() : fallback;
    }

    public static int intOr(JsonElement e, int fallback) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()
                ? e.getAsInt() : fallback;
    }

    /*
     * The four readers below are for a save only, never for pack or editor JSON: a save written before 2026-10-22
     * stored numbers and booleans through JsonOps/NbtOps, which narrowed a small number to the byte 1/0 and read
     * every byte back as a number. A boolean therefore still stands for the number 1/0 here (see JsonTags), while a
     * pack keeps the strict reading above: a boolean where a pack wants a number is a broken pack, not a legacy one.
     */

    /** A long from a save; a boolean is the old build's 1/0 for a narrow number. */
    public static long savedLong(JsonElement e, long fallback) {
        Long value = savedNumberOrNull(e);
        return value != null ? value : fallback;
    }

    public static int savedInt(JsonElement e, int fallback) {
        Long value = savedNumberOrNull(e);
        return value != null ? value.intValue() : fallback;
    }

    public static double savedDouble(JsonElement e, double fallback) {
        if (e == null || !e.isJsonPrimitive()) {
            return fallback;
        }
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) {
            return p.getAsBoolean() ? 1 : 0;
        }
        return p.isNumber() ? p.getAsDouble() : fallback;
    }

    /**
     * A boolean from a save. A save made before 2026-10-22 could hold a boolean as the number 1/0 instead, so those
     * read as true/false here (and this build never writes a number where a boolean belongs).
     */
    public static boolean booleanOr(JsonElement e, boolean fallback) {
        Boolean value = booleanOrNull(e);
        return value != null ? value : fallback;
    }

    /** The boolean, or null when the element is absent or is neither a boolean nor a number. */
    public static Boolean booleanOrNull(JsonElement e) {
        if (e == null || !e.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) {
            return p.getAsBoolean();
        }
        return p.isNumber() ? p.getAsLong() != 0 : null;
    }

    private static Long savedNumberOrNull(JsonElement e) {
        if (e == null || !e.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isNumber()) {
            return p.getAsLong();
        }
        return p.isBoolean() ? (p.getAsBoolean() ? 1L : 0L) : null;
    }

    /**
     * A map coordinate or size from a pack or the editor: NaN and the infinities (1e999) would turn the whole map
     * blank, so they become 0, and anything beyond ±{@link #MAX_COORDINATE} is pulled back to that edge.
     */
    public static double coordinate(double value) {
        if (!Double.isFinite(value)) {
            return 0;
        }
        return Math.max(-MAX_COORDINATE, Math.min(MAX_COORDINATE, value));
    }

    /** {@code cooldown_seconds} from an emergency block, clamped before the conversion to milliseconds. */
    public static long cooldownMs(JsonObject o, String key, long defaultSeconds) {
        long seconds = longOr(o.get(key), defaultSeconds);
        seconds = Math.max(0, Math.min(seconds, MAX_COOLDOWN_SECONDS));
        return seconds * 1000L;
    }
}
