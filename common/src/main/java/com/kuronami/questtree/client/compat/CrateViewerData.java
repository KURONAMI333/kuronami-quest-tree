package com.kuronami.questtree.client.compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kuronami.questtree.Constants;
import com.kuronami.questtree.client.ClientProgress;
import com.kuronami.questtree.item.ModItems;
import com.kuronami.questtree.progress.CrateOdds;
import com.kuronami.questtree.progress.LootCrateRules;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Builds the crate entries the three recipe viewers show: one crate, its table, and every item it can give. */
public final class CrateViewerData {

    private CrateViewerData() {
    }

    /** One reward the crate can give, with the chance one crate grants it at least once (0..1). */
    public record Content(ItemStack stack, double chance) {
    }

    /** One crate as a viewer sees it; the stack carries the table's name and glow already. */
    public record Recipe(String tableId, String stringId, ItemStack crate, List<Content> contents) {

        public Recipe {
            contents = List.copyOf(contents);
        }

        public ResourceLocation id() {
            String path = tableId.toLowerCase(java.util.Locale.ROOT)
                    .replace(':', '/').replaceAll("[^a-z0-9_./-]", "_");
            return ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "crate/" + path);
        }

        public double chance(int index) {
            return index < 0 || index >= contents.size() ? 0 : contents.get(index).chance();
        }

        public boolean matches(ItemStack stack) {
            if (stack.isEmpty()) {
                return false;
            }
            if (ItemStack.isSameItemSameComponents(crate, stack)) {
                return true;
            }
            for (Content content : contents) {
                if (ItemStack.isSameItemSameComponents(content.stack(), stack)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Every crate in the synced bundle, in table order. Called when a viewer asks, not on every packet. */
    public static List<Recipe> all() {
        JsonObject tables = ClientProgress.rawTables();
        List<Recipe> out = new ArrayList<>();
        for (var e : tables.entrySet()) {
            if (!e.getValue().isJsonObject()) {
                continue;
            }
            JsonObject table = e.getValue().getAsJsonObject();
            LootCrateRules.Crate crate = LootCrateRules.crate(e.getKey(), table).orElse(null);
            if (crate == null) {
                continue;
            }
            ItemStack crateStack = ModItems.lootCrate(e.getKey(), crate);
            if (crateStack.isEmpty()) {
                continue;
            }
            out.add(new Recipe(e.getKey(), crate.stringId(), crateStack, contentsOf(table)));
        }
        return out;
    }

    public static List<Recipe> matching(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return List.of();
        }
        return all().stream().filter(recipe -> recipe.matches(stack)).toList();
    }

    /**
     * The chance one opening of {@code tableId}'s crate gives {@code stack}, or {@link Double#NaN} when the table is
     * unknown or does not list it.
     */
    public static double chanceOf(String tableId, ItemStack stack) {
        JsonObject tables = ClientProgress.rawTables();
        if (tableId == null || !tables.has(tableId) || !tables.get(tableId).isJsonObject()) {
            return Double.NaN;
        }
        for (Content content : contentsOf(tables.getAsJsonObject(tableId))) {
            if (ItemStack.isSameItemSameComponents(content.stack(), stack)) {
                return content.chance();
            }
        }
        return Double.NaN;
    }

    private static List<Content> contentsOf(JsonObject table) {
        JsonElement entriesElement = table.get("entries");
        if (entriesElement == null || !entriesElement.isJsonArray()) {
            return List.of();
        }
        int rolls = (int) number(table, "rolls", 1);
        double empty = Math.max(0, number(table, "empty_weight", 0));
        JsonArray entries = entriesElement.getAsJsonArray();
        List<ItemStack> stacks = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        List<int[]> entryStacks = new ArrayList<>();
        for (JsonElement entry : entries) {
            if (!entry.isJsonObject()) {
                continue;
            }
            JsonObject row = entry.getAsJsonObject();
            // LootCrates.roll only rolls entries with a reward object
            if (!row.has("reward") || !row.get("reward").isJsonObject()) {
                continue;
            }
            List<ItemStack> given = QuestViewerRecipe.stacksOfReward(row.getAsJsonObject("reward"));
            if (given.isEmpty()) {
                continue;
            }
            List<Integer> indices = new ArrayList<>();
            for (ItemStack stack : given) {
                int found = indexOf(stacks, stack);
                if (found < 0) {
                    found = stacks.size();
                    stacks.add(stack);
                }
                if (!indices.contains(found)) {
                    indices.add(found);
                }
            }
            weights.add(number(row, "weight", 1));
            entryStacks.add(indices.stream().mapToInt(Integer::intValue).toArray());
        }
        if (stacks.isEmpty()) {
            return List.of();
        }
        double total = empty;
        for (double weight : weights) {
            total += Math.max(0, weight);
        }
        List<Content> out = new ArrayList<>();
        for (int i = 0; i < stacks.size(); i++) {
            double weight = 0;
            boolean always = false;
            for (int e = 0; e < weights.size(); e++) {
                if (!has(entryStacks.get(e), i)) {
                    continue;
                }
                double entryWeight = weights.get(e);
                if (entryWeight == 0 || total <= 0) {
                    always = true;
                } else if (entryWeight > 0) {
                    weight += entryWeight;
                }
            }
            out.add(new Content(stacks.get(i), always ? 1 : CrateOdds.chance(weight, total, rolls)));
        }
        return out;
    }

    private static boolean has(int[] indices, int index) {
        for (int i : indices) {
            if (i == index) {
                return true;
            }
        }
        return false;
    }

    private static int indexOf(List<ItemStack> stacks, ItemStack stack) {
        for (int i = 0; i < stacks.size(); i++) {
            if (ItemStack.isSameItemSameComponents(stacks.get(i), stack)) {
                return i;
            }
        }
        return -1;
    }

    private static double number(JsonObject o, String key, double fallback) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()
                ? e.getAsDouble() : fallback;
    }
}
