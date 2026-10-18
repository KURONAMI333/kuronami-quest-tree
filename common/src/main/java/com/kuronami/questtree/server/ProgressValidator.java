package com.kuronami.questtree.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
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
 * ERRORs mean a node cannot work as written. The caller drops exactly those nodes and keeps the rest of the pack
 * running; the reported dependencies on a dropped node are reported as WARNINGs so a cascade never empties the tree.
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
     * WARNING); the game-side implementation lives in {@code GameRegistry}.
     */
    public interface Registry {
        boolean known(String kind, String id);

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
            "observation", "stat", "xp", "fluid", "energy", "break", "stage", "custom", "interaction");
    private static final Set<String> TASK_UNIMPLEMENTED = Set.of("custom", "interaction");
    private static final Set<String> REWARD_TYPES = Set.of(
            "item", "xp", "xp_levels", "command", "table", "advancement", "stage", "attribute", "points",
            "tag", "toast", "trophy", "scoreboard", "custom", "dummy");
    private static final Set<String> REWARD_UNIMPLEMENTED = Set.of("scoreboard", "custom", "dummy");

    private ProgressValidator() {
    }

    public static Result validate(JsonObject bundle, Map<String, Source> sources) {
        return validate(bundle, sources, Registry.NONE);
    }

    /**
     * @param bundle  the merged chapters/reward_tables the model will be built from (after patches)
     * @param sources key {@code chapters/<ns>:<name>} or {@code reward_tables/<ns>:<name>} → the file's raw text
     * @param registry the running game's ids, or {@link Registry#NONE}
     */
    public static Result validate(JsonObject bundle, Map<String, Source> sources, Registry registry) {
        Result out = new Result();
        JsonObject chapters = obj(bundle, "chapters");
        JsonObject tables = obj(bundle, "reward_tables");

        // duplicate member names ("declared twice") can only be seen in the text; Gson keeps the last one silently
        for (Map.Entry<String, Source> e : sources.entrySet()) {
            Source s = e.getValue();
            Scan scan = scan(s.rawText());
            String prefix = e.getKey().startsWith("chapters/") ? "chapters." + local(e.getKey().substring("chapters/".length())) + "." : "";
            for (String dup : scan.duplicates()) {
                out.problems.add(new Problem(Severity.ERROR, s.file(), prefix + dup,
                        "object member is declared more than once", lineAt(scan.lines(), dup)));
            }
        }

        // every node of the merged bundle, with where to look for its line
        Map<String, ChapterRef> refs = new LinkedHashMap<>();
        Map<String, String> nodeChapter = new LinkedHashMap<>();
        Map<String, JsonObject> nodes = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : chapters.entrySet()) {
            if (!e.getValue().isJsonObject()) {
                continue;
            }
            String chapter = e.getKey();
            refs.put(chapter, ref(chapter, sources));
            for (Map.Entry<String, JsonElement> n : obj(e.getValue().getAsJsonObject(), "nodes").entrySet()) {
                if (n.getValue().isJsonObject()) {
                    String key = chapter + "/" + n.getKey();
                    nodeChapter.put(key, chapter);
                    nodes.put(key, n.getValue().getAsJsonObject());
                }
            }
        }

        // pass 1: the node itself (types, required fields, ids, counts)
        for (Map.Entry<String, JsonObject> e : nodes.entrySet()) {
            String key = e.getKey();
            JsonObject node = e.getValue();
            String chapter = nodeChapter.get(key);
            String rel = "nodes." + e.getKey().substring(chapter.length() + 1);
            int before = out.problems.size();
            checkNode(out, refs.get(chapter), key, chapter, rel, node, tables, registry);
            if (countErrors(out, before) > 0) {
                out.droppedNodes.add(key);
            }
        }

        // pass 2: references. A reference to a node that never existed is an ERROR (progression would silently open
        // early); a reference to a node that was already dropped is a WARNING, so one bad node cannot empty the tree
        Set<String> known = nodes.keySet();
        for (Map.Entry<String, JsonObject> e : nodes.entrySet()) {
            String key = e.getKey();
            if (out.droppedNodes.contains(key)) {
                continue;
            }
            JsonObject node = e.getValue();
            String chapter = nodeChapter.get(key);
            String namespace = namespace(chapter);
            ChapterRef r = refs.get(chapter);
            String rel = "nodes." + e.getKey().substring(chapter.length() + 1);
            int before = out.problems.size();
            for (JsonElement x : arr(obj(node, "requires"), "nodes")) {
                if (!x.isJsonPrimitive()) {
                    continue;
                }
                String raw = x.getAsString();
                String target = raw.contains("/") ? namespace + ":" + raw : chapter + "/" + raw;
                if (!known.contains(target)) {
                    problem(out, Severity.ERROR, r, rel + ".requires.nodes",
                            "requires node " + quote(raw) + ", which does not exist");
                } else if (out.droppedNodes.contains(target)) {
                    problem(out, Severity.WARNING, r, rel + ".requires.nodes",
                            "requires node " + quote(raw) + ", which was dropped");
                }
            }
            for (JsonElement x : arr(node, "exclusive_with")) {
                if (!x.isJsonPrimitive()) {
                    continue;
                }
                String raw = x.getAsString();
                String target = chapter + "/" + raw;
                if (!known.contains(target)) {
                    problem(out, Severity.ERROR, r, rel + ".exclusive_with",
                            "exclusive_with " + quote(raw) + ", which does not exist");
                }
            }
            if (countErrors(out, before) > 0) {
                out.droppedNodes.add(key);
            }
        }

        // pass 3: cycles in the dependencies. Every node of a hard cycle waits on the next, so none can ever be
        // reached: drop them. A cycle through an OR requirement might still open, so it is only a warning
        List<List<String>> soft = new ArrayList<>();
        for (List<String> cycle : cycles(nodes, nodeChapter, out.droppedNodes, soft)) {
            String names = String.join(" -> ", cycle);
            for (String key : cycle) {
                String chapter = nodeChapter.get(key);
                problem(out, Severity.ERROR, refs.get(chapter), "nodes." + key.substring(chapter.length() + 1) + ".requires.nodes",
                        "is part of a dependency cycle: " + names);
                out.droppedNodes.add(key);
            }
        }
        for (List<String> cycle : soft) {
            String names = String.join(" -> ", cycle);
            for (String key : cycle) {
                String chapter = nodeChapter.get(key);
                problem(out, Severity.WARNING, refs.get(chapter), "nodes." + key.substring(chapter.length() + 1) + ".requires.nodes",
                        "is part of a possible dependency cycle: " + names);
            }
        }

        // reward tables: entries are rewards too
        for (Map.Entry<String, JsonElement> e : tables.entrySet()) {
            if (!e.getValue().isJsonObject()) {
                continue;
            }
            checkTable(out, sources.get("reward_tables/" + e.getKey()), e.getKey(), e.getValue().getAsJsonObject(), tables, registry);
        }
        return out;
    }

    /** Fork an ERROR from a JSON syntax error: Gson's message carries the line. */
    public static Problem parseError(String file, String rawText, Throwable ex) {
        String message = ex.getMessage() == null ? "invalid JSON" : ex.getMessage().trim();
        return new Problem(Severity.ERROR, file, "", "could not be parsed: " + message, lineFrom(message));
    }

    /** The 1-based line of a path in a file, or 0 when it cannot be found (patches, computed values). */
    public static int lineOf(String rawText, String path) {
        return lineAt(scan(rawText).lines(), path);
    }

    // ------------------------------------------------------------------ one node

    private static void checkNode(Result out, ChapterRef ref, String key, String chapter, String rel, JsonObject node,
                                  JsonObject tables, Registry registry) {
        String nodePath = "chapters." + local(chapter) + "." + rel;
        JsonArray tasks = arr(node, "tasks");
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).isJsonObject()) {
                task(out, ref, nodePath + ".tasks[" + i + "]", tasks.get(i).getAsJsonObject(), registry);
            } else {
                problem(out, Severity.ERROR, ref, nodePath + ".tasks[" + i + "]", "task is not an object");
            }
        }
        JsonArray rewards = arr(node, "rewards");
        for (int i = 0; i < rewards.size(); i++) {
            if (rewards.get(i).isJsonObject()) {
                reward(out, ref, nodePath + ".rewards[" + i + "]", rewards.get(i).getAsJsonObject(), tables, registry);
            } else {
                problem(out, Severity.ERROR, ref, nodePath + ".rewards[" + i + "]", "reward is not an object");
            }
        }
        JsonArray ranks = arr(node, "ranks");
        for (int i = 0; i < ranks.size(); i++) {
            if (!ranks.get(i).isJsonObject()) {
                problem(out, Severity.ERROR, ref, nodePath + ".ranks[" + i + "]", "rank is not an object");
                continue;
            }
            for (JsonElement rw : arr(ranks.get(i).getAsJsonObject(), "rewards")) {
                if (rw.isJsonObject()) {
                    reward(out, ref, nodePath + ".ranks[" + i + "].rewards", rw.getAsJsonObject(), tables, registry);
                }
            }
        }
    }

    private static void task(Result out, ChapterRef ref, String path, JsonObject t, Registry registry) {
        String type = str(t, "type");
        if (type.isEmpty()) {
            problem(out, Severity.ERROR, ref, path, "task has no \"type\"");
            return;
        }
        if (!TASK_TYPES.contains(type)) {
            problem(out, Severity.ERROR, ref, path + ".type", "unknown task type " + quote(type));
            return;
        }
        if (TASK_UNIMPLEMENTED.contains(type)) {
            problem(out, Severity.WARNING, ref, path + ".type", "task type " + quote(type) + " is not implemented yet");
        }
        switch (type) {
            case "item" -> {
                if (spec(t.get("item")).isEmpty()) {
                    problem(out, Severity.ERROR, ref, path + ".item", "item task has no item");
                } else {
                    id(out, ref, path + ".item", "item", spec(t.get("item")), registry);
                }
            }
            case "kill" -> id(out, ref, path + ".entity", "entity", str(t, "entity"), registry);
            case "advancement" -> required(out, ref, path, t, "advancement");
            case "structure" -> required(out, ref, path, t, "structure");
            case "biome" -> required(out, ref, path, t, "biome");
            case "dimension" -> {
                required(out, ref, path, t, "dimension");
                id(out, ref, path + ".dimension", "dimension", str(t, "dimension"), registry);
            }
            case "location" -> {
                JsonElement pos = t.get("position");
                if (pos == null || !pos.isJsonArray() || pos.getAsJsonArray().size() < 3) {
                    problem(out, Severity.ERROR, ref, path + ".position", "location task needs a position of 3 numbers");
                }
            }
            case "observation" -> {
                required(out, ref, path, t, "target");
                String kind = str(t, "observe_type");
                if (kind.startsWith("entity")) {
                    id(out, ref, path + ".target", "entity", str(t, "target"), registry);
                } else if (!kind.isEmpty()) {
                    id(out, ref, path + ".target", "block", str(t, "target"), registry);
                }
            }
            case "stat" -> required(out, ref, path, t, "stat");
            case "fluid" -> {
                if (spec(t.get("fluid")).isEmpty()) {
                    problem(out, Severity.ERROR, ref, path + ".fluid", "fluid task has no fluid");
                } else {
                    id(out, ref, path + ".fluid", "fluid", spec(t.get("fluid")), registry);
                }
            }
            case "break" -> id(out, ref, path + ".block", "block", spec(t.get("block")), registry);
            case "stage" -> required(out, ref, path, t, "stage");
            default -> {
            }
        }
        positive(out, ref, path + ".count", t, "count");
        if (type.equals("fluid") || type.equals("energy")) {
            positive(out, ref, path + ".amount", t, "amount");
        }
    }

    private static void reward(Result out, ChapterRef ref, String path, JsonObject rw, JsonObject tables, Registry registry) {
        String type = str(rw, "type");
        if (type.isEmpty()) {
            problem(out, Severity.ERROR, ref, path, "reward has no \"type\"");
            return;
        }
        if (!REWARD_TYPES.contains(type)) {
            problem(out, Severity.ERROR, ref, path + ".type", "unknown reward type " + quote(type));
            return;
        }
        if (REWARD_UNIMPLEMENTED.contains(type)) {
            problem(out, Severity.WARNING, ref, path + ".type", "reward type " + quote(type) + " is not implemented yet");
        }
        switch (type) {
            case "item" -> {
                String id = itemRewardId(rw);
                if (id.isEmpty()) {
                    problem(out, Severity.ERROR, ref, path + ".item", "item reward has no item");
                } else {
                    id(out, ref, path + ".item", "item", id, registry);
                }
            }
            case "command" -> {
                if (str(rw, "command").isEmpty() && str(rw, "unlock_command").isEmpty()) {
                    problem(out, Severity.ERROR, ref, path, "command reward has no command");
                }
            }
            case "table" -> {
                String table = str(rw, "table");
                if (table.isEmpty()) {
                    problem(out, Severity.ERROR, ref, path + ".table", "table reward has no table");
                } else if (!tables.has(table) || !tables.get(table).isJsonObject()) {
                    problem(out, Severity.ERROR, ref, path + ".table", "reward table " + quote(table) + " does not exist");
                } else if (!tables.getAsJsonObject(table).has("entries") || !tables.getAsJsonObject(table).get("entries").isJsonArray()) {
                    problem(out, Severity.ERROR, ref, path + ".table", "reward table " + quote(table) + " has no entries");
                }
            }
            case "advancement" -> required(out, ref, path, rw, "advancement");
            case "stage" -> required(out, ref, path, rw, "stage");
            case "attribute" -> {
                required(out, ref, path, rw, "attribute");
                id(out, ref, path + ".attribute", "attribute", str(rw, "attribute"), registry);
            }
            case "tag" -> required(out, ref, path, rw, "tag");
            case "trophy" -> {
                String trophy = str(rw, "trophy");
                if (trophy.isEmpty()) {
                    problem(out, Severity.ERROR, ref, path + ".trophy", "trophy reward has no trophy");
                } else if (!trophy.contains(":")) {
                    problem(out, Severity.WARNING, ref, path + ".trophy", "trophy " + quote(trophy) + " has no namespace");
                }
            }
            case "xp", "xp_levels" -> positive(out, ref, path + ".amount", rw, "amount");
            default -> {
            }
        }
    }

    private static void checkTable(Result out, Source source, String id, JsonObject table, JsonObject tables, Registry registry) {
        ChapterRef ref = new ChapterRef(source == null ? "" : source.file(), source == null ? null : scan(source.rawText()));
        String path = "reward_tables." + local(id);
        JsonElement entries = table.get("entries");
        if (entries == null || !entries.isJsonArray()) {
            problem(out, Severity.ERROR, ref, path, "reward table has no \"entries\" array");
            return;
        }
        for (int i = 0; i < entries.getAsJsonArray().size(); i++) {
            JsonElement e = entries.getAsJsonArray().get(i);
            if (!e.isJsonObject()) {
                problem(out, Severity.ERROR, ref, path + ".entries[" + i + "]", "entry is not an object");
                continue;
            }
            JsonObject o = e.getAsJsonObject();
            JsonElement reward = o.get("reward");
            if (reward == null || !reward.isJsonObject()) {
                problem(out, Severity.ERROR, ref, path + ".entries[" + i + "]", "entry has no \"reward\"");
            } else {
                reward(out, ref, path + ".entries[" + i + "].reward", reward.getAsJsonObject(), tables, registry);
            }
            positive(out, ref, path + ".entries[" + i + "].weight", o, "weight");
        }
        positive(out, ref, path + ".rolls", table, "rolls");
        if (table.has("loot_crate") && !table.get("loot_crate").isJsonNull()) {
            problem(out, Severity.WARNING, ref, path + ".loot_crate", "loot crates are not implemented yet");
        }
    }

    // ------------------------------------------------------------------ dependency cycles

    /**
     * Cycles among {@code requires}. Tarjan's strongly connected components: a component of two or more nodes, or a
     * node that requires itself, is a cycle. Nodes already dropped in an earlier pass are left out.
     * <p>
     * Two things are not cycles here. A node that opens without its requirements (the Puffish root, see
     * {@code ProgressRules.unlocked}) is never blocked, so its edges are left out and a cycle through it disappears.
     * And a node whose requirements are an OR ({@code "any"} / {@code "one"} / a {@code min}) can open from another
     * branch, so such a cycle is only reported as a possible one; only a cycle where every node must have all of its
     * requirements met (and so can never be reached) is a dead end that drops the nodes.
     */
    private static List<List<String>> cycles(Map<String, JsonObject> nodes, Map<String, String> nodeChapter, Set<String> dropped, List<List<String>> soft) {
        Map<String, List<String>> edges = new LinkedHashMap<>();
        for (Map.Entry<String, JsonObject> e : nodes.entrySet()) {
            if (dropped.contains(e.getKey()) || alwaysOpen(e.getValue())) {
                continue;
            }
            String chapter = nodeChapter.get(e.getKey());
            String namespace = namespace(chapter);
            List<String> targets = new ArrayList<>();
            for (JsonElement x : arr(obj(e.getValue(), "requires"), "nodes")) {
                if (!x.isJsonPrimitive()) {
                    continue;
                }
                String raw = x.getAsString();
                String target = raw.contains("/") ? namespace + ":" + raw : chapter + "/" + raw;
                if (nodes.containsKey(target) && !dropped.contains(target) && !alwaysOpen(nodes.get(target))) {
                    targets.add(target);
                }
            }
            edges.put(e.getKey(), targets);
        }
        List<List<String>> found = new ArrayList<>();
        for (List<String> component : strongComponents(edges)) {
            if (component.size() > 1 || (component.size() == 1 && edges.getOrDefault(component.get(0), List.of()).contains(component.get(0)))) {
                boolean allMustPass = component.stream().allMatch(k -> nodes.containsKey(k) && allOfItsRequirements(nodes.get(k)));
                (allMustPass ? found : soft).add(component);
            }
        }
        return found;
    }

    /** A node the rules always unlock: it needs nothing, or it is a Puffish root. */
    private static boolean alwaysOpen(JsonObject node) {
        if (arr(obj(node, "requires"), "nodes").isEmpty()) {
            return true;
        }
        JsonElement root = node.get("root");
        return root != null && root.isJsonPrimitive() && root.getAsBoolean();
    }

    /** Whether a node cannot open until every requirement is done (an AND, not an OR). */
    private static boolean allOfItsRequirements(JsonObject node) {
        JsonObject req = obj(node, "requires");
        int min = req.has("min") && req.get("min").isJsonPrimitive() ? req.get("min").getAsInt() : 0;
        String mode = str(req, "mode");
        return min <= 0 && (mode.isEmpty() || mode.equals("all"));
    }

    private static List<List<String>> strongComponents(Map<String, List<String>> edges) {
        List<List<String>> components = new ArrayList<>();
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> low = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> onStack = new HashSet<>();
        int[] next = {0};
        for (String v : edges.keySet()) {
            if (!index.containsKey(v)) {
                connect(v, edges, index, low, stack, onStack, next, components);
            }
        }
        return components;
    }

    private static void connect(String v, Map<String, List<String>> edges, Map<String, Integer> index, Map<String, Integer> low,
                                Deque<String> stack, Set<String> onStack, int[] next, List<List<String>> out) {
        index.put(v, next[0]);
        low.put(v, next[0]);
        next[0]++;
        stack.push(v);
        onStack.add(v);
        for (String w : edges.getOrDefault(v, List.of())) {
            if (!index.containsKey(w)) {
                connect(w, edges, index, low, stack, onStack, next, out);
                low.put(v, Math.min(low.get(v), low.get(w)));
            } else if (onStack.contains(w)) {
                low.put(v, Math.min(low.get(v), index.get(w)));
            }
        }
        if (low.get(v).equals(index.get(v))) {
            List<String> component = new ArrayList<>();
            String w;
            do {
                w = stack.pop();
                onStack.remove(w);
                component.add(w);
            } while (!w.equals(v));
            out.add(component);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void required(Result out, ChapterRef ref, String path, JsonObject o, String field) {
        if (str(o, field).isEmpty()) {
            problem(out, Severity.ERROR, ref, path + "." + field, field + " task/reward has no " + field);
        }
    }

    /**
     * A number that is 0 or negative quietly becomes 1 in the model ({@code ProgressRules.target}), so it never does
     * what the author wrote. Omitting the field is the documented default of 1, so only a value that is there and
     * unusable is reported.
     */
    private static void positive(Result out, ChapterRef ref, String path, JsonObject o, String field) {
        if (!o.has(field) || !o.get(field).isJsonPrimitive()) {
            return;
        }
        double v = o.get(field).getAsDouble();
        if (v <= 0) {
            problem(out, Severity.WARNING, ref, path, quote(field) + " is " + trim(v) + "; treated as 1");
        }
    }

    private static void id(Result out, ChapterRef ref, String path, String kind, String spec, Registry registry) {
        if (spec.isEmpty() || registry == Registry.NONE) {
            return;
        }
        boolean tag = spec.startsWith("#");
        String value = tag ? spec.substring(1) : spec;
        if (!value.contains(":")) {
            problem(out, Severity.WARNING, ref, path, kind + " " + quote(spec) + " has no namespace");
            return;
        }
        if (!registry.known(kind, spec)) {
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

    private record ChapterRef(String file, Scan scan) {
        Map<String, Integer> lines() {
            return scan == null ? Map.of() : scan.lines();
        }
    }

    private static ChapterRef ref(String chapter, Map<String, Source> sources) {
        Source s = sources.get("chapters/" + chapter);
        return new ChapterRef(s == null ? "" : s.file(), s == null ? null : scan(s.rawText()));
    }

    /** Turn an absolute path like {@code chapters.basics.nodes.iron.x} back into {@code nodes.iron.x}. */
    private static String relative(ChapterRef ref, String path) {
        int marker = path.indexOf(".nodes.");
        return marker < 0 ? path : path.substring(marker + 1);
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
        private final String s;
        private int i;
        private int line = 1;

        Text(String s) {
            this.s = s;
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
                object(path, lines, duplicates);
            } else if (c == '[') {
                array(path, lines, duplicates);
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
            Set<String> seen = new HashSet<>();
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

    /** Walk up from {@code path} to the nearest recorded line, so an object reports the line it starts on. */
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

    private static String itemRewardId(JsonObject rw) {
        JsonElement it = rw.get("item");
        if (it == null) {
            return "";
        }
        if (it.isJsonPrimitive()) {
            return it.getAsString();
        }
        return spec(it);
    }

    /** An id-or-tag matcher as one string: an id, or {@code #tag} (same shape as ProgressService). */
    private static String spec(JsonElement e) {
        if (e == null) {
            return "";
        }
        if (e.isJsonPrimitive()) {
            return e.getAsString();
        }
        if (!e.isJsonObject()) {
            return "";
        }
        JsonObject o = e.getAsJsonObject();
        if (o.has("tag") && o.get("tag").isJsonPrimitive()) {
            return "#" + o.get("tag").getAsString();
        }
        if (o.has("id") && o.get("id").isJsonPrimitive()) {
            return o.get("id").getAsString();
        }
        if (o.has("fluid") && o.get("fluid").isJsonPrimitive()) {
            return o.get("fluid").getAsString();
        }
        return "";
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
