package com.kuronami.questtree.client.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kuronami.questtree.progress.RestrictionRules;

import java.util.ArrayList;
import java.util.List;

/**
 * Which fields each task, reward, node, chapter and reward table has (2026-10-21, phase 3). It is plain data with no
 * Minecraft types, so the round-trip tests cover exactly the fields the forms render. Every name below was checked
 * against the code that reads it ({@code ProgressRules}, {@code ProgressService}, {@code Rewards}, {@code ClientModel});
 * a field nothing reads is left out, because a row that does nothing is worse than no row.
 */
public final class ContentSchema {

    private static final String P = "screen.kuronami_quest_tree.editor.field.";

    public static final List<String> TASK_TYPES = List.of(
            "item", "checkmark", "kill", "advancement", "structure", "biome", "dimension", "location",
            "observation", "stat", "xp", "fluid", "energy", "break", "place_block", "stage", "custom", "reach_day");

    /** {@code choice} is a {@code table} in mode {@code choice}; it is listed apart because the form treats it so. */
    public static final List<String> REWARD_TYPES = List.of(
            "item", "xp", "xp_levels", "table", "choice", "command", "advancement", "stage", "toast",
            "attribute", "points", "tag", "scoreboard", "custom", "trophy");

    public static final List<String> SHAPES = List.of("", "square", "rsquare", "circle", "diamond", "hexagon",
            "octagon", "pentagon", "gear", "heart", "none");

    /** The actions a restriction rule may name, in the order the editor offers them. */
    public static final List<String> RESTRICTION_ACTIONS = RestrictionRules.ACTIONS;

    private ContentSchema() {
    }

    public static List<FieldSpec> questFields() {
        return List.of(
                FieldSpec.text("title", P + "title"),
                FieldSpec.text("subtitle", P + "subtitle"),
                FieldSpec.multiline("description", P + "description"),
                new FieldSpec("shape", FieldSpec.Kind.ENUM, P + "shape", SHAPES, false),
                FieldSpec.decimal("size", P + "size"),
                FieldSpec.picker("icon", P + "icon", FieldSpec.Kind.ITEM),
                FieldSpec.decimal("icon_scale", P + "icon_scale"),
                FieldSpec.integer("detail_width", P + "detail_width"),
                FieldSpec.bool("visibility.hide_lock_icon", P + "hide_lock_icon"),
                new FieldSpec("hide_from_recipe_viewer", FieldSpec.Kind.ENUM, P + "hide_from_recipe_viewer",
                        List.of("", "true", "false"), false),
                FieldSpec.text("guide_page", P + "guide_page"),
                FieldSpec.text("preset", P + "preset"),
                FieldSpec.bool("milestone", P + "milestone"),
                FieldSpec.bool("optional", P + "optional"),
                FieldSpec.bool("root", P + "root"),
                FieldSpec.integer("cost", P + "cost"),
                FieldSpec.choice("visibility.hidden_until", P + "hidden_until", "", "complete", "deps_complete",
                        "deps_visible"),
                FieldSpec.bool("visibility.hide_details_until_startable", P + "hide_details"),
                FieldSpec.bool("visibility.hide_text_until_complete", P + "hide_text"),
                FieldSpec.integer("visibility.invisible_until_tasks", P + "invisible_until_tasks"),
                FieldSpec.bool("lines.hide_to_deps", P + "hide_lines_to_deps"),
                FieldSpec.bool("lines.hide_to_dependents", P + "hide_lines_to_dependents"),
                FieldSpec.flag("repeat", P + "repeatable"),
                FieldSpec.integer("repeat.cooldown", P + "cooldown"),
                FieldSpec.integer("repeat.max", P + "repeat_max"),
                FieldSpec.choice("repeat.reset", P + "reset", "", "daily", "weekly"),
                FieldSpec.bool("sequential_tasks", P + "sequential_tasks"),
                FieldSpec.integer("tasks_required", P + "tasks_required"),
                FieldSpec.choice("requires.mode", P + "requires_mode", "", "all", "any", "all_started",
                        "any_started"),
                FieldSpec.integer("requires.min", P + "requires_min"),
                FieldSpec.text("exclusive.group", P + "exclusive_group"),
                FieldSpec.integer("exclusive.max", P + "exclusive_max"));
    }

    public static List<FieldSpec> taskFields(String type) {
        List<FieldSpec> out = new ArrayList<>(taskOwnFields(type));
        out.addAll(taskCommonFields());
        return out;
    }

