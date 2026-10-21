package com.kuronami.questtree.client;

/**
 * Identity key for per-frame caches that must not survive a new sync (2026-10-22). Two models can look equal while
 * carrying different nodes, so the key follows object identity, not {@code equals}.
 */
public final class CacheKey {

    private CacheKey() {
    }

    public static String of(Object... parts) {
        StringBuilder out = new StringBuilder();
        for (Object part : parts) {
            if (out.length() > 0) {
                out.append('|');
            }
            out.append(part == null ? "null" : System.identityHashCode(part));
        }
        return out.toString();
    }
}
