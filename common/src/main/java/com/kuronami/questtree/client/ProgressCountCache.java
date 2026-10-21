package com.kuronami.questtree.client;

/**
 * One small count worked out from the current progress and rules. Computed once per pair instead of once per frame;
 * the progress packet replaces the progress object and a resync replaces the rules, so identity is enough.
 */
final class ProgressCountCache {

    private Object progress;
    private Object rules;
    private int value;

    boolean valid(Object progress, Object rules) {
        return value >= 0 && this.progress == progress && this.rules == rules;
    }

    int value() {
        return value;
    }

    void store(Object progress, Object rules, int value) {
        this.progress = progress;
        this.rules = rules;
        this.value = Math.max(0, value);
    }

    void clear() {
        value = -1;
        progress = null;
        rules = null;
    }
}
