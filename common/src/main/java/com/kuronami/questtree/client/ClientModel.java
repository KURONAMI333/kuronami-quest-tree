package com.kuronami.questtree.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kuronami.questtree.data.ProgressModel;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The client's read-only view of the synced bundle: chapters with their nodes, already resolved to item stacks and
 * text. Text lookup order: data pack translations for the selected language, then en_us, then resource pack
 * translations (I18n), then the first task's item name (FTB Quests behaviour; most DarkRPG quests rely on it).
 */
public final class ClientModel {

    public final List<Chapter> chapters = new ArrayList<>();
    /** Reward tables, used to show the options of a choice reward. */
    final JsonObject tables;
    /** Every node by global key. */
    public final Map<String, Node> nodesByKey = new LinkedHashMap<>();
    /** Chapter of every node, by node key. */
    public final Map<String, Chapter> chapterOf = new LinkedHashMap<>();
    private final Map<String, String> text = new LinkedHashMap<>();
    private final Map<String, String> fallback = new LinkedHashMap<>();

    public ClientModel(JsonObject bundle) {
        this.tables = obj(bundle, "reward_tables");
        String locale = Minecraft.getInstance().getLanguageManager().getSelected().toLowerCase(Locale.ROOT);
        JsonObject lang = obj(bundle, "lang");
        copyText(lang, "en_us", fallback);
        copyText(lang, locale, text);
        Map<String, Integer> groupOrder = new LinkedHashMap<>();
        for (JsonElement g : arr(bundle, "groups")) {
            if (g.isJsonObject() && g.getAsJsonObject().has("id")) {
                JsonObject go = g.getAsJsonObject();
                groupOrder.put(go.get("id").getAsString(), go.has("order") ? go.get("order").getAsInt() : groupOrder.size());
            }
        }
        for (Map.Entry<String, JsonElement> e : obj(bundle, "chapters").entrySet()) {
            if (!e.getValue().isJsonObject() || !e.getKey().contains(":")) {
                continue;
            }
            try {
                chapters.add(new Chapter(this, e.getKey(), e.getValue().getAsJsonObject(), groupOrder));
            } catch (RuntimeException ex) {
                // one malformed chapter must not hide the others
                com.kuronami.questtree.Constants.LOG.warn("Skipping progression chapter {}: {}", e.getKey(), ex.toString());
            }
        }
        for (Chapter c : chapters) {
            for (Node n : c.nodes.values()) {
                nodesByKey.put(n.key, n);
                chapterOf.put(n.key, c);
            }
        }
        chapters.sort(Comparator.comparingInt((Chapter c) -> c.groupOrder).thenComparingInt(c -> c.order).thenComparing(c -> c.id));
    }

    private static void copyText(JsonObject lang, String locale, Map<String, String> into) {
        if (!lang.has(locale) || !lang.get(locale).isJsonObject()) {
            return;
        }
        for (Map.Entry<String, JsonElement> e : lang.getAsJsonObject(locale).entrySet()) {
            JsonElement v = e.getValue();
            into.put(e.getKey(), v.isJsonPrimitive() ? v.getAsString() : v.toString());
        }
    }

    /** Translation from the synced data, or null. */
    public String lookup(String key) {
        String v = text.get(key);
        return v != null ? v : fallback.get(key);
    }

    Component component(String key, Component orElse) {
        String v = lookup(key);
        if (v != null) {
            return parseText(v);
        }
        if (net.minecraft.client.resources.language.I18n.exists(key)) {
            return Component.translatable(key);
        }
        return orElse;
    }

    /** The title of the chapter's group, or null when it has no group or the group has no title. */
    public Component groupTitle(Chapter c) {
        if (c.group == null) {
            return null;
        }
        return component("progression." + c.namespace + ".group." + c.group + ".title", null);
    }