    /** The fields that belong to one task type only; changing the type removes exactly these. */
    public static List<FieldSpec> taskOwnFields(String type) {
        List<FieldSpec> out = new ArrayList<>();
        switch (type == null ? "" : type) {
            case "item" -> {
                out.add(FieldSpec.picker("item", P + "item", FieldSpec.Kind.ITEM));
                out.add(FieldSpec.integer("count", P + "count"));
                out.add(FieldSpec.bool("consume", P + "consume"));
                out.add(FieldSpec.bool("task_screen_only", P + "task_screen_only"));
                out.add(FieldSpec.choice("match_components", P + "match_components", "", "none", "fuzzy", "strict"));
            }
            case "kill" -> {
                out.add(FieldSpec.picker("entity", P + "entity", FieldSpec.Kind.ENTITY));
                out.add(FieldSpec.integer("count", P + "count"));
            }
            case "advancement" -> {
                out.add(FieldSpec.picker("advancement", P + "advancement", FieldSpec.Kind.ADVANCEMENT));
                out.add(FieldSpec.text("criterion", P + "criterion"));
            }
            case "structure" -> out.add(FieldSpec.picker("structure", P + "structure", FieldSpec.Kind.STRUCTURE));
            case "biome" -> out.add(FieldSpec.picker("biome", P + "biome", FieldSpec.Kind.BIOME));
            case "dimension" -> out.add(FieldSpec.picker("dimension", P + "dimension", FieldSpec.Kind.DIMENSION));
            case "location" -> {
                out.add(FieldSpec.integer("position[0]", P + "x"));
                out.add(FieldSpec.integer("position[1]", P + "y"));
                out.add(FieldSpec.integer("position[2]", P + "z"));
                out.add(FieldSpec.integer("size[0]", P + "size_x"));
                out.add(FieldSpec.integer("size[1]", P + "size_y"));
                out.add(FieldSpec.integer("size[2]", P + "size_z"));
                out.add(FieldSpec.picker("dimension", P + "dimension", FieldSpec.Kind.DIMENSION));
                out.add(FieldSpec.bool("ignore_dimension", P + "ignore_dimension"));
            }
            case "observation" -> {
                out.add(FieldSpec.choice("observe_type", P + "observe_type", "", "block", "block_tag", "block_state",
                        "entity_type", "entity_type_tag"));
                out.add(FieldSpec.text("target", P + "target"));
                out.add(FieldSpec.integer("ticks", P + "ticks"));
            }
            case "stat" -> {
                out.add(FieldSpec.text("stat", P + "stat"));
                out.add(FieldSpec.integer("amount", P + "amount"));
            }
            case "xp" -> {
                out.add(FieldSpec.bool("points", P + "xp_points"));
                out.add(FieldSpec.integer("amount", P + "amount"));
            }
            case "fluid" -> {
                out.add(FieldSpec.text("fluid", P + "fluid"));
                out.add(FieldSpec.integer("amount", P + "amount"));
            }
            case "energy" -> out.add(FieldSpec.integer("amount", P + "amount"));
            case "break" -> {
                out.add(FieldSpec.text("block", P + "block"));
                out.add(FieldSpec.integer("count", P + "count"));
            }
            case "place_block" -> {
                out.add(FieldSpec.text("block", P + "block"));
                out.add(FieldSpec.integer("count", P + "count"));
            }
            case "stage" -> out.add(FieldSpec.text("stage", P + "stage"));
            case "reach_day" -> out.add(FieldSpec.integer("day", P + "day"));
            case "custom" -> {
                out.add(FieldSpec.bool("enable_button", P + "enable_button"));
                out.add(FieldSpec.integer("amount", P + "amount"));
            }
            default -> {
                // checkmark and an unknown type have the common fields only; the type itself stays as it was
            }
        }
        return out;
    }

    public static List<FieldSpec> taskCommonFields() {
        return List.of(
                FieldSpec.bool("optional", P + "optional"),
                FieldSpec.picker("icon", P + "icon", FieldSpec.Kind.ITEM),
                FieldSpec.text("title", P + "task_title"));
    }

    public static String rewardType(JsonObject reward) {
        String type = EditorDoc.text(reward, "type");
        if (type.equals("table") && EditorDoc.text(reward, "mode").equals("choice")) {
            return "choice";
        }
        return type.isEmpty() ? "item" : type;
    }

    /** {@code choice} is a table in mode {@code choice}; the other types are the JSON's own type. */
    public static void setRewardType(JsonObject reward, String type) {
        if ("choice".equals(type)) {
            reward.addProperty("type", "table");
            reward.addProperty("mode", "choice");
            return;
        }
        reward.addProperty("type", type);
        if (!"table".equals(type)) {
            reward.remove("mode");
        } else if (!reward.has("mode") || EditorDoc.text(reward, "mode").equals("choice")) {
            reward.addProperty("mode", "random");
        }
    }

    public static List<FieldSpec> rewardFields(String type) {
        List<FieldSpec> out = new ArrayList<>(rewardOwnFields(type));
        out.addAll(rewardCommonFields());
        return out;
    }

