package com.kuronami.questtree.client.viewer;

/**
 * Debounces the one viewer that has to be told to reload (EMI). A sync, a progress packet and an editor save can
 * arrive in a burst; waiting for a quiet window turns that into one reload. Pure and clock-driven, so the timing
 * rule is tested without a client.
 */
public final class ViewerRefresh {

    /** How long the index must stay unchanged before EMI is asked to reload. */
    public static final long QUIET_MS = 3000;

    private boolean dirty;
    private long changedAt;

    /** Notes that the index changed at {@code now} (milliseconds) and restarts the quiet window. */
    public synchronized void mark(long now) {
        dirty = true;
        changedAt = now;
    }

    /** Whether a marked change has now been quiet long enough to reload. */
    public synchronized boolean due(long now) {
        return dirty && now - changedAt >= QUIET_MS;
    }

    /** Marks the reload done, so it is not asked for again until the next change. */
    public synchronized void done() {
        dirty = false;
    }

    /** Forgets any pending change, e.g. when no viewer needs a reload. */
    public synchronized void reset() {
        dirty = false;
    }

    public synchronized boolean dirty() {
        return dirty;
    }
}
