package com.kuronami.questtree.client;

/** Whether a client-side index can answer for the current progress, rules and pins. */
final class IndexFreshness {

    private IndexFreshness() {
    }

    static boolean same(Object builtProgress, Object builtRules, int builtPins,
                        Object progress, Object rules, int pins) {
        return builtProgress == progress && builtRules == rules && builtPins == pins;
    }
}
