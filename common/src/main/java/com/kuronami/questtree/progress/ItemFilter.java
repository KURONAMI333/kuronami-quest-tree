package com.kuronami.questtree.progress;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An FTB Filter System "smart filter" expression (the {@code ftbfiltersystem:filter} component of an
 * {@code ftbfiltersystem:smart_filter} item), as FTB Quests uses it for item tasks and icons.
 * <p>
 * The grammar follows FTB Filter System 21.1.4 ({@code dev.ftb.mods.ftbfiltersystem.util.FilterParser}):
 * a filter is a list of {@code name(argument)} terms with no separator, and the whole list must match (an implicit
 * {@code and}). The name is a resource location whose namespace defaults to {@code ftbfiltersystem}. The argument
 * runs to the matching {@code )}; a backslash makes the next character literal (it is skipped when matching
 * parentheses and removed from the argument). Compound filters ({@code and}, {@code or}, {@code not},
 * {@code only_one}) parse their argument again as a list.
 * <p>
 * Parsing is pure (no registry access) so it can be unit tested; only {@link #test} and {@link #examples} touch the
 * game registries, through the nested {@code Eval} class, which is loaded only when they are called.
 */
public final class ItemFilter {

    /** Item id of the FTB Filter System smart filter. */
    public static final String SMART_FILTER_ID = "ftbfiltersystem:smart_filter";
    /** Data component holding the filter expression on a smart filter item. */
    public static final String FILTER_COMPONENT = "ftbfiltersystem:filter";

    private static final String NAMESPACE = "ftbfiltersystem";
    private static final int MAX_EXAMPLES = 64;
    // nesting deeper than this is treated as malformed instead of risking a stack overflow
    private static final int MAX_DEPTH = 64;
    private static final int CACHE_LIMIT = 4096;
    private static final Map<String, ItemFilter> CACHE = new ConcurrentHashMap<>();

    private final String expression;
    private final Node root;
    private final String error;
    private final Set<String> unsupported;
    // examples are derived from registries and tags, so they are kept until clearCache() (tag reload, world change)
    private volatile List<ItemStack> examples;

    private ItemFilter(String expression, Node root, String error, Set<String> unsupported) {
        this.expression = expression;
        this.root = root;
        this.error = error;
        this.unsupported = unsupported;
    }

    /**
     * Parses a filter expression. Never throws: malformed input gives a filter that matches nothing and reports
     * {@link #isValid()} {@code false}. Results are cached by expression string.
     */
    public static ItemFilter parse(String expr) {
        String key = expr == null ? "" : expr;
        if (expr == null) {
            return new ItemFilter(key, null, "no expression", Set.of());
        }
        ItemFilter cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        ItemFilter f;
        try {
            Set<String> unsupported = new LinkedHashSet<>();
            Node root = new Node(Node.Kind.AND, "root", key, parseList(key, 0, unsupported));
            f = new ItemFilter(key, root, null, Collections.unmodifiableSet(unsupported));
        } catch (Malformed | RuntimeException e) {
            f = new ItemFilter(key, null, String.valueOf(e.getMessage()), Set.of());
        }
        if (CACHE.size() >= CACHE_LIMIT) {
            CACHE.clear();
        }
        CACHE.put(key, f);
        return f;
    }

    /** Drops the parse cache and every cached example list. Call after tags or registries are reloaded. */
    public static void clearCache() {
        for (ItemFilter f : CACHE.values()) {
            f.examples = null;
        }
        CACHE.clear();
    }

    /**
     * Whether a task item JSON is a smart filter carrying an expression, e.g.
     * {@code {"id":"ftbfiltersystem:smart_filter","components":{"ftbfiltersystem:filter":"item(minecraft:stone)"}}}.
     */
    public static boolean isFilterItem(JsonObject itemJson) {
        return filterExpression(itemJson) != null;
    }

    /** The filter expression of a smart filter item JSON, or {@code null} when the JSON is not one. */
    public static String filterExpression(JsonObject itemJson) {
        if (itemJson == null || !itemJson.has("id") || !itemJson.get("id").isJsonPrimitive()
                || !SMART_FILTER_ID.equals(itemJson.get("id").getAsString())) {
            return null;
        }
        JsonElement comps = itemJson.get("components");
        if (comps == null || !comps.isJsonObject()) {
            return null;
        }
        JsonElement f = comps.getAsJsonObject().get(FILTER_COMPONENT);
        return f != null && f.isJsonPrimitive() ? f.getAsString() : null;
    }

    /** The expression this filter was parsed from. */
    public String expression() {
        return expression;
    }

    /** Whether the expression parsed. An invalid filter matches nothing and has no examples. */
    public boolean isValid() {
        return root != null;
    }

    /** Why parsing failed, or {@code null} when the filter is valid. */
    public String error() {
        return error;
    }

    /**
     * The implicit top-level {@code and} (function name {@code root}) whose children are the expression's terms,
     * or {@code null} when the filter is invalid.
     */
    public Node root() {
        return root;
    }

    /**
     * Function names that parsed but cannot be evaluated here and always evaluate to false: names FTB Filter System
     * does not define (including other mods' filters, reported with their namespace) and {@code custom}, which
     * needs FTB Filter System's own event. Meant for logging once per filter.
     */
    public Set<String> unsupportedFunctions() {
        return unsupported;
    }

    /** Whether the stack passes the filter. Same as {@code test(stack, null)}. */
    public boolean test(ItemStack stack) {
        return test(stack, null);
    }

    /**
     * Whether the stack passes the filter. Empty stacks never pass. {@code registries} (may be null) is used to
     * decode {@code component(...)} values that refer to registry entries; without it plain NBT ops are used.
     * Any failure while evaluating counts as no match.
     */
    public boolean test(ItemStack stack, HolderLookup.Provider registries) {
        if (root == null || stack == null) {
            return false;
        }
        try {
            return Eval.test(root, stack, registries);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Items the filter stands for, for a cycling icon (at most 64): {@code item} and {@code item_tag} terms are
     * expanded, {@code or} joins its children, and {@code and} takes the first child that yields items and keeps
     * those passing the whole {@code and} (all of them if none pass, so an icon still shows). Empty when nothing can
     * be derived, e.g. {@code block()} alone. Never throws; cached until {@link #clearCache()}.
     */
    public List<ItemStack> examples() {
        if (root == null) {
            return List.of();
        }
        List<ItemStack> e = examples;
        if (e == null) {
            try {
                e = Eval.examples(root);
            } catch (RuntimeException ex) {
                e = List.of();
            }
            examples = e;
        }
        return e;
    }

    @Override
    public String toString() {
        return root == null ? "invalid(" + expression + ")" : root.childrenString();
    }

    // ---- parsing (pure) ----

    private static final class Malformed extends Exception {
        Malformed(String message) {
            super(message);
        }
    }

    private static List<Node> parseList(String s, int depth, Set<String> unsupported) throws Malformed {
        if (depth > MAX_DEPTH) {
            throw new Malformed("nested too deeply");
        }
        List<Node> out = new ArrayList<>();
        s = s.trim();
        while (!s.isEmpty()) {
            int open = s.indexOf('(');
            if (open <= 0) {
                throw new Malformed("expected name(argument) at: " + s);
            }
            int close = matchingParen(s, open);
            String fn = functionName(s.substring(0, open).trim());
            String arg = unescape(s.substring(open + 1, close).trim());
            out.add(build(fn, arg, depth, unsupported));
            s = s.substring(close + 1).trim();
        }
        return out;
    }

    private static int matchingParen(String s, int open) throws Malformed {
        int level = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '(') {
                level++;
            } else if (c == ')' && --level == 0) {
                return i;
            }
        }
        throw new Malformed("unbalanced parentheses: " + s);
    }

    private static String unescape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\\' && ++i >= s.length()) {
                break;
            }
            b.append(s.charAt(i));
        }
        return b.toString();
    }

    /** Normalised function name: the path for the ftbfiltersystem namespace, the full location otherwise. */
    private static String functionName(String name) throws Malformed {
        int colon = name.indexOf(':');
        String ns = colon > 0 ? name.substring(0, colon) : NAMESPACE;
        String path = colon > 0 ? name.substring(colon + 1) : name;
        if (!validNamespace(ns) || path.isEmpty() || !validPath(path)) {
            throw new Malformed("invalid filter name: " + name);
        }
        return ns.equals(NAMESPACE) ? path : ns + ":" + path;
    }

    private static boolean validLocation(String s) {
        int colon = s.indexOf(':');
        String ns = colon >= 0 ? s.substring(0, colon) : "minecraft";
        String path = colon >= 0 ? s.substring(colon + 1) : s;
        return validNamespace(ns) && validPath(path);
    }

    private static boolean validNamespace(String ns) {
        for (int i = 0; i < ns.length(); i++) {
            char c = ns.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '-' || c == '.')) {
                return false;
            }
        }
        return true;
    }

    private static boolean validPath(String path) {
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '-' || c == '.' || c == '/')) {
                return false;
            }
        }
        return true;
    }

    private static Node build(String fn, String arg, int depth, Set<String> unsupported) throws Malformed {
        switch (fn) {
            case "and":
                return new Node(Node.Kind.AND, fn, arg, parseList(arg, depth + 1, unsupported));
            case "or":
                return new Node(Node.Kind.OR, fn, arg, parseList(arg, depth + 1, unsupported));
            case "only_one":
                return new Node(Node.Kind.ONLY_ONE, fn, arg, parseList(arg, depth + 1, unsupported));
            case "not": {
                List<Node> children = parseList(arg, depth + 1, unsupported);
                if (children.size() > 1) {
                    throw new Malformed("not() takes at most one filter");
                }
                return new Node(Node.Kind.NOT, fn, arg, children);
            }
            case "item":
            case "item_tag":
                if (arg.isEmpty() || !validLocation(arg)) {
                    throw new Malformed("invalid id in " + fn + "(" + arg + ")");
                }
                return new Node(fn.equals("item") ? Node.Kind.ITEM : Node.Kind.ITEM_TAG, fn, arg, List.of());
            case "mod":
                return new Node(Node.Kind.MOD, fn, arg, List.of());
            case "block":
                return new Node(Node.Kind.BLOCK, fn, arg, List.of());
            case "component": {
                // FTB Filter System: optional "strict:" / "fuzzy:" prefix (fuzzy by default), then SNBT
                boolean strict = arg.startsWith("strict:");
                String data = strict || arg.startsWith("fuzzy:") ? arg.substring(arg.indexOf(':') + 1) : arg;
                Node n = new Node(Node.Kind.COMPONENT, fn, arg, List.of());
                n.strict = strict;
                n.data = data;
                return n;
            }
            case "stack_size":
            case "max_stack_size":
            case "food_value":
            case "durability": {
                Node n = new Node(switch (fn) {
                    case "stack_size" -> Node.Kind.STACK_SIZE;
                    case "max_stack_size" -> Node.Kind.MAX_STACK_SIZE;
                    case "food_value" -> Node.Kind.FOOD_VALUE;
                    default -> Node.Kind.DURABILITY;
                }, fn, arg, List.of());
                parseComparison(n, arg, fn.equals("durability"));
                return n;
            }
            case "expression": {
                // "<expression>/<display name>": FTB splits at the first '/'
                String[] parts = arg.split("/", 2);
                Node sub = new Node(Node.Kind.AND, "root", parts[0], parseList(parts[0], depth + 1, unsupported));
                Node n = new Node(Node.Kind.EXPRESSION, fn, arg, List.of(sub));
                n.data = parts.length > 1 ? parts[1] : "";
                return n;
            }
            case "custom":
                unsupported.add(fn);
                return new Node(Node.Kind.CUSTOM, fn, arg, List.of());
            default:
                unsupported.add(fn);
                return new Node(Node.Kind.UNKNOWN, fn, arg, List.of());
        }
    }

    /** FTB Filter System's NumericComparison: operator, then an integer, then '%' where allowed. */
    private static void parseComparison(Node n, String arg, boolean allowPercent) throws Malformed {
        String s = arg;
        if (s.endsWith("%")) {
            if (!allowPercent) {
                throw new Malformed("percentage not allowed in " + n.function + "(" + arg + ")");
            }
            n.percent = true;
            s = s.substring(0, s.length() - 1);
        }
        int digit = -1;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) {
                digit = i;
                break;
            }
        }
        if (digit < 0) {
            throw new Malformed("no number in " + n.function + "(" + arg + ")");
        }
        String op = s.substring(0, digit);
        if (!List.of("=", "!=", ">", "<", "<=", ">=").contains(op)) {
            throw new Malformed("unknown comparison '" + op + "' in " + n.function + "(" + arg + ")");
        }
        try {
            n.value = Integer.parseInt(s.substring(digit));
        } catch (NumberFormatException e) {
            throw new Malformed("bad number in " + n.function + "(" + arg + ")");
        }
        n.op = op;
    }

    /** One parsed term. Compound terms have children; leaf terms keep their (unescaped) argument. */
    public static final class Node {

        /** What a term does. UNKNOWN and CUSTOM always evaluate to false. */
        public enum Kind {
            AND, OR, NOT, ONLY_ONE, ITEM, ITEM_TAG, MOD, BLOCK, COMPONENT,
            STACK_SIZE, MAX_STACK_SIZE, DURABILITY, FOOD_VALUE, EXPRESSION, CUSTOM, UNKNOWN
        }

        private final Kind kind;
        private final String function;
        private final String argument;
        private final List<Node> children;
        // leaf details, set while parsing
        private boolean strict;
        private String data;
        private String op;
        private int value;
        private boolean percent;
        // resolved game object (Item, TagKey, CompoundTag, mod item list), filled lazily by Eval
        private volatile Object resolved;

        Node(Kind kind, String function, String argument, List<Node> children) {
            this.kind = kind;
            this.function = function;
            this.argument = argument;
            this.children = List.copyOf(children);
        }

        /** The kind of term. */
        public Kind kind() {
            return kind;
        }

        /** Function name without the {@code ftbfiltersystem:} namespace ({@code root} for the top level). */
        public String function() {
            return function;
        }

        /** The argument text after unescaping (for compounds, the children's source). */
        public String argument() {
            return argument;
        }

        /** Child terms of {@code and}/{@code or}/{@code not}/{@code only_one}/{@code expression}. */
        public List<Node> children() {
            return children;
        }

        /** For {@code component}: whether the prefix was {@code strict:}. */
        public boolean strict() {
            return strict;
        }

        /** For {@code component}: the SNBT without its prefix; for {@code expression}: the display name. */
        public String data() {
            return data;
        }

        /** For comparison terms: the operator ({@code = != > < <= >=}), else {@code null}. */
        public String op() {
            return op;
        }

        /** For comparison terms: the number compared against. */
        public int value() {
            return value;
        }

        /** For {@code durability}: whether the number is a percentage of the maximum. */
        public boolean percent() {
            return percent;
        }

        String childrenString() {
            StringBuilder b = new StringBuilder();
            for (Node c : children) {
                b.append(c);
            }
            return b.toString();
        }

        /** Canonical form: {@code name(argument)}, compounds with their children concatenated. */
        @Override
        public String toString() {
            boolean compound = kind == Kind.AND || kind == Kind.OR || kind == Kind.NOT || kind == Kind.ONLY_ONE;
            return function + "(" + (compound ? childrenString() : argument) + ")";
        }

        boolean containsUnsupported() {
            if (kind == Kind.UNKNOWN || kind == Kind.CUSTOM) {
                return true;
            }
            for (Node c : children) {
                if (c.containsUnsupported()) {
                    return true;
                }
            }
            return false;
        }
    }

    // ---- evaluation (touches the game; loaded only when test/examples run) ----

    private static final class Eval {

        static boolean test(Node n, ItemStack s, HolderLookup.Provider reg) {
            if (s.isEmpty()) {
                return false;
            }
            try {
                return switch (n.kind) {
                    case AND -> {
                        for (Node c : n.children) {
                            if (!test(c, s, reg)) {
                                yield false;
                            }
                        }
                        yield true;
                    }
                    case OR -> {
                        for (Node c : n.children) {
                            if (test(c, s, reg)) {
                                yield true;
                            }
                        }
                        yield false;
                    }
                    case ONLY_ONE -> {
                        int hits = 0;
                        for (Node c : n.children) {
                            if (test(c, s, reg) && ++hits > 1) {
                                yield false;
                            }
                        }
                        yield hits == 1;
                    }
                    // an unsupported term is "unknown", not "no": negating it must not turn every item into a match
                    case NOT -> !n.children.isEmpty() && !n.children.get(0).containsUnsupported()
                            && !test(n.children.get(0), s, reg);
                    case ITEM -> {
                        Item item = item(n);
                        yield item != null && s.is(item);
                    }
                    case ITEM_TAG -> s.is(tag(n));
                    case MOD -> BuiltInRegistries.ITEM.getKey(s.getItem()).getNamespace().equals(n.argument);
                    case BLOCK -> s.getItem() instanceof BlockItem && s.getItem() != Items.AIR;
                    case COMPONENT -> component(n, s, reg);
                    case STACK_SIZE -> compare(n, s.getCount());
                    case MAX_STACK_SIZE -> compare(n, s.getMaxStackSize());
                    case DURABILITY -> {
                        int max = s.getMaxDamage();
                        int left = max == 0 ? 0 : max - s.getDamageValue();
                        yield compare(n, max != 0 && n.percent ? (int) (left * 100L / max) : left);
                    }
                    case FOOD_VALUE -> {
                        FoodProperties food = s.get(DataComponents.FOOD);
                        yield compare(n, food == null ? 0 : food.nutrition());
                    }
                    case EXPRESSION -> test(n.children.get(0), s, reg);
                    case CUSTOM, UNKNOWN -> false;
                };
            } catch (RuntimeException e) {
                return false;
            }
        }

        static boolean compare(Node n, int v) {
            return switch (n.op) {
                case "=" -> v == n.value;
                case "!=" -> v != n.value;
                case ">" -> v > n.value;
                case "<" -> v < n.value;
                case "<=" -> v <= n.value;
                case ">=" -> v >= n.value;
                default -> false;
            };
        }

        static Item item(Node n) {
            Object r = n.resolved;
            if (r == null) {
                ResourceLocation id = ResourceLocation.tryParse(n.argument);
                Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
                r = item == null || item == Items.AIR ? Boolean.FALSE : item;
                n.resolved = r;
            }
            return r instanceof Item i ? i : null;
        }

        @SuppressWarnings("unchecked")
        static TagKey<Item> tag(Node n) {
            Object r = n.resolved;
            if (r == null) {
                r = TagKey.create(Registries.ITEM, ResourceLocation.parse(n.argument));
                n.resolved = r;
            }
            return (TagKey<Item>) r;
        }

        static CompoundTag componentTag(Node n) {
            Object r = n.resolved;
            if (r == null) {
                String d = n.data;
                // FTB Filter System wraps the SNBT in braces unless it starts or ends with one
                if (!d.startsWith("{") && !d.endsWith("}")) {
                    d = "{" + d + "}";
                }
                try {
                    r = TagParser.parseTag(d);
                } catch (Exception e) {
                    r = Boolean.FALSE;
                }
                n.resolved = r;
            }
            return r instanceof CompoundTag t ? t : null;
        }

        /**
         * fuzzy: every listed component is present and equal (as FTB Filter System, a stack without any component
         * changes only matches an empty list). strict: additionally, the stack adds no component beyond the listed.
         */
        static boolean component(Node n, ItemStack s, HolderLookup.Provider reg) {
            CompoundTag want = componentTag(n);
            if (want == null) {
                return false;
            }
            DataComponentPatch patch = s.getComponentsPatch();
            if (patch.isEmpty()) {
                return want.isEmpty();
            }
            DynamicOps<Tag> ops = reg != null ? reg.createSerializationContext(NbtOps.INSTANCE) : NbtOps.INSTANCE;
            for (String key : want.getAllKeys()) {
                ResourceLocation id = ResourceLocation.tryParse(key);
                DataComponentType<?> type = id == null ? null : BuiltInRegistries.DATA_COMPONENT_TYPE.get(id);
                if (type == null || !matches(type, want.get(key), s, ops)) {
                    return false;
                }
            }
            if (n.strict) {
                for (Map.Entry<DataComponentType<?>, Optional<?>> e : patch.entrySet()) {
                    ResourceLocation id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(e.getKey());
                    if (e.getValue().isPresent() && (id == null || !want.contains(id.toString()))) {
                        return false;
                    }
                }
            }
            return true;
        }

        static <T> boolean matches(DataComponentType<T> type, Tag wanted, ItemStack s, DynamicOps<Tag> ops) {
            T actual = s.get(type);
            Codec<T> codec = type.codec();
            if (actual == null || codec == null) {
                return false;
            }
            Optional<T> decoded = codec.parse(ops, wanted).result();
            if (decoded.isPresent()) {
                return decoded.get().equals(actual);
            }
            // the value could not be decoded (e.g. it needs registries we lack): compare the stack's encoded form
            Optional<Tag> encoded = codec.encodeStart(ops, actual).result();
            return encoded.isPresent() && NbtUtils.compareNbt(wanted, encoded.get(), true);
        }

        static List<ItemStack> examples(Node root) {
            Set<Item> items = new LinkedHashSet<>();
            collect(root, items);
            List<ItemStack> out = new ArrayList<>(items.size());
            for (Item i : items) {
                out.add(new ItemStack(i));
            }
            return List.copyOf(out);
        }

        static void collect(Node n, Set<Item> out) {
            if (out.size() >= MAX_EXAMPLES) {
                return;
            }
            try {
                switch (n.kind) {
                    case OR -> {
                        for (Node c : n.children) {
                            collect(c, out);
                        }
                    }
                    case ONLY_ONE -> {
                        Set<Item> all = new LinkedHashSet<>();
                        for (Node c : n.children) {
                            collect(c, all);
                        }
                        keepPassing(n, all, out, false);
                    }
                    case AND -> {
                        for (Node c : n.children) {
                            if (c.kind == Node.Kind.NOT || c.containsUnsupported()) {
                                continue;
                            }
                            Set<Item> candidates = new LinkedHashSet<>();
                            collect(c, candidates);
                            if (!candidates.isEmpty()) {
                                keepPassing(n, candidates, out, true);
                                return;
                            }
                        }
                    }
                    case EXPRESSION -> collect(n.children.get(0), out);
                    case ITEM -> {
                        Item item = item(n);
                        if (item != null) {
                            out.add(item);
                        }
                    }
                    case ITEM_TAG -> BuiltInRegistries.ITEM.getTag(tag(n)).ifPresent(set -> {
                        for (Holder<Item> h : set) {
                            if (out.size() >= MAX_EXAMPLES) {
                                break;
                            }
                            if (h.value() != Items.AIR) {
                                out.add(h.value());
                            }
                        }
                    });
                    case MOD -> {
                        for (Item item : modItems(n)) {
                            if (out.size() >= MAX_EXAMPLES) {
                                break;
                            }
                            out.add(item);
                        }
                    }
                    default -> {
                        // block(), component(), comparisons, not(), unknown: no item list to derive
                    }
                }
            } catch (RuntimeException e) {
                // keep what was collected so far
            }
        }

        static void keepPassing(Node n, Set<Item> candidates, Set<Item> out, boolean fallBackToAll) {
            List<Item> passing = new ArrayList<>();
            for (Item i : candidates) {
                if (test(n, new ItemStack(i), null)) {
                    passing.add(i);
                }
            }
            for (Item i : passing.isEmpty() && fallBackToAll ? candidates : passing) {
                if (out.size() >= MAX_EXAMPLES) {
                    break;
                }
                out.add(i);
            }
        }

        @SuppressWarnings("unchecked")
        static List<Item> modItems(Node n) {
            Object r = n.resolved;
            if (r == null) {
                List<Item> items = new ArrayList<>();
                for (Item item : BuiltInRegistries.ITEM) {
                    if (items.size() >= MAX_EXAMPLES) {
                        break;
                    }
                    if (item != Items.AIR && BuiltInRegistries.ITEM.getKey(item).getNamespace().equals(n.argument)) {
                        items.add(item);
                    }
                }
                r = List.copyOf(items);
                n.resolved = r;
            }
            return (List<Item>) r;
        }
    }
}
