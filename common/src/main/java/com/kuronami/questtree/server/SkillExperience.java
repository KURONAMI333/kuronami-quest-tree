package com.kuronami.questtree.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kuronami.questtree.Constants;
import com.kuronami.questtree.data.ProgressModel;
import com.kuronami.questtree.points.Expression;
import com.kuronami.questtree.progress.AntiFarming;
import com.kuronami.questtree.progress.PlayerProgress;
import com.kuronami.questtree.progress.ProgressRules;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Experience sources of skill chapters, in Pufferfish's Skills' own format so imported trees keep their numbers:
 * <pre>{"type": "puffish_skills:mine_block", "data": {
 *    "variables": {"is_ripe": {"operations": [{"type": "get_mined_block_state"},
 *                                             {"type": "puffish_skills:test", "data": {"block": "#minecraft:crops", "state": {"age": "7"}}}]}},
 *    "experience": [{"condition": "is_ripe", "expression": "1"}]}}</pre>
 * A variable is a chain of operations over game objects; the first operation reads from the event ("get_mined_block_state"),
 * later ones convert or test. An operation this class does not know makes the variable 0 (no experience rather than
 * wrong experience) and is logged once, so pack authors see what to change.
 */
public final class SkillExperience {

    private static final Set<String> WARNED = new HashSet<>();
    private static final Map<String, Expression> PARSED = new HashMap<>();
    /** Anti-farming windows of every source; runtime state, cleared when the server stops (2026-10-20). */
    private static final AntiFarming ANTI_FARMING = new AntiFarming();
    /** When the windows were last swept; sweeping every award walks every source of every player (2026-10-22 review). */
    private static long antiFarmingCleaned;

    private SkillExperience() {
    }

    // ------------------------------------------------------------------ event entry points

    public static void onMine(ServerPlayer player, BlockState state) {
        Map<String, Object> ctx = base(player);
        ctx.put("mined_block_state", state);
        ctx.put("tool_item_stack", player.getMainHandItem());
        award(player, "mine_block", ctx);
    }

    static void onKill(ServerPlayer player, PlayerProgress p, Entity killed) {
        if (!(killed instanceof LivingEntity living)) {
            return;
        }
        Map<String, Object> ctx = base(player);
        ctx.put("killed_living_entity", living);
        ctx.put("weapon_item_stack", player.getMainHandItem());
        ctx.put("damage_source", living.getLastDamageSource());
        ctx.put("dropped_experience", (double) living.getExperienceReward(player.serverLevel(), player));
        award(player, "kill_entity", ctx);
    }

    public static void onCraft(ServerPlayer player, ItemStack crafted) {
        Map<String, Object> ctx = base(player);
        ctx.put("crafted_item_stack", crafted);
        award(player, "craft_item", ctx);
    }

    /** An enchanting table result, as Pufferfish's Skills' {@code enchant_item} source (levels spent included). */
    public static void onEnchant(ServerPlayer player, ItemStack enchanted, int levels) {
        Map<String, Object> ctx = base(player);
        ctx.put("enchanted_item_stack", enchanted);
        ctx.put("levels", (double) levels);
        award(player, "enchant_item", ctx);
    }

    public static void onSmelt(ServerPlayer player, ItemStack smelted) {
        Map<String, Object> ctx = base(player);
        ctx.put("smelted_item_stack", smelted);
        award(player, "smelt_item", ctx);
    }

    public static void onEat(ServerPlayer player, ItemStack food) {
        Map<String, Object> ctx = base(player);
        ctx.put("eaten_item_stack", food);
        award(player, "eat_food", ctx);
    }

    public static void onFish(ServerPlayer player, ItemStack caught) {
        Map<String, Object> ctx = base(player);
        ctx.put("fished_item_stack", caught);
        ctx.put("tool_item_stack", player.getMainHandItem());
        award(player, "fish_item", ctx);
    }

