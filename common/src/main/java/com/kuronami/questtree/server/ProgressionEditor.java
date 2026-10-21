package com.kuronami.questtree.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kuronami.questtree.Constants;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * Applies one editor op to the writable overlay, validates the candidate, and writes only when the validator would
 * still load the book (2026-10-20).
 * <p>
 * The ordering is the whole point: the previous overlay is read from disk, the op is applied <em>in memory</em>, the
 * chapter is checked with {@link ProgressValidator} (including references from other chapters, because deleting a node
 * can break a reader and not just the chapter being edited), and the file is written last. A refusal therefore costs
 * nothing but the reply.
 * <p>
 * Each chapter has its own revision. A client edits against the revision it last saw; an older one is refused instead
 * of merged, which is what keeps two operators from silently overwriting each other. Undo is a snapshot of the chapter
 * file, the same shape Tenet uses: it survives ordinary edits but not a server restart, and the tombstone on disk is
 * what survives the restart.
 * <p>
 * Ids are minted here, never carried by the client. A node id is unique within its chapter and short hex; renaming is
 * not an operation, because it would invalidate requirements, exclusivity, translation keys and every player's
 * progress at once.
 */
public final class ProgressionEditor {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int HISTORY_LIMIT = 50;

    /** What happened, in the shape of {@code EditorReplyPayload}. */
    public record Outcome(boolean ok, boolean stale, boolean changed, String createdId, long revision,
                          List<String> messages) {
    }

    private record Snapshot(String text) {
    }

    private static final class History {
        final Deque<Snapshot> undo = new ArrayDeque<>();
        final Deque<Snapshot> redo = new ArrayDeque<>();
    }

    private final Path overlayRoot;
    private final Supplier<String> ids;
    private final ProgressValidator.Registry registry;
    private final Map<String, History> histories = new LinkedHashMap<>();
    /** Table undo steps, kept apart from {@link #histories} so a table and a chapter that share an id never mix. */
    private final Map<String, History> tableHistories = new LinkedHashMap<>();

    public ProgressionEditor(Path worldRoot) {
        this(worldRoot, ProgressionEditor::randomId, ProgressValidator.Registry.NONE);
    }

    public ProgressionEditor(Path worldRoot, ProgressValidator.Registry registry) {
        this(worldRoot, ProgressionEditor::randomId, registry);
    }

    public ProgressionEditor(Path worldRoot, Supplier<String> ids, ProgressValidator.Registry registry) {
        // LevelResource.ROOT is "." on a real server: an unnormalized root makes every file path contain "/./", so the
        // fileFor/tableFileFor startsWith guard refused every write as "outside the overlay". Normalize once here.
        this.overlayRoot = worldRoot.toAbsolutePath().normalize().resolve(ProgressionOverlay.DIR);
        this.ids = ids;
        this.registry = registry;
    }

    public Path overlayRoot() {
        return overlayRoot;
    }

    /**
     * One op.
     *
     * @param packChapters the pack's chapters after patches (no overlay)
     * @param tables       the pack's reward tables
     * @param packSources  the pack's raw text, for validator lines
     * @param chapterId    the chapter the op is about
     * @param revision     the revision the client edited against
     * @param op           one of the op names listed in {@link #applyOp}
     * @param args         the op's JSON arguments
     * @param unreadable   pack chapters whose file would not parse (the validator cannot resolve references into them)
     */
    public Outcome apply(JsonObject packChapters, JsonObject tables, Map<String, ProgressValidator.Source> packSources,
                         String chapterId, long revision, String op, JsonObject args, Set<String> unreadable) {
        return apply(packChapters, tables, packSources, chapterId, revision, op, args, unreadable, 2);
    }

    /**
     * One op, from a writer with {@code writerPermissionLevel}. Adding or changing a command reward (and its
     * permission level) is a capability the writer must already hold themselves: an editor with level 2 must not be
     * able to write a level-4 command for someone else to trigger (2026-10-22 release review).
     */
    public Outcome apply(JsonObject packChapters, JsonObject tables, Map<String, ProgressValidator.Source> packSources,
                         String chapterId, long revision, String op, JsonObject args, Set<String> unreadable,
                         int writerPermissionLevel) {
        // A chapter does not exist yet when it is being created, so it has no id to carry: the request sends an empty
        // chapterId and the namespace in the args, and the server mints the id. Every other op needs a real one.
        boolean creatingChapter = op.equals("create_chapter");
        boolean tableOp = op.equals("update_table") || op.equals("delete_table") || op.equals("restore_table");
        if (!creatingChapter && !tableOp && !validChapterId(chapterId)) {
            return fail("chapter id is not <namespace>:<chapter>", 0);
        }
        ProgressionOverlay.Overlay overlay = ProgressionOverlay.read(overlayRoot);
        List<String> readErrors = new ArrayList<>(errors(overlay.problems));
        if (!readErrors.isEmpty()) {
            // writing on top of a layer that cannot be read would hide the hand edit that broke it
            return fail(readErrors, creatingChapter ? 0 : overlay.revision(chapterId));
        }
        args = args == null ? new JsonObject() : args;
        if (tableOp) {
            return applyTable(overlay, packChapters, tables, packSources, chapterId, revision, op, args, unreadable,
                    writerPermissionLevel);
        }
        JsonObject before = packChapters.deepCopy();
        Map<String, ProgressValidator.Source> beforeSources = new LinkedHashMap<>(packSources);
        overlay.applyTo(before, beforeSources);
        long current = creatingChapter ? 0 : overlay.revision(chapterId);
        // a table undo names the table's revision, not a chapter's, so it is checked by tableHistory itself
        if ((op.equals("undo") || op.equals("redo"))
                && undoIsAboutATable(overlay, packChapters, tables, chapterId)) {
            return tableHistory(overlay, packChapters, tables, packSources, chapterId, revision, op, unreadable,
                    writerPermissionLevel);
        }
        if (!creatingChapter && revision != current) {
            return new Outcome(false, true, false, "", current,
                    List.of("chapter changed while you were editing it; reload it and try again"));
        }
        if (op.equals("undo") || op.equals("redo")) {
            return history(overlay, packChapters, tables, packSources, chapterId, current, op, unreadable,
                    writerPermissionLevel);
        }
        return applyOp(overlay, packChapters, tables, packSources, before, beforeSources, chapterId, current,
                op, args, unreadable, writerPermissionLevel);
    }

    // ------------------------------------------------------------------ operations

