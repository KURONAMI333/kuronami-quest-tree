package com.kuronami.questtree.importer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Converts an FTB Quests quest book ({@code config/ftbquests/quests}) into Kuronami's Quest Tree chapters (DATA_MODEL.md).
 * <p>
 * Key tables: a source key maps to a target path, {@code null} means dropped on purpose (ids, editor-only data);
 * anything not listed is counted in the report as unmapped. Tested on six public packs with the Python prototype
 * (_research/progression_ui/importer/convert.py): every quest and skill converted, only a few image settings unmapped.
 */
public final class FtbQuestsImporter {

    private static final Map<String, String> QUEST = Map.ofEntries(
            Map.entry("id", ""), Map.entry("x", "x"), Map.entry("y", "y"), Map.entry("shape", "shape"), Map.entry("size", "size"),
            Map.entry("icon", "icon"), Map.entry("icon_scale", "icon_scale"), Map.entry("optional", "optional"),
            Map.entry("title", "lang:title"), Map.entry("subtitle", "lang:subtitle"), Map.entry("description", "lang:desc"),
            Map.entry("dependency_requirement", "requires.mode"), Map.entry("min_required_dependencies", "requires.min"),
            Map.entry("max_completable_dependents", "exclusive.max"), Map.entry("hide_dependency_lines", "lines.hide_to_deps"),
            Map.entry("hide_dependent_lines", "lines.hide_to_dependents"), Map.entry("hide_until_deps_visible", "visibility.hidden_until"),
            Map.entry("hide_until_deps_complete", "visibility.hidden_until"), Map.entry("invisible", "visibility.hidden_until"),
            Map.entry("invisible_until_tasks", "visibility.invisible_until_tasks"),
            Map.entry("hide_details_until_startable", "visibility.hide_details_until_startable"),
            Map.entry("hide_text_until_complete", "visibility.hide_text_until_complete"), Map.entry("hide_lock_icon", "visibility.hide_lock_icon"),
            Map.entry("can_repeat", "repeat"), Map.entry("repeat_cooldown", "repeat.cooldown"), Map.entry("disable_toast", "toast"),
            Map.entry("require_sequential_tasks", "sequential_tasks"), Map.entry("min_width", "detail_width"),
            Map.entry("progression_mode", "progression_mode"), Map.entry("disable_recipe_mod", "hide_from_recipe_viewer"),
            Map.entry("ignore_reward_blocking", "ignore_reward_blocking"), Map.entry("guide_page", "guide_page"),
            Map.entry("tasks_ignore_dependencies", "tasks_ignore_requires"), Map.entry("hide", "visibility.hidden_until"),
            Map.entry("optional_task", ""));
    private static final Map<String, String> TASK_COMMON = Map.of("id", "", "type", "", "icon", "icon", "title", "lang:title",
            "optional_task", "optional", "disable_toast", "toast");
    private static final Map<String, Map<String, String>> TASK = Map.ofEntries(
            Map.entry("item", Map.of("item", "item", "count", "count", "consume_items", "consume", "match_components", "match_components",
                    "match_nbt", "match_components", "only_from_crafting", "only_from_crafting", "weak_nbt_match", "match_components")),
            Map.entry("checkmark", Map.of()), Map.entry("kill", Map.of("entity", "entity", "value", "count", "custom_name", "custom_name")),
            Map.entry("advancement", Map.of("advancement", "advancement", "criterion", "criterion")),
            Map.entry("structure", Map.of("structure", "structure", "tags", "")), Map.entry("biome", Map.of("biome", "biome")),
            Map.entry("dimension", Map.of("dimension", "dimension")),
            Map.entry("location", Map.of("dimension", "dimension", "position", "position", "size", "size", "ignore_dimension", "ignore_dimension")),
            Map.entry("observation", Map.of("observe_type", "observe_type", "observation_type", "observe_type", "to_observe", "target", "timer", "ticks")),
            Map.entry("stat", Map.of("stat", "stat", "value", "count")), Map.entry("xp", Map.of("value", "count", "points", "points")),
            Map.entry("fluid", Map.of("fluid", "fluid", "amount", "amount")), Map.entry("forge_energy", Map.of("value", "amount", "max_input", "max_input")),
            Map.entry("gamestage", Map.of("stage", "stage", "team_stage", "team")), Map.entry("stage", Map.of("stage", "stage")),
            Map.entry("interaction", Map.of()), Map.entry("custom", Map.of()),
            Map.entry("block", Map.of("block", "block", "count", "count")));
    private static final Map<String, String> TASK_RENAME = Map.of("forge_energy", "energy", "gamestage", "stage", "block", "break");
    private static final Map<String, String> REWARD_COMMON = Map.of("id", "", "type", "", "icon", "icon", "title", "lang:title",
            "team_reward", "team", "auto", "auto", "exclude_from_claim_all", "exclude_from_claim_all", "ignore_reward_blocking", "ignore_reward_blocking");
    private static final Map<String, Map<String, String>> REWARD = Map.ofEntries(
            Map.entry("item", Map.of("item", "item", "count", "count", "random_bonus", "random_bonus", "only_one", "only_one")),
            Map.entry("xp", Map.of("xp", "amount")), Map.entry("xp_levels", Map.of("xp_levels", "amount")),
            Map.entry("random", Map.of("table_id", "table")), Map.entry("loot", Map.of("table_id", "table")),
            Map.entry("choice", Map.of("table_id", "table")), Map.entry("all_table", Map.of("table_id", "table")),
            Map.entry("command", Map.of("command", "command", "permission_level", "permission_level", "silent", "silent",
                    "elevate_perms", "elevate", "feedback_message", "lang:feedback")),
            Map.entry("advancement", Map.of("advancement", "advancement", "criterion", "criterion")),
            Map.entry("gamestage", Map.of("stage", "stage", "remove", "remove")), Map.entry("toast", Map.of("description", "lang:desc")),
            Map.entry("custom", Map.of()));
    private static final Map<String, String> REWARD_RENAME = Map.of("random", "table", "loot", "table", "choice", "table",
            "all_table", "table", "gamestage", "stage");
    private static final Map<String, String> TABLE_MODE = Map.of("random", "random", "loot", "loot", "choice", "choice", "all_table", "all");
    private static final Map<String, String> DEP_MODE = Map.of("all_completed", "all", "one_completed", "any",
            "all_started", "all_started", "one_started", "any_started");
    private static final List<String> IMAGE_KEYS = List.of("image", "x", "y", "width", "height", "rotation", "order", "id", "click",
            "hover", "dev", "color", "alpha", "corner", "align_to_corner", "click_action", "dependency");

