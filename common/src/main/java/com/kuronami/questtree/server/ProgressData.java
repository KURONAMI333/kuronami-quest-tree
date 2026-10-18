package com.kuronami.questtree.server;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kuronami.questtree.Constants;
import com.kuronami.questtree.network.SyncPayload;
import com.kuronami.questtree.platform.Services;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;

/**
 * Server-side holder of everything under {@code data/<ns>/progression/} (DATA_MODEL.md).
 * Rebuilt on every data pack reload, then sent to players as one compressed JSON bundle.
 * <p>
 * The bundle keeps the files as JSON instead of decoding them into records here: the client is the only reader in
 * this version, and keeping unknown keys intact lets new fields reach the screen without a server-side change.
 */
public final class ProgressData {

    private static final String ROOT = "progression";
    private static final Gson GSON = new Gson();

    /** Bundle without translations, plus translations per locale; a player gets their locale and en_us only. */
    private static JsonObject base = new JsonObject();
    private static Map<String, JsonObject> langs = new TreeMap<>();
    private static final Map<String, byte[]> COMPRESSED = new java.util.concurrent.ConcurrentHashMap<>();
    private static int generation;
    private static int chapterCount;
    /** What the last reload found wrong (DATA_MODEL.md "検査"), for the log, {@code /quest_tree validate} and the join hint. */
    private static List<ProgressValidator.Problem> problems = List.of();
    private static int errorCount;
    private static int warningCount;
    /** Problems found while reading files; filled during {@link #reload} and drained into {@link #problems}. */
    private static final List<ProgressValidator.Problem> readProblems = new ArrayList<>();

    private ProgressData() {
    }

    public static void reload(ResourceManager rm) {
        JsonObject bundle = new JsonObject();
        JsonObject chapters = new JsonObject();
        JsonObject tables = new JsonObject();
        JsonObject patches = new JsonObject();
        JsonArray groups = new JsonArray();
        JsonObject lang = new JsonObject();

        Map<String, ProgressValidator.Source> sources = new LinkedHashMap<>();
        readProblems.clear();
        List<ProgressValidator.Problem> problems = new ArrayList<>();
        // chapters whose file would not parse: a reference into one cannot be called missing (DATA_MODEL.md "検査")
        Set<String> unreadable = new LinkedHashSet<>();

        readAll(rm, ROOT + "/chapters", chapters, sources, unreadable);
        readAll(rm, ROOT + "/reward_tables", tables, sources, null);
        readAll(rm, ROOT + "/patches", patches, null, null);
        for (Map.Entry<ResourceLocation, Resource> e : rm.listResources(ROOT, id -> id.getPath().equals(ROOT + "/groups.json")).entrySet()) {
            JsonElement g = read(e.getKey(), e.getValue());
            if (g != null && g.isJsonArray()) {
                for (JsonElement el : g.getAsJsonArray()) {
                    if (el.isJsonObject()) {
                        el.getAsJsonObject().addProperty("namespace", e.getKey().getNamespace());
                    }
                    groups.add(el);
                }
            }
        }
        // translations shipped inside the data pack (imported packs carry their own text, like FTB Quests does)
        Map<String, JsonObject> byLocale = new TreeMap<>();
        for (Map.Entry<ResourceLocation, Resource> e : rm.listResources(ROOT + "/lang", id -> id.getPath().endsWith(".json")).entrySet()) {
            JsonElement l = read(e.getKey(), e.getValue());
            if (l == null || !l.isJsonObject()) {
                continue;
            }
            String file = e.getKey().getPath();
            String locale = file.substring(file.lastIndexOf('/') + 1, file.length() - 5);
            JsonObject target = byLocale.computeIfAbsent(locale, k -> new JsonObject());
            l.getAsJsonObject().entrySet().forEach(kv -> target.add(kv.getKey(), kv.getValue()));
        }
        byLocale.forEach(lang::add);

        applyPatches(chapters, patches);
        bundle.add("chapters", chapters);
        bundle.add("reward_tables", tables);
        bundle.add("groups", groups);

        // check the data before it becomes a model: a broken node is dropped, the rest of the pack keeps working
        ProgressValidator.Result result;
        try {
            result = ProgressValidator.validate(bundle, sources, GameRegistry.INSTANCE, unreadable);
        } catch (RuntimeException ex) {
            // the check must never take a reload down with it: keep the data as it is and report that it happened
            Constants.LOG.error("Could not check the progression data", ex);
            result = new ProgressValidator.Result();
            result.problems.add(new ProgressValidator.Problem(ProgressValidator.Severity.ERROR, "", "",
                    "the progression data could not be checked: " + ex, 0));
        }
        problems.addAll(readProblems);
        problems.addAll(result.problems);
        prune(chapters, result.droppedNodes);

        // The model build is the last reader of the bundle, and the pruning above is what makes it safe. If something
        // still gets through, keep the data that is already loaded: publishing a bundle the server cannot model would
        // leave the client holding progression the server does not have.
        com.kuronami.questtree.data.ProgressModel built;
        try {
            built = com.kuronami.questtree.data.ProgressModel.of(bundle);
        } catch (RuntimeException ex) {
            Constants.LOG.error("Could not build the progression model", ex);
            problems.add(new ProgressValidator.Problem(ProgressValidator.Severity.ERROR, "", "",
                    "the progression data could not be read as a model: " + ex, 0));
            built = null;
        }

        ProgressData.problems = List.copyOf(problems);
        errorCount = 0;
        warningCount = 0;
        for (ProgressValidator.Problem p : problems) {
            if (p.severity() == ProgressValidator.Severity.ERROR) {
                errorCount++;
            } else {
                warningCount++;
            }
        }
        if (!problems.isEmpty()) {
            Constants.LOG.warn("{} errors, {} warnings in progression data", errorCount, warningCount);
            for (ProgressValidator.Problem p : problems) {
                Constants.LOG.warn("{}", p.format());
            }
        }
        if (built == null) {
            return;
        }

        base = bundle;
        langs = byLocale;
        COMPRESSED.clear();
        model = built;
        generation++;
        chapterCount = chapters.size();
        Constants.LOG.info("Loaded {} progression chapters ({} KB for en_us, {} locales)", chapterCount, bundleFor("en_us").length / 1024, lang.size());
    }

