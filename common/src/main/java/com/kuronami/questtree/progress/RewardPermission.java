package com.kuronami.questtree.progress;

/**
 * The permission level a reward's command runs with (2026-10-22 release review).
 * <p>
 * The level a data pack asks for is capped by the server setting (default 2), so an edited-in command cannot run at
 * level 4 unless the server owner raises the cap. The result is also kept inside 0-4, which is what the command source
 * accepts.
 */
public final class RewardPermission {

    /** What a command reward runs with when the pack does not name a level. */
    public static final int DEFAULT = 2;
    /** The highest level a reward may ever ask for. */
    public static final int MAX = 4;

    private RewardPermission() {
    }

    public static int cap(int requested, int configured) {
        int allowed = Math.max(0, Math.min(MAX, configured));
        return Math.max(0, Math.min(allowed, requested));
    }
}
