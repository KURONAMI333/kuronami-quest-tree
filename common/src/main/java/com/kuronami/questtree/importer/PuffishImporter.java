package com.kuronami.questtree.importer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts Pufferfish's Skills categories into Kuronami's Quest Tree skill chapters (a chapter with {@code points}).
 * <p>
 * Input is one map per category: file name (category.json, definitions.json, skills.json, connections.json,
 * experience.json) to parsed JSON. Categories without category.json are patches of a tree another mod ships
 * (Mystic Horizons overrides SimplySkills this way) and become {@code patches} targeting that tree.
 * Experience curves and source conditions are kept in Pufferfish's own expression format on purpose: FTB packs
 * write conditional sources (silk touch, ores in stone) that would not survive a translation.
 */
public final class PuffishImporter {

    private static final Set<String> KNOWN_REWARDS = Set.of("attribute", "command", "points", "scoreboard", "tag", "dummy");
    private static final List<String> REQUIRED = List.of("required_skills", "required_points", "required_spent_points", "required_exclusions");

    private PuffishImporter() {
    }

    /** @param source {@code namespace/category} used for ids, e.g. {@code ftb/mining} */
    public static void convert(String source, Map<String, JsonElement> files, ImportOutput out) {
        String ns = out.namespace;
        String category = source.substring(source.indexOf('/') + 1);
        JsonObject cat = obj(files.get("category.json"));
        JsonObject defs = obj(files.get("definitions.json"));
        JsonObject skills = obj(files.get("skills.json"));
        JsonObject cons = obj(files.get("connections.json"));
        JsonObject exp = obj(files.get("experience.json"));
        boolean patch = !files.containsKey("category.json");
        // keep the source namespace: two mods may both ship a "combat" tree
        String cid = "skill_" + FtbQuestsImporter.slug(source.replace('/', '_'));
        out.count("skill_categories", 1);

        JsonObject o = new JsonObject();
        JsonObject points = new JsonObject();
        for (Map.Entry<String, JsonElement> kv : cat.entrySet()) {
            JsonElement v = kv.getValue();
            switch (kv.getKey()) {
                case "title" -> out.text("en_us", "progression." + ns + "." + cid + ".title", text(v));
                case "icon" -> o.add("icon", icon(v));
                case "background" -> {
                    JsonObject bg = new JsonObject();
                    if (v.isJsonObject()) {
                        bg.add("texture", v.getAsJsonObject().get("texture"));
                        bg.addProperty("fit", v.getAsJsonObject().has("position") ? v.getAsJsonObject().get("position").getAsString() : "tile");
                    } else {
                        bg.add("texture", v);
                    }
                    o.add("background", bg);
                }
                case "unlocked_by_default" -> points.add("unlocked_by_default", v);
                case "exclusive_root" -> points.add("exclusive_root", v);
                case "spent_points_limit" -> points.add("spent_limit", v);
                case "erase_on_death" -> points.add("erase_on_death", v);
                case "starting_points" -> points.add("start", v);
                case "colors" -> o.add("line_colors", v);
                default -> out.unknown("category", kv.getKey());
            }
        }
        for (Map.Entry<String, JsonElement> kv : exp.entrySet()) {
            switch (kv.getKey()) {
                case "experience_per_level" -> points.add("per_level", kv.getValue());
                case "sources" -> {
                    points.add("sources", kv.getValue());
                    out.count("exp_sources", kv.getValue().isJsonArray() ? kv.getValue().getAsJsonArray().size() : 0);
                }
                case "level_limit" -> points.add("level_limit", kv.getValue());
                case "enabled" -> points.add("experience_enabled", kv.getValue());
                default -> out.unknown("experience", kv.getKey());
            }
        }
        o.add("points", points);

        JsonObject nodes = new JsonObject();
        for (Map.Entry<String, JsonElement> sk : skills.entrySet()) {
            JsonObject s = sk.getValue().getAsJsonObject();
            String nid = FtbQuestsImporter.slug(sk.getKey());
            String prefix = "progression." + ns + "." + cid + "." + nid;
            out.count("skills", 1);
            JsonObject n = new JsonObject();
            // Pufferfish places skills in pixels; Kuronami's Quest Tree uses node-size units like FTB Quests (one node ~ 32 px there)
            n.addProperty("x", s.has("x") ? s.get("x").getAsDouble() / 32.0 : 0);
            n.addProperty("y", s.has("y") ? s.get("y").getAsDouble() / 32.0 : 0);
            if (s.has("root") && s.get("root").getAsBoolean()) {
                n.addProperty("root", true);
            }
            // Keep the definition id: skills that share one definition share its rewards, and the scoreboard reward
            // scores the number of unlocked skills of that definition (Pufferfish's Skills, 2026-10-20).
            if (s.has("definition") && s.get("definition").isJsonPrimitive()) {
                n.addProperty("definition", s.get("definition").getAsString());
            }
            for (String k : s.keySet()) {
                if (!Set.of("x", "y", "definition", "root").contains(k)) {
                    out.unknown("skill", k);
                }
            }
            JsonObject d = s.has("definition") ? obj(defs.get(s.get("definition").getAsString())) : new JsonObject();
            for (Map.Entry<String, JsonElement> kv : d.entrySet()) {
                JsonElement v = kv.getValue();
                switch (kv.getKey()) {
                    case "title" -> out.text("en_us", prefix + ".title", text(v));
                    case "description" -> out.text("en_us", prefix + ".desc", text(v));
                    case "extra_description" -> out.text("en_us", prefix + ".desc2", text(v));
                    case "icon" -> n.add("icon", icon(v));
                    case "frame" -> n.add("frame", v);
                    case "size" -> n.addProperty("size", v.getAsDouble() / 26.0);
                    case "cost" -> n.add("cost", v);
                    case "rewards" -> {
                        JsonArray rw = new JsonArray();
                        for (JsonElement r : v.getAsJsonArray()) {
                            JsonObject ro = r.getAsJsonObject();
                            String type = ro.has("type") ? ro.get("type").getAsString() : "?";
                            String shortType = type.substring(type.indexOf(':') + 1);
                            JsonObject conv = ro.has("data") && ro.get("data").isJsonObject() ? ro.getAsJsonObject("data").deepCopy() : new JsonObject();
                            // Pufferfish's Skills falls back to its dummy reward for an optional type that is missing
                            boolean required = !ro.has("required") || !ro.get("required").isJsonPrimitive()
                                    || ro.get("required").getAsJsonPrimitive().isBoolean() && ro.get("required").getAsBoolean();
                            if (shortType.equals("points")) {
                                // PS names the amount "points" and the target "category"; ours are "amount" and "chapter"
                                if (conv.has("points")) {
                                    conv.add("amount", conv.get("points"));
                                    conv.remove("points");
                                }
                                if (conv.has("category")) {
                                    String target = conv.get("category").getAsString();
                                    if (!target.substring(target.indexOf(':') + 1).equals(category)) {
                                        out.unknown("skill_reward_category", target);
                                    }
                                    // the same category is the node's own chapter; the reward falls back to it
                                    conv.remove("category");
                                }
                            }
                            if (KNOWN_REWARDS.contains(shortType)) {
                                conv.addProperty("type", shortType);
                            } else if (!required) {
                                conv.addProperty("type", "dummy");
                            } else {
                                conv.addProperty("type", type);
                                out.unknown("skill_reward_type", type);
                            }
                            rw.add(conv);
                            out.count("skill_rewards", 1);
                        }
                        n.add("rewards", rw);
                    }
                    case "metadata", "editor-color" -> { }
                    default -> {
                        if (REQUIRED.contains(kv.getKey())) {
                            FtbQuestsImporter.put(n, "requires." + kv.getKey().substring("required_".length()), v);
                        } else {
                            out.unknown("definition", kv.getKey());
                        }
                    }
                }
            }
            if (!n.has("cost")) {
                n.addProperty("cost", 1);
            }
            nodes.add(nid, n);
        }
        // normal connection: either side opens from the other; exclusive: taking one closes the other
        connect(cons, "normal", nodes, out);
        connect(cons, "exclusive", nodes, out);
        o.add("nodes", nodes);
        if (patch) {
            o.addProperty("target", ns + ":" + cid);
            out.patches.put("patch_" + cid, o);
            out.note("partial category (patch of another mod's tree)");
        } else {
            out.chapters.put(cid, o);
        }
    }

