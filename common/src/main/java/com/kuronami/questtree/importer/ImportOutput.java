package com.kuronami.questtree.importer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Collects converted data and writes it as a data pack ({@code data/<ns>/progression/...}), plus a report of
 * everything that was counted, skipped on purpose, or could not be mapped.
 */
public final class ImportOutput {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public final String namespace;
    public final Map<String, JsonObject> chapters = new LinkedHashMap<>();
    public final Map<String, JsonObject> patches = new LinkedHashMap<>();
    public final Map<String, JsonObject> tables = new LinkedHashMap<>();
    public final JsonArray groups = new JsonArray();
    public final Map<String, JsonObject> lang = new TreeMap<>();
    public final Map<String, Integer> counts = new TreeMap<>();
    public final Map<String, Integer> unmapped = new TreeMap<>();
    public final Map<String, Integer> notes = new TreeMap<>();
    /** Lang prefix of every task and reward by its (lower-case) id, so lang-file text lands next to its quest. */
    public final Map<String, String> textPrefix = new java.util.HashMap<>();
    /** File-level defaults (FTB Quests data.snbt) kept as settings.json. */
    public JsonObject settings;

    public ImportOutput(String namespace) {
        this.namespace = namespace;
    }

    public void count(String key, int n) {
        counts.merge(key, n, Integer::sum);
    }

    public void unknown(String where, String key) {
        unmapped.merge(where + "." + key, 1, Integer::sum);
    }

    public void note(String what) {
        notes.merge(what, 1, Integer::sum);
    }

    public void text(String locale, String key, JsonElement value) {
        String v;
        if (value.isJsonArray()) {
            StringBuilder b = new StringBuilder();
            for (JsonElement e : value.getAsJsonArray()) {
                if (!b.isEmpty()) {
                    b.append('\n');
                }
                b.append(e.isJsonPrimitive() ? e.getAsString() : e.toString());
            }
            v = b.toString();
        } else {
            v = value.isJsonPrimitive() ? value.getAsString() : value.toString();
        }
        lang.computeIfAbsent(locale, k -> new JsonObject()).addProperty(key, v);
    }

    /** Writes a data pack folder at {@code root} (replacing files with the same names, leaving others alone). */
    public void writeTo(Path root) throws IOException {
        Path base = root.resolve("data").resolve(namespace).resolve("progression");
        // a re-import replaces the previous one: chapters deleted or renamed in the source must not linger
        deleteTree(base);
        Files.createDirectories(base);
        write(root.resolve("pack.mcmeta"), mcmeta());
        for (Map.Entry<String, JsonObject> e : chapters.entrySet()) {
            write(base.resolve("chapters").resolve(e.getKey() + ".json"), e.getValue());
        }
        for (Map.Entry<String, JsonObject> e : patches.entrySet()) {
            write(base.resolve("patches").resolve(e.getKey() + ".json"), e.getValue());
        }
        for (Map.Entry<String, JsonObject> e : tables.entrySet()) {
            write(base.resolve("reward_tables").resolve(e.getKey() + ".json"), e.getValue());
        }
        if (!groups.isEmpty()) {
            write(base.resolve("groups.json"), groups);
        }
        if (settings != null) {
            write(base.resolve("settings.json"), settings);
        }
        for (Map.Entry<String, JsonObject> e : lang.entrySet()) {
            write(base.resolve("lang").resolve(e.getKey() + ".json"), e.getValue());
        }
        write(root.resolve("import_report_" + namespace + ".json"), report());
    }

    public JsonObject report() {
        JsonObject r = new JsonObject();
        r.add("counts", GSON.toJsonTree(counts));
        r.add("unmapped", GSON.toJsonTree(unmapped));
        r.add("notes", GSON.toJsonTree(notes));
        return r;
    }

    private static JsonObject mcmeta() {
        JsonObject pack = new JsonObject();
        pack.addProperty("pack_format", 48);
        pack.addProperty("description", "Kuronami's Quest Tree: imported quests and skills");
        JsonObject root = new JsonObject();
        root.add("pack", pack);
        return root;
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    private static void write(Path file, JsonElement json) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(json), StandardCharsets.UTF_8);
    }
}
