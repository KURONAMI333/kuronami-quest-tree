package com.kuronami.questtree.server;

import com.google.gson.JsonObject;
import com.kuronami.questtree.data.ProgressModel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The server side of FTB Quests' {@code custom} task and reward (2026-10-20). Their checks are Java code registered by
 * addons, so a pack that uses them without an addon has nothing to run: KQT reports what happened here instead of
 * pretending the task or reward did something. Addons can listen without a compile-time dependency beyond this class.
 */
public final class QuestEvents {

    /**
     * One custom task submit or reward claim.
     *
     * @param task true for a task (the player pressed its button), false for a reward
     * @param index the task index, or -1 for a reward
     */
    public record Custom(ServerPlayer player, ProgressModel.Node node, JsonObject data, boolean task, int index) {
    }

    private static final List<Consumer<Custom>> LISTENERS = new CopyOnWriteArrayList<>();

    private QuestEvents() {
    }

    public static void register(Consumer<Custom> listener) {
        LISTENERS.add(listener);
    }

    static void fire(ServerPlayer player, ProgressModel.Node node, JsonObject data, boolean task, int index) {
        if (LISTENERS.isEmpty()) {
            return;
        }
        Custom event = new Custom(player, node, data, task, index);
        for (Consumer<Custom> listener : LISTENERS) {
            listener.accept(event);
        }
    }
}
