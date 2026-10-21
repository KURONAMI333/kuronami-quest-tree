package com.kuronami.questtree.api;

import com.google.gson.JsonObject;
import com.kuronami.questtree.data.ProgressModel;
import net.minecraft.server.level.ServerPlayer;

/**
 * A reward type supplied by another mod, registered with {@code QuestTreeApi.registerRewardType(id, type)}.
 * <p>
 * The handler runs on the server after the claim was accepted; it must do its own permission and range checks and
 * must not trust the client. Exceptions are caught by Quest Tree and logged, like the built-in rewards.
 */
public interface QuestRewardType {

    void give(RewardContext context);

    /** One reward claim: {@code choice} is the chosen table slot, or -1 when no choice applies. */
    record RewardContext(ServerPlayer player, ProgressModel.Node node, JsonObject reward, int choice) {

        public JsonObject data() {
            return reward;
        }
    }
}
