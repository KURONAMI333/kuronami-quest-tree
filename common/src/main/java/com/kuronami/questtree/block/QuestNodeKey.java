package com.kuronami.questtree.block;

import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The keys a task block saves its target under. {@code quest_node} is the current quest key; {@code node} is read as
 * a fallback so blocks placed by an earlier build keep their quest (2026-10-21 review). {@code quest_chapter} holds a
 * chapter target (2026-10-22, G3); it wins when both are present.
 */
public final class QuestNodeKey {

    public static final String KEY = "quest_node";
    public static final String CHAPTER_KEY = "quest_chapter";
    public static final String LEGACY_KEY = "node";

    private QuestNodeKey() {
    }

    /** Reads the saved quest, preferring the current key over the older one. */
    public static String read(Predicate<String> has, Function<String, String> get) {
        String value = has.test(KEY) ? get.apply(KEY) : get.apply(LEGACY_KEY);
        return value == null ? "" : value;
    }
}
