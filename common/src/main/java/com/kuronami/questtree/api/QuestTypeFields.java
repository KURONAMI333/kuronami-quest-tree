package com.kuronami.questtree.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which task and reward types other mods supply, and the fields each wants edited, for the in-game editor's type list
 * and field rows. {@link QuestTreeApi} is the front door: its register and describe calls write here, and the editor's
 * schema reads it.
 * <p>
 * It holds no Minecraft types on purpose. The editor schema and its round-trip tests run without the game, so the
 * registry a client draws from and the registry a test fills have to be the same plain object.
 * <p>
 * Ids are namespaced keys as {@code ResourceLocation#toString} writes them ({@code example:level}). Use the
 * {@code QuestTreeApi} methods when an id is a bare {@code ResourceLocation} or a script's string.
 */
public final class QuestTypeFields {

    private static final Map<String, List<QuestField>> TASK_FIELDS = new ConcurrentHashMap<>();
    private static final Map<String, List<QuestField>> REWARD_FIELDS = new ConcurrentHashMap<>();
    private static final Set<String> TASK_IDS = ConcurrentHashMap.newKeySet();
    private static final Set<String> REWARD_IDS = ConcurrentHashMap.newKeySet();

    private QuestTypeFields() {
    }

    /** Records a registered task type id, so the editor offers it. {@link QuestTreeApi} calls this after a handler is registered. */
    public static void registerTask(String id) {
        if (id != null && !id.isBlank()) {
            TASK_IDS.add(id);
        }
    }

    public static void registerReward(String id) {
        if (id != null && !id.isBlank()) {
            REWARD_IDS.add(id);
        }
    }

    /** Forgets a task type id and its declaration, for a registration that was removed. */
    public static void unregisterTask(String id) {
        if (id != null) {
            TASK_IDS.remove(id);
            TASK_FIELDS.remove(id);
        }
    }

    public static void unregisterReward(String id) {
        if (id != null) {
            REWARD_IDS.remove(id);
            REWARD_FIELDS.remove(id);
        }
    }

    /** Stores the task type's field declaration; an empty list drops it (the editor then shows the raw JSON row). */
    public static void describeTask(String id, List<QuestField> fields) {
        if (id == null || id.isBlank()) {
            return;
        }
        if (fields == null || fields.isEmpty()) {
            TASK_FIELDS.remove(id);
        } else {
            TASK_FIELDS.put(id, List.copyOf(fields));
        }
    }

    public static void describeReward(String id, List<QuestField> fields) {
        if (id == null || id.isBlank()) {
            return;
        }
        if (fields == null || fields.isEmpty()) {
            REWARD_FIELDS.remove(id);
        } else {
            REWARD_FIELDS.put(id, List.copyOf(fields));
        }
    }

    /** The registered task type ids, sorted; the editor offers each one beside its built-in types. */
    public static List<String> taskTypes() {
        return sorted(TASK_IDS);
    }

    public static List<String> rewardTypes() {
        return sorted(REWARD_IDS);
    }

    public static List<QuestField> taskFields(String id) {
        return id == null ? List.of() : TASK_FIELDS.getOrDefault(id, List.of());
    }

    public static List<QuestField> rewardFields(String id) {
        return id == null ? List.of() : REWARD_FIELDS.getOrDefault(id, List.of());
    }

    private static List<String> sorted(Set<String> ids) {
        List<String> out = new ArrayList<>(ids);
        Collections.sort(out);
        return out;
    }
}
