package com.kuronami.questtree.progress;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

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
