package com.kuronami.questtree.progress;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Conditions-gated restrictions on items and blocks (DATA_MODEL.md "制限"): until the rule's {@code conditions} hold,
 * the named items and blocks cannot be equipped, used, attacked with, placed or broken, and the reason is shown
 * briefly. The rules come from {@code data/<ns>/progression/restrictions.json}, merged across namespaces; the shape
 * and the matching live here so a test needs no game.
 * <p>
 * Matching is by id and by tag. The caller supplies the ids and a {@link TagMatcher} (the game side asks the item or
 * the block state), so this class reads the JSON once and stays free of registries.
 */
public final class RestrictionRules {

    public static final String EQUIP = "equip";
    public static final String USE = "use";
    public static final String ATTACK = "attack";
    public static final String PLACE = "place";
    public static final String BREAK = "break";
    /** The five actions a rule may name; an omitted list means all of them. */
    public static final List<String> ACTIONS = List.of(EQUIP, USE, ATTACK, PLACE, BREAK);

    /** Whether the item or block state the caller holds is in a tag. */
    public interface TagMatcher {
        boolean has(String tag);
    }

    /** One written rule. */
    public static final class Rule {
        private final String id;
        private final JsonObject json;
        private final List<String> items;
        private final List<String> itemTags;
        private final List<String> blocks;
        private final List<String> blockTags;
        /** Null when the rule names no actions: it then applies to all five. */
        private final Set<String> actions;
        private final String message;

        Rule(String id, JsonObject json, List<String> items, List<String> itemTags,
             List<String> blocks, List<String> blockTags, Set<String> actions, String message) {
            this.id = id;
            this.json = json;
            this.items = items;
            this.itemTags = itemTags;
            this.blocks = blocks;
            this.blockTags = blockTags;
            this.actions = actions;
            this.message = message;
        }

        public String id() {
            return id;
        }

        /** The rule's own JSON, which is also the holder the condition list is read from. */
        public JsonObject json() {
            return json;
        }

        /** The pack's reason, or an empty string when it wrote none. */
        public String message() {
            return message;
        }

        /** The actions the rule names, or every action when it names none. */
        public Set<String> actions() {
            return actions == null ? Set.copyOf(ACTIONS) : Collections.unmodifiableSet(actions);
        }

        public boolean covers(String action) {
            return actions == null || actions.contains(action);
        }

        boolean matchesItem(String action, String itemId, TagMatcher tags) {
            if (!covers(action) || (items.isEmpty() && itemTags.isEmpty())) {
                return false;
            }
            if (items.contains(itemId)) {
                return true;
            }
            for (String tag : itemTags) {
                if (tags.has(tag)) {
                    return true;
                }
            }
            return false;
        }

