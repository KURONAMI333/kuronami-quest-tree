package com.kuronami.questtree.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * FTB Quests' {@code cleanup_stale_translations} for KQT's language files (2026-10-22). FTB Quests decodes a numeric
 * object id out of each key; KQT names its objects, so the same idea is applied to names:
 * <ul>
 *   <li>a key that the loaded book still uses is never stale;</li>
 *   <li>a chapter, node, group or table key whose object is gone is stale;</li>
 *   <li>anything else (a task-text override, an unknown shape) is left alone, because deleting it would be a guess.</li>
 * </ul>
 * Only namespaces still present in the book are judged: when a whole namespace is gone its keys cannot be split into
 * namespace and name, so they stay. Reads and writes are plain JSON, so the rule is testable without the game.
 */
public final class TranslationCleanup {

    private static final String KEY_PREFIX = "progression.";

    private TranslationCleanup() {
    }

    /** Every key the current book reads, directly or through a string it writes. */
    public static Set<String> used(JsonObject bundle) {
        Set<String> used = new LinkedHashSet<>();
        for (Map.Entry<String, JsonElement> e : obj(bundle, "chapters").entrySet()) {
            if (!e.getValue().isJsonObject()) {
                continue;
            }
            String[] id = splitId(e.getKey());
            if (id == null) {
                continue;
            }
            String base = KEY_PREFIX + id[0] + "." + id[1];
            used.add(base + ".title");
            used.add(base + ".subtitle");
            for (String node : obj(e.getValue().getAsJsonObject(), "nodes").keySet()) {
                String key = base + "." + node;
                used.add(key + ".title");
                used.add(key + ".subtitle");
                used.add(key + ".desc");
            }
        }
        for (JsonElement e : arr(bundle, "groups")) {
            if (e.isJsonObject()) {
                String id = str(e.getAsJsonObject(), "id");
                int colon = id.indexOf(':');
                if (colon > 0) {
                    used.add(KEY_PREFIX + id.substring(0, colon) + ".group." + id.substring(colon + 1) + ".title");
                }
            }
        }
        for (String id : obj(bundle, "reward_tables").keySet()) {
            String[] split = splitId(id);
            if (split != null) {
                used.add(KEY_PREFIX + split[0] + ".table." + split[1] + ".title");
            }
        }
        // a task's text is a language key when it has one: `<text>.title` (DATA_MODEL.md "Translations")
        for (Map.Entry<String, JsonElement> c : obj(bundle, "chapters").entrySet()) {
            if (!c.getValue().isJsonObject()) {
                continue;
            }
            for (Map.Entry<String, JsonElement> n : obj(c.getValue().getAsJsonObject(), "nodes").entrySet()) {
                if (n.getValue().isJsonObject()) {
                    collectTaskText(arr(n.getValue().getAsJsonObject(), "tasks"), used);
                    collectTaskText(arr(n.getValue().getAsJsonObject(), "rewards"), used);
                }
            }
        }
        // decorations and anything else that writes a key verbatim
        collectStrings(bundle, used);
        return used;
    }

