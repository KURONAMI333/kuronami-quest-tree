package com.kuronami.questtree.progress;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Bounds the restriction hints that ride along with the progress packet (2026-10-23 review). A pack can name every
 * item and block of an unmet rule, so the list used to grow without limit and the custom payload failed to encode
 * once it passed its codec limits; a player was then disconnected on every join. The list is cut here, before it
 * reaches {@code ProgressPayload}, and the data and event sizes are taken into the budget so the whole payload stays
 * below the 1 MiB limit with room to spare.
 */
public final class HintLimits {

    /** Most hints sent in one packet; the codec allows more, but no pack needs this many for a tooltip. */
    public static final int MAX_HINTS = 2048;
    /** Longest single hint, in code points. */
    public static final int MAX_HINT_CHARS = 256;
    /** Longest single hint, in UTF-8 bytes: the payload codec allows 512 bytes and this leaves room for the id. */
    public static final int MAX_HINT_BYTES_PER_ENTRY = 480;
    /** Most bytes all hints together may take, before the length prefixes. */
    public static final int MAX_HINT_BYTES = 64 * 1024;
    /** Space left free in the payload for the codec's own overhead. */
    public static final int RESERVED_BYTES = 16 * 1024;

    /** One list entry costs a varint length prefix and its bytes. */
    private static final int PER_HINT_OVERHEAD = 5;

    /**
     * What was kept and what was cut.
     *
     * @param hints     the hints that fit
     * @param dropped   hints left out because the count or byte budget ran out
     * @param shortened hints cut to {@link #MAX_HINT_CHARS}
     */
    public record Result(List<String> hints, int dropped, int shortened) {
    }

    private HintLimits() {
    }

    /**
     * Cuts one hint to the length the packet allows. The string codec's limit is counted in UTF-8 bytes, so a hint of
     * 256 Japanese characters would still be 768 bytes and fail to encode; both limits are applied.
     */
    public static String truncate(String hint) {
        if (hint == null) {
            return "";
        }
        if (hint.codePointCount(0, hint.length()) <= MAX_HINT_CHARS
                && utf8Bytes(hint) <= MAX_HINT_BYTES_PER_ENTRY) {
            return hint;
        }
        int end = 0;
        int bytes = 0;
        int chars = 0;
        while (end < hint.length() && chars < MAX_HINT_CHARS) {
            int codePoint = hint.codePointAt(end);
            int size = utf8Size(codePoint);
            if (bytes + size > MAX_HINT_BYTES_PER_ENTRY) {
                break;
            }
            bytes += size;
            end += Character.charCount(codePoint);
            chars++;
        }
        return hint.substring(0, end);
    }

    /** The UTF-8 size of one code point. */
    private static int utf8Size(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        return codePoint < 0x10000 ? 3 : 4;
    }

    /** The UTF-8 size the string codec writes, or 0 for null. */
    public static int utf8Bytes(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    /** The bytes the event strings take in the packet, including their length prefixes. */
    public static int eventBytes(List<String> events) {
        if (events == null || events.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (String event : events) {
            total += utf8Bytes(event) + PER_HINT_OVERHEAD;
        }
        return total;
    }

    /**
     * The hints that fit in one payload of {@code payloadMax} bytes once {@code dataBytes} of compressed progress and
     * {@code eventBytes} of events are already in it.
     */
    public static Result limit(List<String> hints, int dataBytes, int eventBytes, int payloadMax) {
        if (hints == null || hints.isEmpty()) {
            return new Result(List.of(), 0, 0);
        }
        long budget = Math.min(MAX_HINT_BYTES,
                (long) payloadMax - dataBytes - eventBytes - RESERVED_BYTES);
        if (budget <= 0) {
            return new Result(List.of(), hints.size(), 0);
        }
        List<String> kept = new ArrayList<>(Math.min(hints.size(), MAX_HINTS));
        int used = 0;
        int shortened = 0;
        for (String hint : hints) {
            if (kept.size() >= MAX_HINTS) {
                break;
            }
            String cut = truncate(hint);
            if (cut.length() != (hint == null ? 0 : hint.length())) {
                shortened++;
            }
            int cost = utf8Bytes(cut) + PER_HINT_OVERHEAD;
            if (used + cost > budget) {
                break;
            }
            kept.add(cut);
            used += cost;
        }
        return new Result(List.copyOf(kept), hints.size() - kept.size(), shortened);
    }
}