        boolean matchesBlock(String action, String blockId, TagMatcher tags) {
            if (!covers(action) || (blocks.isEmpty() && blockTags.isEmpty())) {
                return false;
            }
            if (blocks.contains(blockId)) {
                return true;
            }
            for (String tag : blockTags) {
                if (tags.has(tag)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** A rule that could not be read as written, for the validator's list ({@code path} is inside the file). */
    public record Problem(String path, String message) {
    }

    /** The parsed rules and what was wrong with the rest. */
    public record Result(List<Rule> rules, List<Problem> problems) {
    }

    private static final RestrictionRules EMPTY = new RestrictionRules(List.of());

    private final List<Rule> rules;
    /** Rules that name an item id, by that id; and every rule with an item target (tag-only rules), in file order. */
    private final Map<String, List<Rule>> byItemId = new LinkedHashMap<>();
    private final List<Rule> itemRules = new ArrayList<>();
    private final Map<String, List<Rule>> byBlockId = new LinkedHashMap<>();
    private final List<Rule> blockRules = new ArrayList<>();

    private RestrictionRules(List<Rule> rules) {
        this.rules = List.copyOf(rules);
        for (Rule r : rules) {
            boolean item = false;
            for (String id : r.items) {
                byItemId.computeIfAbsent(id, k -> new ArrayList<>()).add(r);
                item = true;
            }
            if (item || !r.itemTags.isEmpty()) {
                itemRules.add(r);
            }
            boolean block = false;
            for (String id : r.blocks) {
                byBlockId.computeIfAbsent(id, k -> new ArrayList<>()).add(r);
                block = true;
            }
            if (block || !r.blockTags.isEmpty()) {
                blockRules.add(r);
            }
        }
    }

    /** No rules: every item and block is allowed. */
    public static RestrictionRules empty() {
        return EMPTY;
    }

    /** Reads the {@code restrictions} array of a bundle; a missing or malformed list is empty. */
    public static RestrictionRules of(JsonObject bundle) {
        if (bundle == null || !bundle.has("restrictions") || !bundle.get("restrictions").isJsonArray()) {
            return EMPTY;
        }
        return new RestrictionRules(parse(bundle.getAsJsonArray("restrictions")).rules());
    }

    /** Parses the rules and reports what had to be skipped or read loosely. */
    public static Result parse(JsonArray array) {
        List<Rule> out = new ArrayList<>();
        List<Problem> problems = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (int i = 0; i < array.size(); i++) {
            String path = "rules[" + i + "]";
            JsonElement e = array.get(i);
            if (!e.isJsonObject()) {
                problems.add(new Problem(path, "a rule must be an object"));
                continue;
            }
            JsonObject o = e.getAsJsonObject();
            String id = primitive(o, "id");
            if (id.isEmpty()) {
                problems.add(new Problem(path + ".id", "a rule needs an id"));
                continue;
            }
            if (!ids.add(id)) {
                problems.add(new Problem(path + ".id", "duplicate rule id '" + id + "'"));
                continue;
            }
            List<String> items = ids(o, "items", path + ".items", problems);
            List<String> itemTags = ids(o, "item_tags", path + ".item_tags", problems);
            List<String> blocks = ids(o, "blocks", path + ".blocks", problems);
            List<String> blockTags = ids(o, "block_tags", path + ".block_tags", problems);
            if (items.isEmpty() && itemTags.isEmpty() && blocks.isEmpty() && blockTags.isEmpty()) {
                problems.add(new Problem(path, "no items or blocks; the rule applies to nothing"));
                continue;
            }
            Set<String> actions = null;
            JsonElement a = o.get("actions");
            if (a != null && !a.isJsonNull()) {
                if (!a.isJsonArray()) {
                    problems.add(new Problem(path + ".actions", "actions must be an array"));
                    actions = Set.of();
                } else {
                    actions = new LinkedHashSet<>();
                    for (int j = 0; j < a.getAsJsonArray().size(); j++) {
                        String name = primitive(a.getAsJsonArray().get(j));
                        if (ACTIONS.contains(name)) {
                            actions.add(name);
                        } else {
                            problems.add(new Problem(path + ".actions[" + j + "]",
                                    "unknown action '" + name + "'; known: " + String.join(", ", ACTIONS)));
                        }
                    }
                    if (actions.isEmpty()) {
                        problems.add(new Problem(path + ".actions", "no known action; the rule applies to nothing"));
                    }
                }
            }
            JsonElement conditions = o.get("conditions");
            if (conditions != null && !conditions.isJsonNull() && !conditions.isJsonArray()) {
                problems.add(new Problem(path + ".conditions", "conditions must be an array"));
            }
            if (conditions == null || !conditions.isJsonArray() || conditions.getAsJsonArray().isEmpty()) {
                // a rule with no condition would forbid its items forever: it is read, but the validator says so
                problems.add(new Problem(path + ".conditions", "no conditions; the rule would never open"));
            }
            out.add(new Rule(id, o, items, itemTags, blocks, blockTags, actions, primitive(o, "message")));
        }
        return new Result(List.copyOf(out), List.copyOf(problems));
    }

    public List<Rule> all() {
        return rules;
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    /** The rules that name this item id or one of its tags and cover the action, in file order. */
    public List<Rule> forItem(String action, String itemId, TagMatcher tags) {
        List<Rule> out = new ArrayList<>();
        List<Rule> byId = byItemId.get(itemId);
        if (byId != null) {
            for (Rule r : byId) {
                if (r.matchesItem(action, itemId, tags)) {
                    out.add(r);
                }
            }
        }
        for (Rule r : itemRules) {
            if (!out.contains(r) && r.matchesItem(action, itemId, tags)) {
                out.add(r);
            }
        }
        return out;
    }

    /** The rules that name this block id or one of its tags and cover the action, in file order. */
    public List<Rule> forBlock(String action, String blockId, TagMatcher tags) {
        List<Rule> out = new ArrayList<>();
        List<Rule> byId = byBlockId.get(blockId);
        if (byId != null) {
            for (Rule r : byId) {
                if (r.matchesBlock(action, blockId, tags)) {
                    out.add(r);
                }
            }
        }
        for (Rule r : blockRules) {
            if (!out.contains(r) && r.matchesBlock(action, blockId, tags)) {
                out.add(r);
            }
        }
        return out;
    }

    private static List<String> ids(JsonObject o, String key, String path, List<Problem> problems) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            return List.of();
        }
        if (!e.isJsonArray()) {
            problems.add(new Problem(path, key + " must be an array"));
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < e.getAsJsonArray().size(); i++) {
            String raw = primitive(e.getAsJsonArray().get(i));
            if (!validId(raw)) {
                problems.add(new Problem(path + "[" + i + "]", "not an id: '" + raw + "'"));
                continue;
            }
            out.add(raw);
        }
        return List.copyOf(out);
    }

    /** Enough of a resource location for a data check: {@code namespace:path} with Minecraft's allowed characters. */
    private static boolean validId(String s) {
        int colon = s.indexOf(':');
        if (colon <= 0 || colon == s.length() - 1 || s.indexOf(':', colon + 1) >= 0) {
            return false;
        }
        return validPart(s.substring(0, colon), false) && validPart(s.substring(colon + 1), true);
    }

    private static boolean validPart(String s, boolean path) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '-' || c == '.' || path && c == '/') {
                continue;
            }
            return false;
        }
        return true;
    }

    private static String primitive(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    private static String primitive(JsonElement e) {
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }
}