    private static void connect(JsonObject cons, String kind, JsonObject nodes, ImportOutput out) {
        if (!cons.has(kind) || !cons.get(kind).isJsonObject()) {
            return;
        }
        JsonObject part = cons.getAsJsonObject(kind);
        for (String direction : List.of("bidirectional", "unidirectional")) {
            for (JsonElement pair : FtbQuestsImporter.arr(part, direction)) {
                JsonArray ab = pair.getAsJsonArray();
                String a = FtbQuestsImporter.slug(ab.get(0).getAsString());
                String b = FtbQuestsImporter.slug(ab.get(1).getAsString());
                if (kind.equals("normal")) {
                    addRequire(nodes, b, a);
                    if (direction.equals("bidirectional")) {
                        addRequire(nodes, a, b);
                    }
                    out.count("skill_connections", 1);
                } else {
                    addTo(nodes, a, "exclusive_with", b);
                    addTo(nodes, b, "exclusive_with", a);
                    out.count("skill_exclusive_pairs", 1);
                }
            }
        }
        for (String k : part.keySet()) {
            if (!k.equals("bidirectional") && !k.equals("unidirectional")) {
                out.unknown("connections", kind + "." + k);
            }
        }
    }

    private static void addRequire(JsonObject nodes, String node, String from) {
        JsonObject n = nodes.has(node) ? nodes.getAsJsonObject(node) : new JsonObject();
        nodes.add(node, n);
        JsonObject r = n.has("requires") ? n.getAsJsonObject("requires") : new JsonObject();
        n.add("requires", r);
        JsonArray list = r.has("nodes") ? r.getAsJsonArray("nodes") : new JsonArray();
        list.add(from);
        r.add("nodes", list);
        r.addProperty("mode", "any");
    }

    private static void addTo(JsonObject nodes, String node, String key, String value) {
        JsonObject n = nodes.has(node) ? nodes.getAsJsonObject(node) : new JsonObject();
        nodes.add(node, n);
        JsonArray list = n.has(key) ? n.getAsJsonArray(key) : new JsonArray();
        list.add(value);
        n.add(key, list);
    }

    private static JsonObject icon(JsonElement v) {
        JsonObject o = new JsonObject();
        if (v.isJsonObject()) {
            JsonObject data = v.getAsJsonObject().has("data") ? v.getAsJsonObject().getAsJsonObject("data") : new JsonObject();
            if (data.has("item")) {
                o.add("id", data.get("item"));
            } else if (data.has("texture")) {
                o.add("texture", data.get("texture"));
            } else if (data.has("effect")) {
                o.add("effect", data.get("effect"));
            }
        } else if (v.isJsonPrimitive()) {
            o.add("id", v);
        }
        return o;
    }

    /** Pufferfish titles are plain strings or text components; keep components as JSON text. */
    private static JsonElement text(JsonElement v) {
        return v;
    }

    private static JsonObject obj(JsonElement e) {
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
    }
}
