package com.kuronami.questtree.server;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kuronami.questtree.Constants;
import com.kuronami.questtree.network.SyncPayload;
import com.kuronami.questtree.platform.Services;
import com.kuronami.questtree.progress.RestrictionRules;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
    /** Pack-wide settings from {@code progression/settings.json}; empty when the pack has none. */
    public static JsonObject settings() {
        return base.has("settings") && base.get("settings").isJsonObject()
                ? base.getAsJsonObject("settings") : new JsonObject();
    }

    /** Emergency item definitions, normalised to the reward shape the item payout reads. */
    public static List<JsonObject> emergencyItems() {
        JsonObject settings = settings();
        JsonObject emergency = settings.has("emergency") && settings.get("emergency").isJsonObject()
                ? settings.getAsJsonObject("emergency") : settings;
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement e : arr(emergency, "items")) {
            if (e.isJsonPrimitive()) {
                JsonObject rw = new JsonObject();
                rw.addProperty("item", e.getAsString());
                out.add(rw);
            } else if (e.isJsonObject()) {
                JsonObject rw = e.getAsJsonObject().deepCopy();
                if (!rw.has("item") && rw.has("id")) {
                    rw.add("item", rw.get("id"));
                }
                if (rw.has("item")) {
                    out.add(rw);
                }
            }
        }
        return out;
    }

    public static long emergencyCooldownMs() {
        JsonObject settings = settings();
        JsonObject emergency = settings.has("emergency") && settings.get("emergency").isJsonObject()
                ? settings.getAsJsonObject("emergency") : settings;
        return com.kuronami.questtree.progress.PackNumbers.cooldownMs(emergency, "cooldown_seconds", 3600);
    }

    private static JsonArray arr(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
    }

    /** What the last rebuild found wrong (DATA_MODEL.md "検査"), for the log, {@code /quest_tree validate} and the join hint. */
    private static List<ProgressValidator.Problem> problems = List.of();
    private static int errorCount;
    private static int warningCount;
    /** Problems found while reading files; filled during {@link #reload} and drained into {@link #packProblems}. */
    private static final List<ProgressValidator.Problem> readProblems = new ArrayList<>();

    /**
     * The pack side, kept between rebuilds: chapters after patches, reward tables, groups, raw text for validator
     * lines, and the chapters whose file would not parse. The overlay is applied on top of a copy of this, so an edit
     * costs one merge and one validation instead of a data pack reload (2026-10-20).
     */
    private static JsonObject packBundle = new JsonObject();
    private static Map<String, ProgressValidator.Source> packSources = new LinkedHashMap<>();
    private static Set<String> packUnreadable = new LinkedHashSet<>();
    private static List<ProgressValidator.Problem> packProblems = List.of();
    /** Node keys ({@code chapter/node}) the validator dropped in the last rebuild, for the export report. */
    private static Set<String> droppedNodes = Set.of();
    /** The open world's root; null before a server has told us which world it is. */
    private static Path overlayRoot;
    /** Reward indices that moved in the last edit, for the store to move saved claims once (2026-10-22). */
    private static Map<String, Map<Integer, Integer>> rewardRemap = Map.of();
    /**
     * The registry the data is checked against. It is a field so that a unit test can use
     * {@link ProgressValidator.Registry#NONE}: the game-side one asks the platform for loaded mods, and a test has
     * none.
     */
    static ProgressValidator.Registry registry = GameRegistry.INSTANCE;

    private ProgressData() {
    }

    public static void reload(ResourceManager rm) {
        JsonObject chapters = new JsonObject();
        JsonObject tables = new JsonObject();
        JsonObject patches = new JsonObject();
        JsonArray groups = new JsonArray();
        JsonObject settings = new JsonObject();
        JsonArray restrictions = new JsonArray();

        Map<String, ProgressValidator.Source> sources = new LinkedHashMap<>();
        readProblems.clear();
        // chapters whose file would not parse: a reference into one cannot be called missing (DATA_MODEL.md "検査")
        Set<String> unreadable = new LinkedHashSet<>();

        readAll(rm, ROOT + "/chapters", chapters, sources, unreadable);
        readAll(rm, ROOT + "/reward_tables", tables, sources, null);
        readAll(rm, ROOT + "/patches", patches, null, null);
        // one settings file: the first namespace that ships one wins, as with a single pack's config
        for (Map.Entry<ResourceLocation, Resource> e : rm.listResources(ROOT,
                id -> id.getPath().equals(ROOT + "/settings.json")).entrySet()) {
            JsonElement el = read(e.getKey(), e.getValue());
            if (el != null && el.isJsonObject()) {
                settings = el.getAsJsonObject();
                break;
            }
        }
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
        readRestrictions(rm, restrictions);
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

        applyPatches(chapters, patches);
        installPack(packOf(chapters, tables, groups, settings, restrictions), sources, unreadable,
                List.copyOf(readProblems), byLocale);
    }

    /**
     * The pack side of a bundle, in the shape {@link #rebuild()} reads. Shared with {@link #installPack}, so a test
     * can hand in exactly what a reload would have kept.
     */
    static JsonObject packOf(JsonObject chapters, JsonObject tables, JsonArray groups, JsonObject settings,
                             JsonArray restrictions) {
        JsonObject pack = new JsonObject();
        pack.add("chapters", chapters);
        pack.add("reward_tables", tables);
        pack.add("groups", groups);
        pack.add("settings", settings);
        pack.add("restrictions", restrictions);
        return pack;
    }

    /** Keeps a pack as if {@link #reload} had read it, then rebuilds. A test needs this: it has no resource manager. */
    static void installPack(JsonObject pack, Map<String, ProgressValidator.Source> sources, Set<String> unreadable,
                            List<ProgressValidator.Problem> problems, Map<String, JsonObject> locales) {
        packBundle = pack;
        packSources = sources;
        packUnreadable = unreadable;
        packProblems = problems;
        langs = locales;
        rebuild();
    }

    /**
     * Rebuilds the bundle from the packs and the writable overlay, validates it, and publishes a new generation.
     * <p>
     * This is the only path an edit takes to the clients. It reads no resource manager: the pack side is kept from the
     * last reload, so a save costs one read of the overlay chapters and one validation, not a Minecraft reload.
     *
     * @return whether a new bundle was published (validation can reject it and leave the loaded one in place)
     */
    private static boolean rebuild() {
        JsonObject bundle = packBundle.deepCopy();
        if (!bundle.has("chapters") || !bundle.get("chapters").isJsonObject()) {
            bundle.add("chapters", new JsonObject());
        }
        if (!bundle.has("reward_tables") || !bundle.get("reward_tables").isJsonObject()) {
            bundle.add("reward_tables", new JsonObject());
        }
        if (!bundle.has("groups") || !bundle.get("groups").isJsonArray()) {
            bundle.add("groups", new JsonArray());
        }
        JsonObject chapters = bundle.getAsJsonObject("chapters");
        JsonObject revisions = new JsonObject();
        bundle.add("revisions", revisions);
        JsonObject deleted = emptyDeleted();
        bundle.add("deleted", deleted);
        Map<String, ProgressValidator.Source> sources = new LinkedHashMap<>(packSources);
        Set<String> unreadable = new LinkedHashSet<>(packUnreadable);
        List<ProgressValidator.Problem> found = new ArrayList<>(packProblems);

        if (overlayRoot != null) {
            try {
                ProgressionOverlay.Overlay overlay = ProgressionOverlay.read(overlayRoot.resolve(ProgressionOverlay.DIR));
                found.addAll(overlay.problems);
                overlay.applyTo(chapters, sources);
                JsonObject tables = bundle.getAsJsonObject("reward_tables");
                overlay.applyTablesTo(tables, sources);
                overlay.applyRestrictionsTo(bundle);
                // editors learn each chapter's current revision from the bundle, without spending a request
                revisions = overlay.revisionsJson();
                bundle.add("revisions", revisions);
                bundle.add("table_revisions", overlay.tableRevisionsJson());
                bundle.add("deleted", overlay.deletedJson());
            } catch (RuntimeException ex) {
                Constants.LOG.error("Could not read the progression overlay", ex);
                found.add(new ProgressValidator.Problem(ProgressValidator.Severity.ERROR, ProgressionOverlay.DIR, "",
                        "the progression overlay could not be read: " + ex, 0));
            }
        }

        // check the data before it becomes a model: a broken node is dropped, the rest of the pack keeps working
        ProgressValidator.Result result;
        try {
            result = ProgressValidator.validate(bundle, sources, registry, unreadable);
        } catch (RuntimeException ex) {
            // the check must never take a rebuild down with it: keep the data as it is and report that it happened
            Constants.LOG.error("Could not check the progression data", ex);
            result = new ProgressValidator.Result();
            result.problems.add(new ProgressValidator.Problem(ProgressValidator.Severity.ERROR, "", "",
                    "the progression data could not be checked: " + ex, 0));
        }
        found.addAll(result.problems);
        droppedNodes = Set.copyOf(result.droppedNodes);
        prune(chapters, result.droppedNodes);

        // The model build is the last reader of the bundle, and the pruning above is what makes it safe. If something
        // still gets through, keep the data that is already loaded: publishing a bundle the server cannot model would
        // leave the client holding progression the server does not have.
        com.kuronami.questtree.data.ProgressModel built;
        try {
            built = com.kuronami.questtree.data.ProgressModel.of(bundle);
        } catch (RuntimeException ex) {
            Constants.LOG.error("Could not build the progression model", ex);
            found.add(new ProgressValidator.Problem(ProgressValidator.Severity.ERROR, "", "",
                    "the progression data could not be read as a model: " + ex, 0));
            built = null;
        }

        problems = List.copyOf(found);
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
            return false;
        }

        base = bundle;
        COMPRESSED.clear();
        model = built;
        restrictions = RestrictionRules.of(bundle);
        generation++;
        chapterCount = chapters.size();
        Constants.LOG.info("Loaded {} progression chapters ({} KB for en_us, {} locales)", chapterCount,
                bundleFor("en_us").length / 1024, langs.size());
        return true;
    }

    /** Called once the server knows which world is open; the overlay lives inside it. */
    public static void setOverlayRoot(Path root) {
        overlayRoot = root;
        rebuild();
    }

    /**
     * The server stopped: the next world must not keep reading this one's overlay, or compare its chapters with this
     * one's when it computes a reward move (2026-10-22 review).
     */
    public static void clearOverlayRoot() {
        overlayRoot = null;
        base = new JsonObject();
        rewardRemap = Map.of();
    }

    /** The reward moves of the last rebuild, cleared once the store has applied them. */
    public static Map<String, Map<Integer, Integer>> takeRewardRemap() {
        Map<String, Map<Integer, Integer>> out = rewardRemap;
        rewardRemap = Map.of();
        return out;
    }

    /**
     * Rebuilds after an edit (the editor asks for this at most once per server tick).
     * <p>
     * Only this rebuild may move a saved reward index (2026-10-22 review). A pack reload or a world change also
     * rebuilds, but it is not an edit: charging its difference to the saved claims would shift them onto a different
     * reward, and a reward the overlay deleted would become claimable again.
     */
    public static void rebuildOverlay() {
        JsonObject oldChapters = chaptersOf(base);
        if (!rebuild()) {
            return;
        }
        rewardRemap = com.kuronami.questtree.progress.RewardIndexRemap.between(oldChapters, chaptersOf(base));
    }

    private static JsonObject chaptersOf(JsonObject bundle) {
        return bundle.has("chapters") && bundle.get("chapters").isJsonObject()
                ? bundle.getAsJsonObject("chapters") : new JsonObject();
    }

    /** The bundle's restore list: no deleted chapters, no deleted nodes. */
    private static JsonObject emptyDeleted() {
        JsonObject deleted = new JsonObject();
        deleted.add("chapters", new JsonArray());
        deleted.add("nodes", new JsonObject());
        return deleted;
    }

    /** The pack chapters after patches, without the overlay: what an edit is applied against. */
    public static JsonObject packChapters() {
        return packBundle.has("chapters") && packBundle.get("chapters").isJsonObject()
                ? packBundle.getAsJsonObject("chapters").deepCopy() : new JsonObject();
    }

    /** The pack reward tables; the writable table overlay is applied on top in {@link #rebuild()}. */
    public static JsonObject packTables() {
        return packBundle.has("reward_tables") && packBundle.get("reward_tables").isJsonObject()
                ? packBundle.getAsJsonObject("reward_tables").deepCopy() : new JsonObject();
    }

    public static Map<String, ProgressValidator.Source> packSources() {
        return new LinkedHashMap<>(packSources);
    }

    public static Set<String> packUnreadable() {
        return new LinkedHashSet<>(packUnreadable);
    }

    /** The validated book the clients see, for {@code /quest_tree export}. */
    public static JsonObject mergedBundle() {
        return base.deepCopy();
    }

    /** The namespace's translations as the readers hold them, for {@code /quest_tree export}. */
    public static Map<String, JsonObject> langs() {
        Map<String, JsonObject> out = new TreeMap<>();
        langs.forEach((locale, entries) -> out.put(locale, entries.deepCopy()));
        return out;
    }

    /** Node keys ({@code chapter/node}) the validator dropped in the last rebuild. */
    public static Set<String> droppedNodes() {
        return droppedNodes;
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
     * Reads {@code progression/restrictions.json} from every namespace. The rules merge in resource order, so a pack
     * can add its own rules beside another pack's; ids are global, and the first namespace that writes one wins.
     */
    private static void readRestrictions(ResourceManager rm, JsonArray rules) {
        Set<String> ids = new LinkedHashSet<>();
        for (Map.Entry<ResourceLocation, Resource> e : rm.listResources(ROOT,
                id -> id.getPath().equals(ROOT + "/restrictions.json")).entrySet()) {
            String file = "data/" + e.getKey().getPath();
            JsonElement el = read(e.getKey(), e.getValue());
            if (el == null) {
                continue;
            }
            JsonElement list = el.isJsonObject() ? el.getAsJsonObject().get("rules") : el;
            if (list == null || !list.isJsonArray()) {
                readProblems.add(new ProgressValidator.Problem(ProgressValidator.Severity.WARNING, file, "rules",
                        "restrictions must be an object with a rules array", 0));
                continue;
            }
            RestrictionRules.Result parsed = RestrictionRules.parse(list.getAsJsonArray());
            for (RestrictionRules.Problem p : parsed.problems()) {
                readProblems.add(new ProgressValidator.Problem(ProgressValidator.Severity.WARNING, file,
                        p.path(), p.message(), 0));
            }
            for (RestrictionRules.Rule rule : parsed.rules()) {
                if (!ids.add(rule.id())) {
                    readProblems.add(new ProgressValidator.Problem(ProgressValidator.Severity.WARNING, file,
                            "rules", "duplicate rule id '" + rule.id() + "'; this copy is ignored", 0));
                    continue;
                }
                rules.add(rule.json());
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

    private static RestrictionRules restrictions = RestrictionRules.empty();

    /** The item and block restrictions of the loaded data (DATA_MODEL.md "制限"), rebuilt with the model. */
    public static RestrictionRules restrictions() {
        return restrictions;
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
