package com.kuronami.questtree.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kuronami.questtree.api.QuestTreeApi;
import com.kuronami.questtree.points.Expression;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the progression data (DATA_MODEL.md) before it is turned into a {@code ProgressModel}, and says where it is
 * wrong with a file, a JSON path and (when the source text is available) a line.
 * <p>
 * Minecraft is not needed here: registry lookups go through {@link Registry}, so the whole thing runs as a plain
 * JUnit test. {@link Registry#NONE} turns those lookups off.
 * <p>
 * An ERROR is a node that cannot work as written: the readers throw on it (which would stop the server tick) or they
 * throw its tasks away (which would open the node at once). The caller drops exactly those nodes and keeps the rest of
 * the pack running. A WARNING is a node that still works, but not as written: a reward that is quietly skipped
 * ({@code Rewards.give} catches what the readers throw), a value that falls back to a default, a node that can open
 * earlier than the author meant because something it named was dropped, or a reference into a chapter whose file would
 * not parse. The one reward problem that is fatal is a reward table that contains itself: handing it out recurses
 * until the stack runs out, and that is an {@code Error} nothing catches.
 */
public final class ProgressValidator {

    /** How bad a problem is: ERROR drops the offending node, WARNING only reports. */
    public enum Severity {
        ERROR, WARNING
    }

    /** One problem, in the shape the log and {@code /quest_tree validate} print. */
    public record Problem(Severity severity, String file, String path, String message, int line) {

        /** {@code data/ns/progression/chapters/basics.json:12 chapters.basics.nodes.iron.tasks[0] - message} */
        public String format() {
            StringBuilder b = new StringBuilder();
            b.append(file == null || file.isEmpty() ? "progression" : file);
            if (line > 0) {
                b.append(':').append(line);
            }
            b.append(' ').append(path == null || path.isEmpty() ? "-" : path);
            b.append(" - ").append(message);
            return b.toString();
        }
    }

    /**
     * Whether an id exists in the running game. Kinds with no cheap check return true (unknown, so never a false
     * WARNING); the game-side implementation lives in {@code GameRegistry}. Only plain ids are ever asked about: a
     * tag ({@code #minecraft:logs}) and a dynamic registry (biome, structure, dimension, stat) cannot be answered
     * while the data is being read.
     */
    public interface Registry {
        boolean known(String kind, String id);

        /** Whether an economy mod registered with FTB Library; the game side answers this, tests keep the default. */
        default boolean economyAvailable() {
            return true;
        }

        Registry NONE = (kind, id) -> true;
    }

    /** A parsed file kept as text, so problems can point at a line: {@code data/ns/progression/chapters/basics.json}. */
    public record Source(String file, String rawText) {
    }

    /** Problems found, and the global node keys ({@code <chapter id>/<node id>}) that must be dropped. */
    public static final class Result {
        public final List<Problem> problems = new ArrayList<>();
        public final Set<String> droppedNodes = new LinkedHashSet<>();

        public int errors() {
            int n = 0;
            for (Problem p : problems) {
                if (p.severity() == Severity.ERROR) {
                    n++;
                }
            }
            return n;
        }

        public int warnings() {
            int n = 0;
            for (Problem p : problems) {
                if (p.severity() == Severity.WARNING) {
                    n++;
                }
            }
            return n;
        }
    }

    // Task and reward types the model knows (DATA_MODEL.md). Types that are recognized but do nothing yet get a
    // WARNING instead of an ERROR, so adding a field to the documented format never breaks a pack that uses it.
    private static final Set<String> TASK_TYPES = Set.of(
            "item", "checkmark", "kill", "advancement", "structure", "biome", "dimension", "location",
            "observation", "stat", "xp", "fluid", "energy", "break", "stage", "custom", "interaction",
            "craft", "use_item", "right_click_block", "interact_entity", "tame", "breed", "ride", "pickup");
    private static final Set<String> TASK_UNIMPLEMENTED = Set.of();
    private static final Set<String> INTERACTION_KINDS = Set.of(
            "craft", "use_item", "right_click_block", "interact_entity", "tame", "breed", "ride", "pickup");
    private static final Set<String> REWARD_TYPES = Set.of(
            "item", "xp", "xp_levels", "command", "table", "advancement", "stage", "attribute", "points",
            "tag", "toast", "trophy", "scoreboard", "custom", "dummy", "currency");
    private static final Set<String> REWARD_UNIMPLEMENTED = Set.of();

    /** Conditions accepted on a task or reward (DATA_MODEL.md "条件"). */
    private static final Set<String> CONDITION_TYPES = Set.of(
            "stage", "score", "item", "advancement", "party_size");

    /** The observation kinds {@code ProgressService.observing} answers; any other kind never matches. */
    private static final Set<String> OBSERVE_KINDS = Set.of(
            "block", "block_tag", "block_state", "entity_type", "entity_type_tag");

    private ProgressValidator() {
    }

    public static Result validate(JsonObject bundle, Map<String, Source> sources) {
        return validate(bundle, sources, Registry.NONE, Set.of());
    }

    public static Result validate(JsonObject bundle, Map<String, Source> sources, Registry registry) {
        return validate(bundle, sources, registry, Set.of());
    }

    /**
     * @param bundle     the merged chapters/reward_tables the model will be built from (after patches)
     * @param sources    key {@code chapters/<ns>:<name>} or {@code reward_tables/<ns>:<name>} → the file's raw text
     * @param registry   the running game's ids, or {@link Registry#NONE}
     * @param unreadable chapter ids ({@code <ns>:<name>}) whose file would not parse: a reference into one of those is
     *                   reported as a warning, because whether the node is there cannot be known
     */
    public static Result validate(JsonObject bundle, Map<String, Source> sources, Registry registry, Set<String> unreadable) {
        Result out = new Result();
        JsonObject chapters = obj(bundle, "chapters");
        JsonObject tables = obj(bundle, "reward_tables");

        // "declared twice" can only be seen in the text: the readers keep the last one silently, so what was written
        // first is already gone by the time the file is parsed
        for (Map.Entry<String, Source> e : sources.entrySet()) {
            Source s = e.getValue();
            Scan scan = scan(s.rawText());
            String prefix = prefixOf(e.getKey());
            for (String dup : scan.duplicates()) {
                out.problems.add(new Problem(Severity.WARNING, s.file(), prefix + dup,
                        "object member is declared more than once; the last one is used", lineAt(scan.lines(), dup)));
            }
        }

        // every node of the merged bundle, with where to look for its line
        Map<String, ChapterRef> refs = new LinkedHashMap<>();
        Map<String, String> nodeChapter = new LinkedHashMap<>();
        Map<String, JsonObject> nodes = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : chapters.entrySet()) {
            String chapter = e.getKey();
            ChapterRef ref = ref(chapter, sources);
            refs.put(chapter, ref);
            if (!e.getValue().isJsonObject()) {
                problem(out, Severity.ERROR, ref, "chapters." + local(chapter),
                        "chapter is not an object, so nothing in it is read");
                continue;
            }
            JsonElement chapterNodes = e.getValue().getAsJsonObject().get("nodes");
            if (chapterNodes != null && !chapterNodes.isJsonObject()) {
                // ProgressModel reads this with obj(...), which hands back an empty object, so every node is gone and
                // prune cannot report it: nothing else would say that they were dropped
                problem(out, Severity.ERROR, ref, "chapters." + local(chapter) + ".nodes",
                        "\"nodes\" is not an object, so the chapter has no nodes");
            }
            for (Map.Entry<String, JsonElement> n : obj(e.getValue().getAsJsonObject(), "nodes").entrySet()) {
                String key = chapter + "/" + n.getKey();
                if (!n.getValue().isJsonObject()) {
                    problem(out, Severity.ERROR, ref, "chapters." + local(chapter) + ".nodes." + n.getKey(),
                            "node is not an object, so it is not read");
                    continue;
                }
                nodeChapter.put(key, chapter);
                nodes.put(key, n.getValue().getAsJsonObject());
            }
        }

        // chapter-level settings: a broken exchange curve only falls back to a fixed cost, so it never drops a node
        for (Map.Entry<String, JsonElement> e : chapters.entrySet()) {
            if (e.getValue().isJsonObject()) {
                checkChapter(out, refs.get(e.getKey()), e.getKey(), e.getValue().getAsJsonObject());
            }
        }

        // pass 1: the node itself (shape, types, required fields, ids, numbers)
        for (Map.Entry<String, JsonObject> e : nodes.entrySet()) {
            String key = e.getKey();
            String chapter = nodeChapter.get(key);
            int before = out.problems.size();
            checkNode(out, refs.get(chapter), nodePath(chapter, key), e.getValue(), tables, registry);
            if (countErrors(out, before) > 0) {
                out.droppedNodes.add(key);
            }
        }

        // reward tables: their entries are rewards too. This comes before the reference passes because a table that
        // cannot be handed out takes the nodes that give it with it, and those drops change what the passes report
        for (Map.Entry<String, JsonElement> e : tables.entrySet()) {
            String id = e.getKey();
            if (!e.getValue().isJsonObject()) {
                problem(out, Severity.WARNING, tableRef(sources, id), "reward_tables." + local(id),
                        "reward table is not an object, so it is not read");
                continue;
            }
            checkTable(out, sources.get("reward_tables/" + id), id, e.getValue().getAsJsonObject(), tables, registry);
        }

        // a table that contains itself (directly or through another table) cannot be handed out: the readers recurse
        // until the stack runs out. A table cannot be dropped, so the nodes that give one are dropped instead
        Set<String> endless = tablesThatNeverEnd(tables);
        if (!endless.isEmpty()) {
            for (String id : endless) {
                problem(out, Severity.ERROR, tableRef(sources, id), "reward_tables." + local(id),
                        "reward table " + quote(local(id)) + " contains itself, so handing it out would never stop");
            }
            for (Map.Entry<String, JsonObject> e : nodes.entrySet()) {
                String key = e.getKey();
                if (out.droppedNodes.contains(key) || !givesEndlessTable(e.getValue(), endless)) {
                    continue;
                }
                String chapter = nodeChapter.get(key);
                out.droppedNodes.add(key);
                problem(out, Severity.ERROR, refs.get(chapter), nodePath(chapter, key),
                        "gives a reward table that contains itself");
            }
        }

        // pass 2: a reference to a node that is not there at all. ProgressModel leaves such a name out of "requires",
        // and the node then needs fewer things than written, so the severity follows what that costs the gate
        for (Map.Entry<String, JsonObject> e : nodes.entrySet()) {
            String key = e.getKey();
            if (out.droppedNodes.contains(key)) {
                continue;
            }
            String chapter = nodeChapter.get(key);
            ChapterRef ref = refs.get(chapter);
            String path = nodePath(chapter, key);
            int before = out.problems.size();
            Severity severity = gateSeverity(e.getValue());
            for (Ref target : refsOf(chapter, e.getValue(), "requires")) {
                referenceProblem(out, ref, path + ".requires.nodes", "requires", target, nodes, chapters, unreadable, severity);
            }
            // "exclusive_with" is only the rival check (ProgressRules.excluded): a name that is not there loses the
            // choice the author meant, but the node still opens and completes as written, so it only warns
            for (Ref target : refsOf(chapter, e.getValue(), "exclusive_with")) {
                referenceProblem(out, ref, path + ".exclusive_with", "exclusive_with", target, nodes, chapters, unreadable, Severity.WARNING);
            }
            if (countErrors(out, before) > 0) {
                out.droppedNodes.add(key);
            }
        }

        // pass 3: the nodes that can never be unlocked at all, whatever the player does
        for (Map.Entry<String, String> e : unreachable(nodes, nodeChapter, out.droppedNodes).entrySet()) {
            String key = e.getKey();
            String chapter = nodeChapter.get(key);
            out.droppedNodes.add(key);
            problem(out, Severity.ERROR, refs.get(chapter), nodePath(chapter, key) + ".requires.nodes", e.getValue());
        }

        // pass 4: what is left and still names something that was dropped. ProgressModel leaves that name out as well,
        // so the node needs fewer things than written and opens earlier: the same loss of gate as pass 2, reported
        // apart only because the drop happened after pass 2 had looked
        for (Map.Entry<String, JsonObject> e : nodes.entrySet()) {
            String key = e.getKey();
            if (out.droppedNodes.contains(key)) {
                continue;
            }
            String chapter = nodeChapter.get(key);
            String path = nodePath(chapter, key);
            for (String field : List.of("requires", "exclusive_with")) {
                for (Ref target : refsOf(chapter, e.getValue(), field)) {
                    if (nodes.containsKey(target.target()) && out.droppedNodes.contains(target.target())) {
                        if (field.equals("requires")) {
                            // a warning: this node is kept (dropping it as well would only spread the loss)
                            problem(out, Severity.WARNING, refs.get(chapter), path + ".requires.nodes",
                                    "names node " + quote(target.raw()) + ", which was dropped, so this node opens earlier than written");
                        } else {
                            problem(out, Severity.WARNING, refs.get(chapter), path + ".exclusive_with",
                                    "names node " + quote(target.raw()) + ", which was dropped, so the two are no longer kept apart");
                        }
                    }
                }
            }
        }
        return out;
    }

    /**
     * Checks one candidate chapter without reloading packs (2026-10-20), for the editor's validate-before-write.
     * <p>
     * References and reachability are book-wide — a node may require {@code other_chapter/node}, and deleting one can
     * break a node that is not in this chapter — so the check still walks the whole book. What changes is the source
     * of the candidate: it is the in-memory result of an op, not a file that was just read. Only the candidate's own
     * problems are returned; the caller compares the full book's ERRORs before and after to catch a break elsewhere.
     *
     * @param candidate the chapter after the edit, or null when the edit removes it
     * @param chapters  the book the candidate belongs in (the candidate is added to a copy, replacing the old one)
     */
    public static Result validateChapter(String chapterId, JsonObject candidate, JsonObject chapters, JsonObject tables,
                                         Map<String, Source> sources, Registry registry) {
        JsonObject merged = chapters.deepCopy();
        if (candidate == null) {
            merged.remove(chapterId);
        } else {
            merged.add(chapterId, candidate);
        }
        JsonObject bundle = new JsonObject();
        bundle.add("chapters", merged);
        bundle.add("reward_tables", tables);
        Result all = validate(bundle, sources, registry, Set.of());
        Result out = new Result();
        String prefix = "chapters." + local(chapterId);
        for (Problem p : all.problems) {
            String path = p.path() == null ? "" : p.path();
            if (path.equals(prefix) || path.startsWith(prefix + ".")) {
                out.problems.add(p);
            }
        }
        for (String key : all.droppedNodes) {
            if (key.startsWith(chapterId + "/")) {
                out.droppedNodes.add(key);
            }
        }
        return out;
    }

    /**
     * What it costs the gate when a "requires" name does not resolve. ProgressModel keeps only the names it can
     * resolve, so the node needs fewer things than written: with mode "all" and no "min" it can end up needing none of
     * them, which drops the gate the author built. "any" and "one" already only ask for one name, and a "min" is still
     * the same number to reach, so those are only worth a warning.
     */
    private static Severity gateSeverity(JsonObject node) {
        JsonObject req = obj(node, "requires");
        String mode = modeOf(node, req);
        int min = intOf(req, "min");
        boolean weakened = min <= 0 && !mode.startsWith("any") && !mode.equals("one");
        return weakened ? Severity.ERROR : Severity.WARNING;
    }

    /** Fork an ERROR from a JSON syntax error: Gson's message carries the line. */
    public static Problem parseError(String file, String rawText, Throwable ex) {
        String message = ex.getMessage() == null ? "invalid JSON" : ex.getMessage().trim();
        return new Problem(Severity.ERROR, file, "", "could not be parsed: " + message, lineFrom(message));
    }

    /**
     * The 1-based line of a path in a file: the nearest recorded line at or above it, and the line of the whole
     * document when the path was never recorded. 0 only when nothing at all was recorded.
     */
    public static int lineOf(String rawText, String path) {
        return lineAt(scan(rawText).lines(), path);
    }

    // ------------------------------------------------------------------ one node

    /**
     * A chapter's own settings. Only {@code points.exchange.cost_per_level} is checked here: it decides the price of
     * the next exchange purchase, and a shape {@code ProgressRules.Exchange.costAt} cannot read falls back to the
     * fixed {@code xp_levels} cost. That is a WARNING (the chapter still works), not an ERROR.
     */
    private static void checkChapter(Result out, ChapterRef ref, String chapterId, JsonObject chapter) {
        JsonElement pointsElement = chapter.get("points");
        if (pointsElement == null) {
            return;
        }
        String path = "chapters." + local(chapterId) + ".points";
        if (!pointsElement.isJsonObject()) {
            problem(out, Severity.WARNING, ref, path, "\"points\" is not an object, so the chapter is not a skill chapter");
            return;
        }
        JsonObject points = pointsElement.getAsJsonObject();
        JsonElement exchangeElement = points.get("exchange");
        if (exchangeElement == null) {
            return;
        }
        if (!exchangeElement.isJsonObject()) {
            problem(out, Severity.WARNING, ref, path + ".exchange", "\"exchange\" is not an object, so it is not read");
            return;
        }
        JsonElement curveElement = exchangeElement.getAsJsonObject().get("cost_per_level");
        if (curveElement == null) {
            return;
        }
        String curvePath = path + ".exchange.cost_per_level";
        if (!curveElement.isJsonObject()) {
            problem(out, Severity.WARNING, ref, curvePath,
                    "\"cost_per_level\" is not an object, so the fixed cost is used");
            return;
        }
        JsonObject curve = curveElement.getAsJsonObject();
        String type = str(curve, "type");
        JsonElement dataElement = curve.get("data");
        if (dataElement == null || !dataElement.isJsonObject()) {
            problem(out, Severity.WARNING, ref, curvePath,
                    "\"cost_per_level\" has no \"data\" object, so the fixed cost is used");
            return;
        }
        JsonObject data = dataElement.getAsJsonObject();
        switch (type) {
            case "values" -> {
                JsonElement values = data.get("values");
                if (values == null || !values.isJsonArray()) {
                    problem(out, Severity.WARNING, ref, curvePath + ".data.values",
                            "\"values\" is not an array, so the fixed cost is used");
                    return;
                }
                for (int i = 0; i < values.getAsJsonArray().size(); i++) {
                    JsonElement v = values.getAsJsonArray().get(i);
                    if (!numeric(v)) {
                        problem(out, Severity.WARNING, ref, curvePath + ".data.values[" + i + "]",
                                "value is not a number, so the fixed cost is used at that level");
                    }
                }
            }
            case "expression" -> {
                JsonElement expression = data.get("expression");
                if (expression == null || !isText(expression)) {
                    problem(out, Severity.WARNING, ref, curvePath + ".data.expression",
                            "\"expression\" is not a string, so the fixed cost is used");
                    return;
                }
                String text = expression.getAsString();
                if (Expression.tooComplex(text)) {
                    problem(out, Severity.WARNING, ref, curvePath + ".data.expression",
                            "expression is longer than " + Expression.MAX_LENGTH + " characters or nests deeper than "
                                    + Expression.MAX_DEPTH + ", so the fixed cost is used");
                    return;
                }
                try {
                    Expression.parse(text);
                } catch (RuntimeException ex) {
                    problem(out, Severity.WARNING, ref, curvePath + ".data.expression",
                            "expression cannot be parsed (" + ex.getMessage() + "), so the fixed cost is used");
                }
            }
            default -> problem(out, Severity.WARNING, ref, curvePath + ".type",
                    "unknown curve type " + quote(type) + ", so the fixed cost is used");
        }
    }

    private static void checkNode(Result out, ChapterRef ref, String nodePath, JsonObject node,
                                  JsonObject tables, Registry registry) {
        // what the node itself is made of. A wrong shape here throws while ProgressModel reads the chapter, or quietly
        // takes the tasks away, which would open the node the moment the pack loads
        JsonElement tasks = node.get("tasks");
        if (tasks != null && !tasks.isJsonArray()) {
            problem(out, Severity.ERROR, ref, nodePath,
                    "\"tasks\" is not an array, so the node has no tasks and is done at once");
        } else {
            for (int i = 0; i < arr(node, "tasks").size(); i++) {
                if (arr(node, "tasks").get(i).isJsonObject()) {
                    task(out, ref, nodePath + ".tasks[" + i + "]", arr(node, "tasks").get(i).getAsJsonObject(), registry);
                } else {
                    problem(out, Severity.ERROR, ref, nodePath + ".tasks[" + i + "]", "task is not an object");
                }
            }
        }
        JsonElement rewards = node.get("rewards");
        if (rewards != null && !rewards.isJsonArray()) {
            problem(out, Severity.WARNING, ref, nodePath, "\"rewards\" is not an array, so nothing is given");
        } else {
            for (int i = 0; i < arr(node, "rewards").size(); i++) {
                if (arr(node, "rewards").get(i).isJsonObject()) {
                    reward(out, ref, nodePath + ".rewards[" + i + "]", arr(node, "rewards").get(i).getAsJsonObject(), tables, registry);
                } else {
                    problem(out, Severity.WARNING, ref, nodePath + ".rewards[" + i + "]", "reward is not an object, so it is skipped");
                }
            }
        }
        JsonArray ranks = arr(node, "ranks");
        if (node.has("ranks") && !node.get("ranks").isJsonArray()) {
            problem(out, Severity.WARNING, ref, nodePath, "\"ranks\" is not an array, so it is not read");
        }
        for (int i = 0; i < ranks.size(); i++) {
            if (!ranks.get(i).isJsonObject()) {
                problem(out, Severity.WARNING, ref, nodePath + ".ranks[" + i + "]", "rank is not an object, so it is skipped");
                continue;
            }
            for (JsonElement rw : arr(ranks.get(i).getAsJsonObject(), "rewards")) {
                if (rw.isJsonObject()) {
                    reward(out, ref, nodePath + ".ranks[" + i + "].rewards", rw.getAsJsonObject(), tables, registry);
                }
            }
        }
        requirements(out, ref, nodePath, node);
        repeat(out, ref, nodePath, node);
        textShape(out, ref, nodePath, node, "shape", Severity.WARNING);
        textShape(out, ref, nodePath, node, "milestone", Severity.WARNING);
        booleanShape(out, ref, nodePath, node, "root");
        booleanShape(out, ref, nodePath, node, "shared");
        booleanShape(out, ref, nodePath, node, "optional");
        booleanShape(out, ref, nodePath, node, "hidden");
        whole(out, ref, nodePath, node, "cost");
        whole(out, ref, nodePath, node, "tasks_required");
    }

    /** The shape of "requires", "exclusive_with" and "exclusive". */
    private static void requirements(Result out, ChapterRef ref, String path, JsonObject node) {
        JsonElement req = node.get("requires");
        if (req != null) {
            if (!req.isJsonObject()) {
                problem(out, Severity.ERROR, ref, path + ".requires",
                        "\"requires\" is not an object, so the node opens by itself");
            } else {
                JsonObject r = req.getAsJsonObject();
                namesShape(out, ref, path + ".requires", r, "nodes");
                textShape(out, ref, path + ".requires", r, "mode", Severity.ERROR);
                whole(out, ref, path + ".requires", r, "min");
                whole(out, ref, path + ".requires", r, "points");
                whole(out, ref, path + ".requires", r, "spent_points");
                whole(out, ref, path + ".requires", r, "exclusions");
                stringArrayShape(out, ref, path + ".requires", r, "mods", "mod ids");
            }
        }
        JsonElement ex = node.get("exclusive_with");
        if (ex != null && !ex.isJsonArray()) {
            problem(out, Severity.ERROR, ref, path + ".exclusive_with", "\"exclusive_with\" is not an array of node names");
        } else {
            namesShape(out, ref, path, node, "exclusive_with");
        }
        JsonElement group = node.get("exclusive");
        if (group != null) {
            if (!group.isJsonObject()) {
                problem(out, Severity.WARNING, ref, path + ".exclusive",
                        "\"exclusive\" is not an object, so the group is not read");
            } else {
                textShape(out, ref, path + ".exclusive", group.getAsJsonObject(), "group", Severity.ERROR);
                whole(out, ref, path + ".exclusive", group.getAsJsonObject(), "max");
            }
        }
    }

    /**
     * The "repeat" block, read by {@code ProgressRules.repeatAfterClaim} while a claim settles. {@code repeatable}
     * takes {@code true} or an object and ignores anything else, so a node written another way does not repeat at all.
     * <p>
     * "max" is how many times the node can be done in total, so it has to be at least 2 for the first repeat to happen
     * ({@code done + 1 >= max} refuses it otherwise); "cooldown" is seconds, and 0 or less only matters for a node with
     * no task to do again, which this check does not look into.
     */
    private static void repeat(Result out, ChapterRef ref, String path, JsonObject node) {
        JsonElement e = node.get("repeat");
        if (e == null) {
            return;
        }
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) {
            return;
        }
        if (!e.isJsonObject()) {
            problem(out, Severity.WARNING, ref, path + ".repeat",
                    "\"repeat\" is neither true nor an object, so the node does not repeat");
            return;
        }
        JsonObject r = e.getAsJsonObject();
        JsonElement cooldown = r.get("cooldown");
        if (cooldown != null) {
            // read with getAsLong while the claim settles, which the server tick reaches without a handler above it
            if (!integral(cooldown)) {
                problem(out, Severity.ERROR, ref, path + ".repeat.cooldown",
                        "\"cooldown\" is not a whole number, so the server would stop reading this node");
            } else if (cooldown.getAsLong() <= 0) {
                problem(out, Severity.WARNING, ref, path + ".repeat.cooldown",
                        "\"cooldown\" is " + cooldown.getAsLong() + ", which is read as no cooldown");
            }
        }
        JsonElement max = r.get("max");
        if (max == null) {
            return;
        }
        if (!max.isJsonPrimitive()) {
            problem(out, Severity.WARNING, ref, path + ".repeat.max",
                    "\"max\" is not a number, so the node repeats without limit");
        } else if (!intValue(max)) {
            problem(out, Severity.ERROR, ref, path + ".repeat.max",
                    "\"max\" is not a whole number, so the server would stop reading this node");
        } else if (max.getAsInt() < 2) {
            problem(out, Severity.WARNING, ref, path + ".repeat.max",
                    "\"max\" is " + max.getAsInt() + ", so the node never repeats");
        }
    }

    /** An array of node names: ProgressModel reads each entry with getAsString, which throws on anything else. */
    private static void namesShape(Result out, ChapterRef ref, String path, JsonObject o, String field) {
        JsonElement a = o.get(field);
        if (a == null) {
            return;
        }
        if (!a.isJsonArray()) {
            problem(out, Severity.ERROR, ref, path + "." + field, quote(field) + " is not an array of node names");
            return;
        }
        for (int i = 0; i < a.getAsJsonArray().size(); i++) {
            // ProgressModel reads a name with getAsString, which takes a number or a boolean as its text and throws on
            // an object, an array or null. A name that is only written oddly is resolved like any other (see refsOf)
            if (!a.getAsJsonArray().get(i).isJsonPrimitive()) {
                problem(out, Severity.ERROR, ref, path + "." + field + "[" + i + "]", "is not a node name");
            }
        }
    }

    private static void task(Result out, ChapterRef ref, String path, JsonObject t, Registry registry) {
        JsonElement type = t.get("type");
        String name = str(t, "type");
        if (name.isEmpty()) {
            problem(out, Severity.ERROR, ref, path, type == null ? "task has no \"type\"" : "\"type\" is not a string");
            return;
        }
        if (!TASK_TYPES.contains(name) && !QuestTreeApi.taskTypeRegistered(name)) {
            problem(out, Severity.ERROR, ref, path + ".type", "unknown task type " + quote(name) + ", so the task never counts");
            return;
        }
        if (TASK_UNIMPLEMENTED.contains(name)) {
            problem(out, Severity.WARNING, ref, path + ".type", "task type " + quote(name) + " is not implemented yet");
        }
        if (name.equals("custom") && (!t.has("enable_button") || !t.get("enable_button").getAsBoolean())) {
            problem(out, Severity.WARNING, ref, path + ".type",
                    "custom task has no check hook here and no submit button, so it never completes");
        }
        switch (name) {
            case "item" -> {
                String filter = t.get("item") != null && t.get("item").isJsonObject()
                        ? com.kuronami.questtree.progress.ItemFilter.filterExpression(t.getAsJsonObject("item")) : null;
                Spec s = itemSpecText(t.get("item"));
                if (filter != null) {
                    // an FTB Filter System smart filter: the expression decides, not the filter item's id
                    var f = com.kuronami.questtree.progress.ItemFilter.parse(filter);
                    if (!f.isValid()) {
                        problem(out, Severity.ERROR, ref, path + ".item", "item filter cannot be read (" + f.error() + "), so it never counts");
                    } else if (f.root().children().isEmpty()) {
                        // as in FTB Filter System, an empty filter takes every item: the task counts whatever is held
                        problem(out, Severity.WARNING, ref, path + ".item", "item filter is empty, so any item counts (and is taken, if the task takes items)");
                    } else if (!f.unsupportedFunctions().isEmpty()) {
                        problem(out, Severity.WARNING, ref, path + ".item", "item filter uses " + f.unsupportedFunctions()
                                + ", which Quest Tree cannot check; those parts never match");
                    }
                } else if (s.unreadable()) {
                    specProblem(out, ref, path + ".item", "item");
                } else if (s.empty()) {
                    problem(out, Severity.ERROR, ref, path + ".item", "item task has no item, so it never counts");
                } else {
                    id(out, ref, path + ".item", "item", s.text(), registry);
                }
                matchComponents(out, ref, path, t);
            }
            case "kill" -> {
                textShape(out, ref, path, t, "entity", Severity.WARNING);
                textShape(out, ref, path, t, "entity_tag", Severity.WARNING);
                textShape(out, ref, path, t, "name", Severity.WARNING);
                textShape(out, ref, path, t, "nbt", Severity.WARNING);
                String entityTag = str(t, "entity_tag");
                if (!entityTag.isEmpty()) {
                    id(out, ref, path + ".entity_tag", "entity", entityTag.startsWith("#") ? entityTag : "#" + entityTag, registry);
                }
                id(out, ref, path + ".entity", "entity", str(t, "entity"), registry);
            }
            case "advancement" -> required(out, ref, path, t, "advancement");
            case "structure" -> required(out, ref, path, t, "structure");
            case "biome" -> required(out, ref, path, t, "biome");
            case "dimension" -> {
                required(out, ref, path, t, "dimension");
                id(out, ref, path + ".dimension", "dimension", str(t, "dimension"), registry);
            }
            case "location" -> location(out, ref, path, t);
            case "observation" -> observation(out, ref, path, t, registry);
            case "stat" -> required(out, ref, path, t, "stat");
            case "fluid" -> {
                Spec s = specText(t.get("fluid"));
                if (s.unreadable()) {
                    specProblem(out, ref, path + ".fluid", "fluid");
                } else if (s.empty()) {
                    problem(out, Severity.ERROR, ref, path + ".fluid", "fluid task has no fluid, so it never counts");
                } else {
                    id(out, ref, path + ".fluid", "fluid", s.text(), registry);
                }
            }
            case "break" -> {
                Spec s = specText(t.get("block"));
                if (s.unreadable()) {
                    specProblem(out, ref, path + ".block", "block");
                } else if (s.text().equals("#")) {
                    problem(out, Severity.ERROR, ref, path + ".block", "the block tag \"#\" is empty, so it never matches");
                } else {
                    // an empty spec matches any block (ProgressService.blockMatches), so it is only worth saying so
                    id(out, ref, path + ".block", "block", s.text(), registry);
                }
            }
            case "stage" -> required(out, ref, path, t, "stage");
            case "craft" -> {
                interactionItem(out, ref, path, t, registry);
                id(out, ref, path + ".recipe", "recipe", str(t, "recipe"), registry);
            }
            case "use_item", "pickup" -> interactionItem(out, ref, path, t, registry);
            case "right_click_block" -> interactionBlock(out, ref, path, t, registry);
            case "interact_entity", "tame", "breed", "ride" -> interactionEntity(out, ref, path, t, registry);
            case "interaction" -> {
                String action = str(t, "action");
                if (action.isEmpty()) {
                    action = str(t, "kind");
                }
                if (action.isEmpty()) {
                    problem(out, Severity.ERROR, ref, path, "interaction task has no \"action\", so it never counts");
                } else if (!INTERACTION_KINDS.contains(action)) {
                    problem(out, Severity.ERROR, ref, path + ".action",
                            "unknown interaction action " + quote(action) + ", so the task never counts");
                } else {
                    switch (action) {
                        case "craft" -> {
                interactionItem(out, ref, path, t, registry);
                id(out, ref, path + ".recipe", "recipe", str(t, "recipe"), registry);
            }
            case "use_item", "pickup" -> interactionItem(out, ref, path, t, registry);
                        case "right_click_block" -> interactionBlock(out, ref, path, t, registry);
                        default -> interactionEntity(out, ref, path, t, registry);
                    }
                }
            }
            default -> {
            }
        }
        conditionList(out, ref, path, t, registry);
        // ProgressRules.target reads all four of these with getAsLong on the server tick, whichever task type it is
        taskNumber(out, ref, path, t, "count", true);
        taskNumber(out, ref, path, t, "amount", false);
        taskNumber(out, ref, path, t, "ticks", true);
        JsonElement item = t.get("item");
        if (item != null && item.isJsonObject()) {
            taskNumber(out, ref, path + ".item", item.getAsJsonObject(), "count", false);
        }
        // ProgressRules reads these on the server tick too, or while a hand-in settles, with no handler above it
        tickFlag(out, ref, path, t, "optional");
        tickFlag(out, ref, path, t, "consume");
    }

    /**
     * FTB Quests' {@code match_components} (none / fuzzy / strict). A typo would silently turn a component-checking
     * hand-in into "any item with this id", so it is an error; two older booleans are still read by the importer.
     */
    private static void matchComponents(Result out, ChapterRef ref, String path, JsonObject t) {
        JsonElement v = t.get("match_components");
        if (v == null) {
            return;
        }
        if (!v.isJsonPrimitive() || v.getAsJsonPrimitive().isBoolean()) {
            problem(out, Severity.ERROR, ref, path + ".match_components", "match_components must be none, fuzzy or strict");
            return;
        }
        String mode = v.getAsString().toLowerCase(java.util.Locale.ROOT);
        if (!mode.equals("none") && !mode.equals("fuzzy") && !mode.equals("strict")) {
            problem(out, Severity.ERROR, ref, path + ".match_components",
                    "match_components must be none, fuzzy or strict, not " + quote(v.getAsString()));
            return;
        }
        JsonElement item = t.get("item");
        boolean hasComponents = item != null && item.isJsonObject()
                && item.getAsJsonObject().has("components") && item.getAsJsonObject().get("components").isJsonObject()
                && !item.getAsJsonObject().getAsJsonObject("components").isEmpty();
        if (!mode.equals("none") && !hasComponents) {
            problem(out, Severity.WARNING, ref, path + ".match_components",
                    "match_components is " + mode + " but the item has no components, so only its id is checked");
        }
    }

    /** The item half of craft / use_item / pickup / interaction. No item means "any item". */
    private static void interactionItem(Result out, ChapterRef ref, String path, JsonObject t, Registry registry) {
        if (!t.has("item")) {
            problem(out, Severity.WARNING, ref, path + ".item", "task has no item, so any item counts");
            return;
        }
        JsonElement it = t.get("item");
        if (it.isJsonPrimitive()) {
            id(out, ref, path + ".item", "item", it.getAsString(), registry);
        } else if (it.isJsonObject()) {
            JsonObject o = it.getAsJsonObject();
            if (o.has("tag")) {
                id(out, ref, path + ".item", "item", "#" + o.get("tag").getAsString(), registry);
            } else if (o.has("id")) {
                id(out, ref, path + ".item", "item", o.get("id").getAsString(), registry);
            } else {
                problem(out, Severity.ERROR, ref, path + ".item", "item task has no item, so it never counts");
            }
        } else {
            problem(out, Severity.ERROR, ref, path + ".item", "item task has no item, so it never counts");
        }
        matchComponents(out, ref, path, t);
    }

    /** The block half of right_click_block / interaction. An empty block means "any block". */
    private static void interactionBlock(Result out, ChapterRef ref, String path, JsonObject t, Registry registry) {
        Spec s = specText(t.get("block"));
        if (s.unreadable()) {
            specProblem(out, ref, path + ".block", "block");
        } else if (!s.empty()) {
            id(out, ref, path + ".block", "block", s.text(), registry);
        }
    }

    /** The entity half of interact_entity / tame / breed / ride / interaction. Empty means "any entity". */
    private static void interactionEntity(Result out, ChapterRef ref, String path, JsonObject t, Registry registry) {
        textShape(out, ref, path, t, "entity", Severity.WARNING);
        textShape(out, ref, path, t, "entity_tag", Severity.WARNING);
        String tag = str(t, "entity_tag");
        if (!tag.isEmpty()) {
            id(out, ref, path + ".entity_tag", "entity", tag.startsWith("#") ? tag : "#" + tag, registry);
        }
        id(out, ref, path + ".entity", "entity", str(t, "entity"), registry);
    }

    /** A "conditions" array: every entry is an object with a known type and the fields that type reads. */
    private static void conditionList(Result out, ChapterRef ref, String path, JsonObject holder, Registry registry) {
        JsonElement e = holder.get("conditions");
        if (e == null) {
            return;
        }
        if (!e.isJsonArray()) {
            problem(out, Severity.ERROR, ref, path + ".conditions",
                    "\"conditions\" is not an array, so the task or reward would be open to everyone");
            return;
        }
        for (int i = 0; i < e.getAsJsonArray().size(); i++) {
            JsonElement c = e.getAsJsonArray().get(i);
            String at = path + ".conditions[" + i + "]";
            if (!c.isJsonObject()) {
                problem(out, Severity.ERROR, ref, at, "condition is not an object, so it can never be met");
                continue;
            }
            JsonObject o = c.getAsJsonObject();
            String raw = str(o, "type");
            if (raw.isEmpty()) {
                problem(out, Severity.ERROR, ref, at, "condition has no \"type\", so it can never be met");
                continue;
            }
            String type = raw.contains(":") ? raw.substring(raw.indexOf(':') + 1) : raw;
            if (!CONDITION_TYPES.contains(type)) {
                if (QuestTreeApi.conditionTypeRegistered(raw)) {
                    // an addon owns this kind and its fields; the five built-ins keep their checks below
                    continue;
                }
                problem(out, Severity.WARNING, ref, at + ".type",
                        "unknown condition type " + quote(raw) + ", which is not registered and not built in, so it can never be met");
                continue;
            }
            switch (type) {
                case "stage" -> {
                    if (str(o, "stage").isEmpty()) {
                        problem(out, Severity.ERROR, ref, at + ".stage", "stage condition has no stage, so it can never be met");
                    }
                }
                case "score" -> {
                    if (str(o, "objective").isEmpty() && str(o, "scoreboard").isEmpty()) {
                        problem(out, Severity.ERROR, ref, at + ".objective", "score condition has no objective");
                    }
                    whole(out, ref, at, o, "min");
                }
                case "item" -> {
                    if (!o.has("item")) {
                        problem(out, Severity.ERROR, ref, at + ".item", "item condition has no item, so it can never be met");
                    } else {
                        interactionItem(out, ref, at, o, registry);
                    }
                    whole(out, ref, at, o, "count");
                }
                case "advancement" -> {
                    if (str(o, "advancement").isEmpty()) {
                        problem(out, Severity.ERROR, ref, at + ".advancement", "advancement condition has no advancement");
                    }
                }
                case "party_size" -> whole(out, ref, at, o, "min");
                default -> {
                }
            }
        }
    }

    private static void location(Result out, ChapterRef ref, String path, JsonObject t) {
        JsonElement pos = t.get("position");
        if (pos == null || !pos.isJsonArray() || pos.getAsJsonArray().size() < 3) {
            problem(out, Severity.ERROR, ref, path + ".position", "location task needs a position of 3 numbers");
        } else {
            numbers(out, ref, path + ".position", pos.getAsJsonArray());
        }
        JsonElement size = t.get("size");
        if (size != null && size.isJsonArray()) {
            numbers(out, ref, path + ".size", size.getAsJsonArray());
        }
    }

    private static void observation(Result out, ChapterRef ref, String path, JsonObject t, Registry registry) {
        String kind = str(t, "observe_type");
        if (t.has("observe_type") && !isText(t.get("observe_type"))) {
            // ProgressService.str answers "" for anything but a string, and "" means "block"
            problem(out, Severity.WARNING, ref, path + ".observe_type", "\"observe_type\" is not a string, so it is read as \"block\"");
        }
        if (kind.isEmpty()) {
            kind = "block";
        } else if (!OBSERVE_KINDS.contains(kind)) {
            // a warning, not an error: dropping the node would open everything behind it early, which is worse than
            // one task that never counts (FTB Quests behaves the same for a kind it does not know)
            problem(out, Severity.WARNING, ref, path + ".observe_type",
                    "unknown observe_type " + quote(kind) + ", so the task never counts");
            return;
        }
        String target = str(t, "target");
        if (target.isEmpty()) {
            problem(out, Severity.ERROR, ref, path + ".target", "observation task has no target");
            return;
        }
        switch (kind) {
            case "block" -> {
                if (!target.startsWith("#")) {
                    id(out, ref, path + ".target", "block", target, registry);
                }
            }
            case "entity_type" -> id(out, ref, path + ".target", "entity", target, registry);
            // "block_tag" and "entity_type_tag" hold a tag (with or without "#"), and what is in a tag cannot be seen
            // while the data is being read. "block_state" is "minecraft:furnace[lit=true]", which only the command
            // parser reads. None of them is checked here, so none of them gets a false warning
            default -> {
            }
        }
    }

    private static void reward(Result out, ChapterRef ref, String path, JsonObject rw, JsonObject tables, Registry registry) {
        JsonElement type = rw.get("type");
        String name = str(rw, "type");
        if (name.isEmpty()) {
            if (type == null) {
                problem(out, Severity.WARNING, ref, path, "reward has no \"type\", so nothing is given");
            } else {
                // ProgressService.type reads this with getAsString for every reward of the node while a claim settles,
                // and the server tick reaches that with no handler above it
                problem(out, Severity.ERROR, ref, path,
                        "\"type\" is not a string, so the server would stop reading this node");
            }
            return;
        }
        if (!REWARD_TYPES.contains(name) && !QuestTreeApi.rewardTypeRegistered(name)) {
            problem(out, Severity.WARNING, ref, path + ".type", "unknown reward type " + quote(name) + ", so nothing is given");
            return;
        }
        if (REWARD_UNIMPLEMENTED.contains(name)) {
            problem(out, Severity.WARNING, ref, path + ".type", "reward type " + quote(name) + " is not implemented yet");
        }
        conditionList(out, ref, path, rw, registry);
        switch (name) {
            case "item" -> itemReward(out, ref, path, rw, registry);
            case "command" -> {
                if (str(rw, "command").isEmpty() && str(rw, "unlock_command").isEmpty()) {
                    problem(out, Severity.WARNING, ref, path, "command reward has no command, so nothing runs");
                }
            }
            case "table" -> {
                String table = str(rw, "table");
                if (table.isEmpty()) {
                    problem(out, Severity.WARNING, ref, path + ".table", "table reward has no table");
                } else if (!tables.has(table) || !tables.get(table).isJsonObject()) {
                    problem(out, Severity.WARNING, ref, path + ".table", "reward table " + quote(table) + " does not exist");
                }
                // read with getAsString inside Rewards.give, which catches it and loses the reward, so it only warns
                textShape(out, ref, path, rw, "mode", Severity.WARNING);
            }
            case "advancement" -> needs(out, ref, path, rw, "advancement");
            case "stage" -> needs(out, ref, path, rw, "stage");
            case "attribute" -> {
                needs(out, ref, path, rw, "attribute");
                id(out, ref, path + ".attribute", "attribute", str(rw, "attribute"), registry);
                numberShape(out, ref, path, rw, "value");
            }
            case "tag" -> needs(out, ref, path, rw, "tag");
            case "trophy" -> {
                String trophy = str(rw, "trophy");
                if (trophy.isEmpty()) {
                    problem(out, Severity.WARNING, ref, path + ".trophy", "trophy reward has no trophy");
                } else if (!trophy.contains(":")) {
                    problem(out, Severity.WARNING, ref, path + ".trophy", "trophy " + quote(trophy) + " has no namespace");
                }
            }
            case "currency" -> {
                amount(out, ref, path, rw);
                if (!registry.economyAvailable()) {
                    problem(out, Severity.WARNING, ref, path,
                            "currency reward has no economy mod to pay it, so it is taken and pays nothing");
                }
            }
            case "xp", "xp_levels" -> amount(out, ref, path, rw);
            default -> {
            }
        }
    }

    /**
     * An "item" reward. {@code Rewards.item} reads only "id": a "#tag" or a "fluid" is not a reward item, and a shape
     * it cannot read is caught by {@code Rewards.give}, so the reward is lost rather than the tick.
     */
    private static void itemReward(Result out, ChapterRef ref, String path, JsonObject rw, Registry registry) {
        JsonElement it = rw.get("item");
        if (it == null) {
            problem(out, Severity.WARNING, ref, path + ".item", "item reward has no item, so nothing is given");
            return;
        }
        Spec s = itemSpecText(it);
        if (s.unreadable()) {
            problem(out, Severity.WARNING, ref, path + ".item", "item reward cannot be read as an id, so nothing is given");
            return;
        }
        if (s.empty() || s.text().startsWith("#")) {
            problem(out, Severity.WARNING, ref, path + ".item",
                    "item reward has no item id, so nothing is given");
            return;
        }
        id(out, ref, path + ".item", "item", s.text(), registry);
    }

    /** {@code Rewards.intOf} keeps only a number, so a missing or unusable amount gives 0 (xp) or 1 (points). */
    private static void amount(Result out, ChapterRef ref, String path, JsonObject rw) {
        JsonElement e = rw.get("amount");
        if (e == null) {
            problem(out, Severity.WARNING, ref, path + ".amount", "\"amount\" is missing, so the reward gives 0");
            return;
        }
        if (!numeric(e)) {
            problem(out, Severity.WARNING, ref, path + ".amount", "\"amount\" is not a number, so the reward gives 0");
            return;
        }
        double v = e.getAsDouble();
        if (v <= 0) {
            problem(out, Severity.WARNING, ref, path + ".amount", "\"amount\" is " + trim(v) + ", so the reward gives 0");
        }
    }

    private static void checkTable(Result out, Source source, String id, JsonObject table, JsonObject tables, Registry registry) {
        ChapterRef ref = tableRef2(source, id);
        String path = "reward_tables." + local(id);
        JsonElement entries = table.get("entries");
        if (entries == null || !entries.isJsonArray()) {
            // Rewards.table warns and gives nothing when there is no "entries" array, and catches anything else
            problem(out, Severity.WARNING, ref, path + ".entries", "reward table has no \"entries\" array, so it gives nothing");
            return;
        }
        for (int i = 0; i < entries.getAsJsonArray().size(); i++) {
            JsonElement e = entries.getAsJsonArray().get(i);
            if (!e.isJsonObject()) {
                problem(out, Severity.WARNING, ref, path + ".entries[" + i + "]",
                        "entry is not an object, so the table hands out nothing");
                continue;
            }
            JsonObject o = e.getAsJsonObject();
            JsonElement reward = o.get("reward");
            if (reward == null || !reward.isJsonObject()) {
                problem(out, Severity.WARNING, ref, path + ".entries[" + i + "]", "entry has no \"reward\", so it gives nothing");
            } else {
                reward(out, ref, path + ".entries[" + i + "].reward", reward.getAsJsonObject(), tables, registry);
            }
            weight(out, ref, path + ".entries[" + i + "]", o);
        }
        whole(out, ref, path, table, "rolls");
        numberShape(out, ref, path, table, "empty_weight");
        if (table.has("loot_crate") && !table.get("loot_crate").isJsonNull() && !table.get("loot_crate").isJsonObject()) {
            problem(out, Severity.WARNING, ref, path + ".loot_crate", "loot crate settings must be an object; rolled as a normal table");
        }
    }

    /** A weight the table does not choose with: Rewards.table reads it with getAsDouble, and 0 means "never". */
    private static void weight(Result out, ChapterRef ref, String path, JsonObject entry) {
        if (!entry.has("weight")) {
            return;
        }
        JsonElement e = entry.get("weight");
        if (!numeric(e)) {
            // Rewards.table builds its weight list before it gives anything: one it cannot read stops the whole table
            problem(out, Severity.WARNING, ref, path + ".weight", "\"weight\" is not a number, so the table hands out nothing");
            return;
        }
        double v = e.getAsDouble();
        if (v <= 0) {
            problem(out, Severity.WARNING, ref, path + ".weight", "\"weight\" is " + trim(v) + ", so a random roll never takes it");
        }
    }

    // ------------------------------------------------------------------ what can never be unlocked

    /**
     * The nodes that can never be unlocked, each with the reason to report.
     * <p>
     * This is {@code ProgressRules.unlocked} run again and again instead of a cycle search: start from the nodes that
     * open by themselves (no requirement left, or a Puffish root) and keep opening every node whose requirements are
     * already met, until nothing changes. Whatever is still closed cannot be reached however the player plays.
     * <p>
     * A cycle search on its own would misreport. Puffish imports both directions of a link as a requirement with
     * {@code mode: "any"} (see {@code PuffishImporter}), so a loop like A -&gt; B -&gt; A where one side is an "any" is
     * normal: that node opens from its other branch. Only the nodes that stay closed are a dead end.
     */
    private static Map<String, String> unreachable(Map<String, JsonObject> nodes, Map<String, String> nodeChapter, Set<String> dropped) {
        Map<String, List<Ref>> refs = new LinkedHashMap<>();
        Map<String, Integer> minOf = new LinkedHashMap<>();
        Map<String, String> modeOf = new LinkedHashMap<>();
        Set<String> open = new LinkedHashSet<>();
        for (Map.Entry<String, JsonObject> e : nodes.entrySet()) {
            String key = e.getKey();
            if (dropped.contains(e.getKey())) {
                continue;
            }
            JsonObject node = e.getValue();
            // ProgressModel keeps only the requirements it can resolve, and a node whose "requires" ends up empty
            // unlocks at once (ProgressRules.unlocked), so unresolvable names are left out here as well
            List<Ref> live = new ArrayList<>();
            for (Ref r : refsOf(nodeChapter.get(key), node, "requires")) {
                if (nodes.containsKey(r.target()) && !dropped.contains(r.target())) {
                    live.add(r);
                }
            }
            refs.put(key, live);
            JsonObject req = obj(node, "requires");
            minOf.put(key, intOf(req, "min"));
            modeOf.put(key, modeOf(node, req));
            if (live.isEmpty() || isRoot(node)) {
                open.add(key);
            }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<String, List<Ref>> e : refs.entrySet()) {
                if (open.contains(e.getKey())) {
                    continue;
                }
                int met = 0;
                for (Ref r : e.getValue()) {
                    if (open.contains(r.target())) {
                        met++;
                    }
                }
                if (satisfied(modeOf.get(e.getKey()), minOf.get(e.getKey()), met, e.getValue().size())) {
                    open.add(e.getKey());
                    changed = true;
                }
            }
        }
        Map<String, String> closed = new LinkedHashMap<>();
        for (Map.Entry<String, List<Ref>> e : refs.entrySet()) {
            if (!open.contains(e.getKey())) {
                closed.put(e.getKey(), reason(e.getValue(), modeOf.get(e.getKey()), minOf.get(e.getKey()), open));
            }
        }
        return closed;
    }

    /** {@code ProgressRules.unlocked}, with the reachable requirements standing in for done/started. */
    private static boolean satisfied(String mode, int min, int met, int size) {
        if (min > 0) {
            return met >= min;
        }
        return mode.startsWith("any") || mode.equals("one") ? met > 0 : met == size;
    }

    private static String modeOf(JsonObject node, JsonObject requires) {
        String mode = str(requires, "mode");
        return mode.isEmpty() ? "all" : mode;
    }

    /** A Puffish root: {@code ProgressRules.unlocked} opens it whatever its requirements say. */
    private static boolean isRoot(JsonObject node) {
        JsonElement root = node.get("root");
        return root != null && root.isJsonPrimitive() && root.getAsJsonPrimitive().isBoolean() && root.getAsBoolean();
    }

    private static String reason(List<Ref> refs, String mode, int min, Set<String> open) {
        int met = 0;
        for (Ref r : refs) {
            if (open.contains(r.target())) {
                met++;
            }
        }
        if (min > 0) {
            return "can never be unlocked: it needs " + min + " of its " + refs.size()
                    + " requirements, and only " + met + " of them can be reached";
        }
        if (mode.startsWith("any") || mode.equals("one")) {
            return "can never be unlocked: it has " + refs.size() + " requirements, and none of them can be reached";
        }
        for (Ref r : refs) {
            if (!open.contains(r.target())) {
                return "can never be unlocked: it requires node " + quote(r.raw()) + ", which can never be unlocked";
            }
        }
        return "can never be unlocked";
    }

    // ------------------------------------------------------------------ reward tables that contain themselves

    /** Every table from which a table that contains itself can be reached, so handing it out would never stop. */
    private static Set<String> tablesThatNeverEnd(JsonObject tables) {
        Map<String, Set<String>> edges = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : tables.entrySet()) {
            Set<String> to = new LinkedHashSet<>();
            if (e.getValue().isJsonObject()) {
                tableTargets(e.getValue().getAsJsonObject(), tables, to);
            }
            edges.put(e.getKey(), to);
        }
        // peel off the tables that cannot reach a loop. A table only ends when every table it can give ends, so the
        // targets that are not known yet have to be counted: stopping at the first known one would call a table safe
        // while another of its targets is inside a loop
        Map<String, Set<String>> from = new LinkedHashMap<>();
        Map<String, Integer> left = new LinkedHashMap<>();
        edges.keySet().forEach(k -> from.put(k, new LinkedHashSet<>()));
        for (Map.Entry<String, Set<String>> e : edges.entrySet()) {
            left.put(e.getKey(), e.getValue().size());
            for (String to : e.getValue()) {
                from.get(to).add(e.getKey());
            }
        }
        Set<String> ends = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> e : left.entrySet()) {
            if (e.getValue() == 0) {
                ends.add(e.getKey());
                queue.add(e.getKey());
            }
        }
        while (!queue.isEmpty()) {
            for (String at : from.get(queue.poll())) {
                if (ends.contains(at)) {
                    continue;
                }
                if (left.merge(at, -1, Integer::sum) == 0) {
                    ends.add(at);
                    queue.add(at);
                }
            }
        }
        Set<String> endless = new LinkedHashSet<>(edges.keySet());
        endless.removeAll(ends);
        return endless;
    }

    /**
     * The tables an entry's reward gives, so the tables can be read as a graph. {@code Rewards.table} builds both of
     * its lists before it gives anything, so an entry it cannot read stops the whole handout and the table reaches no
     * other table at all.
     */
    private static void tableTargets(JsonObject table, JsonObject tables, Set<String> to) {
        JsonElement entries = table.get("entries");
        if (entries == null || !entries.isJsonArray()) {
            return;
        }
        for (JsonElement e : entries.getAsJsonArray()) {
            if (!e.isJsonObject()) {
                return;
            }
            JsonObject entry = e.getAsJsonObject();
            JsonElement reward = entry.get("reward");
            if (reward == null || !reward.isJsonObject()) {
                continue;
            }
            JsonObject rw = reward.getAsJsonObject();
            if (!str(rw, "type").equals("table")) {
                continue;
            }
            String mode = str(rw, "mode");
            if (mode.equals("choice")) {
                // a choice inside a table is handed out as nothing (Rewards.give with no pick), so it never recurses
                continue;
            }
            if (!mode.equals("all")) {
                // the default branch rolls for an entry, so one with no weight left to roll is never taken
                JsonElement w = entry.get("weight");
                if (w != null) {
                    if (!numeric(w)) {
                        return;
                    }
                    if (w.getAsDouble() <= 0) {
                        continue;
                    }
                }
            }
            String id = str(rw, "table");
            if (tables.has(id)) {
                to.add(id);
            }
        }
    }

    /** Whether the node or one of its ranks gives one of the tables that can never be handed out. */
    private static boolean givesEndlessTable(JsonObject node, Set<String> endless) {
        for (JsonElement rw : arr(node, "rewards")) {
            if (rw.isJsonObject() && endless.contains(str(rw.getAsJsonObject(), "table"))) {
                return true;
            }
        }
        for (JsonElement rank : arr(node, "ranks")) {
            if (!rank.isJsonObject()) {
                continue;
            }
            for (JsonElement rw : arr(rank.getAsJsonObject(), "rewards")) {
                if (rw.isJsonObject() && endless.contains(str(rw.getAsJsonObject(), "table"))) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ helpers

    private static void required(Result out, ChapterRef ref, String path, JsonObject o, String field) {
        if (str(o, field).isEmpty()) {
            problem(out, Severity.ERROR, ref, path + "." + field, field + " task has no " + field);
        }
    }

    /** A reward missing its field only gives nothing ({@code Rewards.give} catches the rest), so it warns. */
    private static void needs(Result out, ChapterRef ref, String path, JsonObject o, String field) {
        if (str(o, field).isEmpty()) {
            problem(out, Severity.WARNING, ref, path + "." + field, field + " reward has no " + field + ", so nothing is given");
        }
    }

    /**
     * A number {@code ProgressRules.target} reads with {@code getAsLong} on the server tick, which has no handler above
     * it. "count" and "ticks" are only read when they are primitive, so anything else quietly falls through to the next
     * field; the others are read whatever they are. A JSON number is always read (a fraction is truncated); a string
     * goes through {@code Long.parseLong}, which takes "16" but throws on "1.5", "1e3", "16 " and "NaN". A value of 0
     * or less quietly becomes 1, which is never what the author wrote.
     */
    private static void taskNumber(Result out, ChapterRef ref, String path, JsonObject o, String field, boolean onlyPrimitive) {
        JsonElement e = o.get(field);
        if (e == null) {
            return;
        }
        if (onlyPrimitive && !e.isJsonPrimitive()) {
            problem(out, Severity.WARNING, ref, path + "." + field, quote(field) + " is not a number, so it is not read");
            return;
        }
        if (!integral(e)) {
            problem(out, Severity.ERROR, ref, path + "." + field,
                    quote(field) + " is not a whole number, so the server would stop reading this node");
            return;
        }
        long v = e.getAsLong();
        if (v <= 0) {
            problem(out, Severity.WARNING, ref, path + "." + field, quote(field) + " is " + v + "; treated as 1");
        }
    }

    /** A reward number: {@code Rewards.give} catches what the readers throw, so a broken one only skips that reward. */
    private static void numberShape(Result out, ChapterRef ref, String path, JsonObject o, String field) {
        JsonElement e = o.get(field);
        if (e != null && !numeric(e)) {
            problem(out, Severity.WARNING, ref, path + "." + field, quote(field) + " is not a number");
        }
    }

    /** {@code ProgressModel.intOf} keeps only a number and silently falls back to its default for anything else. */
    private static void stringArrayShape(Result out, ChapterRef ref, String path, JsonObject o, String field, String what) {
        JsonElement a = o.get(field);
        if (a == null) {
            return;
        }
        if (!a.isJsonArray()) {
            problem(out, Severity.WARNING, ref, path + "." + field, quote(field) + " is not an array of " + what + ", so it is not read");
            return;
        }
        for (int i = 0; i < a.getAsJsonArray().size(); i++) {
            if (!a.getAsJsonArray().get(i).isJsonPrimitive() || !a.getAsJsonArray().get(i).getAsJsonPrimitive().isString()) {
                problem(out, Severity.WARNING, ref, path + "." + field + "[" + i + "]", "is not a " + what + " string, so it is skipped");
            }
        }
    }

    private static void whole(Result out, ChapterRef ref, String path, JsonObject o, String field) {
        JsonElement e = o.get(field);
        if (e != null && !(e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber())) {
            problem(out, Severity.WARNING, ref, path + "." + field, quote(field) + " is not a number, so the default is used");
        }
    }

    /** A boolean the readers take with {@code boolOf}: anything else is read as false. */
    private static void booleanShape(Result out, ChapterRef ref, String path, JsonObject o, String field) {
        JsonElement e = o.get(field);
        if (e != null && !(e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean())) {
            problem(out, Severity.WARNING, ref, path + "." + field, quote(field) + " is not true or false, so it is read as false");
        }
    }

    /** A field the readers take with getAsString: a non-string is read as "" (which then means the default). */
    private static void textShape(Result out, ChapterRef ref, String path, JsonObject o, String field, Severity severity) {
        if (o.has(field) && !isText(o.get(field))) {
            problem(out, severity, ref, path + "." + field, quote(field) + " is not a string");
        }
    }

    private static void numbers(Result out, ChapterRef ref, String path, JsonArray a) {
        for (int i = 0; i < a.size(); i++) {
            if (!intValue(a.get(i))) {
                problem(out, Severity.ERROR, ref, path + "[" + i + "]", "is not a whole number");
            }
        }
    }

    /**
     * A node name that is not there, at the severity the caller gives it. A name whose chapter would not parse is
     * only a warning whatever the caller asked for: whether the node is there simply cannot be known.
     */
    private static void referenceProblem(Result out, ChapterRef ref, String path, String what, Ref target,
                                         Map<String, JsonObject> nodes, JsonObject chapters, Set<String> unreadable,
                                         Severity severity) {
        if (nodes.containsKey(target.target())) {
            return;
        }
        String chapter = chapterOf(target.target());
        if (unreadable.contains(chapter) && !chapters.has(chapter)) {
            problem(out, Severity.WARNING, ref, path,
                    what + " node " + quote(target.raw()) + " in chapter " + quote(chapter) + ", which could not be read");
            return;
        }
        if (chapters.has(chapter)) {
            problem(out, severity, ref, path, what + " node " + quote(target.raw()) + ", which does not exist");
        } else {
            problem(out, severity, ref, path,
                    what + " node " + quote(target.raw()) + " in chapter " + quote(chapter) + ", which does not exist");
        }
    }

    /**
     * Kinds whose id is compared to a full id as text ({@code entityMatches}, {@code blockMatches},
     * {@code dimension().location().toString().equals(...)}), so a name with no namespace can never match one. The
     * others (item, fluid, attribute) go through {@code ResourceLocation.tryParse}, which supplies "minecraft" when the
     * namespace is left out, which is why they are not reported.
     */
    private static final Set<String> NAMESPACE_KINDS = Set.of("block", "entity", "dimension");

    private static void id(Result out, ChapterRef ref, String path, String kind, String spec, Registry registry) {
        if (spec.isEmpty() || registry == Registry.NONE) {
            return;
        }
        if (spec.equals("#")) {
            problem(out, Severity.ERROR, ref, path, kind + " " + quote(spec) + " is an empty #tag, so it never matches");
            return;
        }
        if (spec.startsWith("#")) {
            // a tag: every tag reader parses the name with ResourceLocation.tryParse (namespace defaults), and what is
            // inside the tag cannot be checked here (see GameRegistry)
            return;
        }
        String full = spec;
        if (!spec.contains(":")) {
            if (NAMESPACE_KINDS.contains(kind)) {
                problem(out, Severity.WARNING, ref, path, kind + " " + quote(spec) + " has no namespace, so it never matches");
                return;
            }
            full = "minecraft:" + spec;
        }
        if (!registry.known(kind, full)) {
            problem(out, Severity.WARNING, ref, path, kind + " " + quote(spec) + " does not exist");
        }
    }

    private static void problem(Result out, Severity severity, ChapterRef ref, String path, String message) {
        out.problems.add(new Problem(severity, ref.file(), path, message, lineAt(ref.lines(), relative(ref, path))));
    }

    private static int countErrors(Result out, int from) {
        int n = 0;
        for (int i = from; i < out.problems.size(); i++) {
            if (out.problems.get(i).severity() == Severity.ERROR) {
                n++;
            }
        }
        return n;
    }

    /** A node name a node lists, already resolved the way ProgressModel resolves it. */
    private record Ref(String raw, String target) {
    }

    /** The names a node lists under {@code field} ({@code requires.nodes} or {@code exclusive_with}). */
    private static List<Ref> refsOf(String chapter, JsonObject node, String field) {
        List<JsonElement> names = new ArrayList<>();
        if (field.equals("requires")) {
            arr(obj(node, "requires"), "nodes").forEach(names::add);
        } else {
            arr(node, field).forEach(names::add);
        }
        List<Ref> out = new ArrayList<>();
        for (JsonElement x : names) {
            if (!x.isJsonPrimitive()) {
                continue;
            }
            // getAsString is what ProgressModel uses, so a number or a boolean reads as its text
            String raw = x.getAsString();
            // ProgressModel resolves a "requires" name that holds a slash against the namespace ("basics/iron" is
            // another chapter of the same namespace), and every "exclusive_with" name against this chapter
            String target = field.equals("requires") && raw.contains("/")
                    ? namespace(chapter) + ":" + raw
                    : chapter + "/" + raw;
            out.add(new Ref(raw, target));
        }
        return out;
    }

    private static String chapterOf(String key) {
        int slash = key.lastIndexOf('/');
        return slash < 0 ? key : key.substring(0, slash);
    }

    private record ChapterRef(String file, Scan scan, String prefix) {
        Map<String, Integer> lines() {
            return scan == null ? Map.of() : scan.lines();
        }
    }

    private static ChapterRef ref(String chapter, Map<String, Source> sources) {
        Source s = sources.get("chapters/" + chapter);
        return new ChapterRef(s == null ? "" : s.file(), s == null ? null : scan(s.rawText()),
                "chapters." + local(chapter) + ".");
    }

    private static ChapterRef tableRef(Map<String, Source> sources, String id) {
        return tableRef2(sources.get("reward_tables/" + id), id);
    }

    private static ChapterRef tableRef2(Source source, String id) {
        return new ChapterRef(source == null ? "" : source.file(), source == null ? null : scan(source.rawText()),
                "reward_tables." + local(id) + ".");
    }

    /** The bundle path of a node: {@code chapters.basics.nodes.iron}. */
    private static String nodePath(String chapter, String key) {
        return "chapters." + local(chapter) + ".nodes." + key.substring(chapter.length() + 1);
    }

    /** Turn a reported bundle path back into the path inside the file, so the line can be found. */
    private static String relative(ChapterRef ref, String path) {
        String p = path == null ? "" : path;
        return ref != null && p.startsWith(ref.prefix()) ? p.substring(ref.prefix().length()) : p;
    }

    /** The bundle path prefix a scanned file's own paths sit under. */
    private static String prefixOf(String sourceKey) {
        int slash = sourceKey.indexOf('/');
        if (slash < 0) {
            return "";
        }
        String kind = sourceKey.substring(0, slash);
        return kind.equals("chapters") || kind.equals("reward_tables") ? kind + "." + local(sourceKey.substring(slash + 1)) + "." : "";
    }

    private static String local(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }

    private static String namespace(String chapter) {
        int colon = chapter.indexOf(':');
        return colon < 0 ? chapter : chapter.substring(0, colon);
    }

    // ------------------------------------------------------------------ JSON text

    private record Scan(Map<String, Integer> lines, List<String> duplicates) {
    }

    /**
     * One pass over the raw text: every path's 1-based line, and any object member declared twice.
     * <p>
     * This is a hand-rolled scan rather than a {@code com.google.gson.stream.JsonReader}: Minecraft's own JsonReader
     * has no public line accessor (it was replaced by a package-private {@code locationString}), so the line numbers
     * have to come from the text itself. A malformed tail just stops the scan; the parse still reports the syntax
     * error, and every lookup falls back to the nearest parent line.
     */
    private static Scan scan(String text) {
        Map<String, Integer> lines = new LinkedHashMap<>();
        List<String> duplicates = new ArrayList<>();
        if (text == null) {
            return new Scan(lines, duplicates);
        }
        try {
            new Text(text).value("", lines, duplicates);
        } catch (Exception ignored) {
            // a partial index is enough
        }
        return new Scan(lines, duplicates);
    }

    /** Just enough JSON awareness to find line numbers and repeated keys; the real parse is Gson's. */
    private static final class Text {
        /**
         * How deep the scan follows objects and arrays before it gives up and keeps what it has. The readers are never
         * at risk from this: Gson refuses a document nested deeper than 255 before any of them sees it. The scan
         * recurses once per level and a stack overflow is an Error, which the catch in {@link #scan} cannot see.
         */
        private static final int MAX_DEPTH = 200;
        private final String s;
        private int i;
        private int line = 1;
        private int depth;

        Text(String s) {
            this.s = s;
        }

        private void enter() {
            if (++depth > MAX_DEPTH) {
                throw new IllegalStateException("nested too deeply to scan");
            }
        }

        private void exit() {
            depth--;
        }

        private void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                    take();
                } else {
                    break;
                }
            }
        }

        private char peek() {
            return i < s.length() ? s.charAt(i) : '\0';
        }

        private char take() {
            char c = s.charAt(i++);
            if (c == '\n') {
                line++;
            }
            return c;
        }

        private String string() {
            StringBuilder b = new StringBuilder();
            take();
            while (i < s.length()) {
                char c = take();
                if (c == '\\') {
                    b.append(take());
                } else if (c == '"') {
                    break;
                } else {
                    b.append(c);
                }
            }
            return b.toString();
        }

        private void literal() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ',' || c == '}' || c == ']' || c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                    break;
                }
                take();
            }
        }

        private void value(String path, Map<String, Integer> lines, List<String> duplicates) {
            ws();
            char c = peek();
            if (c == '{') {
                enter();
                object(path, lines, duplicates);
                exit();
            } else if (c == '[') {
                enter();
                array(path, lines, duplicates);
                exit();
            } else if (c == '"') {
                lines.putIfAbsent(path, line);
                string();
            } else if (c != '\0') {
                lines.putIfAbsent(path, line);
                literal();
            }
        }

        private void object(String path, Map<String, Integer> lines, List<String> duplicates) {
            lines.putIfAbsent(path, line);
            take();
            Set<String> seen = new LinkedHashSet<>();
            ws();
            if (peek() == '}') {
                take();
                return;
            }
            while (i < s.length()) {
                ws();
                if (peek() != '"') {
                    return;
                }
                String name = string();
                String child = path.isEmpty() ? name : path + "." + name;
                if (!seen.add(name)) {
                    duplicates.add(child);
                }
                ws();
                if (peek() == ':') {
                    take();
                }
                value(child, lines, duplicates);
                ws();
                if (peek() == ',') {
                    take();
                    continue;
                }
                if (peek() == '}') {
                    take();
                }
                return;
            }
        }

        private void array(String path, Map<String, Integer> lines, List<String> duplicates) {
            lines.putIfAbsent(path, line);
            take();
            int n = 0;
            ws();
            if (peek() == ']') {
                take();
                return;
            }
            while (i < s.length()) {
                value(path + "[" + n + "]", lines, duplicates);
                n++;
                ws();
                if (peek() == ',') {
                    take();
                    continue;
                }
                if (peek() == ']') {
                    take();
                }
                return;
            }
        }
    }

    /**
     * The line of a path: its own line, or the line of the nearest ancestor that was recorded (walking up
     * "a.b[0].c" to "a.b[0]", "a.b", "a"), so a value inside an object is reported where that object starts. A path
     * that was never recorded falls back to the line of the whole document, and to 0 when even that is missing.
     */
    private static int lineAt(Map<String, Integer> lines, String path) {
        String p = path == null ? "" : path;
        while (!p.isEmpty()) {
            Integer line = lines.get(p);
            if (line != null) {
                return line;
            }
            int cut = Math.max(p.lastIndexOf('.'), p.lastIndexOf('['));
            if (cut < 0) {
                break;
            }
            p = p.substring(0, cut);
        }
        Integer root = lines.get("");
        return root == null ? 0 : root;
    }

    private static final Pattern LINE = Pattern.compile("line (\\d+)");

    private static int lineFrom(String message) {
        Matcher m = LINE.matcher(message);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** What a matcher reader gets, and whether it throws instead of reading at all. */
    private record Spec(String text, boolean unreadable) {

        boolean empty() {
            return text.isEmpty();
        }
    }

    /**
     * {@code ProgressService.spec} (a block or fluid matcher) as one string. An object with none of those keys reads as
     * "" (which means "any block" for a break task), while an array, null or a non-string value under one of the keys
     * makes {@code getAsString} throw.
     */
    private static Spec specText(JsonElement e) {
        if (e == null) {
            return new Spec("", false);
        }
        if (e.isJsonPrimitive()) {
            return new Spec(e.getAsString(), false);
        }
        if (!e.isJsonObject()) {
            return new Spec("", true);
        }
        JsonObject o = e.getAsJsonObject();
        if (o.has("tag")) {
            return specValue(o.get("tag"), true);
        }
        if (o.has("id")) {
            return specValue(o.get("id"), false);
        }
        return o.has("fluid") ? specValue(o.get("fluid"), false) : new Spec("", false);
    }

    /**
     * {@code ProgressService.itemSpec} as one string: only "tag" and "id", no "fluid". There is no shape check before
     * {@code getAsJsonObject}, so an array or null throws as well.
     */
    private static Spec itemSpecText(JsonElement e) {
        if (e == null) {
            return new Spec("", false);
        }
        if (e.isJsonPrimitive()) {
            return new Spec(e.getAsString(), false);
        }
        if (!e.isJsonObject()) {
            return new Spec("", true);
        }
        JsonObject o = e.getAsJsonObject();
        if (o.has("tag")) {
            return specValue(o.get("tag"), true);
        }
        return o.has("id") ? specValue(o.get("id"), false) : new Spec("", false);
    }

    private static Spec specValue(JsonElement v, boolean tag) {
        return v.isJsonPrimitive() ? new Spec((tag ? "#" : "") + v.getAsString(), false) : new Spec("", true);
    }

    /**
     * A matcher the readers throw on. These run on the server tick with no handler above them, so the node would stop
     * the server rather than never count.
     */
    private static void specProblem(Result out, ChapterRef ref, String path, String field) {
        problem(out, Severity.ERROR, ref, path,
                quote(field) + " cannot be read as an id or a #tag, so the server would stop reading this node");
    }

    /**
     * Whether a reader that calls {@code getAsDouble} can turn the value into a number. Gson parses a numeric string
     * ("16", "1.5", "1e3", "NaN"), and throws on anything else (a boolean, a word, an object, an array, null).
     */
    private static boolean numeric(JsonElement e) {
        if (e == null || !e.isJsonPrimitive()) {
            return false;
        }
        if (e.getAsJsonPrimitive().isNumber()) {
            return true;
        }
        if (!e.getAsJsonPrimitive().isString()) {
            return false;
        }
        try {
            Double.parseDouble(e.getAsString());
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    /**
     * Whether a reader that calls {@code getAsLong} can turn the value into a number: a JSON number always, and a
     * string only when {@code Long.parseLong} takes it. That is stricter than {@link #numeric}: "1.5", "1e3" and "16 "
     * are read by {@code getAsDouble} but stop {@code getAsLong}.
     */
    private static boolean integral(JsonElement e) {
        if (e == null || !e.isJsonPrimitive()) {
            return false;
        }
        if (e.getAsJsonPrimitive().isNumber()) {
            return true;
        }
        if (!e.getAsJsonPrimitive().isString()) {
            return false;
        }
        try {
            Long.parseLong(e.getAsString());
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    /**
     * Whether a reader that calls {@code getAsInt} can turn the value into a number. Same as {@link #integral} except
     * for the range: a string that only fits in a long stops {@code Integer.parseInt}.
     */
    private static boolean intValue(JsonElement e) {
        if (e == null || !e.isJsonPrimitive()) {
            return false;
        }
        if (e.getAsJsonPrimitive().isNumber()) {
            return true;
        }
        if (!e.getAsJsonPrimitive().isString()) {
            return false;
        }
        try {
            Integer.parseInt(e.getAsString());
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    /**
     * A boolean the server tick reads with {@code getAsBoolean} ({@code optional} and {@code consume} of a task).
     * Gson throws on an object, an array or null, and nothing above the tick handles that, so the node would stop the
     * server; a number or a word is only read as false.
     */
    private static void tickFlag(Result out, ChapterRef ref, String path, JsonObject o, String field) {
        JsonElement e = o.get(field);
        if (e == null) {
            return;
        }
        if (!e.isJsonPrimitive()) {
            problem(out, Severity.ERROR, ref, path + "." + field,
                    quote(field) + " is not true or false, so the server would stop reading this node");
        } else if (!e.getAsJsonPrimitive().isBoolean()) {
            problem(out, Severity.WARNING, ref, path + "." + field, quote(field) + " is not true or false, so it is read as false");
        }
    }

    private static boolean isText(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString();
    }

    private static int intOf(JsonObject o, String field) {
        JsonElement e = o.get(field);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsInt() : 0;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    private static JsonObject obj(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : new JsonObject();
    }

    private static JsonArray arr(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray();
    }

    private static String quote(String s) {
        return "\"" + s + "\"";
    }

    private static String trim(double v) {
        return v == Math.floor(v) && !Double.isInfinite(v) ? Long.toString((long) v) : Double.toString(v);
    }
}
