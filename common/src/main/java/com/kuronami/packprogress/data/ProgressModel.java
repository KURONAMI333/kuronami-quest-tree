package com.kuronami.packprogress.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Side-independent model of the progression data (DATA_MODEL.md), used by the server for progress checks and
 * rewards. Holds ids, links and raw JSON for tasks/rewards; resolving items and text is left to the caller so this
 * class has no client or registry dependency (and stays testable without the game).
 * <p>
 * Node keys are global: {@code <chapter id>/<node id>} where the chapter id is {@code <namespace>:<path>}.
 */
public final class ProgressModel {

    public final Map<String, Chapter> chapters = new LinkedHashMap<>();
    /** Every node by global key. */
    public final Map<String, Node> nodes = new LinkedHashMap<>();
    public final Map<String, JsonObject> rewardTables = new LinkedHashMap<>();

    public static ProgressModel of(JsonObject bundle) {
        ProgressModel m = new ProgressModel();
        JsonObject chapters = obj(bundle, "chapters");
        for (Map.Entry<String, JsonElement> e : chapters.entrySet()) {
            if (e.getValue().isJsonObject()) {
                Chapter c = new Chapter(e.getKey(), e.getValue().getAsJsonObject());
                m.chapters.put(c.id, c);
                c.nodes.values().forEach(n -> m.nodes.put(n.key, n));
            }
        }
        for (Map.Entry<String, JsonElement> e : obj(bundle, "reward_tables").entrySet()) {
            if (e.getValue().isJsonObject()) {
                m.rewardTables.put(e.getKey(), e.getValue().getAsJsonObject());
            }
        }
        // resolve requirement references: "node" (same chapter) or "chapter/node" (another chapter of the same namespace)
        for (Node n : m.nodes.values()) {
            for (String r : n.requiresRaw) {
                String key = r.contains("/") ? n.chapter.namespace + ":" + r : n.chapter.id + "/" + r;
                if (m.nodes.containsKey(key)) {
                    n.requires.add(key);
                }
            }
            for (String x : n.exclusiveWithRaw) {
                String key = n.chapter.id + "/" + x;
                if (m.nodes.containsKey(key)) {
                    n.exclusiveWith.add(key);
                }
            }
        }
        // exclusive groups ("exclusive": {"group", "max"}) — nodes sharing a group within a chapter
        for (Chapter c : m.chapters.values()) {
            Map<String, List<Node>> groups = new LinkedHashMap<>();
            for (Node n : c.nodes.values()) {
                if (n.exclusiveGroup != null) {
                    groups.computeIfAbsent(n.exclusiveGroup, k -> new ArrayList<>()).add(n);
                }
            }
            c.exclusiveGroups.putAll(groups);
        }
        return m;
    }

    public static final class Chapter {
        public final String id;
        public final String namespace;
        public final String path;
        public final Map<String, Node> nodes = new LinkedHashMap<>();
        /** Skill chapters have a points block (experience sources, curve, limits). */
        public final JsonObject points;
        public final Map<String, List<Node>> exclusiveGroups = new LinkedHashMap<>();

        Chapter(String id, JsonObject o) {
            this.id = id;
            int colon = id.indexOf(':');
            this.namespace = id.substring(0, colon);
            this.path = id.substring(colon + 1);
            this.points = o.has("points") && o.get("points").isJsonObject() ? o.getAsJsonObject("points") : null;
            for (Map.Entry<String, JsonElement> e : obj(o, "nodes").entrySet()) {
                if (e.getValue().isJsonObject()) {
                    nodes.put(e.getKey(), new Node(this, e.getKey(), e.getValue().getAsJsonObject()));
                }
            }
        }

        public boolean isSkills() {
            return points != null;
        }
    }

    public static final class Node {
        public final Chapter chapter;
        public final String id;
        public final String key;
        public final JsonObject json;
        public final List<JsonObject> tasks;
        public final List<JsonObject> rewards;
        public final int cost;
        public final boolean optional;
        public final boolean root;
        /** "all" (default), "any", "all_started", "any_started" */
        public final String requireMode;
        public final int requireMin;
        public final int tasksRequired;
        public final String exclusiveGroup;
        public final int exclusiveMax;
        public final int ranks;
        final List<String> requiresRaw = new ArrayList<>();
        final List<String> exclusiveWithRaw = new ArrayList<>();
        public final List<String> requires = new ArrayList<>();
        public final List<String> exclusiveWith = new ArrayList<>();

        Node(Chapter chapter, String id, JsonObject o) {
            this.chapter = chapter;
            this.id = id;
            this.key = chapter.id + "/" + id;
            this.json = o;
            List<JsonObject> t = new ArrayList<>();
            for (JsonElement e : arr(o, "tasks")) {
                if (e.isJsonObject()) {
                    t.add(e.getAsJsonObject());
                }
            }
            this.tasks = Collections.unmodifiableList(t);
            List<JsonObject> r = new ArrayList<>();
            for (JsonElement e : arr(o, "rewards")) {
                if (e.isJsonObject()) {
                    r.add(e.getAsJsonObject());
                }
            }
            this.rewards = Collections.unmodifiableList(r);
            this.cost = intOf(o, "cost", 0);
            this.optional = boolOf(o, "optional");
            this.root = boolOf(o, "root");
            JsonObject req = obj(o, "requires");
            for (JsonElement e : arr(req, "nodes")) {
                requiresRaw.add(e.getAsString());
            }
            for (JsonElement e : arr(o, "exclusive_with")) {
                exclusiveWithRaw.add(e.getAsString());
            }
            this.requireMode = req.has("mode") ? req.get("mode").getAsString() : "all";
            this.requireMin = intOf(req, "min", 0);
            this.tasksRequired = intOf(o, "tasks_required", 0);
            JsonObject ex = obj(o, "exclusive");
            this.exclusiveGroup = ex.has("group") ? ex.get("group").getAsString() : null;
            this.exclusiveMax = intOf(ex, "max", 1);
            this.ranks = o.has("ranks") && o.get("ranks").isJsonArray() ? o.getAsJsonArray("ranks").size() : 0;
        }
    }

    static JsonObject obj(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : new JsonObject();
    }

    static JsonArray arr(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray();
    }

    static int intOf(JsonObject o, String k, int def) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsJsonPrimitive().isNumber() ? o.get(k).getAsInt() : def;
    }

    public static boolean boolOf(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsJsonPrimitive().isBoolean() && o.get(k).getAsBoolean();
    }
}
