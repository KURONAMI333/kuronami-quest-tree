package com.kuronami.questtree.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kuronami.questtree.Constants;
import com.kuronami.questtree.data.ProgressModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * What another mod talks to: register a task, reward or condition type, listen to progression, and nothing else.
 * <h2>Adding a type</h2>
 * <pre>{@code
 * ResourceLocation id = ResourceLocation.fromNamespaceAndPath("example", "level");
 * QuestTreeApi.registerTaskType(id, context -> context.player().experienceLevel);
 * QuestTreeApi.registerRewardType(id, context -> context.player().giveExperienceLevels(5));
 * QuestTreeApi.registerConditionType(id, context -> context.player().experienceLevel >= 30);
 * }</pre>
 * A pack then writes {@code "type": "example:level"} and the server consults the handler on its tick (tasks) or at
 * claim time (rewards). Java registration replaces a type with the same id, so a reload can update one without a
 * restart; a script's register call never replaces one, so the next script reload cannot take a Java handler with it.
 * <h2>Events</h2>
 * <pre>{@code
 * QuestTreeApi.addListener(event -> {
 *     if (event.kind() == QuestEvent.Kind.COMPLETE) { ... }
 * });
 * }</pre>
 * Listeners run on the server thread once per moment; {@code player} is the member whose action caused it.
 * <p>
 * Nothing here is required at runtime: without an addon the registries stay empty, and the JSON types shipped in
 * DATA_MODEL.md work as before. This class is safe to call from common code on either loader.
 */
public final class QuestTreeApi {

    /** A progression moment. {@code task} is the task index, or -1 for a quest-level event. */
    public record QuestEvent(Kind kind, ServerPlayer player, ProgressModel.Node node, int task) {

        public enum Kind {
            COMPLETE, CLAIM, TASK
        }
    }

    private static final Map<String, QuestTaskType> TASKS = new ConcurrentHashMap<>();
    private static final Map<String, QuestRewardType> REWARDS = new ConcurrentHashMap<>();
    private static final Map<String, QuestConditionType> CONDITIONS = new ConcurrentHashMap<>();
    private static final List<Consumer<QuestEvent>> LISTENERS = new CopyOnWriteArrayList<>();

    private QuestTreeApi() {
    }

    public static void registerTaskType(ResourceLocation id, QuestTaskType type) {
        if (id == null || type == null) {
            Constants.LOG.warn("Task type registration needs an id and a handler (id {}, handler {})", id, type);
            return;
        }
        TASKS.put(key(id), type);
    }

    public static void registerRewardType(ResourceLocation id, QuestRewardType type) {
        if (id == null || type == null) {
            Constants.LOG.warn("Reward type registration needs an id and a handler (id {}, handler {})", id, type);
            return;
        }
        REWARDS.put(key(id), type);
    }

    public static void registerConditionType(ResourceLocation id, QuestConditionType type) {
        if (id == null || type == null) {
            Constants.LOG.warn("Condition type registration needs an id and a handler (id {}, handler {})", id, type);
            return;
        }
        CONDITIONS.put(key(id), type);
    }

    public static void addListener(Consumer<QuestEvent> listener) {
        LISTENERS.add(listener);
    }

    /**
     * String form, for a script bridge. The name carries {@code ById} so a null first argument cannot make the call
     * ambiguous with the {@link ResourceLocation} form. An id that is not a resource location is ignored and logged. A
     * script never replaces an existing type: that would overwrite a Java addon's handler, and the next script reload
     * would remove both. The existing handler is kept and false is returned.
     */
    public static boolean registerTaskTypeById(String id, QuestTaskType type) {
        String key = key(id);
        if (key == null) {
            Constants.LOG.warn("Not a task type id: '{}'", id);
            return false;
        }
        if (type == null) {
            Constants.LOG.warn("Task type '{}' has no handler", key);
            return false;
        }
        if (TASKS.putIfAbsent(key, type) != null) {
            Constants.LOG.warn("Task type '{}' is already registered; the existing handler is kept", key);
            return false;
        }
        return true;
    }