    /** The keys in one locale that name an object the book no longer has. */
    public static List<String> stale(JsonObject bundle, JsonObject locale) {
        Set<String> used = used(bundle);
        Set<String> namespaces = namespaces(bundle);
        Set<String> chapterPaths = new HashSet<>();
        Set<String> groups = new HashSet<>();
        Set<String> tablePaths = new HashSet<>();
        for (String id : obj(bundle, "chapters").keySet()) {
            String[] split = splitId(id);
            if (split != null) {
                chapterPaths.add(split[1]);
            }
        }
        for (JsonElement e : arr(bundle, "groups")) {
            if (e.isJsonObject()) {
                String id = str(e.getAsJsonObject(), "id");
                int colon = id.indexOf(':');
                if (colon > 0) {
                    groups.add(id.substring(colon + 1));
                }
            }
        }
        for (String id : obj(bundle, "reward_tables").keySet()) {
            String[] split = splitId(id);
            if (split != null) {
                tablePaths.add(split[1]);
            }
        }

        List<String> stale = new ArrayList<>();
        for (String key : locale.keySet()) {
            if (!key.startsWith(KEY_PREFIX) || used.contains(key)) {
                continue;
            }
            String ns = namespaceOf(key, namespaces);
            if (ns == null) {
                continue;
            }
            String rest = key.substring((KEY_PREFIX + ns + ".").length());
            if (rest.startsWith("group.") && rest.endsWith(".title")) {
                if (!groups.contains(rest.substring("group.".length(), rest.length() - ".title".length()))) {
                    stale.add(key);
                }
                continue;
            }
            if (rest.startsWith("table.") && rest.endsWith(".title")) {
                if (!tablePaths.contains(rest.substring("table.".length(), rest.length() - ".title".length()))) {
                    stale.add(key);
                }
                continue;
            }
            String suffix = suffixOf(rest);
            if (suffix == null) {
                continue;
            }
            String middle = rest.substring(0, rest.length() - suffix.length());
            if (chapterPaths.contains(middle)) {
                continue;
            }
            // node ids have no dots, so the last segment is the node and the rest is the chapter path
            int cut = middle.lastIndexOf('.');
            String node = cut > 0 ? middle.substring(cut + 1) : "";
            String chapter = cut > 0 ? middle.substring(0, cut) : "";
            boolean nodeExists = !node.isEmpty() && chapterPaths.contains(chapter)
                    && nodeExists(bundle, ns + ":" + chapter, node);
            if (!nodeExists) {
                stale.add(key);
            }
        }
        return stale;
    }

    /** Removes exactly the given keys and reports how many were there. */
    public static int remove(JsonObject locale, List<String> keys) {
        int removed = 0;
        for (String key : keys) {
            if (locale.remove(key) != null) {
                removed++;
            }
        }
        return removed;
    }

    private static boolean nodeExists(JsonObject bundle, String chapterId, String node) {
        JsonObject chapter = obj(obj(bundle, "chapters"), chapterId);
        return chapter.has("nodes") && chapter.get("nodes").isJsonObject()
                && chapter.getAsJsonObject("nodes").has(node);
    }

    private static Set<String> namespaces(JsonObject bundle) {
        Set<String> out = new HashSet<>();
        for (String id : obj(bundle, "chapters").keySet()) {
            String[] split = splitId(id);
            if (split != null) {
                out.add(split[0]);
            }
        }
        for (String id : obj(bundle, "reward_tables").keySet()) {
            String[] split = splitId(id);
            if (split != null) {
                out.add(split[0]);
            }
        }
        return out;
    }

    /** The longest known namespace this key starts with, so a namespace containing a dot still wins. */
    private static String namespaceOf(String key, Set<String> namespaces) {
        String best = null;
        for (String ns : namespaces) {
            if (key.startsWith(KEY_PREFIX + ns + ".") && (best == null || ns.length() > best.length())) {
                best = ns;
            }
        }
        return best;
    }

    private static String suffixOf(String rest) {
        for (String suffix : new String[]{".subtitle", ".title", ".desc"}) {
            if (rest.endsWith(suffix)) {
                return suffix;
            }
        }
        return null;
    }

    private static void collectTaskText(JsonArray tasks, Set<String> used) {
        for (JsonElement e : tasks) {
            if (e.isJsonObject()) {
                String text = str(e.getAsJsonObject(), "text");
                if (!text.isEmpty()) {
                    used.add(text + ".title");
                }
            }
        }
    }

    private static void collectStrings(JsonElement e, Set<String> used) {
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            String s = e.getAsString();
            if (s.startsWith(KEY_PREFIX)) {
                used.add(s);
            }
        } else if (e.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : e.getAsJsonObject().entrySet()) {
                collectStrings(entry.getValue(), used);
            }
        } else if (e.isJsonArray()) {
            for (JsonElement child : e.getAsJsonArray()) {
                collectStrings(child, used);
            }
        }
    }

    private static String[] splitId(String id) {
        int colon = id.indexOf(':');
        return colon > 0 && colon < id.length() - 1 ? new String[]{id.substring(0, colon), id.substring(colon + 1)} : null;
    }

    private static JsonObject obj(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : new JsonObject();
    }

    private static JsonArray arr(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : "";
    }
}