    /** Plain text with FTB colour codes, or a JSON text component (Pufferfish titles often are {"translate": ...}). */
    static Component parseText(String v) {
        String t = v.trim();
        if ((t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))) {
            try {
                var level = Minecraft.getInstance().level;
                var provider = level != null ? level.registryAccess() : net.minecraft.core.RegistryAccess.EMPTY;
                Component c = Component.Serializer.fromJson(t, provider);
                if (c != null) {
                    return c;
                }
            } catch (RuntimeException ignored) {
                // not a component after all: show as text
            }
        }
        return Component.literal(FtbText.legacyColors(v));
    }

    public static final class Chapter {
        public final String id;
        public final String namespace;
        public final String path;
        public final ItemStack icon;
        public final int order;
        public final int groupOrder;
        /** The chapter's group id (as written by the pack, lower-cased), or null when it has none. */
        public final String group;
        public final boolean skills;
        public final Component title;
        public final Map<String, Node> nodes = new LinkedHashMap<>();
        public final List<Decoration> decorations = new ArrayList<>();
        /** The chapter's "defaults.shape"; null when unset, so each node takes the shape of its role. */
        public final String defaultShape;
        public final double defaultSize;
        final boolean defaultSizeSet;
        /** Chapter-wide "hide dependency lines" (FTB Quests chapter default). */
        public final boolean hideLines;
        public final boolean hideDetailsByDefault;
        /** Tiles that stand for a quest of another chapter (FTB Quests quest links). */
        public final List<Link> links = new ArrayList<>();

        Chapter(ClientModel m, String id, JsonObject o, Map<String, Integer> groupOrder) {
            this.id = id;
            int colon = id.indexOf(':');
            this.namespace = id.substring(0, colon);
            this.path = id.substring(colon + 1);
            this.icon = stack(o.get("icon"));
            this.order = o.has("order") ? o.get("order").getAsInt() : 0;
            this.groupOrder = o.has("group") ? groupOrder.getOrDefault(o.get("group").getAsString(), 999) : 999;
            this.group = o.has("group") ? o.get("group").getAsString() : null;
            this.skills = o.has("points") && o.get("points").isJsonObject();
            JsonObject defaults = obj(o, "defaults");
            this.defaultShape = defaults.has("shape") && !defaults.get("shape").getAsString().isEmpty() ? defaults.get("shape").getAsString() : null;
            this.defaultSizeSet = defaults.has("size") && defaults.get("size").isJsonPrimitive();
            this.defaultSize = defaultSizeSet ? defaults.get("size").getAsDouble() : 1;
            this.hideDetailsByDefault = defaults.has("hide_details_until_startable") && defaults.get("hide_details_until_startable").isJsonPrimitive()
                    && defaults.get("hide_details_until_startable").getAsBoolean();
            this.hideLines = defaults.has("hide_lines") && defaults.get("hide_lines").isJsonPrimitive() && defaults.get("hide_lines").getAsBoolean();
            Component fallbackTitle = o.has("title") && o.get("title").isJsonPrimitive() ? parseText(o.get("title").getAsString()) : Component.literal(path);
            this.title = m.component("progression." + namespace + "." + path + ".title", fallbackTitle);
            for (Map.Entry<String, JsonElement> n : obj(o, "nodes").entrySet()) {
                if (!n.getValue().isJsonObject()) {
                    continue;
                }
                try {
                    nodes.put(n.getKey(), new Node(m, this, n.getKey(), n.getValue().getAsJsonObject()));
                } catch (RuntimeException ex) {
                    com.kuronami.questtree.Constants.LOG.warn("Skipping node {} in {}: {}", n.getKey(), id, ex.toString());
                }
            }
            for (JsonElement d : arr(o, "decorations")) {
                if (d.isJsonObject()) {
                    decorations.add(new Decoration(this, d.getAsJsonObject()));
                }
            }
            for (JsonElement l : arr(o, "links")) {
                if (l.isJsonObject() && l.getAsJsonObject().has("node")) {
                    links.add(new Link(this, l.getAsJsonObject()));
                }
            }
            decorations.sort(Comparator.comparingInt(d -> d.order));
        }

        /** Global node key for a reference written in this chapter: "node" or "otherchapter/node" (same namespace). */
        public String keyOf(String ref) {
            return ref.contains("/") ? namespace + ":" + ref : id + "/" + ref;
        }

        public ItemStack displayIcon() {
            if (!icon.isEmpty()) {
                return icon;
            }
            return nodes.values().stream().map(n -> n.icon).filter(s -> !s.isEmpty()).findFirst().orElse(new ItemStack(Items.BOOK));
        }
    }

    /** Milestones are drawn larger unless the node or its chapter sets a size. */
    static final double MILESTONE_SIZE = 1.5;

    public static final class Node {
        public final String id;
        /** Global key {@code <chapter id>/<node id>}, as used by the progress data. */
        public final String key;
        /** Options of a choice reward (pick one), empty when the node has none. */
        public final List<ItemStack> choice = new ArrayList<>();
        public final double x;
        public final double y;
        public final double size;
        public final String shape;
        public final ItemStack icon;
        public final boolean optional;
        public final List<String> requires = new ArrayList<>();
        /** Requirements as global keys (may point into other chapters). */
        public final List<String> requireKeys = new ArrayList<>();
        /** A skill tree's starting node (Pufferfish's "root"); lines flow outward from these. */
        public final boolean root;
        /** FTB Quests' "hide dependency lines" / "hide dependent lines": authors thin out busy maps with these. */
        public final boolean hideLinesToRequired;
        /** FTB Quests: show only the title until the quest can be started / show the text only once done. */
        public final boolean hideDetailsUntilStartable;
        public final boolean hideTextUntilComplete;
        public final boolean hideLinesToDependents;
        public final List<Task> tasks = new ArrayList<>();
        public final List<ItemStack> rewards = new ArrayList<>();
        public final List<String> rewardLines = new ArrayList<>();
        public final int cost;
        public final Component title;
        /** FTB Quests' subtitle: one step under the title in the sheet and on the map's hover tooltip, or null. */
        public final Component subtitle;
        public final List<String> description;

        Node(ClientModel m, Chapter ch, String id, JsonObject o) {
            this.id = id;
            this.key = ch.id + "/" + id;
            this.x = o.has("x") ? o.get("x").getAsDouble() : 0;
            this.y = o.has("y") ? o.get("y").getAsDouble() : 0;
            boolean milestone = ProgressModel.boolOf(o, "milestone");
            this.root = ProgressModel.boolOf(o, "root");
            this.size = o.has("size") ? Math.max(0.3, o.get("size").getAsDouble())
                    : milestone && !ch.defaultSizeSet ? MILESTONE_SIZE : Math.max(0.3, ch.defaultSize);
            String sh = o.has("shape") ? o.get("shape").getAsString() : "";
            this.shape = !sh.isEmpty() && !sh.equals("default") ? sh : ch.defaultShape != null ? ch.defaultShape : ProgressModel.roleShape(o);
            this.optional = o.has("optional") && o.get("optional").isJsonPrimitive() && o.get("optional").getAsBoolean();
            this.cost = o.has("cost") ? o.get("cost").getAsInt() : 0;
            JsonObject req = obj(o, "requires");
            for (JsonElement r : arr(req, "nodes")) {
                requires.add(r.getAsString());
                requireKeys.add(ch.keyOf(r.getAsString()));
            }
            JsonObject vis = obj(o, "visibility");
            this.hideDetailsUntilStartable = vis.has("hide_details_until_startable") ? vis.get("hide_details_until_startable").getAsBoolean()
                    : ch.hideDetailsByDefault;
            this.hideTextUntilComplete = vis.has("hide_text_until_complete") && vis.get("hide_text_until_complete").getAsBoolean();
            JsonObject lines = obj(o, "lines");
            this.hideLinesToRequired = lines.has("hide_to_deps") && lines.get("hide_to_deps").getAsBoolean();
            this.hideLinesToDependents = lines.has("hide_to_dependents") && lines.get("hide_to_dependents").getAsBoolean();
            for (JsonElement t : arr(o, "tasks")) {
                if (t.isJsonObject()) {
                    tasks.add(new Task(m, t.getAsJsonObject(), o.has("repeat") && !(o.get("repeat").isJsonPrimitive() && !o.get("repeat").getAsBoolean())));
                }
            }
            for (JsonElement r : arr(o, "rewards")) {
                if (!r.isJsonObject()) {
                    continue;
                }
                JsonObject ro = r.getAsJsonObject();
                String type = ro.has("type") ? ro.get("type").getAsString() : "";
                // any reward may name its own icon (FTB Quests reward icons; command rewards have nothing else to show)
                ItemStack own = ro.has("icon") ? stack(ro.get("icon")) : ItemStack.EMPTY;
                if (!own.isEmpty()) {
                    rewards.add(own);
                    continue;
                }
                switch (type) {
                    case "trophy" -> {
                        ItemStack t = trophyPreview(ro.has("trophy") ? ro.get("trophy").getAsString() : "");
                        if (!t.isEmpty()) {
                            rewards.add(t);
                        }
                    }
                    case "item" -> rewards.add(stack(ro.get("item")));
                    case "xp", "xp_levels" -> rewards.add(new ItemStack(Items.EXPERIENCE_BOTTLE, Math.max(1, Math.min(64, ro.has("amount") ? ro.get("amount").getAsInt() : 1))));
                    case "table" -> {
                        String mode = ro.has("mode") ? ro.get("mode").getAsString() : "random";
                        JsonObject table = ro.has("table") ? obj(m.tables, ro.get("table").getAsString()) : new JsonObject();
                        if (mode.equals("choice")) {
                            for (JsonElement en : arr(table, "entries")) {
                                JsonObject rw = en.isJsonObject() ? obj(en.getAsJsonObject(), "reward") : new JsonObject();
                                choice.add(rw.has("item") ? stack(rw.get("item")) : new ItemStack(Items.EXPERIENCE_BOTTLE));
                            }
                        } else {
                            rewards.add(new ItemStack(Items.CHEST));
                        }
                    }
                    case "attribute" -> rewardLines.add(attributeLine(ro));
                    default -> { }
                }
            }
            ItemStack ic = stack(o.get("icon"));
            if (ic.isEmpty() && !tasks.isEmpty()) {
                ic = tasks.get(0).icon();
            }
            this.icon = ic;
            String base = "progression." + ch.namespace + "." + ch.path + "." + id;
            Component fallbackTitle = o.has("title") && o.get("title").isJsonPrimitive() ? parseText(o.get("title").getAsString())
                    : !tasks.isEmpty() ? tasks.get(0).name() : Component.literal(id);
            this.title = m.component(base + ".title", fallbackTitle);
            // the importer keeps FTB Quests' subtitle under the same prefix; it is optional, so null when absent
            String sub = m.lookup(base + ".subtitle");
            this.subtitle = sub == null ? null : parseText(sub);
            String d = m.lookup(base + ".desc");
            // Pufferfish descriptions are JSON text components ({"translate": ..., "fallback": ...}): resolve them first,
            // or the raw JSON shows in the details sheet
            String dt = d == null ? null : d.trim();
            if (dt != null && ((dt.startsWith("{") && dt.endsWith("}")) || (dt.startsWith("[") && dt.endsWith("]")))) {
                // JSON only: a failed parse keeps the text as it was (parseText's fallback would apply colour codes twice)
                try {
                    var level = Minecraft.getInstance().level;
                    Component c = Component.Serializer.fromJson(dt, level != null ? level.registryAccess() : net.minecraft.core.RegistryAccess.EMPTY);
                    if (c != null) {
                        d = c.getString();
                    }
                } catch (RuntimeException ignored) {
                    // not JSON after all
                }
            }
            this.description = d == null ? List.of() : FtbText.descriptionLines(d);
        }

        private static String attributeLine(JsonObject ro) {
            String attr = ro.has("attribute") ? ro.get("attribute").getAsString() : "?";
            double v = ro.has("value") ? ro.get("value").getAsDouble() : 0;
            String op = ro.has("operation") ? ro.get("operation").getAsString() : "addition";
            String name = attr.substring(attr.indexOf(':') + 1).replace('_', ' ').replace('.', ' ');
            boolean pct = !op.equals("addition") && !op.equals("add_value");
            String num = pct ? String.format(Locale.ROOT, "%+.0f%%", v * 100) : String.format(Locale.ROOT, "%+.1f", v).replace(".0", "");
            return num + " " + name;
        }
    }

    public static final class Task {
        public final String type;
        /** The item to collect for item tasks; empty for every other type. */
        public final ItemStack item;
        public final long count;
        /** Item tasks that take the items (a Submit button) instead of checking the inventory. */
        public final boolean consume;
        public final boolean optional;
        /** What the row shows: the target (mob, advancement, biome...) rather than just the kind of task. */
        private ItemStack icon;
        private Component name;
        /** Advancements reach the client after the bundle (first server tick), so their text is looked up again later. */
        private final JsonObject source;
        private boolean resolved;
        private final boolean authorTitle;

        Task(ClientModel m, JsonObject o, boolean repeatable) {
            this.type = o.has("type") ? o.get("type").getAsString() : "";
            // same rule as ProgressRules.consumes: repeatable quests take their items
            this.consume = (type.equals("item") && (repeatable || (o.has("consume") && o.get("consume").getAsBoolean()))) || type.equals("xp");
            this.optional = o.has("optional") && o.get("optional").isJsonPrimitive() && o.get("optional").getAsBoolean();
            this.item = type.equals("item") && o.has("item") ? stack(o.get("item")) : ItemStack.EMPTY;
            JsonObject it = o.has("item") && o.get("item").isJsonObject() ? o.getAsJsonObject("item") : new JsonObject();
            this.count = o.has("count") ? o.get("count").getAsLong() : it.has("count") ? it.get("count").getAsLong() : 1;
            this.source = o;
            TaskText.Shown shown = TaskText.describe(type, o, item);
            ItemStack explicitIcon = o.has("icon") ? stack(o.get("icon")) : ItemStack.EMPTY;
            this.icon = !explicitIcon.isEmpty() ? explicitIcon : shown.icon();
            // a title written by the pack author wins (FTB Quests task titles, from the quest or its lang files)
            Component title = null;
            if (o.has("text")) {
                String t = m.lookup(o.get("text").getAsString() + ".title");
                title = t == null ? null : parseText(t);
            }
            if (title == null && o.has("title") && o.get("title").isJsonPrimitive()) {
                title = parseText(o.get("title").getAsString());
            }
            this.name = title != null ? title : shown.name();
            this.authorTitle = title != null;
            this.resolved = !type.equals("advancement") || shown.resolved();
        }

        public ItemStack icon() {
            resolve();
            return icon;
        }

        public Component name() {
            resolve();
            return name;
        }

        private void resolve() {
            if (resolved) {
                return;
            }
            TaskText.Shown shown = TaskText.describe(type, source, item);
            if (shown.resolved()) {
                resolved = true;
                if (!source.has("icon")) {
                    icon = shown.icon();
                }
                if (!authorTitle) {
                    name = shown.name();
                }
            }
        }
    }

    public static final class Decoration {
        public final ResourceLocation image;
        public final double x;
        public final double y;
        public final double w;
        public final double h;
        public final double rotation;
        public final int order;
        public final boolean dev;
        /** Shown only once this node (global key) is done; null = always. */
        public final String visibleAfter;
        /** Click: open this node (global key), or open this URL (after the vanilla confirm screen). */
        public final String clickNode;
        public final String clickUrl;
        /** Tint (0xRRGGBB) and opacity (0..1) from FTB Quests' color / alpha. */
        public final int color;
        public final float alpha;
        public final List<Component> hover = new ArrayList<>();

        Decoration(Chapter ch, JsonObject o) {
            ResourceLocation img = null;
            if (o.has("image")) {
                String s = o.get("image").getAsString();
                ResourceLocation rl = ResourceLocation.tryParse(s);
                if (rl != null) {
                    // FTB Quests accepts "ns:block/x" (atlas sprite path) as well as full texture paths
                    img = rl.getPath().endsWith(".png") ? rl : ResourceLocation.fromNamespaceAndPath(rl.getNamespace(), "textures/" + rl.getPath() + ".png");
                }
            }
            this.image = img;
            this.x = num(o, "x");
            this.y = num(o, "y");
            this.w = o.has("w") ? num(o, "w") : 1;
            this.h = o.has("h") ? num(o, "h") : 1;
            this.rotation = num(o, "rotation");
            this.order = o.has("order") ? o.get("order").getAsInt() : 0;
            this.dev = o.has("dev") && o.get("dev").getAsBoolean();
            this.visibleAfter = o.has("visible_after") ? ch.keyOf(o.get("visible_after").getAsString()) : null;
            JsonObject click = obj(o, "click");
            String type = click.has("type") ? click.get("type").getAsString() : "";
            String target = click.has("target") ? click.get("target").getAsString() : "";
            this.clickNode = type.equals("node") && !target.isEmpty() ? ch.keyOf(target) : null;
            this.clickUrl = type.equals("url") && (target.startsWith("https://") || target.startsWith("http://")) ? target : null;
            this.color = o.has("color") && o.get("color").isJsonPrimitive() ? o.get("color").getAsInt() & 0xFFFFFF : 0xFFFFFF;
            this.alpha = o.has("alpha") && o.get("alpha").isJsonPrimitive() ? Math.max(0, Math.min(255, o.get("alpha").getAsInt())) / 255f : 1;
            for (JsonElement e : arr(o, "hover")) {
                if (e.isJsonPrimitive()) {
                    hover.add(parseText(e.getAsString()));
                }
            }
        }
    }

    /** A tile in one chapter that stands for a quest of another chapter; clicking it goes there. */
    public static final class Link {
        public final String node;
        public final double x;
        public final double y;
        public final double size;
        public final String shape;

        Link(Chapter ch, JsonObject o) {
            String ref = o.get("node").getAsString();
            this.node = ref.contains(":") ? ref : ch.keyOf(ref);
            this.x = num(o, "x");
            this.y = num(o, "y");
            this.size = o.has("size") ? Math.max(0.3, num(o, "size")) : ch.defaultSize;
            this.shape = o.has("shape") ? o.get("shape").getAsString() : "";
        }
    }

    /**
     * The trophy as Kuronami's Trophies draws it (its item with the trophy component), so the reward shows the actual
     * trophy before it is claimed. Empty when that mod is not installed.
     */
    static ItemStack trophyPreview(String id) {
        var level = Minecraft.getInstance().level;
        var player = Minecraft.getInstance().player;
        ResourceLocation item = ResourceLocation.fromNamespaceAndPath("kuronami_trophies", "trophy");
        if (id.isEmpty() || level == null || player == null || !BuiltInRegistries.ITEM.containsKey(item)) {
            return ItemStack.EMPTY;
        }
        JsonObject data = new JsonObject();
        data.addProperty("trophy", id);
        com.google.gson.JsonArray uuid = new com.google.gson.JsonArray();
        for (int v : net.minecraft.core.UUIDUtil.uuidToIntArray(player.getUUID())) {
            uuid.add(v);
        }
        data.add("recipient", uuid);
        data.addProperty("name", "");
        data.addProperty("date", "");
        JsonObject components = new JsonObject();
        components.add("kuronami_trophies:trophy", data);
        JsonObject json = new JsonObject();
        json.addProperty("id", item.toString());
        json.addProperty("count", 1);
        json.add("components", components);
        return ItemStack.CODEC.parse(level.registryAccess().createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE), json)
                .result().orElse(ItemStack.EMPTY);
    }

    static ItemStack stack(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return ItemStack.EMPTY;
        }
        String id = e.isJsonPrimitive() ? e.getAsString() : e.isJsonObject() && e.getAsJsonObject().has("id") ? e.getAsJsonObject().get("id").getAsString() : null;
        if (id == null) {
            return ItemStack.EMPTY;
        }
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) {
            return ItemStack.EMPTY;
        }
        Item item = BuiltInRegistries.ITEM.get(rl);
        if (item == Items.AIR) {
            return ItemStack.EMPTY;
        }
        int count = e.isJsonObject() && e.getAsJsonObject().has("count") ? e.getAsJsonObject().get("count").getAsInt() : 1;
        return new ItemStack(item, Math.max(1, count));
    }

    static JsonObject obj(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : new JsonObject();
    }

    static JsonArray arr(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray();
    }

    private static double num(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : 0;
    }
}
