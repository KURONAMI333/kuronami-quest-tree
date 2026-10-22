package com.kuronami.questtree.block;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/** Loader-filled holders for the two blocks and their items. */
public final class ModBlocks {

    private static Block taskBlock;
    private static Block barrierBlock;
    private static Block detectorBlock;
    private static Item taskItem;
    private static Item barrierItem;
    private static Item detectorItem;

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

    public static void setDetector(Block block, Item item) {
        detectorBlock = block;
        detectorItem = item;
    }

    public static Block taskBlock() {
        return taskBlock;
    }

    public static Block barrierBlock() {
        return barrierBlock;
    }

    public static Item taskItem() {
        return taskItem;
    }

    public static Item barrierItem() {
        return barrierItem;
    }

    public static Block detectorBlock() {
        return detectorBlock;
    }

    public static Item detectorItem() {
        return detectorItem;
    }

    public static ItemStack taskItem(String node) {
        ItemStack stack = new ItemStack(taskItem);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.of(nodeTag("quest_node", node)));
        return stack;
    }

    public static ItemStack barrierItem(String mode, String key) {
        return barrierItem(mode, key, null);
    }

    public static ItemStack barrierItem(String mode, String key, BarrierTeleport teleport) {
        ItemStack stack = new ItemStack(barrierItem);
        net.minecraft.nbt.CompoundTag tag = nodeTag("quest_key", key);
        tag.putString("quest_mode", mode);
        if (teleport != null) {
            net.minecraft.nbt.CompoundTag saved = new net.minecraft.nbt.CompoundTag();
            saved.putBoolean("relative", teleport.relative());
            saved.putString("dimension", teleport.dimension());
            saved.putInt("x", teleport.x());
            saved.putInt("y", teleport.y());
            saved.putInt("z", teleport.z());
            if (teleport.yaw() != null) {
                saved.putFloat("yaw", teleport.yaw());
            }
            if (teleport.pitch() != null) {
                saved.putFloat("pitch", teleport.pitch());
            }
            tag.put("teleport", saved);
        }
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.of(tag));
        return stack;
    }

    public static ItemStack detectorItem(String target, int radius) {
        ItemStack stack = new ItemStack(detectorItem);
        net.minecraft.nbt.CompoundTag tag = nodeTag(QuestDetectorBlockEntity.TARGET_KEY, target);
        tag.putInt(QuestDetectorBlockEntity.RADIUS_KEY, radius);
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
