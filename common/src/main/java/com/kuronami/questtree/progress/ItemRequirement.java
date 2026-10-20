package com.kuronami.questtree.progress;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;

/**
 * What one item task accepts: an item id, an item tag, an FTB Filter System smart filter, plus the optional component
 * check from FTB Quests' {@code match_components}. The importer wrote that field since the first port but nothing read
 * it, so a hand-in of the right item with the wrong NBT still counted (2026-10-20).
 * <p>
 * FTB Quests' three modes are kept as they are: {@code none} ignores components, {@code fuzzy} requires every component
 * written in the task to be present and equal, and {@code strict} requires the stack's whole component map to be equal
 * (it also ignores the count, as {@link ItemStack#isSameItemSameComponents} does).
 */
public final class ItemRequirement {

    public enum Match {
        NONE, FUZZY, STRICT;

        static Match of(JsonObject task) {
            JsonElement v = task.get("match_components");
            if (v == null || !v.isJsonPrimitive()) {
                // 1.20.1 and older wrote two booleans instead of the enum
                v = task.get("match_nbt");
            }
            if (v == null || !v.isJsonPrimitive()) {
                return NONE;
            }
            JsonPrimitive p = v.getAsJsonPrimitive();
            if (p.isBoolean()) {
                if (!p.getAsBoolean()) {
                    return NONE;
                }
                return task.has("weak_nbt_match") && task.get("weak_nbt_match").getAsBoolean() ? FUZZY : STRICT;
            }
            return switch (p.getAsString().toLowerCase(Locale.ROOT)) {
                case "fuzzy" -> FUZZY;
                case "strict" -> STRICT;
                default -> NONE;
            };
        }
    }

    private final String spec;
    private final Match match;
    private final JsonObject components;
    private final JsonObject itemJson;

    private ItemRequirement(String spec, Match match, JsonObject components, JsonObject itemJson) {
        this.spec = spec;
        this.match = match;
        this.components = components;
        this.itemJson = itemJson;
    }

    /** The requirement of an item task. */
    public static ItemRequirement of(JsonObject task) {
        JsonElement it = task.get("item");
        if (it == null || it.isJsonNull()) {
            return new ItemRequirement("", Match.NONE, null, null);
        }
        if (it.isJsonPrimitive()) {
            return new ItemRequirement(it.getAsString(), Match.NONE, null, null);
        }
        if (!it.isJsonObject()) {
            return new ItemRequirement("", Match.NONE, null, null);
        }
        JsonObject o = it.getAsJsonObject();
        String filter = ItemFilter.filterExpression(o);
        String spec = filter != null ? "~" + filter
                : o.has("tag") ? "#" + o.get("tag").getAsString()
                : o.has("id") ? o.get("id").getAsString() : "";
        JsonObject comps = o.has("components") && o.get("components").isJsonObject()
                ? o.getAsJsonObject("components") : null;
        return new ItemRequirement(spec, Match.of(task), comps, o);
    }

    /** The matcher as it was before components were considered (an id, {@code #tag}, or {@code ~filter}). */
    public String spec() {
        return spec;
    }

    public Match match() {
        return match;
    }

    /** A stable key for caches: two tasks with different components must not share a counted amount. */
    public String key() {
        if (components == null || components.isEmpty() || match == Match.NONE) {
            return spec;
        }
        return spec + "|" + match.name() + "|" + components;
    }

    public boolean matches(ItemStack stack, HolderLookup.Provider registries) {
        if (stack == null || stack.isEmpty() || spec.isEmpty()) {
            return false;
        }
        if (!baseMatches(stack, registries)) {
            return false;
        }
        // FTB Filter System owns the matching of its filter item; its components are the expression, not a requirement
        if (spec.startsWith("~") || match == Match.NONE) {
            return true;
        }
        // A task with no components still means "the vanilla item", defaults included: strict and fuzzy both compare
        // the expected stack's components, so skipping that check let a damaged or renamed item through (2026-10-20).
        ItemStack expected = expectedStack(registries);
        if (expected.isEmpty()) {
            // a tag or an unreadable item has no stack to build; treat it as "no components"
            return stack.getComponents().isEmpty();
        }
        if (match == Match.STRICT) {
            return ItemStack.isSameItemSameComponents(expected, stack);
        }
        var wanted = expected.getComponents();
        var actual = stack.getComponents();
        return wanted.stream().allMatch(c -> actual.has(c.type()) && actual.get(c.type()).equals(c.value()));
    }

    private boolean baseMatches(ItemStack stack, HolderLookup.Provider registries) {
        if (spec.startsWith("~")) {
            return ItemFilter.parse(spec.substring(1)).test(stack, registries);
        }
        if (spec.startsWith("#")) {
            ResourceLocation id = ResourceLocation.tryParse(spec.substring(1));
            return id != null && stack.is(TagKey.create(Registries.ITEM, id));
        }
        ResourceLocation id = ResourceLocation.tryParse(spec);
        if (id == null) {
            return false;
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        return item != net.minecraft.world.item.Items.AIR && stack.is(item);
    }

    /**
     * The task's item with its components, built through the vanilla item codec. Empty when the pack wrote a component
     * this game version cannot read; such a task then only passes if it asks for no components.
     */
    private ItemStack expectedStack(HolderLookup.Provider registries) {
        if (itemJson == null) {
            return ItemStack.EMPTY;
        }
        try {
            JsonObject json = itemJson.deepCopy();
            json.remove("count");
            json.addProperty("count", 1);
            DynamicOps<JsonElement> ops = registries != null
                    ? registries.createSerializationContext(JsonOps.INSTANCE) : JsonOps.INSTANCE;
            return ItemStack.CODEC.parse(ops, json).result().orElse(ItemStack.EMPTY);
        } catch (RuntimeException e) {
            return ItemStack.EMPTY;
        }
    }
}
