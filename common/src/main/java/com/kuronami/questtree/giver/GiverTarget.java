package com.kuronami.questtree.giver;

import com.kuronami.questtree.data.ProgressModel;

import java.util.Set;

/**
 * What a quest giver opens: one quest ({@code <namespace>:<chapter>/<node>}) or one chapter
 * ({@code <namespace>:<path>}). The two id shapes overlap, so the kind is part of the value, not something a reader
 * guesses from the string.
 */
public record GiverTarget(String id, boolean chapter) {

    public GiverTarget {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("a giver target needs an id");
        }
    }

    public static GiverTarget node(String id) {
        return new GiverTarget(id, false);
    }

    public static GiverTarget chapter(String id) {
        return new GiverTarget(id, true);
    }

    /** The saved form, one string: {@code node|<id>} or {@code chapter|<id>}. */
    public String encode() {
        return (chapter ? "chapter|" : "node|") + id;
    }

    /** Reads {@link #encode()}, or null for anything else. */
    public static GiverTarget decode(String raw) {
        if (raw == null) {
            return null;
        }
        if (raw.startsWith("chapter|")) {
            String id = raw.substring("chapter|".length());
            return id.isEmpty() ? null : chapter(id);
        }
        if (raw.startsWith("node|")) {
            String id = raw.substring("node|".length());
            return id.isEmpty() ? null : node(id);
        }
        return null;
    }

    /**
     * Reads an id typed into a command: an exact chapter id is a chapter, an exact node id is a quest, and anything
     * else is unknown. A node id is never treated as a chapter by prefix, so {@code pack:chapter/node} cannot open
     * the chapter it belongs to by accident.
     */
    public static GiverTarget resolve(String raw, Set<String> chapters, Set<String> nodes) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        if (chapters.contains(raw)) {
            return chapter(raw);
        }
        return nodes.contains(raw) ? node(raw) : null;
    }

    /** The same lookup against a loaded book. */
    public static GiverTarget resolve(String raw, ProgressModel model) {
        return model == null ? null : resolve(raw, model.chapters.keySet(), model.nodes.keySet());
    }
}