    public static boolean registerRewardTypeById(String id, QuestRewardType type) {
        String key = key(id);
        if (key == null) {
            Constants.LOG.warn("Not a reward type id: '{}'", id);
            return false;
        }
        if (type == null) {
            Constants.LOG.warn("Reward type '{}' has no handler", key);
            return false;
        }
        if (REWARDS.putIfAbsent(key, type) != null) {
            Constants.LOG.warn("Reward type '{}' is already registered; the existing handler is kept", key);
            return false;
        }
        return true;
    }

    public static boolean registerConditionTypeById(String id, QuestConditionType type) {
        String key = key(id);
        if (key == null) {
            Constants.LOG.warn("Not a condition type id: '{}'", id);
            return false;
        }
        if (type == null) {
            Constants.LOG.warn("Condition type '{}' has no handler", key);
            return false;
        }
        if (CONDITIONS.putIfAbsent(key, type) != null) {
            Constants.LOG.warn("Condition type '{}' is already registered; the existing handler is kept", key);
            return false;
        }
        return true;
    }

    /** Removes a type an addon registered, for a script reload. Returns whether one was there. */
    public static boolean unregisterTaskType(String id) {
        String key = key(id);
        return key != null && TASKS.remove(key) != null;
    }

    public static boolean unregisterRewardType(String id) {
        String key = key(id);
        return key != null && REWARDS.remove(key) != null;
    }

    public static boolean unregisterConditionType(String id) {
        String key = key(id);
        return key != null && CONDITIONS.remove(key) != null;
    }

    public static boolean taskTypeRegistered(String id) {
        return TASKS.containsKey(id);
    }

    public static boolean rewardTypeRegistered(String id) {
        return REWARDS.containsKey(id);
    }

    public static boolean conditionTypeRegistered(String id) {
        return CONDITIONS.containsKey(id);
    }

    /** The registered task type, or null for a built-in type or one whose addon is absent. */
    public static QuestTaskType taskType(String id) {
        return TASKS.get(id);
    }

    public static QuestRewardType rewardType(String id) {
        return REWARDS.get(id);
    }

    /** Whether every entry in {@code data.conditions} passes; a missing list means yes. */
    public static boolean conditionsMet(ServerPlayer player, ProgressModel.Node node, JsonObject data, boolean task) {
        if (!data.has("conditions")) {
            return true;
        }
        JsonElement value = data.get("conditions");
        if (!value.isJsonArray()) {
            return false;
        }
        JsonArray conditions = value.getAsJsonArray();
        for (JsonElement entry : conditions) {
            if (!entry.isJsonObject()) {
                return false;
            }
            JsonObject condition = entry.getAsJsonObject();
            String type = condition.has("type") && condition.get("type").isJsonPrimitive()
                    ? condition.get("type").getAsString() : "";
            QuestConditionType handler = CONDITIONS.get(type);
            if (handler == null) {
                return false;
            }
            try {
                if (!handler.test(new QuestConditionType.ConditionContext(player, node, condition, task))) {
                    return false;
                }
            } catch (RuntimeException ex) {
                Constants.LOG.error("Condition '{}' of {} failed: {}", type, node.key, ex.toString());
                return false;
            }
        }
        return true;
    }

    /** Server side: tells the listeners about one moment. */
    public static void fire(QuestEvent.Kind kind, ServerPlayer player, ProgressModel.Node node, int task) {
        QuestEvent event = new QuestEvent(kind, player, node, task);
        for (Consumer<QuestEvent> listener : LISTENERS) {
            try {
                listener.accept(event);
            } catch (RuntimeException ex) {
                Constants.LOG.error("Quest Tree API listener failed: {}", ex.toString());
            }
        }
    }

    private static String key(ResourceLocation id) {
        return id == null ? "" : id.toString();
    }

    /** The registry key for a script's id, or null when it is not a resource location. */
    private static String key(String id) {
        ResourceLocation parsed = id == null ? null : ResourceLocation.tryParse(id);
        return parsed == null ? null : key(parsed);
    }
}