    private Outcome applyOp(ProgressionOverlay.Overlay overlay, JsonObject packChapters, JsonObject tables,
                            Map<String, ProgressValidator.Source> packSources, JsonObject before,
                            Map<String, ProgressValidator.Source> beforeSources, String chapterId, long current,
                            String op, JsonObject args, Set<String> unreadable, int writerPermissionLevel) {
        boolean packHasChapter = object(packChapters, chapterId);
        ProgressionOverlay.ChapterOverlay overlayChapter = overlay.chapter(chapterId);
        String createdId = "";
        JsonObject currentChapter = object(before, chapterId) ? before.getAsJsonObject(chapterId) : null;

        switch (op) {
            case "create_chapter" -> {
                if (currentChapter != null) {
                    return fail("chapter " + chapterId + " already exists", current);
                }
                String namespace = text(args, "namespace", Constants.MOD_ID);
                if (!safeSegment(namespace)) {
                    return fail("namespace is not a valid resource namespace", current);
                }
                String name;
                do {
                    name = ids.get();
                } while (!safeSegment(name) || overlay.chapter(namespace + ":" + name) != null);
                String id = namespace + ":" + name;
                ProgressionOverlay.ChapterOverlay created = new ProgressionOverlay.ChapterOverlay(id);
                created.base = false;
                created.replace = true;
                JsonObject initial = args.has("chapter") && args.get("chapter").isJsonObject()
                        ? args.getAsJsonObject("chapter").deepCopy() : new JsonObject();
                JsonElement initialNodes = initial.remove("nodes");
                created.chapter = initial;
                if (initialNodes != null && initialNodes.isJsonObject()) {
                    // The client may describe a starting tree, but its ids are only labels for that request: mint
                    // every id here and rewrite the references between the initial nodes to match. An id sent from
                    // outside would otherwise decide a permanent name (and translation key).
                    Set<String> mintedIds = new HashSet<>();
                    Map<String, String> renamed = new LinkedHashMap<>();
                    for (String oldId : initialNodes.getAsJsonObject().keySet()) {
                        renamed.put(oldId, mintNodeId(mintedIds, created));
                    }
                    for (Map.Entry<String, JsonElement> n : initialNodes.getAsJsonObject().entrySet()) {
                        if (n.getValue().isJsonObject()) {
                            JsonObject node = n.getValue().getAsJsonObject().deepCopy();
                            rewriteNodeReferences(node, renamed);
                            created.nodes.put(renamed.get(n.getKey()), new ProgressionOverlay.NodeChange(
                                    ProgressionOverlay.Action.ADD, node, false));
                        }
                    }
                }
                overlay.chapters.put(id, created);
                createdId = id;
                chapterId = id;
                current = 0;
                overlayChapter = created;
            }
            case "create_node" -> {
                if (currentChapter == null) {
                    return fail("chapter does not exist", current);
                }
                overlayChapter = ensure(overlay, chapterId, packHasChapter);
                JsonObject node = args.has("node") && args.get("node").isJsonObject()
                        ? args.getAsJsonObject("node").deepCopy() : new JsonObject();
                String id = mintNodeId(nodeIds(currentChapter), overlayChapter);
                overlayChapter.nodes.put(id, new ProgressionOverlay.NodeChange(
                        ProgressionOverlay.Action.ADD, node, false));
                createdId = id;
            }
            case "update_node", "move_node" -> {
                if (currentChapter == null) {
                    return fail("chapter does not exist", current);
                }
                overlayChapter = ensure(overlay, chapterId, packHasChapter);
                String id = requiredText(args, "id");
                if (!validNodeId(id)) {
                    return fail("node id must use only a-z, 0-9 and _", current);
                }
                JsonObject existing = node(currentChapter, id);
                if (existing == null) {
                    ProgressionOverlay.NodeChange tomb = overlayChapter.nodes.get(id);
                    if (tomb != null && tomb.action() == ProgressionOverlay.Action.REMOVE) {
                        return fail("node " + id + " is deleted; restore it first", current);
                    }
                    return fail("node " + id + " does not exist", current);
                }
                JsonObject patch;
                if (op.equals("move_node")) {
                    Double x = number(args, "x");
                    Double y = number(args, "y");
                    if (x == null || y == null) {
                        return fail("move_node needs numeric x and y", current);
                    }
                    patch = new JsonObject();
                    patch.addProperty("x", x);
                    patch.addProperty("y", y);
                } else {
                    if (!args.has("patch") || !args.get("patch").isJsonObject()) {
                        return fail("update_node needs a JSON patch object", current);
                    }
                    patch = args.getAsJsonObject("patch");
                    if (patch.has("id")) {
                        return fail("a node id cannot be renamed", current);
                    }
                }
                JsonObject updated = existing.deepCopy();
                mergePatch(updated, patch);
                if (updated.equals(existing)) {
                    return ok("", List.of(), current, false);
                }
                ProgressionOverlay.Action action = packNode(packChapters, chapterId, id) != null
                        ? ProgressionOverlay.Action.REPLACE : ProgressionOverlay.Action.ADD;
                overlayChapter.nodes.put(id, new ProgressionOverlay.NodeChange(action, updated, false));
            }
            case "delete_node" -> {
                if (currentChapter == null) {
                    return fail("chapter does not exist", current);
                }
                overlayChapter = ensure(overlay, chapterId, packHasChapter);
                String id = requiredText(args, "id");
                if (!validNodeId(id)) {
                    return fail("node id must use only a-z, 0-9 and _", current);
                }
                JsonObject existing = node(currentChapter, id);
                if (existing == null) {
                    return fail("node " + id + " does not exist", current);
                }
                boolean edited = overlayChapter.nodes.containsKey(id)
                        && overlayChapter.nodes.get(id).action() != ProgressionOverlay.Action.REMOVE;
                overlayChapter.nodes.put(id, new ProgressionOverlay.NodeChange(
                        ProgressionOverlay.Action.REMOVE, existing.deepCopy(), edited));
            }
            case "restore_node" -> {
                if (currentChapter == null) {
                    return fail("chapter does not exist", current);
                }
                overlayChapter = ensure(overlay, chapterId, packHasChapter);
                String id = requiredText(args, "id");
                if (!validNodeId(id)) {
                    return fail("node id must use only a-z, 0-9 and _", current);
                }
                ProgressionOverlay.NodeChange tomb = overlayChapter.nodes.get(id);
                if (tomb == null || tomb.action() != ProgressionOverlay.Action.REMOVE) {
                    return fail("node " + id + " is not deleted", current);
                }
                JsonObject packNode = packNode(packChapters, chapterId, id);
                if (!tomb.edited() && packNode != null) {
                    // the pack still has it and the overlay had never changed it: let the pack's copy come back
                    overlayChapter.nodes.remove(id);
                } else {
                    overlayChapter.nodes.put(id, new ProgressionOverlay.NodeChange(
                            packNode == null ? ProgressionOverlay.Action.ADD : ProgressionOverlay.Action.REPLACE,
                            tomb.node().deepCopy(), false));
                }
                createdId = id;
            }
            case "update_chapter" -> {
                if (currentChapter == null) {
                    return fail("chapter does not exist", current);
                }
                if (!args.has("patch") || !args.get("patch").isJsonObject()) {
                    return fail("update_chapter needs a JSON patch object", current);
                }
                JsonObject patch = args.getAsJsonObject("patch");
                if (patch.has("nodes")) {
                    return fail("change nodes with the node operations", current);
                }
                overlayChapter = ensure(overlay, chapterId, packHasChapter);
                // RFC 7396 against the chapter the client sees, not against the overlay's fragment. The overlay keeps
                // the full value of every key the patch changed, so a nested patch (display.title) cannot drop the
                // siblings the pack supplied (display.icon).
                JsonObject merged = currentChapter.deepCopy();
                merged.remove("nodes");
                JsonObject updated = merged.deepCopy();
                mergePatch(updated, patch);
                if (updated.equals(merged)) {
                    return ok("", List.of(), current, false);
                }
                if (overlayChapter.replace) {
                    overlayChapter.chapter = updated.deepCopy();
                    overlayChapter.removed.clear();
                } else {
                    JsonObject packChapter = object(packChapters, chapterId) ? packChapters.getAsJsonObject(chapterId) : null;
                    Set<String> keys = new LinkedHashSet<>(merged.keySet());
                    keys.addAll(updated.keySet());
                    for (String key : keys) {
                        if (key.equals("nodes")) {
                            continue;
                        }
                        boolean had = merged.has(key);
                        boolean has = updated.has(key);
                        if (has && (!had || !merged.get(key).equals(updated.get(key)))) {
                            overlayChapter.chapter.add(key, updated.get(key).deepCopy());
                            overlayChapter.removed.remove(key);
                        } else if (!has && had) {
                            overlayChapter.chapter.remove(key);
                            if (packChapter != null && packChapter.has(key)) {
                                // the key came from the pack: only an explicit marker can keep it deleted
                                overlayChapter.removed.add(key);
                            } else {
                                overlayChapter.removed.remove(key);
                            }
                        }
                    }
                }
            }
            case "delete_chapter" -> {
                if (currentChapter == null) {
                    return fail("chapter does not exist", current);
                }
                overlayChapter = ensure(overlay, chapterId, packHasChapter);
                boolean edited = overlayChapter.replace || overlayChapter.chapter.size() > 0
                        || !overlayChapter.nodes.isEmpty();
                overlayChapter.base = packHasChapter;
                overlayChapter.replace = true;
                overlayChapter.remove = true;
                overlayChapter.edited = edited;
                overlayChapter.chapter = currentChapter.deepCopy();
                overlayChapter.nodes.clear();
            }
            case "restore_chapter" -> {
                if (overlayChapter == null || !overlayChapter.remove) {
                    return fail("chapter " + chapterId + " is not deleted", current);
                }
                overlayChapter.remove = false;
                if (!overlayChapter.edited && overlayChapter.base && packHasChapter) {
                    // the pack still has the untouched copy: bring that back rather than pinning a snapshot
                    overlayChapter.replace = false;
                    overlayChapter.chapter = new JsonObject();
                    overlayChapter.removed.clear();
                    overlayChapter.nodes.clear();
                } else {
                    overlayChapter.replace = true;
                }
                overlayChapter.edited = false;
                createdId = chapterId;
            }
            case "link", "unlink" -> {
                if (currentChapter == null) {
                    return fail("chapter does not exist", current);
                }
                overlayChapter = ensure(overlay, chapterId, packHasChapter);
                String from = requiredText(args, "from");
                String to = requiredText(args, "to");
                if (!validNodeId(from) || !validReferenceId(to)) {
                    return fail("link needs ids made of a-z, 0-9 and _", current);
                }
                JsonObject existing = node(currentChapter, from);
                if (existing == null) {
                    return fail("node " + from + " does not exist", current);
                }
                JsonObject requires = existing.has("requires") && existing.get("requires").isJsonObject()
                        ? existing.getAsJsonObject("requires").deepCopy() : new JsonObject();
                if (existing.has("requires") && !existing.get("requires").isJsonObject()) {
                    return fail("\"requires\" is not an object; fix it before linking", current);
                }
                JsonArray names = requires.has("nodes") && requires.get("nodes").isJsonArray()
                        ? requires.getAsJsonArray("nodes").deepCopy() : new JsonArray();
                if (requires.has("nodes") && !requires.get("nodes").isJsonArray()) {
                    return fail("\"requires.nodes\" is not an array; fix it before linking", current);
                }
                boolean present = false;
                for (JsonElement n : names) {
                    if (n.isJsonPrimitive() && n.getAsString().equals(to)) {
                        present = true;
                    }
                }
                if (op.equals("link") && present || op.equals("unlink") && !present) {
                    return ok("", List.of(), current, false);
                }
                JsonArray changed = new JsonArray();
                if (op.equals("link")) {
                    for (JsonElement n : names) {
                        changed.add(n);
                    }
                    changed.add(to);
                } else {
                    for (JsonElement n : names) {
                        if (!n.isJsonPrimitive() || !n.getAsString().equals(to)) {
                            changed.add(n);
                        }
                    }
                }
                requires.add("nodes", changed);
                JsonObject updated = existing.deepCopy();
                updated.add("requires", requires);
                ProgressionOverlay.Action action = packNode(packChapters, chapterId, from) != null
                        ? ProgressionOverlay.Action.REPLACE : ProgressionOverlay.Action.ADD;
                overlayChapter.nodes.put(from, new ProgressionOverlay.NodeChange(action, updated, false));
            }
            default -> {
                return fail("unknown operation " + op, current);
            }
        }

        JsonObject after = packChapters.deepCopy();
        Map<String, ProgressValidator.Source> afterSources = new LinkedHashMap<>(packSources);
        overlay.applyTo(after, afterSources);

        List<String> escalation = commandEscalation(before, after, tables, tables, writerPermissionLevel);
        if (!escalation.isEmpty()) {
            return fail(escalation, current);
        }
        List<String> refusal = newErrors(before, beforeSources, after, afterSources, tables, unreadable);
        if (!refusal.isEmpty()) {
            return fail(refusal, current);
        }
        List<String> warnings = newWarnings(before, beforeSources, after, afterSources, tables, chapterId, unreadable);
        long newRevision = current + 1;
        if (overlayChapter != null) {
            overlayChapter.revision = newRevision;
            try {
                String previous = readText(overlayChapter);
                write(overlayChapter);
                overlay.revisions.put(chapterId, newRevision);
                ProgressionOverlay.writeRevisions(overlayRoot, overlay.revisions);
                pushChapterHistory(chapterId, new Snapshot(previous));
            } catch (IOException ex) {
                Constants.LOG.error("Could not write the progression overlay for {}", chapterId, ex);
                return fail("could not write the overlay: " + ex.getMessage(), current);
            }
        }
        return new Outcome(true, false, true, createdId, newRevision, warnings);
    }

