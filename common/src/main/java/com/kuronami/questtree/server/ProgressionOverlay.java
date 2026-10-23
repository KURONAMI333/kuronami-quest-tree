package com.kuronami.questtree.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The writable layer over the data packs, at {@code <world>/kuronami_quest_tree/progression/} (2026-10-20).
 * <p>
 * A data pack can be a zip or a mod's read-only pack, so an editor cannot write "the pack". This layer is read after
 * every pack and has explicit replace and tombstone semantics, which is the smallest shape that can delete a node a
 * pack still ships. Precedence, lowest to highest:
 * <ol>
 *     <li>chapters, reward tables and groups from data packs;</li>
 *     <li>{@code patches/*.json} from data packs, as before;</li>
 *     <li>this overlay, chapter by chapter.</li>
 * </ol>
 * A chapter file is {@code chapters/<namespace>/<chapter path>.json}: the first folder is the namespace and the rest is
 * the chapter id's path, so the ids stay what the bundle already uses ({@code <namespace>:<path>}).
 * <p>
 * <strong>Chapter file.</strong> {@code chapter} holds chapter fields. Without {@code replace}, those fields are merged
 * onto the pack chapter one key at a time (the same rule patches use) and the pack's nodes stay unless a node entry
 * says otherwise. With {@code replace}, the overlay chapter is the whole chapter. {@code remove} is a tombstone: the
 * chapter is hidden, but {@code chapter} keeps what was removed so it can be put back. {@code base} records whether a
 * pack had the chapter when it was first edited; {@code edited} on a tombstone records whether the overlay had changed
 * it before the delete, which is what lets a restore either bring the untouched pack chapter back or put the author's
 * version back.
 * <p>
 * <strong>Node entry.</strong> {@code {"action": "add"|"replace"|"remove", "node": {...}}}. {@code remove} keeps the
 * removed node as the tombstone. A missing action is read as {@code add} when the entry has no {@code remove}/{@code
 * replace} flag, so a hand-written overlay stays readable.
 * <p>
 * This class only reads and merges JSON. Whether the result may be written is {@link ProgressionEditor}'s decision;
 * {@link ProgressData} runs the validator once the merge is done.
 */
public final class ProgressionOverlay {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    public static final String DIR = "kuronami_quest_tree/progression";

    /** What an overlay node entry does to the chapter. */
    public enum Action {
        ADD, REPLACE, REMOVE
    }

    /** One overlay node entry. {@code node} is the new content, or the tombstone's kept content for {@code REMOVE}. */
    public record NodeChange(Action action, JsonObject node, boolean edited) {
    }

    /** One chapter's overlay: the fields, the node entries, and the metadata the editor keeps beside them. */
    public static final class ChapterOverlay {
        public final String id;
        public long revision;
        public boolean base;
        public boolean replace;
        public boolean remove;
        public boolean edited;
        public JsonObject chapter = new JsonObject();
        /** Top-level chapter fields a {@code null} patch removed from the pack chapter (RFC 7396 deletion). */
        public final Set<String> removed = new LinkedHashSet<>();
        public final Map<String, NodeChange> nodes = new LinkedHashMap<>();
        /** Where the overlay file is; empty for a chapter the editor created in memory and has not written yet. */
        public String file = "";
        /** The file's raw text, for validator line numbers; null when the chapter came only from a pack. */
        public String raw = "";

        public ChapterOverlay(String id) {
            this.id = id;
        }

        /** The file's JSON, in the shape {@link #read} reads back. */
        public JsonObject toJson() {
            JsonObject out = new JsonObject();
            out.addProperty("revision", revision);
            out.addProperty("base", base);
            if (replace) {
                out.addProperty("replace", true);
            }
            if (remove) {
                out.addProperty("remove", true);
                out.addProperty("edited", edited);
            }
            if (chapter.size() > 0) {
                out.add("chapter", chapter.deepCopy());
            }
            if (!removed.isEmpty()) {
                JsonArray names = new JsonArray();
                removed.forEach(names::add);
                out.add("removed", names);
            }
            if (!nodes.isEmpty()) {
                JsonObject n = new JsonObject();
                for (Map.Entry<String, NodeChange> e : nodes.entrySet()) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("action", switch (e.getValue().action()) {
                        case ADD -> "add";
                        case REPLACE -> "replace";
                        case REMOVE -> "remove";
                    });
                    entry.add("node", e.getValue().node().deepCopy());
                    if (e.getValue().edited()) {
                        entry.addProperty("edited", true);
                    }
                    n.add(e.getKey(), entry);
                }
                out.add("nodes", n);
            }
            return out;
        }
    }

    /**
     * One reward table's overlay (2026-10-21, phase 3): the whole table, because a table is small and partial table
     * patches would have to decide which entry a nested array index means. {@code base} records whether the pack had
     * the table when it was first edited; the revision guard is per table and separate from chapters.
     */
    public static final class TableOverlay {
        public final String id;
        public long revision;
        public boolean base;
        public boolean remove;
        public JsonObject table = new JsonObject();
        public String file = "";
        public String raw = "";

        public TableOverlay(String id) {
            this.id = id;
        }

        public JsonObject toJson() {
            JsonObject out = new JsonObject();
            out.addProperty("revision", revision);
            out.addProperty("base", base);
            if (remove) {
                out.addProperty("remove", true);
            }
            out.add("table", table.deepCopy());
            return out;
        }
    }

    /**
     * The whole restrictions file (2026-10-22, G6): one array of rules, written whole. A rule is an entry in an array,
     * so a partial patch would only decide which index a condition belongs to; the revision is one number for the file.
     * {@code null} rules mean the pack's own {@code restrictions.json} is used unchanged.
     */
    public static final class RestrictionOverlay {
        public long revision;
        public JsonArray rules = new JsonArray();
        public String file = "";
        public String raw = "";

        public JsonObject toJson() {
            JsonObject out = new JsonObject();
            out.addProperty("revision", revision);
            out.add("rules", rules.deepCopy());
            return out;
        }
    }

    /**
     * The whole pack settings file (2026-10-22, p1-crates): one revision and one object, written whole. A settings
     * object is a small config, so a partial patch would only decide which key a nested path means.
     */
    public static final class SettingsOverlay {
        public long revision;
        public JsonObject settings = new JsonObject();
        public String file = "";
        public String raw = "";

        public JsonObject toJson() {
            JsonObject out = new JsonObject();
            out.addProperty("revision", revision);
            out.add("settings", settings.deepCopy());
            return out;
        }
    }

    /**
     * One locale's overlay (2026-10-22, p6-editor): the whole locale object and one revision, written whole so an
     * editor can remove a key the pack shipped. The file is {@code lang/<locale>.json} under the progression folder.
     */
    public static final class LangOverlay {
        public final String locale;
        public long revision;
        public JsonObject entries = new JsonObject();
        public String file = "";
        public String raw = "";

        public LangOverlay(String locale) {
            this.locale = locale;
        }

        public JsonObject toJson() {
            JsonObject out = new JsonObject();
            out.addProperty("revision", revision);
            out.add("entries", entries.deepCopy());
            return out;
        }
    }

    /** Everything the overlay directory held, plus what could not be read. */
    public static final class Overlay {
        public final Map<String, ChapterOverlay> chapters = new LinkedHashMap<>();
        /** Per-chapter revisions, including chapters whose file an undo deleted (see {@code revisions.json}). */
        public final Map<String, Long> revisions = new LinkedHashMap<>();
        public final Map<String, TableOverlay> tables = new LinkedHashMap<>();
        /** Per-table revisions (see {@code table_revisions.json}); kept apart from chapter revisions. */
        public final Map<String, Long> tableRevisions = new LinkedHashMap<>();
        /** The whole restrictions file, or null when it does not exist and the pack's rules apply. */
        public RestrictionOverlay restrictions;
        /** The whole settings file, or null when it does not exist and the pack's settings apply. */
        public SettingsOverlay settings;
        /** Locale to the whole locale object the editor wrote for it; absent locales keep the pack's text. */
        public final Map<String, LangOverlay> langs = new LinkedHashMap<>();
        public final List<ProgressValidator.Problem> problems = new ArrayList<>();

        public ChapterOverlay chapter(String id) {
            return chapters.get(id);
        }

        public long revision(String id) {
            Long revision = revisions.get(id);
            return revision == null ? 0 : revision;
        }

        public long tableRevision(String id) {
            Long revision = tableRevisions.get(id);
            return revision == null ? 0 : revision;
        }

        /** The revisions the clients receive with the bundle, so an editor knows what to send without a reply. */
        public JsonObject revisionsJson() {
            JsonObject out = new JsonObject();
            for (Map.Entry<String, Long> e : revisions.entrySet()) {
                out.addProperty(e.getKey(), e.getValue());
            }
            return out;
        }

        public JsonObject tableRevisionsJson() {
            JsonObject out = new JsonObject();
            for (Map.Entry<String, Long> e : tableRevisions.entrySet()) {
                out.addProperty(e.getKey(), e.getValue());
            }
            return out;
        }

        /** Locale to its revision, so the translation screen knows what its save must name. */
        public long langRevision(String locale) {
            LangOverlay overlay = langs.get(locale);
            return overlay == null ? 0 : overlay.revision;
        }

        public JsonObject langRevisionsJson() {
            JsonObject out = new JsonObject();
            for (Map.Entry<String, LangOverlay> e : langs.entrySet()) {
                out.addProperty(e.getKey(), e.getValue().revision);
            }
            return out;
        }

        /**
         * The tombstones the restore list offers, for the bundle: deleted chapters by id, and each live chapter's
         * deleted node ids. A deleted chapter keeps its node tombstones in its file, but restoring the chapter is the
         * one meaningful action there, so its nodes are not listed until it is back.
         */
        public JsonObject deletedJson() {
            JsonObject out = new JsonObject();
            JsonArray deletedChapters = new JsonArray();
            JsonObject deletedNodes = new JsonObject();
            JsonArray deletedTables = new JsonArray();
            for (TableOverlay table : tables.values()) {
                if (table.remove) {
                    deletedTables.add(table.id);
                }
            }
            for (ChapterOverlay chapter : chapters.values()) {
                if (chapter.remove) {
                    deletedChapters.add(chapter.id);
                    continue;
                }
                JsonArray ids = new JsonArray();
                for (Map.Entry<String, NodeChange> e : chapter.nodes.entrySet()) {
                    if (e.getValue().action() == Action.REMOVE) {
                        ids.add(e.getKey());
                    }
                }
                if (!ids.isEmpty()) {
                    deletedNodes.add(chapter.id, ids);
                }
            }
            out.add("chapters", deletedChapters);
            out.add("tables", deletedTables);
            out.add("nodes", deletedNodes);
            return out;
        }

        /**
         * Merges this layer into {@code chapters} in place and points the validator's sources at the overlay files.
         * The caller passes the pack's chapters (after patches) and a mutable copy of the pack sources.
         */
        public void applyTo(JsonObject chapters, Map<String, ProgressValidator.Source> sources) {
            for (ChapterOverlay overlay : new ArrayList<>(this.chapters.values())) {
                if (overlay.remove) {
                    chapters.remove(overlay.id);
                    continue;
                }
                JsonElement pack = chapters.get(overlay.id);
                JsonObject merged;
                if (overlay.replace) {
                    merged = overlay.chapter.deepCopy();
                } else {
                    merged = pack != null && pack.isJsonObject() ? pack.getAsJsonObject().deepCopy() : new JsonObject();
                    for (String removed : overlay.removed) {
                        if (!removed.equals("nodes")) {
                            merged.remove(removed);
                        }
                    }
                    for (Map.Entry<String, JsonElement> f : overlay.chapter.entrySet()) {
                        if (f.getKey().equals("nodes")) {
                            continue;
                        }
                        merged.add(f.getKey(), f.getValue().deepCopy());
                    }
                }
                JsonElement current = merged.get("nodes");
                JsonObject nodes = current != null && current.isJsonObject() ? current.getAsJsonObject() : new JsonObject();
                if (current == null || !current.isJsonObject()) {
                    merged.add("nodes", nodes);
                }
                for (Map.Entry<String, NodeChange> e : overlay.nodes.entrySet()) {
                    NodeChange change = e.getValue();
                    if (change.action() == Action.REMOVE) {
                        nodes.remove(e.getKey());
                    } else {
                        nodes.add(e.getKey(), change.node().deepCopy());
                    }
                }
                chapters.add(overlay.id, merged);
                if (!overlay.file.isEmpty()) {
                    sources.put("chapters/" + overlay.id, new ProgressValidator.Source(overlay.file, overlay.raw));
                }
            }
        }

        /**
         * Merges the table overlay into {@code tables} in place. A table is replaced whole: its entries are an array,
         * and RFC 7396 would replace the array anyway, so a partial table would only decide where an index points.
         */
        public void applyTablesTo(JsonObject tables, Map<String, ProgressValidator.Source> sources) {
            for (TableOverlay overlay : new ArrayList<>(this.tables.values())) {
                if (overlay.remove) {
                    tables.remove(overlay.id);
                    continue;
                }
                tables.add(overlay.id, overlay.table.deepCopy());
                if (!overlay.file.isEmpty()) {
                    sources.put("reward_tables/" + overlay.id,
                            new ProgressValidator.Source(overlay.file, overlay.raw));
                }
            }
        }

    /**
     * Puts the overlay's restrictions into {@code bundle} when the file exists: it replaces the pack's rule array
     * whole, so an editor can delete a pack rule. Without the file the pack's array and revision 0 stay as they are.
     */
    public void applyRestrictionsTo(JsonObject bundle) {
        if (restrictions == null) {
            return;
        }
        bundle.add("restrictions", restrictions.rules.deepCopy());
        bundle.addProperty("restrictions_revision", restrictions.revision);
    }

    /**
     * Puts the overlay's settings into {@code bundle} when the file exists; the object replaces the pack's settings
     * whole, so an editor can also remove a key the pack shipped.
     */
    public void applySettingsTo(JsonObject bundle) {
        if (settings == null) {
            return;
        }
        bundle.add("settings", settings.settings.deepCopy());
        bundle.addProperty("settings_revision", settings.revision);
    }

    /**
     * Replaces the pack's text for every locale the editor wrote; a locale without an overlay file keeps the pack's
     * own object. The object is whole, so a key the editor removed leaves the book instead of falling back to English.
     */
    public void applyLangsTo(Map<String, JsonObject> into) {
        for (Map.Entry<String, LangOverlay> e : langs.entrySet()) {
            into.put(e.getKey(), e.getValue().entries.deepCopy());
        }
    }

    }

    private ProgressionOverlay() {
    }

    private static void readLangs(Path progressionRoot, Overlay out) {
        Path dir = progressionRoot.resolve("lang");
        if (!Files.isDirectory(dir)) {
            return;
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.list(dir)) {
            walk.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".json")).forEach(files::add);
        } catch (IOException ex) {
            out.problems.add(new ProgressValidator.Problem(ProgressValidator.Severity.ERROR, display(progressionRoot, dir),
                    "", "could not read the translation overlay: " + ex.getMessage(), 0));
            return;
        }
        files.sort(Path::compareTo);
        for (Path file : files) {
            String name = file.getFileName().toString();
            String locale = name.substring(0, name.length() - 5).toLowerCase(java.util.Locale.ROOT);
            String shown = display(progressionRoot, file);
            if (!validLocale(locale)) {
                out.problems.add(new ProgressValidator.Problem(ProgressValidator.Severity.ERROR, shown, "",
                        "translation files are named <locale>.json, for example ja_jp.json", 0));
                continue;
            }
            String text;
            try {
                text = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                out.problems.add(new ProgressValidator.Problem(ProgressValidator.Severity.ERROR, shown, "",
                        "could not be read: " + ex.getMessage(), 0));
                continue;
            }
            JsonElement parsed;
            try {
                parsed = JsonParser.parseString(text);
            } catch (Exception ex) {
                out.problems.add(ProgressValidator.parseError(shown, text, ex));
                continue;
            }
            if (!parsed.isJsonObject()) {
                out.problems.add(new ProgressValidator.Problem(ProgressValidator.Severity.ERROR, shown, "",
                        "the translation file is not a JSON object", 0));
                continue;
            }
            JsonObject o = parsed.getAsJsonObject();
            JsonElement entries = o.get("entries");
            if (entries == null || !entries.isJsonObject()) {
                out.problems.add(new ProgressValidator.Problem(ProgressValidator.Severity.ERROR, shown, "entries",
                        "\"entries\" is not an object, so the file is ignored and the pack's text applies", 0));
                continue;
            }
            LangOverlay overlay = new LangOverlay(locale);
            overlay.file = shown;
            overlay.raw = text;
            overlay.revision = number(o, "revision");
            overlay.entries = entries.getAsJsonObject().deepCopy();
            out.langs.put(locale, overlay);
        }
    }

    /** Whether a locale folder name is one the game would ask for: {@code en_us}, {@code ja_jp}, {@code zh_cn}. */
    public static boolean validLocale(String locale) {
        if (locale == null || locale.length() < 2 || locale.length() > 16) {
            return false;
        }
        for (int i = 0; i < locale.length(); i++) {
            char c = locale.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '_') {
                return false;
            }
        }
        return true;
    }

    /** Writes {@code lang/<locale>.json} atomically; the caller passes the new revision and the whole locale object. */
    public static void writeLang(Path progressionRoot, String locale, long revision, JsonObject entries) throws IOException {
        LangOverlay overlay = new LangOverlay(locale);
        overlay.revision = revision;
        overlay.entries = entries.deepCopy();
        Path dir = progressionRoot.resolve("lang");
        Files.createDirectories(dir);
        Path file = dir.resolve(locale + ".json");
        Path temp = Files.createTempFile(dir, "lang", ".tmp");
        try {
            Files.writeString(temp, GSON.toJson(overlay.toJson()), StandardCharsets.UTF_8);
            moveAtomically(temp, file);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void readSettings(Path progressionRoot, Overlay out) {
        Path file = progressionRoot.resolve("settings.json");
        if (!Files.isRegularFile(file)) {
            return;
        }
        String shown = display(progressionRoot, file);
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            out.problems.add(new ProgressValidator.Problem(ProgressValidator.Severity.ERROR, shown, "",
                    "could not be read: " + ex.getMessage(), 0));
            return;
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(text);
        } catch (Exception ex) {
            out.problems.add(ProgressValidator.parseError(shown, text, ex));
            return;
        }
        if (!parsed.isJsonObject()) {
            out.problems.add(new ProgressValidator.Problem(ProgressValidator.Severity.ERROR, shown, "",
                    "the settings file is not a JSON object", 0));
            return;
        }
        JsonObject o = parsed.getAsJsonObject();
        SettingsOverlay overlay = new SettingsOverlay();
        overlay.file = shown;
        overlay.raw = text;
        overlay.revision = number(o, "revision");
        JsonElement settings = o.get("settings");
        if (settings == null || !settings.isJsonObject()) {
            out.problems.add(new ProgressValidator.Problem(ProgressValidator.Severity.ERROR, shown, "settings",
                    "\"settings\" is not an object, so the file is ignored and the pack's settings apply", 0));
            return;
        }
        overlay.settings = settings.getAsJsonObject().deepCopy();
        out.settings = overlay;
    }

    /** Writes {@code settings.json} atomically; the caller passes the new revision and the complete settings object. */
    public static void writeSettings(Path progressionRoot, long revision, JsonObject settings) throws IOException {
        SettingsOverlay overlay = new SettingsOverlay();
        overlay.revision = revision;
        overlay.settings = settings.deepCopy();
        Path file = progressionRoot.resolve("settings.json");
        Files.createDirectories(progressionRoot);
        Path temp = Files.createTempFile(progressionRoot, "settings", ".tmp");
        try {
            Files.writeString(temp, GSON.toJson(overlay.toJson()), StandardCharsets.UTF_8);
            moveAtomically(temp, file);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void readRestrictions(Path progressionRoot, Overlay out) {
        Path file = progressionRoot.resolve("restrictions.json");
        if (!Files.isRegularFile(file)) {
            return;
        }
        String shown = display(progressionRoot, file);
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            out.problems.add(new ProgressValidator.Problem(ProgressValidator.Severity.ERROR, shown, "",
                    "could not be read: " + ex.getMessage(), 0));
            return;
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(text);
        } catch (Exception ex) {
            out.problems.add(ProgressValidator.parseError(shown, text, ex));
            return;
        }
        if (!parsed.isJsonObject()) {
            out.problems.add(new ProgressValidator.Problem(ProgressValidator.Severity.ERROR, shown, "",
                    "the restrictions file is not a JSON object", 0));
            return;
        }
        JsonObject o = parsed.getAsJsonObject();
        RestrictionOverlay overlay = new RestrictionOverlay();
        overlay.file = shown;
        overlay.raw = text;
        overlay.revision = number(o, "revision");
        JsonElement rules = o.get("rules");
        if (rules != null && rules.isJsonArray()) {
            overlay.rules = rules.getAsJsonArray().deepCopy();
            // The editor writes the whole array, so its parse problems must reach /quest_tree validate like the pack's
            // own file does. Without this line a saved bad rule was read at runtime but never listed (2026-10-22).
            for (com.kuronami.questtree.progress.RestrictionRules.Problem problem
                    : com.kuronami.questtree.progress.RestrictionRules.parse(overlay.rules).problems()) {
                out.problems.add(new ProgressValidator.Problem(ProgressValidator.Severity.WARNING, shown,
                        problem.path(), problem.message(), 0));
            }
        } else {
            out.problems.add(new ProgressValidator.Problem(ProgressValidator.Severity.ERROR, shown, "rules",
                    "\"rules\" is not an array, so the file is ignored and the pack's rules apply", 0));
            return;
        }
        out.restrictions = overlay;
    }

    /** Writes {@code restrictions.json} atomically; the caller passes the new revision and the complete rule array. */
    public static void writeRestrictions(Path progressionRoot, long revision, JsonArray rules) throws IOException {
        RestrictionOverlay overlay = new RestrictionOverlay();
        overlay.revision = revision;
        overlay.rules = rules.deepCopy();
        Path file = progressionRoot.resolve("restrictions.json");
        Files.createDirectories(progressionRoot);
        Path temp = Files.createTempFile(progressionRoot, "restrictions", ".tmp");
        try {
            Files.writeString(temp, GSON.toJson(overlay.toJson()), StandardCharsets.UTF_8);
            moveAtomically(temp, file);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Reads the overlay under {@code progressionRoot} (the {@code progression} folder, not the world root). */
    public static Overlay read(Path progressionRoot) {
        Overlay out = new Overlay();
        readSettings(progressionRoot, out);
        readRestrictions(progressionRoot, out);
        Path chaptersDir = progressionRoot.resolve("chapters");
        if (!Files.isDirectory(chaptersDir)) {
            readTables(progressionRoot, out);
            readRevisions(progressionRoot, out);
            readTableRevisions(progressionRoot, out);
            readLangs(progressionRoot, out);
            return out;
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(chaptersDir)) {
            walk.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".json")).forEach(files::add);
        } catch (IOException ex) {
            out.problems.add(new ProgressValidator.Problem(ProgressValidator.Severity.ERROR, display(progressionRoot, chaptersDir),
                    "", "could not read the editor overlay: " + ex.getMessage(), 0));
            return out;
        }
        files.sort(Path::compareTo);
        for (Path file : files) {
            String relative = chaptersDir.relativize(file).toString().replace(java.io.File.separatorChar, '/');
            String id = chapterId(relative);
            String shown = display(progressionRoot, file);
            if (id == null) {
                out.problems.add(new ProgressValidator.Problem(ProgressValidator.Severity.ERROR, shown, "",
                        "overlay chapter file is not chapters/<namespace>/<chapter>.json", 0));
                continue;
            }
            String text;
            try {
                text = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                out.problems.add(new ProgressValidator.Problem(ProgressValidator.Severity.ERROR, shown, "",
                        "could not be read: " + ex.getMessage(), 0));
                continue;
            }
            JsonElement parsed;
            try {
                parsed = JsonParser.parseString(text);
            } catch (Exception ex) {
                out.problems.add(ProgressValidator.parseError(shown, text, ex));
                continue;
            }
            if (!parsed.isJsonObject()) {
                out.problems.add(new ProgressValidator.Problem(ProgressValidator.Severity.ERROR, shown, "",
                        "the chapter file is not a JSON object", 0));
                continue;
            }
            ChapterOverlay overlay = readChapter(id, parsed.getAsJsonObject(), shown, text, out.problems);
            if (out.chapters.containsKey(id)) {
                out.problems.add(new ProgressValidator.Problem(ProgressValidator.Severity.ERROR, shown, "",
                        "chapter " + id + " is declared by more than one overlay file", 0));
                continue;
            }
            out.chapters.put(id, overlay);
            out.revisions.merge(id, overlay.revision, Math::max);
        }
        readTables(progressionRoot, out);
        readRevisions(progressionRoot, out);
        readTableRevisions(progressionRoot, out);
        readLangs(progressionRoot, out);
        return out;
    }

    /** Reads {@code tables/<namespace>/<table>.json}; a table is one object, not a patch of one. */
    private static void readTables(Path progressionRoot, Overlay out) {
        Path tablesDir = progressionRoot.resolve("tables");
        if (!Files.isDirectory(tablesDir)) {
            return;
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(tablesDir)) {
            walk.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".json")).forEach(files::add);
        } catch (IOException ex) {
            out.problems.add(new ProgressValidator.Problem(ProgressValidator.Severity.ERROR,
                    display(progressionRoot, tablesDir), "",
                    "could not read the table overlay: " + ex.getMessage(), 0));
            return;
        }
        files.sort(Path::compareTo);
        for (Path file : files) {
            String relative = tablesDir.relativize(file).toString().replace(java.io.File.separatorChar, '/');
            String id = chapterId(relative);
            String shown = display(progressionRoot, file);
            if (id == null) {
                out.problems.add(new ProgressValidator.Problem(ProgressValidator.Severity.ERROR, shown, "",
                        "overlay table file is not tables/<namespace>/<table>.json", 0));
                continue;
            }
            String text;
            try {
                text = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                out.problems.add(new ProgressValidator.Problem(ProgressValidator.Severity.ERROR, shown, "",
                        "could not be read: " + ex.getMessage(), 0));
                continue;
            }
            JsonElement parsed;
            try {
                parsed = JsonParser.parseString(text);
            } catch (Exception ex) {
                out.problems.add(ProgressValidator.parseError(shown, text, ex));
                continue;
            }
            if (!parsed.isJsonObject()) {
                out.problems.add(new ProgressValidator.Problem(ProgressValidator.Severity.ERROR, shown, "",
                        "the table file is not a JSON object", 0));
                continue;
            }
            TableOverlay overlay = readTable(id, parsed.getAsJsonObject(), shown, text, out.problems);
            if (out.tables.containsKey(id)) {
                out.problems.add(new ProgressValidator.Problem(ProgressValidator.Severity.ERROR, shown, "",
                        "table " + id + " is declared by more than one overlay file", 0));
                continue;
            }
            out.tables.put(id, overlay);
            out.tableRevisions.merge(id, overlay.revision, Math::max);
        }
    }

    static TableOverlay readTable(String id, JsonObject o, String file, String raw,
                                  List<ProgressValidator.Problem> problems) {
        TableOverlay overlay = new TableOverlay(id);
        overlay.file = file;
        overlay.raw = raw;
        overlay.revision = number(o, "revision");
        overlay.base = bool(o, "base");
        overlay.remove = bool(o, "remove");
        JsonElement table = o.get("table");
        if (table == null || !table.isJsonObject()) {
            problems.add(new ProgressValidator.Problem(ProgressValidator.Severity.ERROR, file, "table",
                    "\"table\" is not an object, so nothing in it is read", 0));
        } else {
            overlay.table = table.getAsJsonObject().deepCopy();
        }
        return overlay;
    }

    /**
     * Reads {@code revisions.json}, the small sidecar that keeps each chapter's revision going across an undo that
     * deleted the chapter file. The highest of the file's own number and the sidecar wins, so a hand edit cannot make a
     * chapter look older than it was.
     */
    private static void readRevisions(Path progressionRoot, Overlay out) {
        Path file = progressionRoot.resolve("revisions.json");
        if (!Files.isRegularFile(file)) {
            return;
        }
        String shown = display(progressionRoot, file);
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                out.problems.add(new ProgressValidator.Problem(ProgressValidator.Severity.ERROR, shown, "",
                        "the revision sidecar is not a JSON object", 0));
                return;
            }
            for (Map.Entry<String, JsonElement> e : parsed.getAsJsonObject().entrySet()) {
                JsonElement value = e.getValue();
                if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                    continue;
                }
                out.revisions.merge(e.getKey(), Math.max(0, value.getAsLong()), Math::max);
            }
        } catch (IOException ex) {
            out.problems.add(new ProgressValidator.Problem(ProgressValidator.Severity.ERROR, shown, "",
                    "could not be read: " + ex.getMessage(), 0));
        } catch (RuntimeException ex) {
            out.problems.add(ProgressValidator.parseError(shown,
                    readQuietly(file), ex));
        }
    }

    /** Writes the revision sidecar atomically; the caller passes the overlay's complete map. */
    public static void writeRevisions(Path progressionRoot, Map<String, Long> revisions) throws IOException {
        writeNumbers(progressionRoot.resolve("revisions.json"), revisions);
    }

    /** Writes the table revision sidecar; separate from chapters so one kind of edit never moves the other's numbers. */
    public static void writeTableRevisions(Path progressionRoot, Map<String, Long> revisions) throws IOException {
        writeNumbers(progressionRoot.resolve("table_revisions.json"), revisions);
    }

    private static void writeNumbers(Path file, Map<String, Long> revisions) throws IOException {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, Long> e : revisions.entrySet()) {
            out.addProperty(e.getKey(), e.getValue());
        }
        Path root = file.getParent();
        Files.createDirectories(root);
        Path temp = Files.createTempFile(root, "revisions", ".tmp");
        try {
            Files.writeString(temp, GSON.toJson(out), StandardCharsets.UTF_8);
            moveAtomically(temp, file);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void readTableRevisions(Path progressionRoot, Overlay out) {
        Path file = progressionRoot.resolve("table_revisions.json");
        if (!Files.isRegularFile(file)) {
            return;
        }
        String shown = display(progressionRoot, file);
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                out.problems.add(new ProgressValidator.Problem(ProgressValidator.Severity.ERROR, shown, "",
                        "the table revision sidecar is not a JSON object", 0));
                return;
            }
            for (Map.Entry<String, JsonElement> e : parsed.getAsJsonObject().entrySet()) {
                JsonElement value = e.getValue();
                if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                    continue;
                }
                out.tableRevisions.merge(e.getKey(), Math.max(0, value.getAsLong()), Math::max);
            }
        } catch (IOException ex) {
            out.problems.add(new ProgressValidator.Problem(ProgressValidator.Severity.ERROR, shown, "",
                    "could not be read: " + ex.getMessage(), 0));
        } catch (RuntimeException ex) {
            out.problems.add(ProgressValidator.parseError(shown, readQuietly(file), ex));
        }
    }

    private static String readQuietly(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "";
        }
    }

    private static void moveAtomically(Path temp, Path file) throws IOException {
        try {
            Files.move(temp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static ChapterOverlay readChapter(String id, JsonObject o, String file, String raw,
                                      List<ProgressValidator.Problem> problems) {
        ChapterOverlay overlay = new ChapterOverlay(id);
        overlay.file = file;
        overlay.raw = raw;
        overlay.revision = number(o, "revision");
        overlay.base = bool(o, "base");
        overlay.replace = bool(o, "replace");
        overlay.remove = bool(o, "remove");
        overlay.edited = bool(o, "edited");
        JsonElement chapter = o.get("chapter");
        if (chapter != null) {
            if (chapter.isJsonObject()) {
                overlay.chapter = chapter.getAsJsonObject().deepCopy();
            } else {
                problems.add(new ProgressValidator.Problem(ProgressValidator.Severity.ERROR, file, "chapter",
                        "\"chapter\" is not an object, so nothing in it is read", 0));
            }
        }
        JsonElement removed = o.get("removed");
        if (removed != null) {
            if (!removed.isJsonArray()) {
                problems.add(new ProgressValidator.Problem(ProgressValidator.Severity.ERROR, file, "removed",
                        "\"removed\" is not an array, so no field removal is read", 0));
            } else {
                for (JsonElement name : removed.getAsJsonArray()) {
                    if (name.isJsonPrimitive() && name.getAsJsonPrimitive().isString()) {
                        overlay.removed.add(name.getAsString());
                    }
                }
            }
        }
        JsonElement nodes = o.get("nodes");
        if (nodes != null) {
            if (!nodes.isJsonObject()) {
                problems.add(new ProgressValidator.Problem(ProgressValidator.Severity.ERROR, file, "nodes",
                        "\"nodes\" is not an object, so no node entry is read", 0));
            } else {
                for (Map.Entry<String, JsonElement> e : nodes.getAsJsonObject().entrySet()) {
                    String path = "nodes." + e.getKey();
                    if (!e.getValue().isJsonObject()) {
                        problems.add(new ProgressValidator.Problem(ProgressValidator.Severity.ERROR, file, path,
                                "node entry is not an object", 0));
                        continue;
                    }
                    JsonObject entry = e.getValue().getAsJsonObject();
                    Action action = action(entry);
                    JsonObject node = new JsonObject();
                    JsonElement content = entry.get("node");
                    if (content != null) {
                        if (content.isJsonObject()) {
                            node = content.getAsJsonObject().deepCopy();
                        } else {
                            problems.add(new ProgressValidator.Problem(ProgressValidator.Severity.ERROR, file, path + ".node",
                                    "\"node\" is not an object", 0));
                        }
                    }
                    if (action == Action.REMOVE && node.isEmpty()) {
                        problems.add(new ProgressValidator.Problem(ProgressValidator.Severity.WARNING, file, path,
                                "removed node has no content, so there is nothing to restore", 0));
                    }
                    overlay.nodes.put(e.getKey(), new NodeChange(action, node, bool(entry, "edited")));
                }
            }
        }
        return overlay;
    }

    private static Action action(JsonObject entry) {
        JsonElement a = entry.get("action");
        if (a != null && a.isJsonPrimitive()) {
            return switch (a.getAsString()) {
                case "replace" -> Action.REPLACE;
                case "remove" -> Action.REMOVE;
                default -> Action.ADD;
            };
        }
        if (bool(entry, "remove")) {
            return Action.REMOVE;
        }
        return bool(entry, "replace") ? Action.REPLACE : Action.ADD;
    }

    private static long number(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? Math.max(0, e.getAsLong()) : 0;
    }

    private static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    /** {@code test/basics.json} → {@code test:basics}; null when the path is not a safe chapter id. */
    private static String chapterId(String relative) {
        if (!relative.endsWith(".json")) {
            return null;
        }
        String path = relative.substring(0, relative.length() - 5);
        int slash = path.indexOf('/');
        if (slash <= 0 || slash == path.length() - 1) {
            return null;
        }
        String namespace = path.substring(0, slash);
        String chapter = path.substring(slash + 1);
        if (!safeSegment(namespace)) {
            return null;
        }
        for (String part : chapter.split("/")) {
            if (!safeSegment(part)) {
                return null;
            }
        }
        return namespace + ":" + chapter;
    }

    /**
     * One path segment of a chapter id. Reading keeps no length cap: a pack may already ship a long id, and
     * rejecting it here would turn the whole layer into read errors, which blocks every editor write in the world.
     * Editor-made ids are capped where they are minted ({@link ProgressionEditor#safeSegment}).
     */
    private static boolean safeSegment(String s) {
        return !s.isEmpty() && !s.equals(".") && !s.equals("..")
                && s.chars().allMatch(c -> c == '_' || c == '-' || c == '.' || Character.isLetterOrDigit(c));
    }

    private static String display(Path root, Path file) {
        return root.relativize(file).toString().replace(java.io.File.separatorChar, '/');
    }
}