    public static List<FieldSpec> rewardOwnFields(String type) {
        List<FieldSpec> out = new ArrayList<>();
        switch (type == null ? "" : type) {
            case "item" -> {
                out.add(FieldSpec.picker("item", P + "item", FieldSpec.Kind.ITEM));
                out.add(FieldSpec.integer("item.count", P + "count"));
                out.add(FieldSpec.integer("random_bonus", P + "random_bonus"));
            }
            case "xp", "xp_levels" -> out.add(FieldSpec.integer("amount", P + "amount"));
            case "table" -> {
                out.add(FieldSpec.text("table", P + "table"));
                out.add(FieldSpec.choice("mode", P + "table_mode", "random", "all", "loot"));
            }
            case "choice" -> out.add(FieldSpec.text("table", P + "table"));
            case "command" -> {
                out.add(FieldSpec.text("command", P + "command"));
                out.add(FieldSpec.integer("permission_level", P + "permission_level"));
                out.add(FieldSpec.bool("silent", P + "silent", true));
            }
            case "advancement" -> {
                out.add(FieldSpec.picker("advancement", P + "advancement", FieldSpec.Kind.ADVANCEMENT));
                out.add(FieldSpec.text("criterion", P + "criterion"));
            }
            case "stage" -> {
                out.add(FieldSpec.text("stage", P + "stage"));
                out.add(FieldSpec.bool("remove", P + "remove_stage"));
            }
            case "attribute" -> {
                out.add(FieldSpec.text("attribute", P + "attribute"));
                out.add(FieldSpec.decimal("value", P + "value"));
                out.add(FieldSpec.choice("operation", P + "operation", "", "add_value", "multiply_base",
                        "multiply_total"));
            }
            case "points" -> {
                out.add(FieldSpec.integer("amount", P + "amount"));
                out.add(FieldSpec.text("chapter", P + "points_chapter"));
            }
            case "tag" -> out.add(FieldSpec.text("tag", P + "tag"));
            case "scoreboard" -> {
                out.add(FieldSpec.text("objective", P + "objective"));
                out.add(FieldSpec.integer("value", P + "value"));
            }
            case "trophy" -> out.add(FieldSpec.text("trophy", P + "trophy"));
            default -> {
                // toast and custom have no fields of their own
            }
        }
        return out;
    }

    public static List<FieldSpec> rewardCommonFields() {
        return List.of(
                FieldSpec.bool("team", P + "team"),
                FieldSpec.bool("auto", P + "auto"),
                FieldSpec.bool("exclude_from_claim_all", P + "exclude_from_claim_all"),
                FieldSpec.picker("icon", P + "icon", FieldSpec.Kind.ITEM),
                FieldSpec.text("title", P + "reward_title"));
    }

    /** A new task of this type, with nothing filled in: the validator names what is missing next to its field. */
    public static JsonObject newTask(String type) {
        JsonObject task = new JsonObject();
        task.addProperty("type", type);
        return task;
    }

    public static JsonObject newReward(String type) {
        JsonObject reward = new JsonObject();
        setRewardType(reward, type);
        return reward;
    }

    /** Changing a task's type drops the fields only the old type had; common fields and unknown ones stay. */
    public static void retypeTask(JsonObject task, String type) {
        String old = EditorDoc.text(task, "type");
        List<String> keep = new ArrayList<>();
        for (FieldSpec f : taskOwnFields(type)) {
            keep.add(topKey(f.path()));
        }
        for (FieldSpec f : taskOwnFields(old)) {
            if (!keep.contains(topKey(f.path()))) {
                task.remove(topKey(f.path()));
            }
        }
        task.addProperty("type", type);
    }

    public static void retypeReward(JsonObject reward, String type) {
        String old = rewardType(reward);
        List<String> keep = new ArrayList<>();
        for (FieldSpec f : rewardOwnFields(type)) {
            keep.add(topKey(f.path()));
        }
        for (FieldSpec f : rewardOwnFields(old)) {
            if (!keep.contains(topKey(f.path()))) {
                reward.remove(topKey(f.path()));
            }
        }
        setRewardType(reward, type);
    }

    private static String topKey(String path) {
        int cut = path.length();
        for (char c : new char[]{'.', '['}) {
            int at = path.indexOf(c);
            if (at >= 0) {
                cut = Math.min(cut, at);
            }
        }
        return path.substring(0, cut);
    }