    // ------------------------------------------------------------------ reward tables

    /**
     * The reward-table ops. A table is written whole: its entries are an array, so a patch of an array index would
     * silently move when another operator reordered the entries. {@code create} lets the id come from the form for a
     * table that does not exist yet; every other update names an existing table.
     */
    private Outcome applyTable(ProgressionOverlay.Overlay overlay, JsonObject packChapters, JsonObject packTables,
                               Map<String, ProgressValidator.Source> packSources, String chapterId, long revision,
                               String op, JsonObject args, Set<String> unreadable, int writerPermissionLevel) {
        String id = text(args, "id", chapterId);
        if (!validChapterId(id)) {
            return fail("table id is not <namespace>:<table>", 0);
        }
        return switch (op) {
            case "update_table" -> updateTable(overlay, packChapters, packTables, packSources, chapterId, id,
                    revision, args, unreadable, writerPermissionLevel);
            case "delete_table" -> deleteTable(overlay, packChapters, packTables, packSources, id, revision,
                    unreadable, writerPermissionLevel);
            case "restore_table" -> restoreTable(overlay, packChapters, packTables, packSources, id, revision,
                    unreadable, writerPermissionLevel);
            default -> fail("unknown operation " + op, overlay.tableRevision(id));
        };
    }

    private Outcome updateTable(ProgressionOverlay.Overlay overlay, JsonObject packChapters, JsonObject packTables,
                                Map<String, ProgressValidator.Source> packSources, String chapterId, String id,
                                long revision, JsonObject args, Set<String> unreadable, int writerPermissionLevel) {
        boolean create = args.has("create") && args.get("create").isJsonPrimitive()
                && args.get("create").getAsJsonPrimitive().isBoolean() && args.get("create").getAsBoolean();
        long current = overlay.tableRevision(id);
        if (!create && revision != current) {
            return staleTable(current);
        }
        ProgressionOverlay.TableOverlay existingOverlay = overlay.tables.get(id);
        JsonObject existing = existingOverlay != null && !existingOverlay.remove ? existingOverlay.table
                : object(packTables, id) ? packTables.getAsJsonObject(id) : null;
        if (create && existing != null) {
            return fail("table " + id + " already exists", current);
        }
        if (!create && existing == null) {
            return fail("table " + id + " does not exist", current);
        }
        JsonObject updated;
        if (args.has("table") && args.get("table").isJsonObject()) {
            updated = args.getAsJsonObject("table").deepCopy();
        } else if (args.has("patch") && args.get("patch").isJsonObject() && existing != null) {
            updated = existing.deepCopy();
            mergePatch(updated, args.getAsJsonObject("patch"));
        } else {
            return fail("update_table needs a JSON table object", current);
        }
        if (existing != null && updated.equals(existing)) {
            return ok(create && chapterId.isEmpty() ? id : "", List.of(), current, false);
        }

        JsonObject before = packChapters.deepCopy();
        Map<String, ProgressValidator.Source> beforeSources = new LinkedHashMap<>(packSources);
        overlay.applyTo(before, beforeSources);
        JsonObject beforeTables = packTables.deepCopy();
        overlay.applyTablesTo(beforeTables, beforeSources);

        ProgressionOverlay.TableOverlay table = existingOverlay == null
                ? new ProgressionOverlay.TableOverlay(id) : existingOverlay;
        table.base = object(packTables, id);
        table.remove = false;
        table.revision = current + 1;
        table.table = updated;
        overlay.tables.put(id, table);

        JsonObject afterTables = packTables.deepCopy();
        Map<String, ProgressValidator.Source> afterSources = new LinkedHashMap<>(beforeSources);
        overlay.applyTablesTo(afterTables, afterSources);
        List<String> escalation = commandEscalation(packChapters, packChapters, beforeTables, afterTables,
                writerPermissionLevel);
        if (!escalation.isEmpty()) {
            return fail(escalation, current);
        }
        List<String> refusal = newErrors(before, beforeSources, beforeTables, before, afterSources, afterTables,
                unreadable);
        if (!refusal.isEmpty()) {
            return fail(refusal, current);
        }
        try {
            Path file = tableFileFor(id);
            String previous = readFileQuietly(file);
            backup(file);
            writeRaw(file, table.toJson());
            table.file = tableDisplayPath(file);
            table.raw = GSON.toJson(table.toJson());
            overlay.tableRevisions.put(id, table.revision);
            ProgressionOverlay.writeTableRevisions(overlayRoot, overlay.tableRevisions);
            // a table's write is undoable like a chapter's: the snapshot is the table file the write replaced
            pushTableHistory(id, new Snapshot(previous));
        } catch (IOException ex) {
            Constants.LOG.error("Could not write the progression table overlay for {}", id, ex);
            return fail("could not write the overlay: " + ex.getMessage(), current);
        }
        return new Outcome(true, false, true, create && chapterId.isEmpty() ? id : "", table.revision, List.of());
    }