    /** Removes the nodes the validator marked as ERROR, so one bad node cannot take the whole pack down. */
    private static void prune(JsonObject chapters, Set<String> dropped) {
        if (dropped.isEmpty()) {
            return;
        }
        for (Map.Entry<String, JsonElement> c : chapters.entrySet()) {
            if (!c.getValue().isJsonObject()) {
                continue;
            }
            JsonElement nodesElement = c.getValue().getAsJsonObject().get("nodes");
            // a "nodes" that is not an object holds nothing to remove: the readers read it with obj(...), which hands
            // back an empty object, and the validator reports it
            if (nodesElement == null || !nodesElement.isJsonObject()) {
                continue;
            }
            JsonObject nodes = nodesElement.getAsJsonObject();
            List<String> remove = new ArrayList<>();
            for (String id : nodes.keySet()) {
                if (dropped.contains(c.getKey() + "/" + id)) {
                    remove.add(id);
                }
            }
            remove.forEach(nodes::remove);
        }
    }

    /** Problems the last reload found (DATA_MODEL.md "検査"), newest first is not needed: the order is the walk order. */
    public static List<ProgressValidator.Problem> problems() {
        return problems;
    }

    public static int errorCount() {
        return errorCount;
    }

    public static int warningCount() {
        return warningCount;
    }

    /** Patch files carry {"target": "<ns>:<chapter>", ...fields}; nodes merge by id, other fields replace. */
    private static void applyPatches(JsonObject chapters, JsonObject patches) {
        for (Map.Entry<String, JsonElement> p : patches.entrySet()) {
            if (!p.getValue().isJsonObject()) {
                continue;
            }
            JsonObject patch = p.getValue().getAsJsonObject();
            String target = patch.has("target") ? patch.get("target").getAsString() : null;
            JsonElement base = target == null ? null : chapters.get(target);
            if (base == null || !base.isJsonObject()) {
                Constants.LOG.warn("Progression patch {} targets missing chapter {}", p.getKey(), target);
                continue;
            }
            JsonObject b = base.getAsJsonObject();
            for (Map.Entry<String, JsonElement> f : patch.entrySet()) {
                if (f.getKey().equals("target")) {
                    continue;
                }
                if (f.getKey().equals("nodes") && f.getValue().isJsonObject() && b.has("nodes") && b.get("nodes").isJsonObject()) {
                    JsonObject nodes = b.getAsJsonObject("nodes");
                    for (Map.Entry<String, JsonElement> n : f.getValue().getAsJsonObject().entrySet()) {
                        JsonElement old = nodes.get(n.getKey());
                        if (old != null && old.isJsonObject() && n.getValue().isJsonObject()) {
                            n.getValue().getAsJsonObject().entrySet().forEach(kv -> old.getAsJsonObject().add(kv.getKey(), kv.getValue()));
                        } else {
                            nodes.add(n.getKey(), n.getValue());
                        }
                    }
                } else {
                    b.add(f.getKey(), f.getValue());
                }
            }
        }
    }

