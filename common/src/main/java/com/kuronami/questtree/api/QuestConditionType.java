package com.kuronami.questtree.api;

import com.google.gson.JsonObject;
import com.kuronami.questtree.data.ProgressModel;
import net.minecraft.server.level.ServerPlayer;

/**
 * A condition type supplied by another mod, registered with {@code QuestTreeApi.registerConditionType(id, type)}.
 * A JSON entry {@code {"type": "yourmod:level", ...}} then gates the task or reward that lists it.
 * <p>
 * The five built-in kinds ({@code stage}, {@code score}, {@code item}, {@code advancement}, {@code party_size}) are
 * judged by the same gauge without registration, so one {@code conditions} array may mix them with registered kinds.
 * A registered id is matched with its namespace. An unregistered type fails closed, so a pack that names a missing
 * addon does not silently hand out its rewards.
 */
public interface QuestConditionType {

    boolean test(ConditionContext context);

    /** The player, the node and the condition's own JSON object. {@code task} is true for a task condition. */
    record ConditionContext(ServerPlayer player, ProgressModel.Node node, JsonObject condition, boolean task) {

        public JsonObject data() {
            return condition;
        }
    }
}
