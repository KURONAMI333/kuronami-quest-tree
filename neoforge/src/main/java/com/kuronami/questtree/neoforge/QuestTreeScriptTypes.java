package com.kuronami.questtree.neoforge;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The type ids each KubeJS script type registered. A reload of one script type forgets only that type's ids, so a type
 * a startup script registered survives a server-script reload (and the other way round).
 */
final class QuestTreeScriptTypes {

    private final Map<String, Set<String>> tasks = new HashMap<>();
    private final Map<String, Set<String>> rewards = new HashMap<>();
    private final Map<String, Set<String>> conditions = new HashMap<>();

    synchronized void task(String scriptType, String id) {
        tasks.computeIfAbsent(scriptType, key -> new LinkedHashSet<>()).add(id);
    }

    synchronized void reward(String scriptType, String id) {
        rewards.computeIfAbsent(scriptType, key -> new LinkedHashSet<>()).add(id);
    }

    synchronized void condition(String scriptType, String id) {
        conditions.computeIfAbsent(scriptType, key -> new LinkedHashSet<>()).add(id);
    }

    /** Removes and returns the ids one script type registered. */
    synchronized Cleared clear(String scriptType) {
        return new Cleared(orEmpty(tasks.remove(scriptType)), orEmpty(rewards.remove(scriptType)),
                orEmpty(conditions.remove(scriptType)));
    }

    private static Set<String> orEmpty(Set<String> ids) {
        return ids == null ? Set.of() : Set.copyOf(ids);
    }

    record Cleared(Set<String> tasks, Set<String> rewards, Set<String> conditions) {
    }
}
