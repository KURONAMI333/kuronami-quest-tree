package com.kuronami.questtree.block;

/**
 * What a detector block force-progresses: a whole quest, or one task of it. The task is named by the same
 * {@code <node key>#<task index>} form that progress uses, so the detector needs no new id scheme. Kept free of game
 * classes so the parsing can be tested without Minecraft.
 */
public record DetectorTarget(String node, int task) {

    /** A target that names the whole quest rather than one task. */
    public static final int WHOLE_QUEST = -1;

    public DetectorTarget {
        if (node == null || node.isEmpty()) {
            throw new IllegalArgumentException("empty quest");
        }
        if (task < WHOLE_QUEST) {
            throw new IllegalArgumentException("negative task index");
        }
    }

    public static DetectorTarget quest(String node) {
        return new DetectorTarget(node, WHOLE_QUEST);
    }

    public static DetectorTarget task(String node, int index) {
        return new DetectorTarget(node, index);
    }

    /** Parses {@code node} or {@code node#task}; throws {@link IllegalArgumentException} for anything else. */
    public static DetectorTarget parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("empty target");
        }
        String value = text.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("empty target");
        }
        int hash = value.indexOf('#');
        if (hash < 0) {
            return quest(value);
        }
        if (hash == 0 || hash == value.length() - 1 || value.indexOf('#', hash + 1) >= 0) {
            throw new IllegalArgumentException("bad task target " + text);
        }
        String node = value.substring(0, hash);
        int index;
        try {
            index = Integer.parseInt(value.substring(hash + 1));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("bad task index " + text, ex);
        }
        if (index < 0) {
            throw new IllegalArgumentException("negative task index " + text);
        }
        return task(node, index);
    }

    public boolean isTask() {
        return task >= 0;
    }
}
