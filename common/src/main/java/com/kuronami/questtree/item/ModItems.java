package com.kuronami.questtree.item;

import com.kuronami.questtree.progress.LootCrateRules;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The mod's items. Each loader registers them and fills this holder, so common code (the first-join gift, the crate
 * pay-out and the server config) never names a loader class.
 */
public final class ModItems {

    private static Item questBook;
    private static Item taskScreenConfigurator;
    private static Item lootCrate;

    private ModItems() {
    }

    public static Item questBook() {
        return questBook;
    }

    public static Item lootCrate() {
        return lootCrate;
    }

    /** Called by the loader's registration; the last one wins, and there is only one. */
    public static void setQuestBook(Item item) {
        questBook = item;
    }

    public static Item taskScreenConfigurator() {
        return taskScreenConfigurator;
    }

    public static void setTaskScreenConfigurator(Item item) {
        taskScreenConfigurator = item;
    }
    public static void setLootCrate(Item item) {
        lootCrate = item;
    }

    /** One crate stack for a table, with the name and glow the table's crate settings carry. */
    public static ItemStack lootCrate(String tableId, LootCrateRules.Crate crate) {
        if (lootCrate == null) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(lootCrate);
        stack.set(ModDataComponents.lootCrate(), tableId);
        if (crate != null && !crate.itemName().isEmpty()) {
            stack.set(DataComponents.CUSTOM_NAME, Component.translatable(crate.itemName()));
        }
        if (crate != null && crate.glow()) {
            stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        return stack;
    }

    /** A stack for a table id whose crate settings could not be read; the tooltip marks it, opening does nothing. */
    public static ItemStack lootCrate(String tableId) {
        if (lootCrate == null) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(lootCrate);
        stack.set(ModDataComponents.lootCrate(), tableId);
        return stack;
    }

    /** The name a crate shows before the table's own item name is applied. */
    public static Component crateName(String stringId) {
        return stringId == null || stringId.isEmpty()
                ? Component.translatable("item.kuronami_quest_tree.loot_crate")
                : Component.literal(stringId).withStyle(ChatFormatting.GRAY);
    }
}