    /** {@code delete_table}: hide the table and keep its content, so {@code restore_table} can put it back. */
    private Outcome deleteTable(ProgressionOverlay.Overlay overlay, JsonObject packChapters, JsonObject packTables,
                                Map<String, ProgressValidator.Source> packSources, String id, long revision,
                                Set<String> unreadable, int writerPermissionLevel) {
        long current = overlay.tableRevision(id);
        if (revision != current) {
            return staleTable(current);
        }
        ProgressionOverlay.TableOverlay existingOverlay = overlay.tables.get(id);
        JsonObject existing = existingOverlay != null && !existingOverlay.remove ? existingOverlay.table
                : object(packTables, id) ? packTables.getAsJsonObject(id) : null;
        if (existing == null) {
            return fail("table " + id + " does not exist", current);
        }

        JsonObject before = packChapters.deepCopy();
        Map<String, ProgressValidator.Source> beforeSources = new LinkedHashMap<>(packSources);
        overlay.applyTo(before, beforeSources);
        JsonObject beforeTables = packTables.deepCopy();
        overlay.applyTablesTo(beforeTables, beforeSources);

        ProgressionOverlay.TableOverlay tomb = existingOverlay == null
                ? new ProgressionOverlay.TableOverlay(id) : existingOverlay;
        tomb.base = object(packTables, id);
        tomb.remove = true;
        tomb.table = existing.deepCopy();
        tomb.revision = current + 1;
        overlay.tables.put(id, tomb);

        JsonObject afterTables = packTables.deepCopy();
        Map<String, ProgressValidator.Source> afterSources = new LinkedHashMap<>(beforeSources);
        overlay.applyTablesTo(afterTables, afterSources);
        List<String> escalation = commandEscalation(packChapters, packChapters, beforeTables, afterTables,
                writerPermissionLevel);
        if (!escalation.isEmpty()) {
            return fail(escalation, current);
        }
        List<String> refusal = newErrors(before, beforeSources, beforeTables, before, afterSources, afterTables,
                unreadable);
        if (!refusal.isEmpty()) {
            return fail(refusal, current);
        }
        List<String> warnings = newTableWarnings(before, beforeSources, beforeTables, before, afterSources, afterTables,
                unreadable);
        try {
            Path file = tableFileFor(id);
            String previous = readFileQuietly(file);
            backup(file);
            writeRaw(file, tomb.toJson());
            tomb.file = tableDisplayPath(file);
            tomb.raw = GSON.toJson(tomb.toJson());
            overlay.tableRevisions.put(id, tomb.revision);
            ProgressionOverlay.writeTableRevisions(overlayRoot, overlay.tableRevisions);
            pushTableHistory(id, new Snapshot(previous));
        } catch (IOException ex) {
            Constants.LOG.error("Could not write the progression table overlay for {}", id, ex);
            return fail("could not write the overlay: " + ex.getMessage(), current);
        }
        return new Outcome(true, false, true, "", tomb.revision, warnings);
    }

