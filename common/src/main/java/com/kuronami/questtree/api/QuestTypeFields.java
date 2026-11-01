package com.kuronami.questtree.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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

    /** What the connected server told this client it has registered (the editor bundle's {@code addon_types}). */
    private static volatile Map<String, List<QuestField>> remoteTaskFields = Map.of();
    private static volatile Map<String, List<QuestField>> remoteRewardFields = Map.of();

    /** Bumped by every register, unregister and describe, so a cache built from this registry can tell it went stale. */
    private static final AtomicLong VERSION = new AtomicLong();

    private QuestTypeFields() {
    }

    /** A number that changes whenever the task or reward registrations or their declarations change. */
    public static long version() {
        return VERSION.get();
    }

    /** Records a registered task type id, so the editor offers it. {@link QuestTreeApi} calls this after a handler is registered. */
    public static void registerTask(String id) {
        if (id != null && !id.isBlank()) {
            TASK_IDS.add(id);
            VERSION.incrementAndGet();
        }
    }

    public static void registerReward(String id) {
        if (id != null && !id.isBlank()) {
            REWARD_IDS.add(id);
            VERSION.incrementAndGet();
        }
    }

    /** Forgets a task type id and its declaration, for a registration that was removed. */
    public static void unregisterTask(String id) {
        if (id != null) {
            TASK_IDS.remove(id);
            TASK_FIELDS.remove(id);
            VERSION.incrementAndGet();
        }
    }

    public static void unregisterReward(String id) {
        if (id != null) {
            REWARD_IDS.remove(id);
            REWARD_FIELDS.remove(id);
            VERSION.incrementAndGet();
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
        VERSION.incrementAndGet();
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
        VERSION.incrementAndGet();
    }

    /**
     * The task type ids the editor offers: those registered in this JVM and those the connected server reported. A
     * server-only add-on, or a type a server script registered, is listed on a client that does not have it.
     */
    public static List<String> taskTypes() {
        return sorted(union(TASK_IDS, remoteTaskFields.keySet()));
    }

    public static List<String> rewardTypes() {
        return sorted(union(REWARD_IDS, remoteRewardFields.keySet()));
    }

    /** Only the ids registered in this JVM; what a server reports to its editors. */
    public static List<String> localTaskTypes() {
        return sorted(TASK_IDS);
    }

    public static List<String> localRewardTypes() {
        return sorted(REWARD_IDS);
    }

    /** The declared fields: this JVM's own declaration wins, else the one the server reported. */
    public static List<QuestField> taskFields(String id) {
        return id == null ? List.of() : TASK_FIELDS.containsKey(id) ? TASK_FIELDS.get(id)
                : remoteTaskFields.getOrDefault(id, List.of());
    }

    public static List<QuestField> rewardFields(String id) {
        return id == null ? List.of() : REWARD_FIELDS.containsKey(id) ? REWARD_FIELDS.get(id)
                : remoteRewardFields.getOrDefault(id, List.of());
    }

    public static List<QuestField> localTaskFields(String id) {
        return id == null ? List.of() : TASK_FIELDS.getOrDefault(id, List.of());
    }

    public static List<QuestField> localRewardFields(String id) {
        return id == null ? List.of() : REWARD_FIELDS.getOrDefault(id, List.of());
    }

    /**
     * This JVM's registrations as the editor bundle carries them: {@code {"tasks": {id: [field...]}, "rewards": ...}},
     * an id with no declaration holding an empty list. Plain data, so a test round-trips it without the game.
     */
    public static JsonObject toJson() {
        JsonObject out = new JsonObject();
        out.add("tasks", sectionJson(TASK_IDS, TASK_FIELDS));
        out.add("rewards", sectionJson(REWARD_IDS, REWARD_FIELDS));
        return out;
    }

    /**
     * Replaces what the connected server reported with {@code addon_types} from its bundle; null or malformed input
     * clears it. A bad entry is skipped alone.
     */
    public static void setRemote(JsonObject addonTypes) {
        remoteTaskFields = readSection(addonTypes, "tasks");
        remoteRewardFields = readSection(addonTypes, "rewards");
    }

    /**
     * Reads a script's JSON array into fields. A script cannot build the record itself, so a bridge passes the JSON
     * text. Each entry is read on its own: one that is not an object, has no path, or has a member of the wrong shape
     * is logged once and skipped, and the rest are kept. An unknown kind is shown as text. Text that is not a JSON
     * array gives an empty list. {@code warn} receives one line per problem (the game log, from the script bridge).
     */
    public static List<QuestField> parseFields(String json, java.util.function.Consumer<String> warn) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (RuntimeException ex) {
            warn.accept("Could not read quest editor fields '" + json + "': " + ex.getMessage());
            return List.of();
        }
        if (!parsed.isJsonArray()) {
            warn.accept("Quest editor fields must be a JSON array: '" + json + "'");
            return List.of();
        }
        List<QuestField> out = new ArrayList<>();
        for (JsonElement element : parsed.getAsJsonArray()) {
            try {
                out.add(parseField(element, warn));
            } catch (RuntimeException ex) {
                warn.accept("Skipping a quest editor field that could not be read (" + ex.getMessage() + "): " + element);
            }
        }
        return out;
    }

    private static QuestField parseField(JsonElement element, java.util.function.Consumer<String> warn) {
        JsonObject o = element.getAsJsonObject();
        String path = o.has("path") ? o.get("path").getAsString() : "";
        String label = o.has("label") ? o.get("label").getAsString() : path;
        String kindName = o.has("kind") ? o.get("kind").getAsString() : "text";
        List<String> options = new ArrayList<>();
        if (o.has("options") && o.get("options").isJsonArray()) {
            for (JsonElement option : o.getAsJsonArray("options")) {
                if (option.isJsonPrimitive()) {
                    options.add(option.getAsString());
                }
            }
        }
        QuestField.Kind kind;
        try {
            kind = QuestField.Kind.valueOf(kindName.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            warn.accept("Unknown quest editor field kind '" + kindName + "' for '" + path + "'; shown as text");
            kind = QuestField.Kind.TEXT;
        }
        return new QuestField(path, kind, label, options);
    }

    private static JsonObject sectionJson(Set<String> ids, Map<String, List<QuestField>> fields) {
        JsonObject section = new JsonObject();
        for (String id : sorted(ids)) {
            JsonArray list = new JsonArray();
            for (QuestField f : fields.getOrDefault(id, List.of())) {
                JsonObject o = new JsonObject();
                o.addProperty("path", f.path());
                o.addProperty("kind", f.kind().name());
                o.addProperty("label", f.label());
                JsonArray options = new JsonArray();
                f.options().forEach(options::add);
                o.add("options", options);
                list.add(o);
            }
            section.add(id, list);
        }
        return section;
    }

    private static Map<String, List<QuestField>> readSection(JsonObject root, String key) {
        if (root == null || !root.has(key) || !root.get(key).isJsonObject()) {
            return Map.of();
        }
        Map<String, List<QuestField>> out = new java.util.HashMap<>();
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject(key).entrySet()) {
            List<QuestField> fields = new ArrayList<>();
            if (e.getValue().isJsonArray()) {
                for (JsonElement fe : e.getValue().getAsJsonArray()) {
                    QuestField f = readField(fe);
                    if (f != null) {
                        fields.add(f);
                    }
                }
            }
            out.put(e.getKey(), List.copyOf(fields));
        }
        return Map.copyOf(out);
    }

    private static QuestField readField(JsonElement e) {
        try {
            JsonObject o = e.getAsJsonObject();
            List<String> options = new ArrayList<>();
            if (o.has("options") && o.get("options").isJsonArray()) {
                for (JsonElement opt : o.getAsJsonArray("options")) {
                    options.add(opt.getAsString());
                }
            }
            return new QuestField(o.get("path").getAsString(), QuestField.Kind.valueOf(o.get("kind").getAsString()),
                    o.has("label") ? o.get("label").getAsString() : "", options);
        } catch (RuntimeException ex) {
            // a field from a newer server whose kind this client lacks is dropped alone; its type keeps the JSON row
            return null;
        }
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new java.util.HashSet<>(a);
        out.addAll(b);
        return out;
    }

    private static List<String> sorted(java.util.Collection<String> ids) {
        List<String> out = new ArrayList<>(ids);
        Collections.sort(out);
        return out;
    }
}
