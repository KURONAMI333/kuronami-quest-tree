package com.kuronami.questtree.progress;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Pufferfish's Skills' anti-farming windows ({@code anti_farming_per_chunk} / {@code anti_farming_per_entity}).
 * <p>
 * Pufferfish's Skills keeps one state per experience source instance, so two chapters (or two sources in one chapter)
 * count independently. The {@code source} given here is the chapter id plus the source's index in the chapter's
 * {@code points.sources} array, so one source's limit never eats another's (2026-10-20 review).
 * <p>
 * Old entries are dropped when a limit is checked and the whole state is cleared when the server stops.
 */
public final class AntiFarming {

    private record DamageAt(long time, float damage) {
    }

    private final Map<String, Deque<Long>> chunkTimes = new HashMap<>();
    private final Map<String, Deque<DamageAt>> entityDamage = new HashMap<>();

    /** Allows one event from a chunk if the source's limit has not run out (Pufferfish's {@code tryIncrement}). */
    public boolean allowInChunk(String source, int limit, int resetSeconds, String chunk, long now) {
        String key = source + "|" + limit + "/" + resetSeconds + "@" + chunk;
        Deque<Long> times = chunkTimes.computeIfAbsent(key, k -> new ArrayDeque<>());
        while (!times.isEmpty() && times.peekFirst() < now) {
            times.removeFirst();
        }
        if (times.size() >= limit) {
            return false;
        }
        times.addLast(now + resetSeconds * 1000L);
        return true;
    }

    /**
     * Caps the damage counted against one entity in the source's window (Pufferfish's {@code addAndLimit}). Returns
     * the allowed part, which is 0 once the limit is reached.
     */
    public float limitDamage(String source, float limit, int resetSeconds, String entity, float damage, long now) {
        String key = source + "|" + limit + "/" + resetSeconds + "@" + entity;
        Deque<DamageAt> hits = entityDamage.computeIfAbsent(key, k -> new ArrayDeque<>());
        dropOutdated(hits, now);
        float total = 0;
        for (DamageAt hit : hits) {
            total += hit.damage();
        }
        if (total >= limit) {
            return 0;
        }
        float allowed = Math.min(Math.max(0, damage), limit - total);
        if (allowed > 0) {
            hits.addLast(new DamageAt(now + resetSeconds * 1000L, allowed));
        }
        return allowed;
    }

    /** Drops expired windows and the empty deques they leave behind (2026-10-20 review). */
    public void removeOutdated(long now) {
        chunkTimes.values().removeIf(times -> {
            while (!times.isEmpty() && times.peekFirst() < now) {
                times.removeFirst();
            }
            return times.isEmpty();
        });
        entityDamage.values().removeIf(hits -> {
            dropOutdated(hits, now);
            return hits.isEmpty();
        });
    }

    /** Forgets every window (server stop). */
    public void clear() {
        chunkTimes.clear();
        entityDamage.clear();
    }

    private static void dropOutdated(Deque<DamageAt> hits, long now) {
        while (!hits.isEmpty() && hits.peekFirst().time() < now) {
            hits.removeFirst();
        }
    }

    /** Number of windows still tracked, for tests. */
    int trackedKeys() {
        return chunkTimes.size() + entityDamage.size();
    }
}