    public static List<FieldSpec> chapterFields() {
        return List.of(
                FieldSpec.text("title", P + "title"),
                FieldSpec.picker("icon", P + "icon", FieldSpec.Kind.ITEM),
                FieldSpec.text("group", P + "group"),
                FieldSpec.integer("order", P + "order"),
                new FieldSpec("defaults.shape", FieldSpec.Kind.ENUM, P + "default_shape", SHAPES, false),
                FieldSpec.decimal("defaults.size", P + "default_size"),
                FieldSpec.bool("defaults.hide_lines", P + "hide_lines"),
                FieldSpec.bool("defaults.hide_details_until_startable", P + "hide_details"),
                FieldSpec.bool("defaults.sequential_tasks", P + "sequential_tasks"),
                FieldSpec.bool("defaults.consume_items", P + "consume_items"),
                FieldSpec.bool("defaults.repeatable", P + "default_repeatable"),
                FieldSpec.integer("defaults.detail_width", P + "detail_width"),
                FieldSpec.text("autofocus", P + "autofocus"),
                FieldSpec.text("preset", P + "preset"),
                FieldSpec.bool("visibility.always_hidden", P + "always_hidden"),
                FieldSpec.text("background", P + "background"));
    }

    /** The skill-chapter settings: present only while {@code points} is an object. */
    public static List<FieldSpec> pointsFields() {
        return List.of(
                FieldSpec.flag("points", P + "skill_chapter"),
                FieldSpec.text("points.per_level", P + "per_level"),
                FieldSpec.integer("points.level_limit", P + "level_limit"),
                FieldSpec.integer("points.start", P + "start_points"),
                FieldSpec.integer("points.spent_limit", P + "spent_limit"),
                FieldSpec.bool("points.unlocked_by_default", P + "unlocked_by_default"),
                FieldSpec.bool("points.stop_when_maxed", P + "stop_when_maxed"),
                FieldSpec.bool("points.reset_on_death", P + "reset_on_death"),
                FieldSpec.integer("points.exchange.xp_levels", P + "xp_levels_exchange"),
                FieldSpec.text("points.pool", P + "points_pool"));
    }

    public static List<FieldSpec> decorationFields() {
        return List.of(
                FieldSpec.text("image", P + "decoration_image"),
                FieldSpec.decimal("x", P + "x"),
                FieldSpec.decimal("y", P + "y"),
                FieldSpec.decimal("w", P + "w"),
                FieldSpec.decimal("h", P + "h"),
                FieldSpec.decimal("rotation", P + "rotation"),
                FieldSpec.integer("order", P + "decoration_order"),
                FieldSpec.text("visible_after", P + "visible_after"));
    }

    public static JsonObject newDecoration() {
        JsonObject d = new JsonObject();
        d.addProperty("x", 0);
        d.addProperty("y", 0);
        d.addProperty("w", 2);
        d.addProperty("h", 2);
        return d;
    }

    public static List<FieldSpec> tableFields() {
        return List.of(
                FieldSpec.text("title", P + "table_title"),
                FieldSpec.bool("hide_tooltip", P + "hide_tooltip"),
                FieldSpec.integer("rolls", P + "rolls"),
                FieldSpec.decimal("empty_weight", P + "empty_weight"),
                FieldSpec.flag("loot_crate", P + "loot_crate"),
                FieldSpec.text("loot_crate.string_id", P + "loot_crate.string_id"),
                FieldSpec.text("loot_crate.item_name", P + "loot_crate.item_name"),
                FieldSpec.text("loot_crate.color", P + "loot_crate.color"),
                FieldSpec.bool("loot_crate.glow", P + "loot_crate.glow"),
                FieldSpec.integer("loot_crate.drops.passive", P + "loot_crate.drops.passive"),
                FieldSpec.integer("loot_crate.drops.monster", P + "loot_crate.drops.monster"),
                FieldSpec.integer("loot_crate.drops.boss", P + "loot_crate.drops.boss"));
    }

    /** One entry of a table: its weight, then the reward's own type and fields under {@code reward}. */
    public static List<FieldSpec> entryFields(String rewardType) {
        List<FieldSpec> out = new ArrayList<>();
        out.add(FieldSpec.decimal("weight", P + "weight"));
        for (FieldSpec f : rewardOwnFields(rewardType)) {
            out.add(f.under("reward."));
        }
        return out;
    }

    public static JsonObject newEntry() {
        JsonObject entry = new JsonObject();
        entry.addProperty("weight", 1);
        entry.add("reward", newReward("item"));
        return entry;
    }

    public static JsonObject newTable() {
        JsonObject table = new JsonObject();
        table.addProperty("rolls", 1);
        table.add("entries", new com.google.gson.JsonArray());
        return table;
    }

    /** Table ids in the bundle, sorted, for the table picker and the table list. */
    public static List<String> tableIds(JsonObject tables) {
        List<String> out = new ArrayList<>();
        for (String id : tables.keySet()) {
            JsonElement e = tables.get(id);
            if (e != null && e.isJsonObject()) {
                out.add(id);
            }
        }
        out.sort(String::compareTo);
        return out;
    }

    public static FieldSpec field(String path, List<FieldSpec> fields) {
        for (FieldSpec field : fields) {
            if (field.path().equals(path)) {
                return field;
            }
        }
        return null;
    }
}