    /** {@code restore_table}: bring back the tombstone's content, or the pack's copy when it was never edited. */
    private Outcome restoreTable(ProgressionOverlay.Overlay overlay, JsonObject packChapters, JsonObject packTables,
                                 Map<String, ProgressValidator.Source> packSources, String id, long revision,
                                 Set<String> unreadable, int writerPermissionLevel) {
        long current = overlay.tableRevision(id);
        if (revision != current) {
            return staleTable(current);
        }
        ProgressionOverlay.TableOverlay table = overlay.tables.get(id);
        if (table == null || !table.remove) {
            return fail("table " + id + " is not deleted", current);
        }

        JsonObject before = packChapters.deepCopy();
        Map<String, ProgressValidator.Source> beforeSources = new LinkedHashMap<>(packSources);
        overlay.applyTo(before, beforeSources);
        JsonObject beforeTables = packTables.deepCopy();
        overlay.applyTablesTo(beforeTables, beforeSources);

        JsonObject packTable = object(packTables, id) ? packTables.getAsJsonObject(id) : null;
        boolean packCopy = table.base && packTable != null && packTable.equals(table.table);
        if (packCopy) {
            // the pack still has the untouched table: let its copy come back instead of pinning a snapshot
            overlay.tables.remove(id);
        } else {
            table.remove = false;
            table.revision = current + 1;
        }

        JsonObject afterTables = packTables.deepCopy();
        Map<String, ProgressValidator.Source> afterSources = new LinkedHashMap<>(beforeSources);
        overlay.applyTablesTo(afterTables, afterSources);
        List<String> escalation = commandEscalation(packChapters, packChapters, beforeTables, afterTables,
                writerPermissionLevel);
        if (!escalation.isEmpty()) {
            return fail(escalation, current);
        }
        List<String> refusal = newErrors(before, beforeSources, beforeTables, before, afterSources, afterTables,
                unreadable);
        if (!refusal.isEmpty()) {
            return fail(refusal, current);
        }
        long newRevision = current + 1;
        try {
            Path file = tableFileFor(id);
            String previous = readFileQuietly(file);
            backup(file);
            if (packCopy) {
                Files.deleteIfExists(file);
            } else {
                table.file = tableDisplayPath(file);
                table.raw = GSON.toJson(table.toJson());
                writeRaw(file, table.toJson());
            }
            overlay.tableRevisions.put(id, newRevision);
            ProgressionOverlay.writeTableRevisions(overlayRoot, overlay.tableRevisions);
            pushTableHistory(id, new Snapshot(previous));
        } catch (IOException ex) {
            Constants.LOG.error("Could not write the progression table overlay for {}", id, ex);
            return fail("could not write the overlay: " + ex.getMessage(), current);
        }
        return new Outcome(true, false, true, packCopy ? "" : id, newRevision, List.of());
    }

    private static Outcome staleTable(long current) {
        return new Outcome(false, true, false, "", current,
                List.of("table changed while you were editing it; reload it and try again"));
    }

    // ------------------------------------------------------------------ undo and redo

    private Outcome history(ProgressionOverlay.Overlay overlay, JsonObject packChapters, JsonObject tables,
                            Map<String, ProgressValidator.Source> packSources, String chapterId, long current,
                            String op, Set<String> unreadable, int writerPermissionLevel) {
        History history = histories.computeIfAbsent(chapterId, k -> new History());
        Deque<Snapshot> from = op.equals("undo") ? history.undo : history.redo;
        Deque<Snapshot> to = op.equals("undo") ? history.redo : history.undo;
        if (from.isEmpty()) {
            return fail("nothing to " + op, current);
        }
        Snapshot target = from.peekLast();
        ProgressionOverlay.ChapterOverlay currentChapter = overlay.chapter(chapterId);
        String currentText = currentChapter == null ? null : readTextQuietly(currentChapter);
        ProgressionOverlay.Overlay restoredOverlay = ProgressionOverlay.read(overlayRoot);
        if (target.text() == null) {
            restoredOverlay.chapters.remove(chapterId);
        } else {
            try {
                JsonObject parsed = JsonParser.parseString(target.text()).getAsJsonObject();
                List<ProgressValidator.Problem> ignored = new ArrayList<>();
                ProgressionOverlay.ChapterOverlay restored = ProgressionOverlay.readChapter(chapterId, parsed,
                        "kuronami_quest_tree/progression/chapters/" + chapterId.replace(':', '/') + ".json",
                        target.text(), ignored);
                restored.revision = current + 1;
                restoredOverlay.chapters.put(chapterId, restored);
            } catch (RuntimeException ex) {
                return fail("the saved undo step cannot be read: " + ex.getMessage(), current);
            }
        }
        JsonObject before = packChapters.deepCopy();
        Map<String, ProgressValidator.Source> beforeSources = new LinkedHashMap<>(packSources);
        overlay.applyTo(before, beforeSources);
        JsonObject after = packChapters.deepCopy();
        Map<String, ProgressValidator.Source> afterSources = new LinkedHashMap<>(packSources);
        restoredOverlay.applyTo(after, afterSources);
        List<String> escalation = commandEscalation(before, after, tables, tables, writerPermissionLevel);
        if (!escalation.isEmpty()) {
            return fail(escalation, current);
        }
        List<String> refusal = newErrors(before, beforeSources, after, afterSources, tables, unreadable);
        if (!refusal.isEmpty()) {
            return fail(refusal, current);
        }
        long newRevision = current + 1;
        try {
            if (target.text() == null) {
                Path file = fileFor(chapterId);
                backup(file);
                Files.deleteIfExists(file);
            } else {
                JsonObject parsed = JsonParser.parseString(target.text()).getAsJsonObject();
                parsed.addProperty("revision", newRevision);
                writeRaw(fileFor(chapterId), parsed);
            }
            restoredOverlay.revisions.put(chapterId, newRevision);
            ProgressionOverlay.writeRevisions(overlayRoot, restoredOverlay.revisions);
        } catch (IOException ex) {
            return fail("could not write the overlay: " + ex.getMessage(), current);
        }
        from.pollLast();
        push(to, new Snapshot(currentText));
        return new Outcome(true, false, true, "", newRevision, List.of());
    }

