package com.kuronami.questtree.block;

import net.minecraft.world.level.block.entity.BlockEntityType;

/** Loader-filled holders for the two block entities, so common block classes never name a loader registry. */
public final class ModBlockEntities {

    private static BlockEntityType<QuestTaskBlockEntity> task;
    private static BlockEntityType<QuestBarrierBlockEntity> barrier;
    private static BlockEntityType<QuestDetectorBlockEntity> detector;
    private static BlockEntityType<TaskScreenBlockEntity> taskScreen;
    private static BlockEntityType<TaskScreenAuxBlockEntity> taskScreenAux;

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

    public static BlockEntityType<QuestDetectorBlockEntity> detector() {
        return detector;
    }

    public static void setDetector(BlockEntityType<QuestDetectorBlockEntity> type) {
        detector = type;
    }
    public static BlockEntityType<TaskScreenBlockEntity> taskScreen() {
        return taskScreen;
    }

    public static void setTaskScreen(BlockEntityType<TaskScreenBlockEntity> type) {
        taskScreen = type;
    }

    public static BlockEntityType<TaskScreenAuxBlockEntity> taskScreenAux() {
        return taskScreenAux;
    }

    public static void setTaskScreenAux(BlockEntityType<TaskScreenAuxBlockEntity> type) {
        taskScreenAux = type;
    }
}
