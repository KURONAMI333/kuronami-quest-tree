package com.kuronami.questtree.block;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/** Loader-filled holders for the two blocks and their items. */
public final class ModBlocks {

    private static Block taskBlock;
    private static Block barrierBlock;
    private static Block lootCrateOpener;
    private static Item taskItem;
    private static Item barrierItem;
    private static Item lootCrateOpenerItem;

    private ModBlocks() {
    }

    public static void setTask(Block block, Item item) {
        taskBlock = block;
        taskItem = item;
    }

    public static void setBarrier(Block block, Item item) {
        barrierBlock = block;
        barrierItem = item;
    }

    public static void setLootCrateOpener(Block block, Item item) {
        lootCrateOpener = block;
        lootCrateOpenerItem = item;
    }

    public static Block taskBlock() {
        return taskBlock;
    }

    public static Block barrierBlock() {
        return barrierBlock;
    }

    public static Block lootCrateOpener() {
        return lootCrateOpener;
    }

    public static Item taskItem() {
        return taskItem;
    }

    public static Item barrierItem() {
        return barrierItem;
    }

    public static Item lootCrateOpenerItem() {
        return lootCrateOpenerItem;
    }

    public static ItemStack taskItem(String node) {
        ItemStack stack = new ItemStack(taskItem);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.of(nodeTag("quest_node", node)));
        return stack;
    }

    public static ItemStack barrierItem(String mode, String key) {
        ItemStack stack = new ItemStack(barrierItem);
        net.minecraft.nbt.CompoundTag tag = nodeTag("quest_key", key);
        tag.putString("quest_mode", mode);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.of(tag));
        return stack;
    }

    private static net.minecraft.nbt.CompoundTag nodeTag(String name, String value) {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putString(name, value);
        return tag;
    }
}
