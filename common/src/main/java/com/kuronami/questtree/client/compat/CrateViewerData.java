package com.kuronami.questtree.client.compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kuronami.questtree.Constants;
import com.kuronami.questtree.client.ClientProgress;
import com.kuronami.questtree.item.ModItems;
import com.kuronami.questtree.progress.LootCrateRules;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Builds the crate entries the three recipe viewers show: one crate, its table, and every item it can give. */
public final class CrateViewerData {

    private CrateViewerData() {
    }

    /** One crate as a viewer sees it; the stack carries the table's name and glow already. */
    public record Recipe(String tableId, String stringId, ItemStack crate, List<ItemStack> contents) {

        public Recipe {
            contents = List.copyOf(contents);
        }

        public ResourceLocation id() {
            String path = tableId.toLowerCase(java.util.Locale.ROOT)
                    .replace(':', '/').replaceAll("[^a-z0-9_./-]", "_");
            return ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "crate/" + path);
        }

        public boolean matches(ItemStack stack) {
            if (stack.isEmpty()) {
                return false;
            }
            if (ItemStack.isSameItemSameComponents(crate, stack)) {
                return true;
            }
            for (ItemStack content : contents) {
                if (ItemStack.isSameItemSameComponents(content, stack)) {
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

    private static List<ItemStack> contentsOf(JsonObject table) {
        JsonElement entriesElement = table.get("entries");
        if (entriesElement == null || !entriesElement.isJsonArray()) {
            return List.of();
        }
        JsonArray entries = entriesElement.getAsJsonArray();
        List<ItemStack> out = new ArrayList<>();
        for (JsonElement entry : entries) {
            if (!entry.isJsonObject()) {
                continue;
            }
            JsonObject row = entry.getAsJsonObject();
            JsonObject reward = row.has("reward") && row.get("reward").isJsonObject()
                    ? row.getAsJsonObject("reward") : row;
            for (ItemStack stack : QuestViewerRecipe.stacksOfReward(reward)) {
                if (stack.isEmpty() || contains(out, stack)) {
                    continue;
                }
                out.add(stack);
            }
        }
        return out;
    }

    private static boolean contains(List<ItemStack> stacks, ItemStack stack) {
        for (ItemStack existing : stacks) {
            if (ItemStack.isSameItemSameComponents(existing, stack)) {
                return true;
            }
        }
        return false;
    }
}
