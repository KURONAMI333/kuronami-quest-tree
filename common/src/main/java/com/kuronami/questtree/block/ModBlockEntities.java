package com.kuronami.questtree.block;

import net.minecraft.world.level.block.entity.BlockEntityType;

/** Loader-filled holders for the two block entities, so common block classes never name a loader registry. */
public final class ModBlockEntities {

    private static BlockEntityType<QuestTaskBlockEntity> task;
    private static BlockEntityType<QuestBarrierBlockEntity> barrier;
    private static BlockEntityType<LootCrateOpenerBlockEntity> lootCrateOpener;

    private ModBlockEntities() {
    }

    public static BlockEntityType<QuestTaskBlockEntity> task() {
        return task;
    }

    public static void setTask(BlockEntityType<QuestTaskBlockEntity> type) {
        task = type;
    }

    public static BlockEntityType<QuestBarrierBlockEntity> barrier() {
        return barrier;
    }

    public static void setBarrier(BlockEntityType<QuestBarrierBlockEntity> type) {
        barrier = type;
    }

    public static BlockEntityType<LootCrateOpenerBlockEntity> lootCrateOpener() {
        return lootCrateOpener;
    }

    public static void setLootCrateOpener(BlockEntityType<LootCrateOpenerBlockEntity> type) {
        lootCrateOpener = type;
    }
}