    /**
     * Undo/redo for a table. Its history is separate from a chapter's, because a table is written whole and its
     * revisions travel in their own sidecar: a chapter undo must not touch a table and the reverse.
     */
    private Outcome tableHistory(ProgressionOverlay.Overlay overlay, JsonObject packChapters, JsonObject packTables,
                                 Map<String, ProgressValidator.Source> packSources, String tableId, long revision,
                                 String op, Set<String> unreadable, int writerPermissionLevel) {
        long current = overlay.tableRevision(tableId);
        History history = tableHistories.computeIfAbsent(tableId, k -> new History());
        Deque<Snapshot> from = op.equals("undo") ? history.undo : history.redo;
        Deque<Snapshot> to = op.equals("undo") ? history.redo : history.undo;
        if (revision != current) {
            return staleTable(current);
        }
        if (from.isEmpty()) {
            return fail("nothing to " + op, current);
        }
        Snapshot target = from.peekLast();
        Path file = tableFileFor(tableId);
        String currentText = readFileQuietly(file);

        ProgressionOverlay.Overlay restoredOverlay = ProgressionOverlay.read(overlayRoot);
        if (target.text() == null) {
            // the write being undone created this table: dropping the overlay entry lets the pack's copy (if any) show
            restoredOverlay.tables.remove(tableId);
        } else {
            try {
                JsonObject parsed = JsonParser.parseString(target.text()).getAsJsonObject();
                List<ProgressValidator.Problem> ignored = new ArrayList<>();
                ProgressionOverlay.TableOverlay restored = ProgressionOverlay.readTable(tableId, parsed,
                        tableDisplayPath(file), target.text(), ignored);
                restored.revision = current + 1;
                restoredOverlay.tables.put(tableId, restored);
            } catch (RuntimeException ex) {
                return fail("the saved undo step cannot be read: " + ex.getMessage(), current);
            }
        }

        JsonObject chapters = packChapters.deepCopy();
        Map<String, ProgressValidator.Source> chapterSources = new LinkedHashMap<>(packSources);
        overlay.applyTo(chapters, chapterSources);
        JsonObject beforeTables = packTables.deepCopy();
        Map<String, ProgressValidator.Source> beforeSources = new LinkedHashMap<>(packSources);
        overlay.applyTablesTo(beforeTables, beforeSources);
        JsonObject afterTables = packTables.deepCopy();
        Map<String, ProgressValidator.Source> afterSources = new LinkedHashMap<>(packSources);
        restoredOverlay.applyTablesTo(afterTables, afterSources);
        List<String> escalation = commandEscalation(chapters, chapters, beforeTables, afterTables,
                writerPermissionLevel);
        if (!escalation.isEmpty()) {
            return fail(escalation, current);
        }
        List<String> refusal = newErrors(chapters, chapterSources, beforeTables, chapters, afterSources, afterTables,
                unreadable);
        if (!refusal.isEmpty()) {
            return fail(refusal, current);
        }

        long newRevision = current + 1;
        try {
            if (target.text() == null) {
                backup(file);
                Files.deleteIfExists(file);
            } else {
                JsonObject parsed = JsonParser.parseString(target.text()).getAsJsonObject();
                parsed.addProperty("revision", newRevision);
                writeRaw(file, parsed);
            }
            restoredOverlay.tableRevisions.put(tableId, newRevision);
            ProgressionOverlay.writeTableRevisions(overlayRoot, restoredOverlay.tableRevisions);
        } catch (IOException ex) {
            return fail("could not write the overlay: " + ex.getMessage(), current);
        }
        from.pollLast();
        push(to, new Snapshot(currentText));
        return new Outcome(true, false, true, "", newRevision, List.of());
    }

    /**
     * An undo request names the chapter to roll back, or a table on a table screen. An id that names a chapter keeps
     * the chapter undo, which is what it did before tables had one; a bare table or a history left by a table whose
     * overlay entry an undo removed is a table undo.
     */
    private boolean undoIsAboutATable(ProgressionOverlay.Overlay overlay, JsonObject packChapters,
                                      JsonObject packTables, String id) {
        if (overlay.chapters.containsKey(id) || object(packChapters, id)) {
            return false;
        }
        return overlay.tables.containsKey(id) || object(packTables, id) || tableHistories.containsKey(id);
    }

    // ------------------------------------------------------------------ command reward escalation

    private static final List<String> COMMAND_KEYS = List.of("command", "unlock_command", "lock_command",
            "permission_level");