    public static void onTakeDamage(ServerPlayer player, DamageSource source, float amount) {
        Map<String, Object> ctx = base(player);
        ctx.put("damage_source", source);
        ctx.put("taken_damage", (double) amount);
        ctx.put("weapon_item_stack", source.getWeaponItem() != null ? source.getWeaponItem() : ItemStack.EMPTY);
        award(player, "take_damage", ctx);
    }

    public static void onDealDamage(ServerPlayer player, LivingEntity target, DamageSource source, float amount) {
        Map<String, Object> ctx = base(player);
        ctx.put("damaged_living_entity", target);
        ctx.put("damage_source", source);
        ctx.put("dealt_damage", (double) amount);
        ctx.put("weapon_item_stack", player.getMainHandItem());
        award(player, "deal_damage", ctx);
    }

    public static void onHeal(ServerPlayer player, float amount) {
        Map<String, Object> ctx = base(player);
        ctx.put("heal_amount", (double) amount);
        award(player, "heal", ctx);
    }

    public static void onStat(ServerPlayer player, net.minecraft.stats.Stat<?> stat, int amount) {
        // statistics change every tick while moving; skip before allocating anything when no chapter uses them
        if (!usedSources(ProgressService.rules().model()).contains("increase_stat")) {
            return;
        }
        Map<String, Object> ctx = base(player);
        ctx.put("stat", stat);
        ctx.put("increase_amount", (double) amount);
        award(player, "increase_stat", ctx);
    }

