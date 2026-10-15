package com.kuronami.packprogress.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
                com.kuronami.packprogress.Constants.LOG.warn("Skipping progression chapter {}: {}", e.getKey(), ex.toString());
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
        public final boolean skills;
        public final Component title;
        public final Map<String, Node> nodes = new LinkedHashMap<>();
        public final List<Decoration> decorations = new ArrayList<>();
        public final String defaultShape;
        public final double defaultSize;

        Chapter(ClientModel m, String id, JsonObject o, Map<String, Integer> groupOrder) {
            this.id = id;
            int colon = id.indexOf(':');
            this.namespace = id.substring(0, colon);
            this.path = id.substring(colon + 1);
            this.icon = stack(o.get("icon"));
            this.order = o.has("order") ? o.get("order").getAsInt() : 0;
            this.groupOrder = o.has("group") ? groupOrder.getOrDefault(o.get("group").getAsString(), 999) : 999;
            this.skills = o.has("points") && o.get("points").isJsonObject();
            JsonObject defaults = obj(o, "defaults");
            this.defaultShape = defaults.has("shape") && !defaults.get("shape").getAsString().isEmpty() ? defaults.get("shape").getAsString() : (skills ? "square" : "square");
            this.defaultSize = defaults.has("size") && defaults.get("size").isJsonPrimitive() ? defaults.get("size").getAsDouble() : 1;
            Component fallbackTitle = o.has("title") && o.get("title").isJsonPrimitive() ? parseText(o.get("title").getAsString()) : Component.literal(path);
            this.title = m.component("progression." + namespace + "." + path + ".title", fallbackTitle);
            for (Map.Entry<String, JsonElement> n : obj(o, "nodes").entrySet()) {
                if (!n.getValue().isJsonObject()) {
                    continue;
                }
                try {
                    nodes.put(n.getKey(), new Node(m, this, n.getKey(), n.getValue().getAsJsonObject()));
                } catch (RuntimeException ex) {
                    com.kuronami.packprogress.Constants.LOG.warn("Skipping node {} in {}: {}", n.getKey(), id, ex.toString());
                }
            }
            for (JsonElement d : arr(o, "decorations")) {
                if (d.isJsonObject()) {
                    decorations.add(new Decoration(d.getAsJsonObject()));
                }
            }
            decorations.sort(Comparator.comparingInt(d -> d.order));
        }

        public ItemStack displayIcon() {
            if (!icon.isEmpty()) {
                return icon;
            }
            return nodes.values().stream().map(n -> n.icon).filter(s -> !s.isEmpty()).findFirst().orElse(new ItemStack(Items.BOOK));
        }
    }

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
        public final List<Task> tasks = new ArrayList<>();
        public final List<ItemStack> rewards = new ArrayList<>();
        public final List<String> rewardLines = new ArrayList<>();
        public final int cost;
        public final Component title;
        public final List<String> description;

        Node(ClientModel m, Chapter ch, String id, JsonObject o) {
            this.id = id;
            this.key = ch.id + "/" + id;
            this.x = o.has("x") ? o.get("x").getAsDouble() : 0;
            this.y = o.has("y") ? o.get("y").getAsDouble() : 0;
            this.size = o.has("size") ? Math.max(0.3, o.get("size").getAsDouble()) : Math.max(0.3, ch.defaultSize);
            String sh = o.has("shape") ? o.get("shape").getAsString() : "";
            this.shape = sh.isEmpty() || sh.equals("default") ? ch.defaultShape : sh;
            this.optional = o.has("optional") && o.get("optional").isJsonPrimitive() && o.get("optional").getAsBoolean();
            this.cost = o.has("cost") ? o.get("cost").getAsInt() : 0;
            JsonObject req = obj(o, "requires");
            for (JsonElement r : arr(req, "nodes")) {
                requires.add(r.getAsString());
            }
            for (JsonElement t : arr(o, "tasks")) {
                if (t.isJsonObject()) {
                    tasks.add(new Task(t.getAsJsonObject()));
                }
            }
            for (JsonElement r : arr(o, "rewards")) {
                if (!r.isJsonObject()) {
                    continue;
                }
                JsonObject ro = r.getAsJsonObject();
                String type = ro.has("type") ? ro.get("type").getAsString() : "";
                switch (type) {
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
                ic = tasks.get(0).item;
            }
            this.icon = ic;
            String base = "progression." + ch.namespace + "." + ch.path + "." + id;
            Component fallbackTitle = o.has("title") && o.get("title").isJsonPrimitive() ? parseText(o.get("title").getAsString())
                    : !tasks.isEmpty() && !tasks.get(0).item.isEmpty() ? tasks.get(0).item.getHoverName() : Component.literal(id);
            this.title = m.component(base + ".title", fallbackTitle);
            String d = m.lookup(base + ".desc");
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
        public final ItemStack item;
        public final long count;
        /** Item tasks that take the items (a Submit button) instead of checking the inventory. */
        public final boolean consume;

        Task(JsonObject o) {
            this.type = o.has("type") ? o.get("type").getAsString() : "";
            this.consume = (type.equals("item") && o.has("consume") && o.get("consume").getAsBoolean()) || type.equals("xp");
            this.item = o.has("item") ? stack(o.get("item")) : (o.has("icon") ? stack(o.get("icon")) : ItemStack.EMPTY);
            JsonObject it = o.has("item") && o.get("item").isJsonObject() ? o.getAsJsonObject("item") : new JsonObject();
            this.count = o.has("count") ? o.get("count").getAsLong() : it.has("count") ? it.get("count").getAsLong() : 1;
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

        Decoration(JsonObject o) {
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
        }
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