    private FtbQuestsImporter() {
    }

    /** Converts {@code questsDir} (the folder that holds chapters/, data.snbt, lang/ ...) into {@code out}. */
    public static void convert(Path questsDir, ImportOutput out) throws IOException {
        String ns = out.namespace;
        Map<String, String[]> idToNode = new HashMap<>();
        Map<String, JsonObject> raw = new java.util.LinkedHashMap<>();
        try (Stream<Path> files = Files.list(questsDir.resolve("chapters"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".snbt")).sorted().toList()) {
                JsonObject ch = read(f).getAsJsonObject();
                String cid = slug(ch.has("filename") ? ch.get("filename").getAsString() : name(f));
                raw.put(cid, ch);
                for (JsonElement q : arr(ch, "quests")) {
                    idToNode.put(q.getAsJsonObject().get("id").getAsString(), new String[]{cid, q.getAsJsonObject().get("id").getAsString().toLowerCase(Locale.ROOT)});
                }
            }
        }
        Path groupsFile = questsDir.resolve("chapter_groups.snbt");
        if (Files.exists(groupsFile)) {
            int order = 0;
            for (JsonElement g : arr(read(groupsFile).getAsJsonObject(), "chapter_groups")) {
                JsonObject go = g.getAsJsonObject();
                JsonObject o = new JsonObject();
                String gid = go.get("id").getAsString().toLowerCase(Locale.ROOT);
                o.addProperty("id", gid);
                o.addProperty("order", order++);
                if (go.has("icon")) {
                    o.add("icon", itemStack(go.get("icon")));
                }
                if (go.has("title")) {
                    out.text("en_us", "progression." + ns + ".group." + gid + ".title", go.get("title"));
                }
                out.groups.add(o);
            }
        }
        for (Map.Entry<String, JsonObject> e : raw.entrySet()) {
            try {
                out.chapters.put(e.getKey(), chapter(e.getKey(), e.getValue(), idToNode, out));
            } catch (RuntimeException ex) {
                // one broken chapter must not stop the rest of the book from coming over
                out.note("chapter skipped (" + e.getKey() + "): " + ex.getMessage());
            }
        }
        Path data = questsDir.resolve("data.snbt");
        if (Files.exists(data)) {
            out.settings = read(data).getAsJsonObject();
        }
        tables(questsDir.resolve("reward_tables"), out);
        lang(questsDir.resolve("lang"), raw, idToNode, out);
        out.count("locales", out.lang.size());
    }

    private static JsonObject chapter(String cid, JsonObject ch, Map<String, String[]> idToNode, ImportOutput out) {
        String ns = out.namespace;
        out.count("chapters", 1);
        JsonObject o = new JsonObject();
        for (Map.Entry<String, JsonElement> kv : ch.entrySet()) {
            String k = kv.getKey();
            JsonElement v = kv.getValue();
            switch (k) {
                case "id", "filename", "quests", "images", "quest_links" -> { }
                case "group" -> o.addProperty("group", v.getAsString().toLowerCase(Locale.ROOT));
                case "order_index" -> o.add("order", v);
                case "icon" -> o.add("icon", itemStack(v));
                case "autofocus_id" -> o.addProperty("autofocus", v.getAsString().toLowerCase(Locale.ROOT));
                case "title" -> out.text("en_us", "progression." + ns + "." + cid + ".title", v);
                case "subtitle" -> out.text("en_us", "progression." + ns + "." + cid + ".subtitle", v);
                case "default_quest_shape" -> put(o, "defaults.shape", v);
                case "default_quest_size" -> put(o, "defaults.size", v);
                case "default_hide_dependency_lines" -> put(o, "defaults.hide_lines", v);
                case "default_min_width" -> put(o, "defaults.detail_width", v);
                case "progression_mode" -> put(o, "defaults.progression_mode", v);
                case "always_invisible" -> put(o, "visibility.always_hidden", v);
                case "hide_quest_until_deps_complete" -> {
                    if (v.isJsonPrimitive() && v.getAsBoolean()) {
                        put(o, "defaults.hidden_until", new JsonPrimitive("deps_complete"));
                    }
                }
                case "hide_quest_until_deps_visible" -> {
                    // "deps_complete" is the stricter of the two and wins when both are set
                    if (v.isJsonPrimitive() && v.getAsBoolean() && !o.has("defaults")
                            || v.isJsonPrimitive() && v.getAsBoolean() && !o.getAsJsonObject("defaults").has("hidden_until")) {
                        put(o, "defaults.hidden_until", new JsonPrimitive("deps_visible"));
                    }
                }
                case "hide_quest_details_until_startable" -> put(o, "defaults.hide_details_until_startable", v);
                case "disable_toast" -> put(o, "defaults.toast", new JsonPrimitive(!v.getAsBoolean()));
                case "consume_items" -> put(o, "defaults.consume_items", v);
                case "default_repeatable_quest" -> put(o, "defaults.repeatable", v);
                default -> out.unknown("chapter", k);
            }
        }
        // an imported chapter keeps the pack's shapes: without a chapter default, nodes would take the shape of their role
        JsonObject defaults = o.has("defaults") ? o.getAsJsonObject("defaults") : null;
        if (defaults == null || !defaults.has("shape") || defaults.get("shape").getAsString().isEmpty()) {
            put(o, "defaults.shape", new JsonPrimitive("square"));
        }
        JsonArray decos = new JsonArray();
        for (JsonElement im : arr(ch, "images")) {
            JsonObject i = im.getAsJsonObject();
            JsonObject d = new JsonObject();
            copy(i, "image", d, "image");
            copy(i, "x", d, "x");
            copy(i, "y", d, "y");
            copy(i, "width", d, "w");
            copy(i, "height", d, "h");
            copy(i, "rotation", d, "rotation");
            copy(i, "order", d, "order");
            copy(i, "color", d, "color");
            copy(i, "alpha", d, "alpha");
            copy(i, "hover", d, "hover");
            if (i.has("click") && i.get("click").isJsonPrimitive()) {
                // FTB Quests: a quest id (optionally "#id") opens that quest; anything else (a URL) is kept as it is
                String click = i.get("click").getAsString().trim();
                String[] t = idToNode.get(click.startsWith("#") ? click.substring(1) : click);
                if (t == null) {
                    t = idToNode.get((click.startsWith("#") ? click.substring(1) : click).toUpperCase(Locale.ROOT));
                }
                if (t != null) {
                    JsonObject c = new JsonObject();
                    c.addProperty("type", "node");
                    c.addProperty("target", t[0].equals(cid) ? t[1] : t[0] + "/" + t[1]);
                    d.add("click", c);
                } else if (!click.isEmpty()) {
                    JsonObject c = new JsonObject();
                    c.addProperty("type", "url");
                    c.addProperty("target", click);
                    d.add("click", c);
                }
            }
            copy(i, "click_action", d, "click_action");
            if (i.has("dependency")) {
                String[] t = idToNode.get(i.get("dependency").getAsString());
                if (t != null) {
                    d.addProperty("visible_after", t[0].equals(cid) ? t[1] : t[0] + "/" + t[1]);
                }
            }
            if (i.has("dev") && i.get("dev").getAsBoolean()) {
                d.addProperty("dev", true);
            }
            for (String k : i.keySet()) {
                if (!IMAGE_KEYS.contains(k)) {
                    out.unknown("decoration", k);
                }
            }
            decos.add(d);
        }
        if (!decos.isEmpty()) {
            o.add("decorations", decos);
            out.count("decorations", decos.size());
        }
        JsonArray links = new JsonArray();
        for (JsonElement l : arr(ch, "quest_links")) {
            JsonObject lo = l.getAsJsonObject();
            String[] t = lo.has("linked_quest") ? idToNode.get(lo.get("linked_quest").getAsString()) : null;
            if (t == null) {
                out.note("link to missing quest");
                continue;
            }
            JsonObject link = new JsonObject();
            link.addProperty("node", t[0] + "/" + t[1]);
            copy(lo, "x", link, "x");
            copy(lo, "y", link, "y");
            copy(lo, "shape", link, "shape");
            copy(lo, "size", link, "size");
            links.add(link);
            out.count("links", 1);
        }
        if (!links.isEmpty()) {
            o.add("links", links);
        }
        JsonObject nodes = new JsonObject();
        for (JsonElement qe : arr(ch, "quests")) {
            JsonObject q = qe.getAsJsonObject();
            String nid = q.get("id").getAsString().toLowerCase(Locale.ROOT);
            nodes.add(nid, quest(cid, nid, q, idToNode, out));
        }
        o.add("nodes", nodes);
        return o;
    }

    private static JsonObject quest(String cid, String nid, JsonObject q, Map<String, String[]> idToNode, ImportOutput out) {
        String prefix = "progression." + out.namespace + "." + cid + "." + nid;
        out.count("nodes", 1);
        JsonObject n = new JsonObject();
        for (Map.Entry<String, JsonElement> kv : q.entrySet()) {
            String k = kv.getKey();
            if (k.equals("tasks") || k.equals("rewards") || k.equals("dependencies")) {
                continue;
            }
            String target = QUEST.get(k);
            if (target == null) {
                out.unknown("quest", k);
                continue;
            }
            mapValue(n, target, k, kv.getValue(), prefix, out);
        }
        // values whose meaning changes, not just the name
        if (n.has("visibility") && n.getAsJsonObject("visibility").has("hidden_until")) {
            n.getAsJsonObject("visibility").remove("hidden_until");
            JsonObject vis = n.getAsJsonObject("visibility");
            if (bool(q, "invisible")) {
                vis.addProperty("hidden_until", "complete");
            } else if (bool(q, "hide_until_deps_complete")) {
                vis.addProperty("hidden_until", "deps_complete");
            } else if (bool(q, "hide_until_deps_visible") || bool(q, "hide")) {
                vis.addProperty("hidden_until", "deps_visible");
            } else {
                // FTB Quests' quest flags are three-valued: an explicit false overrides the chapter default
                vis.addProperty("hidden_until", "none");
            }
            if (vis.isEmpty()) {
                n.remove("visibility");
            }
        }
        if (n.has("requires") && n.getAsJsonObject("requires").has("mode")) {
            JsonObject r = n.getAsJsonObject("requires");
            r.addProperty("mode", DEP_MODE.getOrDefault(r.get("mode").getAsString(), r.get("mode").getAsString()));
        }
        if (n.has("repeat")) {
            n.remove("repeat");
            if (bool(q, "can_repeat")) {
                JsonObject rep = new JsonObject();
                rep.addProperty("cooldown", q.has("repeat_cooldown") ? q.get("repeat_cooldown").getAsLong() : 0);
                n.add("repeat", rep);
            }
        }
        if (n.has("toast")) {
            n.addProperty("toast", !bool(q, "disable_toast"));
        }
        if (n.has("exclusive")) {
            JsonObject ex = new JsonObject();
            ex.addProperty("group", "after_" + nid);
            ex.add("max", q.get("max_completable_dependents"));
            n.add("exclusive", ex);
            out.note("exclusive branch (max_completable_dependents)");
        }
        JsonArray deps = new JsonArray();
        for (JsonElement d : arr(q, "dependencies")) {
            String[] t = idToNode.get(d.getAsString());
            if (t == null) {
                out.note("dependency on missing quest");
                continue;
            }
            deps.add(t[0].equals(cid) ? t[1] : t[0] + "/" + t[1]);
            if (!t[0].equals(cid)) {
                out.count("cross_chapter_dependencies", 1);
            }
        }
        if (!deps.isEmpty()) {
            JsonObject r = n.has("requires") ? n.getAsJsonObject("requires") : new JsonObject();
            r.add("nodes", deps);
            n.add("requires", r);
        }
        JsonArray tasks = new JsonArray();
        for (JsonElement te : arr(q, "tasks")) {
            JsonObject t = te.getAsJsonObject();
            String ty = typeOf(t);
            out.count("tasks", 1);
            Map<String, String> spec = TASK.get(ty);
            if (spec == null) {
                out.unknown("task_type", ty);
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("type", TASK_RENAME.getOrDefault(ty, ty));
            String tp = prefix + ".task." + id(t);
            out.textPrefix.put(id(t), tp);
            for (Map.Entry<String, JsonElement> kv : t.entrySet()) {
                String target = spec.containsKey(kv.getKey()) ? spec.get(kv.getKey()) : TASK_COMMON.get(kv.getKey());
                if (target == null) {
                    out.unknown("task[" + ty + "]", kv.getKey());
                    continue;
                }
                mapValue(o, target, kv.getKey(), kv.getValue(), tp, out);
            }
            if (o.has("toast")) {
                o.addProperty("toast", !bool(t, "disable_toast"));
            }
            // where the task's own title lives in the language files (from the quest file or FTB's lang files)
            o.addProperty("text", tp);
            tasks.add(o);
        }
        n.add("tasks", tasks);
        JsonArray rewards = new JsonArray();
        for (JsonElement re : arr(q, "rewards")) {
            JsonObject r = re.getAsJsonObject();
            String ty = typeOf(r);
            out.count("rewards", 1);
            out.textPrefix.put(id(r), prefix + ".reward." + id(r));
            JsonObject o = reward(ty, r, prefix + ".reward." + id(r), out, "reward");
            if (o != null) {
                rewards.add(o);
            }
        }
        n.add("rewards", rewards);
        return n;
    }

    private static JsonObject reward(String ty, JsonObject r, String langPrefix, ImportOutput out, String where) {
        Map<String, String> spec = REWARD.get(ty);
        if (spec == null) {
            out.unknown(where + "_type", ty);
            return null;
        }
        JsonObject o = new JsonObject();
        o.addProperty("type", REWARD_RENAME.getOrDefault(ty, ty));
        for (Map.Entry<String, JsonElement> kv : r.entrySet()) {
            if (kv.getKey().equals("weight")) {
                continue;
            }
            String target = spec.containsKey(kv.getKey()) ? spec.get(kv.getKey()) : REWARD_COMMON.get(kv.getKey());
            if (target == null) {
                out.unknown(where + "[" + ty + "]", kv.getKey());
                continue;
            }
            mapValue(o, target, kv.getKey(), kv.getValue(), langPrefix, out);
        }
        if (TABLE_MODE.containsKey(ty)) {
            o.addProperty("mode", TABLE_MODE.get(ty));
            if (o.has("table")) {
                o.addProperty("table", out.namespace + ":" + slug(o.get("table").getAsString()));
            }
        }
        if (ty.equals("item") && o.has("count") && o.has("item")) {
            o.getAsJsonObject("item").add("count", o.remove("count"));
        }
        return o;
    }

    private static void tables(Path dir, ImportOutput out) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".snbt")).sorted().toList()) {
                JsonObject t = read(f).getAsJsonObject();
                String tid = slug(t.has("id") ? t.get("id").getAsString() : name(f));
                JsonArray entries = new JsonArray();
                for (JsonElement re : arr(t, "rewards")) {
                    JsonObject r = re.getAsJsonObject();
                    String ty = r.has("type") ? typeOf(r) : "item";
                    JsonObject rw = reward(ty, r, "progression." + out.namespace + ".table." + tid, out, "table_reward");
                    if (rw == null) {
                        continue;
                    }
                    JsonObject entry = new JsonObject();
                    entry.addProperty("weight", r.has("weight") ? r.get("weight").getAsDouble() : 1.0);
                    entry.add("reward", rw);
                    entries.add(entry);
                }
                JsonObject tab = new JsonObject();
                tab.addProperty("rolls", t.has("loot_size") ? t.get("loot_size").getAsInt() : 1);
                tab.addProperty("empty_weight", t.has("empty_weight") ? t.get("empty_weight").getAsDouble() : 0);
                tab.add("entries", entries);
                if (t.has("loot_crate")) {
                    tab.add("loot_crate", t.get("loot_crate"));
                    out.count("loot_crates", 1);
                }
                if (t.has("title")) {
                    out.text("en_us", "progression." + out.namespace + ".table." + tid + ".title", t.get("title"));
                }
                out.tables.put(tid, tab);
                out.count("reward_tables", 1);
            }
        }
    }

    /** FTB Quests 2101+ keeps text in lang/<locale>.snbt or lang/<locale>/chapters/*.snbt; ids there are upper-case hex. */
    private static void lang(Path dir, Map<String, JsonObject> raw, Map<String, String[]> idToNode, ImportOutput out) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        String ns = out.namespace;
        Map<String, String> chapterById = new HashMap<>();
        raw.forEach((cid, ch) -> chapterById.put(ch.get("id").getAsString(), cid));
        Map<String, String> field = Map.of("quest_desc", "desc", "quest_subtitle", "subtitle", "title", "title", "subtitle", "subtitle");
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".snbt")).toList()) {
                String locale = dir.relativize(f).getName(0).toString().replace(".snbt", "");
                for (Map.Entry<String, JsonElement> kv : read(f).getAsJsonObject().entrySet()) {
                    String[] parts = kv.getKey().split("\\.");
                    if (parts.length < 3) {
                        continue;
                    }
                    String kind = parts[0];
                    String oid = parts[1].toUpperCase(Locale.ROOT);
                    String fld = field.getOrDefault(parts[parts.length - 1], parts[parts.length - 1]);
                    switch (kind) {
                        case "quest" -> {
                            String[] t = idToNode.get(oid);
                            if (t != null) {
                                out.text(locale, "progression." + ns + "." + t[0] + "." + t[1] + "." + fld, kv.getValue());
                            } else {
                                out.note("translation for a deleted quest");
                            }
                        }
                        case "chapter" -> out.text(locale, "progression." + ns + "." + chapterById.getOrDefault(oid, oid.toLowerCase(Locale.ROOT)) + "." + fld, kv.getValue());
                        case "task", "reward" -> {
                            // text of a task or reward belongs next to its quest's text, where the quest file puts it too
                            String pre = out.textPrefix.get(oid.toLowerCase(Locale.ROOT));
                            out.text(locale, (pre != null ? pre : "progression." + ns + "." + kind + "." + oid.toLowerCase(Locale.ROOT)) + "." + fld, kv.getValue());
                        }
                        case "chapter_group" ->
                                // the group list (convert(), chapter_groups.snbt) writes the title under
                                // progression.<ns>.group.<gid>.title, so a lang entry must land on that same key
                                out.text(locale, "progression." + ns + ".group." + oid.toLowerCase(Locale.ROOT) + "." + fld, kv.getValue());
                        case "file", "reward_table", "image" ->
                                out.text(locale, "progression." + ns + "." + kind + "." + oid.toLowerCase(Locale.ROOT) + "." + fld, kv.getValue());
                        default -> out.note("lang key kind " + kind);
                    }
                }
                out.count("lang_files", 1);
            }
        }
    }

    // ------------------------------------------------------------ helpers

    private static void mapValue(JsonObject into, String target, String key, JsonElement v, String langPrefix, ImportOutput out) {
        if (target.isEmpty()) {
            return;
        }
        if (target.startsWith("lang:")) {
            out.text("en_us", langPrefix + "." + target.substring(5), v);
            return;
        }
        if (target.equals("item") || target.equals("icon")) {
            v = itemStack(v);
        }
        if (target.equals("optional") && v.isJsonPrimitive()) {
            v = new JsonPrimitive(v.getAsJsonPrimitive().isBoolean() ? v.getAsBoolean() : v.getAsDouble() != 0);
        }
        put(into, target, v);
    }

    /** FTB Quests writes items either as "minecraft:stone" or {id, count, components}. */
    static JsonObject itemStack(JsonElement v) {
        JsonObject o = new JsonObject();
        if (v.isJsonPrimitive()) {
            o.addProperty("id", v.getAsString());
        } else if (v.isJsonObject()) {
            JsonObject s = v.getAsJsonObject();
            o.addProperty("id", s.has("id") ? s.get("id").getAsString() : "minecraft:air");
            if (s.has("count") && s.get("count").getAsInt() != 1) {
                o.add("count", s.get("count"));
            }
            if (s.has("components")) {
                o.add("components", s.get("components"));
            } else if (s.has("tag")) {
                o.add("components", s.get("tag"));
            }
        } else {
            o.addProperty("id", "minecraft:air");
        }
        return o;
    }

    static void put(JsonObject obj, String path, JsonElement value) {
        String[] parts = path.split("\\.");
        for (int i = 0; i < parts.length - 1; i++) {
            if (!obj.has(parts[i]) || !obj.get(parts[i]).isJsonObject()) {
                obj.add(parts[i], new JsonObject());
            }
            obj = obj.getAsJsonObject(parts[i]);
        }
        obj.add(parts[parts.length - 1], value);
    }

    private static void copy(JsonObject from, String k, JsonObject to, String as) {
        if (from.has(k)) {
            to.add(as, from.get(k));
        }
    }

    private static boolean bool(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsBoolean();
    }

    static Iterable<JsonElement> arr(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray();
    }

    private static String typeOf(JsonObject o) {
        String t = o.has("type") ? o.get("type").getAsString() : "?";
        return t.substring(t.indexOf(':') + 1);
    }

    private static String id(JsonObject o) {
        return o.has("id") ? o.get("id").getAsString().toLowerCase(Locale.ROOT) : "x";
    }

    static String slug(String s) {
        String r = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_").replaceAll("^_+|_+$", "");
        return r.isEmpty() ? "x" : r;
    }

    private static String name(Path f) {
        String n = f.getFileName().toString();
        return n.substring(0, n.lastIndexOf('.'));
    }

    private static JsonElement read(Path f) throws IOException {
        return Snbt.parse(Files.readString(f, StandardCharsets.UTF_8));
    }
}
