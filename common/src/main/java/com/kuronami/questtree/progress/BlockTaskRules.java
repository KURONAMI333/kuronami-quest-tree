package com.kuronami.questtree.progress;

import java.util.function.Predicate;

/**
 * What the {@code break} and {@code place_block} tasks share: a block spec's matcher and the capped step of their
 * counters. Kept free of Minecraft types so the rules can be checked without a running game.
 */
public final class BlockTaskRules {

    private BlockTaskRules() {
    }

    /** An empty spec matches any block; {@code #name} goes through the tag lookup; otherwise the full id must match. */
    public static boolean matches(String spec, String blockId, Predicate<String> hasTag) {
        if (spec.isEmpty()) {
            return true;
        }
        if (spec.startsWith("#")) {
            return hasTag.test(spec.substring(1));
        }
        return blockId.equals(spec);
    }

    /** One broken or placed block adds one, never past the target. */
    public static long next(long had, long target) {
        return had < target ? Math.min(had + 1, target) : had;
    }
}
