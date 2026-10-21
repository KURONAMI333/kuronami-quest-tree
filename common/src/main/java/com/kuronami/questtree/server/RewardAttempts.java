package com.kuronami.questtree.server;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Remembers which rewards already failed, so a reward that throws on every claim logs once instead of every time. */
final class RewardAttempts {

    private final Set<String> logged = ConcurrentHashMap.newKeySet();

    /** True the first time a key is seen, false afterwards. */
    boolean first(String key) {
        return logged.add(key);
    }
}
