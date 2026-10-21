package com.kuronami.questtree.progress;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Short-lived answers for quest barriers, keyed by player and by what was asked (2026-10-21 review). A player walking
 * against two barriers must not overwrite one answer with the other, and a full server must not grow the map without
 * bound: each player keeps at most {@code maxPerPlayer} answers, oldest first. Game-free so the rules can be tested.
 */
public final class BarrierCache<V> {

    private final int ttl;
    private final int maxPerPlayer;
    private final Map<UUID, LinkedHashMap<String, Entry<V>>> values = new LinkedHashMap<>();

    private record Entry<V>(V value, int tick) {
    }

    public BarrierCache(int ttl, int maxPerPlayer) {
        this.ttl = Math.max(1, ttl);
        this.maxPerPlayer = Math.max(1, maxPerPlayer);
    }

    public V get(UUID player, String mode, String key, int tick) {
        LinkedHashMap<String, Entry<V>> mine = values.get(player);
        if (mine == null) {
            return null;
        }
        String k = key(mode, key);
        Entry<V> entry = mine.get(k);
        if (entry == null || entry.tick() + ttl < tick) {
            return null;
        }
        return entry.value();
    }

    public void put(UUID player, String mode, String key, int tick, V value) {
        LinkedHashMap<String, Entry<V>> mine = values.computeIfAbsent(player, k -> new LinkedHashMap<>());
        String k = key(mode, key);
        mine.remove(k);
        mine.put(k, new Entry<>(value, tick));
        while (mine.size() > maxPerPlayer) {
            mine.remove(mine.keySet().iterator().next());
        }
    }

    public void clear(UUID player) {
        values.remove(player);
    }

    public void clearAll() {
        values.clear();
    }

    private static String key(String mode, String key) {
        return mode + '\0' + key;
    }
}
