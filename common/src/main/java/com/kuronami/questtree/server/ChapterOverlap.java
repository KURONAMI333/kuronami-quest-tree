package com.kuronami.questtree.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Finds the same book loaded twice under different namespaces (a kubejs data pack and a later
 * {@code /quest_tree import} of the same FTB Quests book). Chapter ids are per namespace, so the validator sees two
 * unrelated chapters and the player sees every quest twice.
 * <p>
 * Two chapters are twins when they carry the same {@code ftbquests_id} (written by the importer), or when their file
 * names are the same and at least one quest is shared: older imports and hand-made copies have no source id, but an
 * import names the chapter file after the FTB chapter file and the quest after the FTB quest id. A shared file name
 * with no shared quest (two packs that both have an "intro" chapter) is not an overlap.
 */
public final class ChapterOverlap {

    /** Key of the source id the importers write on a chapter and on a node. */
    public static final String SOURCE_ID = "ftbquests_id";

    /**
     * @param namespace the namespace whose chapters are counted
     * @param other     the namespace that holds their twins
     * @param chapters  how many chapters of {@code namespace} have a twin in {@code other}
     * @param quests    how many nodes of those chapters share a source id or node name with a node of the twin
     */
    public record Overlap(String namespace, String other, int chapters, int quests) {
    }

    private ChapterOverlap() {
    }

    /**
     * Both directions are returned ({@code a} against {@code b} and {@code b} against {@code a}), each counted from
     * its own side.
     *
     * @param chapters chapter objects keyed {@code <namespace>:<name>}
     * @param only     when not null, only overlaps that involve this namespace are returned
     */
    public static List<Overlap> find(Map<String, JsonObject> chapters, String only) {
        Map<String, List<String>> bySource = new HashMap<>();
        Map<String, List<String>> byName = new HashMap<>();
        for (Map.Entry<String, JsonObject> e : chapters.entrySet()) {
            String source = sourceId(e.getValue());
            if (source != null) {
                bySource.computeIfAbsent(source, k -> new ArrayList<>()).add(e.getKey());
            }
            byName.computeIfAbsent(local(e.getKey()), k -> new ArrayList<>()).add(e.getKey());
        }
        Map<String, Integer> chapterCount = new TreeMap<>();
        Map<String, Integer> questCount = new TreeMap<>();
        for (Map.Entry<String, JsonObject> e : chapters.entrySet()) {
            String key = e.getKey();
            String ns = namespace(key);
            // other namespace -> its twin chapters of this one
            Map<String, Set<String>> twins = new TreeMap<>();
            Map<String, Set<String>> bySourceTwins = new TreeMap<>();
            String source = sourceId(e.getValue());
            if (source != null) {
                addTwins(bySourceTwins, bySource.get(source), ns);
                addTwins(twins, bySource.get(source), ns);
            }
            addTwins(twins, byName.get(local(key)), ns);
            for (Map.Entry<String, Set<String>> t : twins.entrySet()) {
                String other = t.getKey();
                if (only != null && !only.equals(ns) && !only.equals(other)) {
                    continue;
                }
                int shared = sharedNodes(e.getValue(), t.getValue(), chapters);
                // a file name alone is common ("intro", "main"): it counts only when a quest is shared as well
                if (shared == 0 && !bySourceTwins.containsKey(other)) {
                    continue;
                }
                String pair = ns + "|" + other;
                chapterCount.merge(pair, 1, Integer::sum);
                questCount.merge(pair, shared, Integer::sum);
            }
        }
        List<Overlap> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : chapterCount.entrySet()) {
            int bar = e.getKey().indexOf('|');
            out.add(new Overlap(e.getKey().substring(0, bar), e.getKey().substring(bar + 1), e.getValue(),
                    questCount.getOrDefault(e.getKey(), 0)));
        }
        return out;
    }

    private static void addTwins(Map<String, Set<String>> into, List<String> candidates, String ownNamespace) {
        if (candidates == null) {
            return;
        }
        for (String c : candidates) {
            String ns = namespace(c);
            if (!ns.equals(ownNamespace)) {
                into.computeIfAbsent(ns, k -> new HashSet<>()).add(c);
            }
        }
    }

    /** Nodes of {@code a} whose source id (or, without one, whose node name) is also in one of the twin chapters. */
    private static int sharedNodes(JsonObject a, Set<String> twinKeys, Map<String, JsonObject> chapters) {
        Set<String> theirs = new HashSet<>();
        for (String key : twinKeys) {
            JsonObject twin = chapters.get(key);
            if (twin == null) {
                continue;
            }
            for (Map.Entry<String, JsonElement> n : nodes(twin).entrySet()) {
                theirs.addAll(identities(n.getKey(), n.getValue()));
            }
        }
        int shared = 0;
        for (Map.Entry<String, JsonElement> n : nodes(a).entrySet()) {
            for (String id : identities(n.getKey(), n.getValue())) {
                if (theirs.contains(id)) {
                    shared++;
                    break;
                }
            }
        }
        return shared;
    }

    private static List<String> identities(String name, JsonElement node) {
        List<String> ids = new ArrayList<>(2);
        if (node.isJsonObject()) {
            String id = sourceId(node.getAsJsonObject());
            if (id != null) {
                ids.add(id);
            }
        }
        ids.add(name.toLowerCase(Locale.ROOT));
        return ids;
    }

    private static JsonObject nodes(JsonObject chapter) {
        JsonElement n = chapter.get("nodes");
        return n != null && n.isJsonObject() ? n.getAsJsonObject() : new JsonObject();
    }

    /** The lower-case source id written on a chapter or node, or null. */
    static String sourceId(JsonObject o) {
        JsonElement v = o.get(SOURCE_ID);
        if (v != null && v.isJsonPrimitive() && !v.getAsString().isBlank()) {
            return v.getAsString().toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private static String namespace(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? "" : id.substring(0, colon);
    }

    private static String local(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }

    /** The chapter objects of a bundle's {@code chapters}, skipping anything that is not an object. */
    public static Map<String, JsonObject> chaptersOf(JsonObject bundle) {
        Map<String, JsonObject> out = new TreeMap<>();
        JsonElement c = bundle == null ? null : bundle.get("chapters");
        if (c != null && c.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : c.getAsJsonObject().entrySet()) {
                if (e.getValue().isJsonObject()) {
                    out.put(e.getKey(), e.getValue().getAsJsonObject());
                }
            }
        }
        return out;
    }
}