    /**
     * A level-2 editor may write ordinary rewards, but not grant a command they could not run themselves. Compares the
     * before/after reward of every node and table entry; a new or changed command key on a writer below level 4 is
     * refused (2026-10-22 release review).
     */
    private static List<String> commandEscalation(JsonObject beforeChapters, JsonObject afterChapters,
                                                  JsonObject beforeTables, JsonObject afterTables, int level) {
        if (level >= 4) {
            return List.of();
        }
        Map<String, JsonObject> before = rewards(beforeChapters, beforeTables);
        Map<String, JsonObject> after = rewards(afterChapters, afterTables);
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, JsonObject> e : after.entrySet()) {
            JsonObject was = before.get(e.getKey());
            JsonObject now = e.getValue();
            if (was != null && now.equals(was)) {
                continue;
            }
            if (was != null && !commandSignature(was).equals(commandSignature(now))) {
                out.add("changing a command reward needs permission level 4 (you have " + level + "): " + e.getKey());
            } else if (was == null && !commandSignature(now).isEmpty()) {
                out.add("adding a command reward needs permission level 4 (you have " + level + "): " + e.getKey());
            }
        }
        return out;
    }

    /** Every reward by a stable place, node rewards and reward-table entries alike. */
    private static Map<String, JsonObject> rewards(JsonObject chapters, JsonObject tables) {
        Map<String, JsonObject> out = new LinkedHashMap<>();
        if (chapters != null) {
            for (Map.Entry<String, JsonElement> c : chapters.entrySet()) {
                if (!c.getValue().isJsonObject()) {
                    continue;
                }
                JsonElement nodes = c.getValue().getAsJsonObject().get("nodes");
                if (nodes == null || !nodes.isJsonObject()) {
                    continue;
                }
                for (Map.Entry<String, JsonElement> n : nodes.getAsJsonObject().entrySet()) {
                    if (!n.getValue().isJsonObject()) {
                        continue;
                    }
                    JsonElement list = n.getValue().getAsJsonObject().get("rewards");
                    if (list == null || !list.isJsonArray()) {
                        continue;
                    }
                    JsonArray array = list.getAsJsonArray();
                    for (int i = 0; i < array.size(); i++) {
                        if (array.get(i).isJsonObject()) {
                            out.put(c.getKey() + "/" + n.getKey() + "#" + i, array.get(i).getAsJsonObject());
                        }
                    }
                }
            }
        }
        if (tables != null) {
            for (Map.Entry<String, JsonElement> t : tables.entrySet()) {
                if (!t.getValue().isJsonObject()) {
                    continue;
                }
                JsonElement entries = t.getValue().getAsJsonObject().get("entries");
                if (entries == null || !entries.isJsonArray()) {
                    continue;
                }
                JsonArray array = entries.getAsJsonArray();
                for (int i = 0; i < array.size(); i++) {
                    if (!array.get(i).isJsonObject()) {
                        continue;
                    }
                    JsonElement reward = array.get(i).getAsJsonObject().get("reward");
                    if (reward != null && reward.isJsonObject()) {
                        out.put("table:" + t.getKey() + "#" + i, reward.getAsJsonObject());
                    }
                }
            }
        }
        return out;
    }

    /** The command-running part of a reward; empty for a reward that cannot run a command. */
    private static String commandSignature(JsonObject reward) {
        boolean command = "command".equals(text(reward, "type", ""));
        for (String key : COMMAND_KEYS) {
            if (reward.has(key)) {
                command = true;
            }
        }
        if (!command) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (String key : COMMAND_KEYS) {
            b.append(key).append('=').append(reward.has(key) ? reward.get(key).toString() : "").append('\n');
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ validation

    private List<String> newErrors(JsonObject before, Map<String, ProgressValidator.Source> beforeSources,
                                   JsonObject after, Map<String, ProgressValidator.Source> afterSources,
                                   JsonObject tables, Set<String> unreadable) {
        return newErrors(before, beforeSources, tables, after, afterSources, tables, unreadable);
    }

    private List<String> newErrors(JsonObject before, Map<String, ProgressValidator.Source> beforeSources,
                                   JsonObject beforeTables, JsonObject after,
                                   Map<String, ProgressValidator.Source> afterSources, JsonObject afterTables,
                                   Set<String> unreadable) {
        // Compare what the problem is, not which file and line it was read from: after an edit the same unchanged
        // problem in this chapter moves from the pack file to the overlay file. Comparing format() would call every
        // pre-existing error "new" and refuse every edit of a pack that already had one.
        Set<String> old = errorKeys(run(before, beforeTables, beforeSources, unreadable).problems);
        List<String> fresh = new ArrayList<>();
        for (ProgressValidator.Problem p : run(after, afterTables, afterSources, unreadable).problems) {
            if (p.severity() == ProgressValidator.Severity.ERROR && !old.contains(errorKey(p))) {
                fresh.add(p.format());
            }
        }
        return fresh;
    }

    private List<String> newWarnings(JsonObject before, Map<String, ProgressValidator.Source> beforeSources,
                                     JsonObject after, Map<String, ProgressValidator.Source> afterSources,
                                     JsonObject tables, String chapterId, Set<String> unreadable) {
        Map<String, String> old = warnings(before, beforeSources, tables, chapterId, unreadable);
        List<String> fresh = new ArrayList<>();
        for (Map.Entry<String, String> e : warnings(after, afterSources, tables, chapterId, unreadable).entrySet()) {
            if (!old.containsKey(e.getKey())) {
                fresh.add(e.getValue());
            }
        }
        return fresh;
    }

    /** Warning sentence per identity, so an unchanged warning is not reported again by an edit that only moves it. */
    private Map<String, String> warnings(JsonObject chapters, Map<String, ProgressValidator.Source> sources,
                                         JsonObject tables, String chapterId, Set<String> unreadable) {
        ProgressValidator.Result result = ProgressValidator.validateChapter(chapterId,
                object(chapters, chapterId) ? chapters.getAsJsonObject(chapterId) : null,
                chapters, tables, sources, registry);
        Map<String, String> out = new LinkedHashMap<>();
        for (ProgressValidator.Problem p : result.problems) {
            if (p.severity() == ProgressValidator.Severity.WARNING) {
                out.putIfAbsent(errorKey(p), p.format());
            }
        }
        return out;
    }

    /**
     * A table is used by chapters, not by one chapter's file: deleting it can add a warning in any of them, so the
     * comparison covers the whole book. The reply then carries those new sentences (for example a reward whose table
     * no longer exists) instead of letting the reference break silently.
     */
    private List<String> newTableWarnings(JsonObject before, Map<String, ProgressValidator.Source> beforeSources,
                                          JsonObject beforeTables, JsonObject after,
                                          Map<String, ProgressValidator.Source> afterSources, JsonObject afterTables,
                                          Set<String> unreadable) {
        Map<String, String> old = tableWarnings(before, beforeTables, beforeSources, unreadable);
        List<String> fresh = new ArrayList<>();
        for (Map.Entry<String, String> e : tableWarnings(after, afterTables, afterSources, unreadable).entrySet()) {
            if (!old.containsKey(e.getKey())) {
                fresh.add(e.getValue());
            }
        }
        return fresh;
    }

    private Map<String, String> tableWarnings(JsonObject chapters, JsonObject tables,
                                              Map<String, ProgressValidator.Source> sources, Set<String> unreadable) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String chapterId : chapters.keySet()) {
            if (!object(chapters, chapterId)) {
                continue;
            }
            ProgressValidator.Result result = ProgressValidator.validateChapter(chapterId,
                    chapters.getAsJsonObject(chapterId), chapters, tables, sources, registry);
            for (ProgressValidator.Problem p : result.problems) {
                if (p.severity() == ProgressValidator.Severity.WARNING) {
                    // the chapter is part of the identity: the same path in two chapters is two different warnings
                    out.putIfAbsent(chapterId + "\u0000" + errorKey(p), p.format());
                }
            }
        }
        return out;
    }

    private ProgressValidator.Result run(JsonObject chapters, JsonObject tables,
                                         Map<String, ProgressValidator.Source> sources, Set<String> unreadable) {
        JsonObject bundle = new JsonObject();
        bundle.add("chapters", chapters);
        bundle.add("reward_tables", tables);
        return ProgressValidator.validate(bundle, sources, registry, unreadable);
    }

    private static Set<String> errors(List<ProgressValidator.Problem> problems) {
        Set<String> out = new LinkedHashSet<>();
        for (ProgressValidator.Problem p : problems) {
            if (p.severity() == ProgressValidator.Severity.ERROR) {
                out.add(p.format());
            }
        }
        return out;
    }

    private static Set<String> errorKeys(List<ProgressValidator.Problem> problems) {
        Set<String> out = new LinkedHashSet<>();
        for (ProgressValidator.Problem p : problems) {
            if (p.severity() == ProgressValidator.Severity.ERROR) {
                out.add(errorKey(p));
            }
        }
        return out;
    }

    /** A problem's identity for before/after comparison: where in the data, and what is wrong. */
    private static String errorKey(ProgressValidator.Problem p) {
        return (p.path() == null ? "" : p.path()) + "\u0000" + (p.message() == null ? "" : p.message());
    }

    // ------------------------------------------------------------------ files

    private ProgressionOverlay.ChapterOverlay ensure(ProgressionOverlay.Overlay overlay, String chapterId,
                                                     boolean packHasChapter) {
        ProgressionOverlay.ChapterOverlay chapter = overlay.chapter(chapterId);
        if (chapter == null) {
            chapter = new ProgressionOverlay.ChapterOverlay(chapterId);
            chapter.base = packHasChapter;
            overlay.chapters.put(chapterId, chapter);
        }
        return chapter;
    }

    private Path fileFor(String chapterId) {
        int colon = chapterId.indexOf(':');
        String namespace = chapterId.substring(0, colon);
        String path = chapterId.substring(colon + 1);
        Path file = overlayRoot.resolve("chapters").resolve(namespace).resolve(path + ".json").normalize();
        if (!file.startsWith(overlayRoot)) {
            throw new IllegalArgumentException("chapter id points outside the overlay: " + chapterId);
        }
        return file;
    }

    private Path tableFileFor(String id) {
        int colon = id.indexOf(':');
        String namespace = id.substring(0, colon);
        String path = id.substring(colon + 1);
        Path file = overlayRoot.resolve("tables").resolve(namespace).resolve(path + ".json").normalize();
        if (!file.startsWith(overlayRoot)) {
            throw new IllegalArgumentException("table id points outside the overlay: " + id);
        }
        return file;
    }

    private String readText(ProgressionOverlay.ChapterOverlay chapter) {
        String text = readTextQuietly(chapter);
        return text;
    }

    private String readTextQuietly(ProgressionOverlay.ChapterOverlay chapter) {
        try {
            Path file = fileFor(chapter.id);
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private static String readFileQuietly(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private String tableDisplayPath(Path file) {
        return "kuronami_quest_tree/progression/" + overlayRoot.relativize(file).toString()
                .replace(java.io.File.separatorChar, '/');
    }

    private void write(ProgressionOverlay.ChapterOverlay chapter) throws IOException {
        Path file = fileFor(chapter.id);
        String previous = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        if (previous != null) {
            backup(file);
        }
        writeRaw(file, chapter.toJson());
        chapter.file = "kuronami_quest_tree/progression/" + overlayRoot.relativize(file).toString()
                .replace(java.io.File.separatorChar, '/');
        chapter.raw = GSON.toJson(chapter.toJson());
    }

    private static void writeRaw(Path file, JsonObject json) throws IOException {
        Files.createDirectories(file.getParent());
        // A crash between truncate and the last byte would leave a half-written chapter the next build refuses. Write
        // beside it and move the finished file into place; same-directory keeps the move atomic on every platform.
        Path temp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temp, GSON.toJson(json), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Keeps the version a write replaced, next to it, for a human who has to recover by hand. */
    private static void backup(Path file) throws IOException {
        if (Files.exists(file)) {
            Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ------------------------------------------------------------------ history

    private void pushChapterHistory(String chapterId, Snapshot snapshot) {
        History history = histories.computeIfAbsent(chapterId, k -> new History());
        push(history.undo, snapshot);
        history.redo.clear();
    }

    private void pushTableHistory(String tableId, Snapshot snapshot) {
        History history = tableHistories.computeIfAbsent(tableId, k -> new History());
        push(history.undo, snapshot);
        history.redo.clear();
    }

    private static void push(Deque<Snapshot> stack, Snapshot snapshot) {
        stack.addLast(snapshot);
        while (stack.size() > HISTORY_LIMIT) {
            stack.removeFirst();
        }
    }

    // ------------------------------------------------------------------ ids and JSON helpers

    private String mintNodeId(Set<String> used, ProgressionOverlay.ChapterOverlay chapter) {
        for (int i = 0; i < 1000; i++) {
            String id = ids.get();
            if (validNodeId(id) && !used.contains(id) && !chapter.nodes.containsKey(id)) {
                used.add(id);
                return id;
            }
        }
        throw new IllegalStateException("could not mint a unique node id");
    }

    private static Set<String> nodeIds(JsonObject chapter) {
        Set<String> out = new HashSet<>();
        JsonElement nodes = chapter.get("nodes");
        if (nodes != null && nodes.isJsonObject()) {
            out.addAll(nodes.getAsJsonObject().keySet());
        }
        return out;
    }

    private static JsonObject packNode(JsonObject chapters, String chapterId, String id) {
        return object(chapters, chapterId) ? node(chapters.getAsJsonObject(chapterId), id) : null;
    }

    private static JsonObject node(JsonObject chapter, String id) {
        if (chapter == null) {
            return null;
        }
        JsonElement nodes = chapter.get("nodes");
        if (nodes == null || !nodes.isJsonObject()) {
            return null;
        }
        JsonElement n = nodes.getAsJsonObject().get(id);
        return n != null && n.isJsonObject() ? n.getAsJsonObject() : null;
    }

    /** RFC 7386 JSON merge patch: objects merge, arrays replace, null removes the member. */
    private static void mergePatch(JsonObject target, JsonObject patch) {
        for (Map.Entry<String, JsonElement> e : patch.entrySet()) {
            JsonElement value = e.getValue();
            if (value.isJsonNull()) {
                target.remove(e.getKey());
            } else if (value.isJsonObject() && target.has(e.getKey()) && target.get(e.getKey()).isJsonObject()) {
                mergePatch(target.getAsJsonObject(e.getKey()), value.getAsJsonObject());
            } else {
                target.add(e.getKey(), value.deepCopy());
            }
        }
    }

    /** A node id the editor accepts from outside: lower-case letters, digits and underscore only. */
    static boolean validNodeId(String id) {
        return id != null && !id.isEmpty()
                && id.chars().allMatch(c -> c == '_' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9');
    }

    /** A reference to another node: a node id, or {@code chapter/node} for another chapter. */
    static boolean validReferenceId(String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        for (String part : id.split("/", -1)) {
            if (!validNodeId(part)) {
                return false;
            }
        }
        return true;
    }

    /** Rewrites references between the initial nodes of a {@code create_chapter} request to their minted ids. */
    private static void rewriteNodeReferences(JsonObject node, Map<String, String> renamed) {
        JsonElement requires = node.get("requires");
        if (requires != null && requires.isJsonObject()) {
            rewriteNames(requires.getAsJsonObject().get("nodes"), renamed);
        }
        rewriteNames(node.get("exclusive_with"), renamed);
    }

    private static void rewriteNames(JsonElement names, Map<String, String> renamed) {
        if (names == null || !names.isJsonArray()) {
            return;
        }
        JsonArray array = names.getAsJsonArray();
        for (int i = 0; i < array.size(); i++) {
            JsonElement name = array.get(i);
            if (!name.isJsonPrimitive() || !name.getAsJsonPrimitive().isString()) {
                continue;
            }
            String replacement = renamed.get(name.getAsString());
            if (replacement != null) {
                array.set(i, new com.google.gson.JsonPrimitive(replacement));
            }
        }
    }

    private static boolean object(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonObject();
    }

    private static String text(JsonObject o, String key, String fallback) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : fallback;
    }

    private static String requiredText(JsonObject o, String key) {
        return text(o, key, "");
    }

    private static Double number(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : null;
    }

    static boolean validChapterId(String id) {
        if (id == null) {
            return false;
        }
        int colon = id.indexOf(':');
        if (colon <= 0 || colon == id.length() - 1) {
            return false;
        }
        if (!safeSegment(id.substring(0, colon))) {
            return false;
        }
        for (String part : id.substring(colon + 1).split("/")) {
            if (!safeSegment(part)) {
                return false;
            }
        }
        return true;
    }

    static boolean safeSegment(String s) {
        return s != null && !s.isEmpty() && !s.equals(".") && !s.equals("..")
                && s.chars().allMatch(c -> c == '_' || c == '-' || c == '.' || Character.isLetterOrDigit(c));
    }

    private static String randomId() {
        return String.format(java.util.Locale.ROOT, "%08x", RandomGenerator.getDefault().nextInt());
    }

    private static Outcome fail(String message, long revision) {
        return fail(List.of(message), revision);
    }

    private static Outcome fail(List<String> messages, long revision) {
        return new Outcome(false, false, false, "", revision, messages);
    }

    private static Outcome ok(String createdId, List<String> messages, long revision, boolean changed) {
        return new Outcome(true, false, changed, createdId, revision, messages);
    }
}
