package com.kuronami.questtree.api;

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
        String key = key(id);
        return key != null && TASKS.containsKey(key);
    }

    public static boolean rewardTypeRegistered(String id) {
        String key = key(id);
        return key != null && REWARDS.containsKey(key);
    }

    public static boolean conditionTypeRegistered(String id) {
        String key = key(id);
        return key != null && CONDITIONS.containsKey(key);
    }

    /** The registered task type, or null for a built-in type or one whose addon is absent. */
    public static QuestTaskType taskType(String id) {
        String key = key(id);
        return key == null ? null : TASKS.get(key);
    }

    public static QuestRewardType rewardType(String id) {
        String key = key(id);
        return key == null ? null : REWARDS.get(key);
    }

    /** The registered condition type, or null for a built-in kind or one whose addon is absent. */
    public static QuestConditionType conditionType(String id) {
        String key = key(id);
        return key == null ? null : CONDITIONS.get(key);
    }

    /**
     * Opens the book for one player on a quest or chapter (2026-10-22, G3). The target is a quest key
     * ({@code <namespace>:<chapter>/<node>}) or a chapter id ({@code <namespace>:<path>}); the player's client applies
     * its own hidden and locked rules, so a locked chapter opens at the chapter list with its hint and a hidden quest
     * stays hidden. Returns false when the id is not in the loaded book.
     * <pre>{@code
     * QuestTreeApi.open(player, "example:basics/start");
     * QuestTreeApi.open(player, "example:basics");
     * }</pre>
     */
    public static boolean open(ServerPlayer player, String target) {
        return player != null && com.kuronami.questtree.server.GiverService.open(player, target);
    }

    /**
     * Whether every entry in {@code data.conditions} passes; a missing list means yes. This is the public face of the
     * one gauge in {@code ProgressService}: built-in kinds and registered addon kinds are judged together.
     */
    public static boolean conditionsMet(ServerPlayer player, ProgressModel.Node node, JsonObject data, boolean task) {
        return com.kuronami.questtree.server.ProgressService.conditionsMet(
                player, com.kuronami.questtree.server.ProgressService.progress(player), node, data, task);
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

    /**
     * The registry key for a script's id, or null when it is not a resource location. Mirrors
     * {@link ResourceLocation#tryParse(String)} so the common tests stay free of game classes.
     */
    private static String key(String id) {
        if (id == null) {
            return null;
        }
        int colon = id.indexOf(':');
        if (colon >= 0) {
            String path = id.substring(colon + 1);
            if (!validPath(path)) {
                return null;
            }
            if (colon == 0) {
                return "minecraft:" + path;
            }
            String namespace = id.substring(0, colon);
            return validNamespace(namespace) ? namespace + ":" + path : null;
        }
        return validPath(id) ? "minecraft:" + id : null;
    }

    private static boolean validNamespace(String namespace) {
        for (int i = 0; i < namespace.length(); i++) {
            char c = namespace.charAt(i);
            if (c != '_' && c != '-' && c != '.' && (c < 'a' || c > 'z') && (c < '0' || c > '9')) {
                return false;
            }
        }
        return true;
    }

    private static boolean validPath(String path) {
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c != '_' && c != '-' && c != '/' && c != '.' && (c < 'a' || c > 'z') && (c < '0' || c > '9')) {
                return false;
            }
        }
        return true;
    }
}