    /**
     * Reads every {@code .json} under {@code dir} into {@code into}, keyed {@code <namespace>:<name>}. The raw text is
     * kept in {@code sources} (for the validator's line numbers) and a file that will not parse becomes a problem
     * instead of just a log line. When {@code unreadable} is given, such a file's id is added to it.
     */
    private static void readAll(ResourceManager rm, String dir, JsonObject into,
                                Map<String, ProgressValidator.Source> sources, Set<String> unreadable) {
        String kind = dir.substring(ROOT.length() + 1);
        for (Map.Entry<ResourceLocation, Resource> e : rm.listResources(dir, id -> id.getPath().endsWith(".json")).entrySet()) {
            String path = e.getKey().getPath();
            String name = path.substring(dir.length() + 1, path.length() - 5);
            String file = "data/" + path;
            String text = readText(e.getValue());
            if (text == null) {
                continue;
            }
            JsonElement el;
            try {
                el = JsonParser.parseString(text);
            } catch (Exception ex) {
                readProblems.add(ProgressValidator.parseError(file, text, ex));
                if (unreadable != null) {
                    unreadable.add(e.getKey().getNamespace() + ":" + name);
                }
                Constants.LOG.error("Could not read progression file {}: {}", e.getKey(), ex.getMessage());
                continue;
            }
            into.add(e.getKey().getNamespace() + ":" + name, el);
            if (sources != null) {
                sources.put(kind + "/" + e.getKey().getNamespace() + ":" + name, new ProgressValidator.Source(file, text));
            }
        }
    }

    private static JsonElement read(ResourceLocation id, Resource res) {
        String text = readText(res);
        if (text == null) {
            return null;
        }
        try {
            return JsonParser.parseString(text);
        } catch (Exception ex) {
            readProblems.add(ProgressValidator.parseError("data/" + id.getPath(), text, ex));
            Constants.LOG.error("Could not read progression file {}: {}", id, ex.getMessage());
            return null;
        }
    }

    private static String readText(Resource res) {
        try (Reader r = res.openAsReader()) {
            StringBuilder b = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) >= 0) {
                b.append(buf, 0, n);
            }
            return b.toString();
        } catch (Exception ex) {
            Constants.LOG.error("Could not read progression file: {}", ex.getMessage());
            return null;
        }
    }

    private static byte[] gzip(byte[] raw) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 4 + 64);
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(raw);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        return out.toByteArray();
    }

    private static byte[] bundleFor(String locale) {
        String loc = langs.containsKey(locale) ? locale : "en_us";
        return COMPRESSED.computeIfAbsent(loc, l -> {
            JsonObject b = base.deepCopy();
            JsonObject lang = new JsonObject();
            if (langs.containsKey("en_us")) {
                lang.add("en_us", langs.get("en_us"));
            }
            if (langs.containsKey(l)) {
                lang.add(l, langs.get(l));
            }
            b.add("lang", lang);
            return gzip(GSON.toJson(b).getBytes(StandardCharsets.UTF_8));
        });
    }

    private static com.kuronami.questtree.data.ProgressModel model = com.kuronami.questtree.data.ProgressModel.of(new JsonObject());

    /** The parsed model of the loaded data, for progress checks. */
    public static com.kuronami.questtree.data.ProgressModel model() {
        return model;
    }

    public static void sync(ServerPlayer player) {
        byte[] data = bundleFor(player.clientInformation().language().toLowerCase(java.util.Locale.ROOT));
        int total = Math.max(1, (data.length + SyncPayload.MAX_PART - 1) / SyncPayload.MAX_PART);
        for (int i = 0; i < total; i++) {
            int from = i * SyncPayload.MAX_PART;
            byte[] part = Arrays.copyOfRange(data, from, Math.min(data.length, from + SyncPayload.MAX_PART));
            Services.PLATFORM.sendToPlayer(player, new SyncPayload(generation, i, total, part));
        }
    }

    public static int chapterCount() {
        return chapterCount;
    }
}
