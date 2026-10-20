package com.kuronami.questtree.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kuronami.questtree.importer.ImportOutput;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The data half of {@code /quest_tree export}: picks one namespace out of the validated bundle (2026-10-20).
 * <p>
 * The namespace is matched as the bundle spells it. Sanitising it before matching turned {@code my-pack} and
 * {@code my.pack} into {@code my_pack}, which then matched no chapter at all. Minecraft's own namespace rule is
 * {@code [a-z0-9_.-]} (minus the two path segments that would escape a folder), and that is what a data pack folder
 * can hold, so only the display folder name is cleaned up.
 */
final class ProgressExport {

    static final String PACK_PREFIX = "quest_tree_export_";

    private ProgressExport() {
    }

    /** The namespace as Minecraft spells it, lower-cased; null when it cannot be a resource namespace. */
    static String validNamespace(String requested) {
        if (requested == null || requested.isEmpty()) {
            return null;
        }
        String ns = requested.toLowerCase(Locale.ROOT);
        if (ns.equals(".") || ns.equals("..")) {
            return null;
        }
        return ns.chars().allMatch(c -> c == '_' || c == '-' || c == '.'
                || c >= 'a' && c <= 'z' || c >= '0' && c <= '9') ? ns : null;
    }

    /** The datapack folder for a namespace; only this name is sanitised, never the namespace used for matching. */
    static String packFolder(String namespace) {
        return PACK_PREFIX + namespace.replaceAll("[^a-z0-9_.-]", "_");
    }

    /**
     * Collects the namespace's chapters, reward tables, groups, translations and dropped-node count. The validated
     * bundle has already lost the nodes the validator marked as ERROR; {@code dropped} says how many belonged here so
     * the command can report them instead of dropping them silently.
     */
    static ImportOutput collect(String namespace, JsonObject bundle, Map<String, JsonObject> langs, Set<String> dropped) {
        ImportOutput out = new ImportOutput(namespace);
        String prefix = namespace + ":";
        JsonObject chapters = bundle.has("chapters") && bundle.get("chapters").isJsonObject()
                ? bundle.getAsJsonObject("chapters") : new JsonObject();
        int nodes = 0;
        for (Map.Entry<String, JsonElement> e : chapters.entrySet()) {
            if (!e.getKey().startsWith(prefix) || !e.getValue().isJsonObject()) {
                continue;
            }
            JsonObject chapter = e.getValue().getAsJsonObject().deepCopy();
            out.chapters.put(e.getKey().substring(prefix.length()), chapter);
            JsonElement chapterNodes = chapter.get("nodes");
            if (chapterNodes != null && chapterNodes.isJsonObject()) {
                nodes += chapterNodes.getAsJsonObject().size();
            }
        }
        JsonObject tables = bundle.has("reward_tables") && bundle.get("reward_tables").isJsonObject()
                ? bundle.getAsJsonObject("reward_tables") : new JsonObject();
        for (Map.Entry<String, JsonElement> e : tables.entrySet()) {
            if (e.getKey().startsWith(prefix) && e.getValue().isJsonObject()) {
                out.tables.put(e.getKey().substring(prefix.length()), e.getValue().getAsJsonObject().deepCopy());
            }
        }
        JsonElement groups = bundle.get("groups");
        if (groups != null && groups.isJsonArray()) {
            for (JsonElement el : groups.getAsJsonArray()) {
                if (el.isJsonObject() && el.getAsJsonObject().has("namespace")
                        && namespace.equals(el.getAsJsonObject().get("namespace").getAsString())) {
                    out.groups.add(el.deepCopy());
                }
            }
        }
        int droppedHere = 0;
        for (String key : dropped) {
            if (key.startsWith(prefix)) {
                droppedHere++;
            }
        }
        // translations carry the namespace inside their key ("progression.<ns>...."); copy only this namespace's text
        int langKeys = 0;
        for (Map.Entry<String, JsonObject> locale : langs.entrySet()) {
            JsonObject selected = new JsonObject();
            for (Map.Entry<String, JsonElement> e : locale.getValue().entrySet()) {
                if (belongsTo(e.getKey(), namespace)) {
                    selected.add(e.getKey(), e.getValue().deepCopy());
                }
            }
            if (selected.size() > 0) {
                out.lang.put(locale.getKey(), selected);
                langKeys += selected.size();
            }
        }
        out.count("chapters", out.chapters.size());
        out.count("nodes", nodes);
        out.count("dropped_nodes", droppedHere);
        out.count("lang", langKeys);
        return out;
    }

    /** {@code progression.<ns>.} is how the readers and importers build every translation key. */
    private static boolean belongsTo(String key, String namespace) {
        return key.startsWith("progression." + namespace + ".")
                || key.startsWith(namespace + ".");
    }

    /** Writes the pack, refusing to replace a folder that is already there unless the caller passed {@code force}. */
    static void write(ImportOutput out, Path root, boolean force) throws IOException {
        if (Files.exists(root) && !force) {
            throw new FileAlreadyExistsException(root.toString());
        }
        out.writeTo(root);
    }
}