    private static Map<String, Object> base(ServerPlayer player) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("player", player);
        return ctx;
    }

    // ------------------------------------------------------------------ evaluation

    private static void award(ServerPlayer player, String source, Map<String, Object> ctx) {
        var rules = ProgressService.rules();
        if (!usedSources(rules.model()).contains(source)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - antiFarmingCleaned >= 1000L) {
            antiFarmingCleaned = now;
            ANTI_FARMING.removeOutdated(now);
        }
        PlayerProgress p = null;
        for (ProgressModel.Chapter c : rules.model().chapters.values()) {
            if (!c.isSkills() || !c.points.has("sources") || !c.points.get("sources").isJsonArray()) {
                continue;
            }
            if (c.points.has("experience_enabled") && !c.points.get("experience_enabled").getAsBoolean()) {
                continue;
            }
            PlayerProgress progress = ProgressService.progress(player);
            if (!ProgressRules.chapterUnlocked(progress, c)) {
                continue;
            }
            // stop_when_maxed: at the cap, do not read the event or walk the sources at all
            if (ProgressModel.boolOf(c.points, "stop_when_maxed") && progress.level.getOrDefault(
                    ProgressRules.key(c), 0) >= intOf(c.points, "level_limit", Integer.MAX_VALUE)) {
                continue;
            }
            double total = 0;
            JsonArray sources = c.points.getAsJsonArray("sources");
            for (int index = 0; index < sources.size(); index++) {
                JsonElement e = sources.get(index);
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject s = e.getAsJsonObject();
                String type = ProgressService.str(s, "type");
                if (!strip(type).equals(source)) {
                    continue;
                }
                JsonObject data = s.has("data") && s.get("data").isJsonObject() ? s.getAsJsonObject("data") : s;
                // each entry counts on its own, as Pufferfish's Skills keeps one anti-farming state per source
                total += sourceAmount(c.id, index, source, data, ctx);
            }
            if (total > 0) {
                if (p == null) {
                    p = ProgressService.progress(player);
                }
                int levels = rules.addExperience(p, c, total);
                ProgressStore.get(player.server).setDirty();
                ProgressService.markExperienceDirty(player);
                if (levels > 0) {
                    ProgressService.event(player, p, "level", c.id + "/");
                }
            }
        }
    }

    private static ProgressModel sourcesFor;
    private static Set<String> used = Set.of();

    /** Source types any chapter uses; most events (stats change every tick while walking) are skipped by this check. */
    private static Set<String> usedSources(ProgressModel m) {
        if (m != sourcesFor) {
            Set<String> s = new HashSet<>();
            for (ProgressModel.Chapter c : m.chapters.values()) {
                if (c.isSkills() && c.points.has("sources") && c.points.get("sources").isJsonArray()) {
                    for (JsonElement e : c.points.getAsJsonArray("sources")) {
                        if (e.isJsonObject()) {
                            s.add(strip(ProgressService.str(e.getAsJsonObject(), "type")));
                        }
                    }
                }
            }
            used = s;
            sourcesFor = m;
        }
        return used;
    }

    /**
     * Pufferfish's Skills anti-farming guards, applied before the expression reads the event (2026-10-20):
     * {@code anti_farming_per_chunk} allows only N events from one chunk until their timers run out, and
     * {@code anti_farming_per_entity} caps the damage counted against one mob in the same window. Books made before
     * the split wrote the same guard as {@code anti_farming} (per chunk for kills, per entity for damage).
     */
    private static double sourceAmount(String where, int index, String source, JsonObject data, Map<String, Object> ctx) {
        if (!source.equals("kill_entity") && !source.equals("deal_damage")) {
            return amount(where, data, ctx);
        }
        Object targetValue = ctx.get(source.equals("kill_entity") ? "killed_living_entity" : "damaged_living_entity");
        if (!(targetValue instanceof Entity target)) {
            return amount(where, data, ctx);
        }
        JsonObject chunk = object(data, "anti_farming_per_chunk");
        JsonObject entity = source.equals("deal_damage") ? object(data, "anti_farming_per_entity") : null;
        if (source.equals("kill_entity") && chunk == null) {
            chunk = object(data, "anti_farming");
        }
        if (source.equals("deal_damage") && entity == null) {
            entity = object(data, "anti_farming");
        }
        if (chunk != null && chunk.has("enabled") && !chunk.get("enabled").getAsBoolean()) {
            chunk = null;
        }
        if (chunk != null) {
            int limit = intOf(chunk, "limit_per_chunk", -1);
            int reset = intOf(chunk, "reset_after_seconds", -1);
            if (limit >= 0 && reset >= 0 && !ANTI_FARMING.allowInChunk(where + "#" + index, limit, reset,
                    target.level().dimension().location() + "@" + target.chunkPosition().toLong(), System.currentTimeMillis())) {
                return 0;
            }
        }
        Map<String, Object> event = ctx;
        if (entity != null) {
            float limit = floatOf(entity, "limit_per_entity", -1);
            int reset = intOf(entity, "reset_after_seconds", -1);
            if (limit >= 0 && reset >= 0) {
                float damage = ctx.get("dealt_damage") instanceof Number n ? n.floatValue() : 0f;
                float allowed = ANTI_FARMING.limitDamage(where + "#" + index, limit, reset,
                        target.getUUID().toString(), damage, System.currentTimeMillis());
                if (allowed <= 1.0E-6f) {
                    return 0;
                }
                event = new HashMap<>(ctx);
                event.put("dealt_damage", (double) allowed);
            }
        }
        return amount(where, data, event);
    }

    /** Server stopping: anti-farming windows do not carry over into the next server run. */
    public static void clearState() {
        ANTI_FARMING.clear();
        antiFarmingCleaned = 0L;
        // parsed expressions and one-time warnings are caches of the loaded data, not player state
        PARSED.clear();
        WARNED.clear();
    }

    private static JsonObject object(JsonObject data, String key) {
        return data.has(key) && data.get(key).isJsonObject() ? data.getAsJsonObject(key) : null;
    }

    private static int intOf(JsonObject o, String key, int fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : fallback;
    }

    private static float floatOf(JsonObject o, String key, float fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsFloat() : fallback;
    }

    /** Experience for one event: evaluates the variables, then the expression (or the first case whose condition holds). */
    static double amount(String where, JsonObject data, Map<String, Object> ctx) {
        Map<String, Double> vars = new HashMap<>();
        if (data.has("variables") && data.get("variables").isJsonObject()) {
            for (Map.Entry<String, JsonElement> v : data.getAsJsonObject("variables").entrySet()) {
                vars.put(v.getKey(), variable(where, v.getValue(), ctx));
            }
        }
        JsonElement exp = data.get("experience");
        if (exp == null) {
            return 0;
        }
        try {
            if (exp.isJsonArray()) {
                for (JsonElement ce : exp.getAsJsonArray()) {
                    JsonObject cs = ce.getAsJsonObject();
                    String cond = cs.has("condition") ? cs.get("condition").getAsString() : "true";
                    if (cond.equals("true") || eval(cond, vars) != 0) {
                        return Math.max(0, eval(cs.get("expression").getAsString(), vars));
                    }
                }
                return 0;
            }
            return Math.max(0, eval(exp.getAsString(), vars));
        } catch (RuntimeException ex) {
            warn(where, "expression", ex.getMessage());
            return 0;
        }
    }

    private static double eval(String expr, Map<String, Double> vars) {
        Expression e = PARSED.computeIfAbsent(expr, Expression::parse);
        return e.eval(vars);
    }

    private static double variable(String where, JsonElement def, Map<String, Object> ctx) {
        if (!def.isJsonObject() || !def.getAsJsonObject().has("operations")) {
            warn(where, "variable", def.toString());
            return 0;
        }
        Object value = null;
        for (JsonElement oe : def.getAsJsonObject().getAsJsonArray("operations")) {
            JsonObject op = oe.getAsJsonObject();
            String type = strip(ProgressService.str(op, "type"));
            JsonObject data = op.has("data") && op.get("data").isJsonObject() ? op.getAsJsonObject("data") : new JsonObject();
            value = apply(where, type, data, value, ctx);
            if (value == null) {
                return 0;
            }
        }
        return number(value);
    }

    /** One operation. Returns null when it cannot be applied to the value. */
    private static Object apply(String where, String type, JsonObject data, Object v, Map<String, Object> ctx) {
        if (type.startsWith("get_") && ctx.containsKey(type.substring(4))) {
            return ctx.get(type.substring(4));
        }
        switch (type) {
            case "test":
                return test(where, data, v) ? 1.0 : 0.0;
            case "get_block":
                return v instanceof BlockState s ? s.getBlock() : null;
            case "get_item":
                return v instanceof ItemStack s ? s.getItem() : null;
            case "get_count":
                return v instanceof ItemStack s ? (double) s.getCount() : null;
            case "get_type":
                return v instanceof Entity e ? e.getType() : null;
            case "as_living_entity":
                return v instanceof LivingEntity l ? l : null;
            case "as_entity":
                return v instanceof Entity e ? e : null;
            case "get_health":
                return v instanceof LivingEntity l ? (double) l.getHealth() : null;
            case "get_max_health":
                return v instanceof LivingEntity l ? (double) l.getMaxHealth() : null;
            case "get_hardness":
                return v instanceof BlockState s ? (double) s.getBlock().defaultDestroyTime() : null;
            case "get_blast_resistance":
                return v instanceof BlockState s ? (double) s.getBlock().getExplosionResistance() : null;
            case "get_attacker":
                return v instanceof DamageSource d ? d.getEntity() : null;
            case "get_nutrition", "get_saturation_modifier": {
                if (!(v instanceof ItemStack s)) {
                    return null;
                }
                FoodProperties f = s.get(DataComponents.FOOD);
                if (f == null) {
                    return 0.0;
                }
                return type.equals("get_nutrition") ? (double) f.nutrition() : (double) f.saturation();
            }
            case "get_world":
                return v instanceof Entity e ? e.level() : null;
            case "get_time_of_day":
                return v instanceof net.minecraft.world.level.Level l ? (double) (l.getDayTime() % 24000L) : null;
            default:
                warn(where, "operation", type);
                return null;
        }
    }

    /** The "test" operation: matches a block state, item, entity or damage source against an id, #tag and state. */
    private static boolean test(String where, JsonObject d, Object v) {
        if (v instanceof BlockState s) {
            if (d.has("block") && !matches(d.get("block").getAsString(), s.getBlock().builtInRegistryHolder().key().location(),
                    tag -> s.is(TagKey.create(Registries.BLOCK, tag)))) {
                return false;
            }
            if (d.has("state") && d.get("state").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : d.getAsJsonObject("state").entrySet()) {
                    Property<?> prop = s.getBlock().getStateDefinition().getProperty(e.getKey());
                    if (prop == null || !valueName(s, prop).equals(e.getValue().getAsString())) {
                        return false;
                    }
                }
            }
            return true;
        }
        if (v instanceof Block b) {
            return !d.has("block") || matches(d.get("block").getAsString(), BuiltInRegistries.BLOCK.getKey(b),
                    tag -> b.builtInRegistryHolder().is(TagKey.create(Registries.BLOCK, tag)));
        }
        if (v instanceof ItemStack st) {
            return !d.has("item") || matches(d.get("item").getAsString(), BuiltInRegistries.ITEM.getKey(st.getItem()),
                    tag -> st.is(TagKey.create(Registries.ITEM, tag)));
        }
        if (v instanceof Item it) {
            return !d.has("item") || matches(d.get("item").getAsString(), BuiltInRegistries.ITEM.getKey(it),
                    tag -> it.builtInRegistryHolder().is(TagKey.create(Registries.ITEM, tag)));
        }
        EntityType<?> type = v instanceof Entity e ? e.getType() : v instanceof EntityType<?> t ? t : null;
        if (type != null) {
            String spec = d.has("entity") ? d.get("entity").getAsString() : d.has("entity_type") ? d.get("entity_type").getAsString()
                    : d.has("type") ? d.get("type").getAsString() : "";
            return spec.isEmpty() || ProgressService.entityMatches(spec, type);
        }
        if (v instanceof DamageSource ds) {
            String spec = d.has("damage") ? d.get("damage").getAsString() : d.has("damage_type") ? d.get("damage_type").getAsString() : "";
            return spec.isEmpty() || matches(spec, ds.typeHolder().unwrapKey().map(k -> k.location()).orElse(null),
                    tag -> ds.is(TagKey.create(Registries.DAMAGE_TYPE, tag)));
        }
        if (v instanceof net.minecraft.stats.Stat<?> st) {
            // accepts the vanilla stat name ("minecraft.custom:minecraft.jump") or "<type>:<value>" ids
            String spec = d.has("stat") ? d.get("stat").getAsString() : "";
            return spec.isEmpty() || spec.equals(st.getName()) || spec.equals(statId(st));
        }
        warn(where, "test on", v == null ? "nothing" : v.getClass().getSimpleName());
        return false;
    }

    private static <T> String statId(net.minecraft.stats.Stat<T> st) {
        var type = BuiltInRegistries.STAT_TYPE.getKey(st.getType());
        var value = st.getType().getRegistry().getKey(st.getValue());
        return type + ":" + value;
    }

    private static <T extends Comparable<T>> String valueName(BlockState s, Property<T> prop) {
        return prop.getName(s.getValue(prop));
    }

    private static boolean matches(String spec, ResourceLocation id, java.util.function.Predicate<ResourceLocation> inTag) {
        if (spec.startsWith("#")) {
            ResourceLocation tag = ResourceLocation.tryParse(spec.substring(1));
            return tag != null && inTag.test(tag);
        }
        return id != null && id.toString().equals(spec.contains(":") ? spec : "minecraft:" + spec);
    }

    private static double number(Object v) {
        if (v instanceof Double d) {
            return d;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof Boolean b) {
            return b ? 1 : 0;
        }
        // an object left at the end of a chain counts as "present"
        return 1;
    }

    private static String strip(String type) {
        int colon = type.indexOf(':');
        return colon < 0 ? type : type.substring(colon + 1);
    }

    private static void warn(String where, String what, String detail) {
        if (WARNED.add(where + what + detail)) {
            Constants.LOG.warn("Skill chapter {}: unsupported {} '{}' (gives no experience)", where, what, detail);
        }
    }
}
