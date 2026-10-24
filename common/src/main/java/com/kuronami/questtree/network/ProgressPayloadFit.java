package com.kuronami.questtree.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

/**
 * Fits the progress JSON into one progress packet by cutting the oldest entries of its {@code recent} list (2026-10-24).
 * {@code recent} is the display-only history of finished quests, so it is the one part that can go without losing
 * progress; {@code completed}, {@code tasks}, {@code xp}, claims and stages stay as they are. Restriction hints are
 * cut first by {@link com.kuronami.questtree.progress.HintLimits}, and when the data alone is already over the limit no
 * hint fits anyway, so cutting {@code recent} is the next step.
 * <p>
 * Minecraft-free so the size rules can be tested without the game.
 */
public final class ProgressPayloadFit {

    /**
     * What to send, and what was cut to get there.
     *
     * @param json          the progress JSON to send; {@code recent} is shortened when the fit needed it
     * @param data          gzip of {@code json}
     * @param droppedRecent how many of the oldest {@code recent} entries were left out
     */
    public record Result(JsonObject json, byte[] data, int droppedRecent) {

        /** Whether the data still does not fit: the caller must not send it and keeps its retry instead. */
        public boolean tooLarge(int payloadMax) {
            return data.length > payloadMax;
        }
    }

    private ProgressPayloadFit() {
    }

    /**
     * Returns {@code progress} as it is when its gzip already fits. Otherwise returns a copy with the oldest
     * {@code recent} entries removed one by one until the gzip fits; every other key is copied through untouched. If
     * even an empty {@code recent} list does not fit, the result is marked {@link Result#tooLarge(int)} and the caller
     * falls back to its usual "do not send" path.
     */
    public static Result fit(JsonObject progress, int payloadMax) {
        byte[] data = compress(progress.toString());
        if (data.length <= payloadMax) {
            return new Result(progress, data, 0);
        }
        JsonArray recent = recent(progress);
        if (recent == null || recent.isEmpty()) {
            return new Result(progress, data, 0);
        }
        // the list is newest first, so the oldest entry is the last one
        JsonObject emptied = progress.deepCopy();
        JsonArray emptiedRecent = recent(emptied);
        int total = emptiedRecent.size();
        while (!emptiedRecent.isEmpty()) {
            emptiedRecent.remove(emptiedRecent.size() - 1);
        }
        byte[] smallest = compress(emptied.toString());
        if (smallest.length > payloadMax) {
            return new Result(emptied, smallest, total);
        }
        JsonObject fitted = progress.deepCopy();
        JsonArray fittedRecent = recent(fitted);
        byte[] best = data;
        int dropped = 0;
        while (!fittedRecent.isEmpty() && best.length > payloadMax) {
            fittedRecent.remove(fittedRecent.size() - 1);
            dropped++;
            best = compress(fitted.toString());
        }
        return new Result(fitted, best, dropped);
    }

    private static JsonArray recent(JsonObject progress) {
        JsonElement recent = progress.get("recent");
        return recent != null && recent.isJsonArray() ? recent.getAsJsonArray() : null;
    }

    /** Gzip of the progress JSON; the node keys repeat a lot, so this is far smaller than the text. */
    public static byte[] compress(String json) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return out.toByteArray();
    }
}
