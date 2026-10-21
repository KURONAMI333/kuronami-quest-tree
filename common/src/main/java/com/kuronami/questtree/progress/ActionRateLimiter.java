package com.kuronami.questtree.progress;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A token bucket per player for screen actions (2026-10-22 release review). Every action used to cost a full
 * re-check of the book, and a party invite could be repeated as fast as the client could send it; the server now
 * drops what is above the limit without replying.
 * <p>
 * Ordinary actions are 20 per second, party actions 2 per second, each with its own bucket. The buckets are small
 * enough that a normal drag, which sends many small ops, still passes.
 */
public final class ActionRateLimiter {

    public static final int ACTIONS_PER_SECOND = 20;
    public static final int PARTY_ACTIONS_PER_SECOND = 2;

    private static final class Bucket {
        double tokens;
        long last;
    }

    private static final Map<UUID, Bucket[]> BUCKETS = new HashMap<>();

    private ActionRateLimiter() {
    }

    /** Consumes one token from the player's ordinary or party bucket; false means the action is dropped. */
    public static synchronized boolean allow(UUID player, boolean party, long nowMillis) {
        Bucket[] pair = BUCKETS.computeIfAbsent(player, k -> new Bucket[]{new Bucket(), new Bucket()});
        Bucket bucket = pair[party ? 1 : 0];
        int limit = party ? PARTY_ACTIONS_PER_SECOND : ACTIONS_PER_SECOND;
        if (bucket.last == 0L) {
            bucket.tokens = limit;
            bucket.last = nowMillis;
        } else {
            long elapsed = Math.max(0L, nowMillis - bucket.last);
            bucket.tokens = Math.min(limit, bucket.tokens + elapsed * limit / 1000.0);
            bucket.last = nowMillis;
        }
        if (bucket.tokens < 1.0) {
            return false;
        }
        bucket.tokens -= 1.0;
        return true;
    }

    /** The player logged out: do not keep a bucket for them. */
    public static synchronized void forget(UUID player) {
        BUCKETS.remove(player);
    }

    /** Tests only: start from empty buckets. */
    static synchronized void clear() {
        BUCKETS.clear();
    }
}
