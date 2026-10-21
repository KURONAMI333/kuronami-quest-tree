package com.kuronami.questtree.api;

import com.google.gson.JsonObject;
import com.kuronami.questtree.data.ProgressModel;
import net.minecraft.server.level.ServerPlayer;

/**
 * A task type supplied by another mod. The progress is this type's own count, in the same units as
 * {@code ProgressRules.target(task)}; the server stores and completes it like the built-in types.
 * <p>
 * Register once, from the mod constructor or common setup: {@code QuestTreeApi.registerTaskType(id, type)}.
 * The JSON shape of the task is the addon's; Quest Tree only reads {@code type}, {@code count}/{@code amount} and
 * {@code conditions} unless the handler asks for more.
 */
public interface QuestTaskType {

    /** How far along this task is for the player now. Returning a value at or above the target completes it. */
    long progress(TaskContext context);

    /** One task check. {@code index} is its position in the node, so a handler can read its own progress. */
    record TaskContext(ServerPlayer player, ProgressModel.Node node, JsonObject task, int index,
                       long currentProgress, long maxProgress) {

        public JsonObject data() {
            return task;
        }
    }
}
