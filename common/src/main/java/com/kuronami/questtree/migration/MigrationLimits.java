package com.kuronami.questtree.migration;

/**
 * Size caps for files read during a migration (2026-10-24). A corrupt or oversized file has to become "unreadable"
 * instead of exhausting the heap of the running server.
 */
public final class MigrationLimits {

    /** Largest SNBT text file (one chapter, one team) read by a migration. */
    public static final long MAX_TEXT_FILE_BYTES = 8L * 1024 * 1024;

    /** Largest compressed NBT file (Pufferfish's player data) read by a migration. */
    public static final long MAX_NBT_BYTES = 64L * 1024 * 1024;

    private MigrationLimits() {
    }
}
