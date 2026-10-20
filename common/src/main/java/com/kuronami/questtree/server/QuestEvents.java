package com.kuronami.questtree.server;

import com.google.gson.JsonObject;
import com.kuronami.questtree.data.ProgressModel;
import com.kuronami.questtree.progress.CustomTaskResult;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The server side of FTB Quests' {@code custom} task and reward. Their checks are Java code registered by addons, so a
 * pack that uses them without an addon has nothing to run. Addons can listen here without a compile-time dependency
 * beyond this class (2026-10-20).
 * <p>
 * A custom task listener may read its progress, set it, add to it or complete it, like FTB Quests'
 * {@code CustomTask.Data} (2026-10-20 review). A listener registered for one task sees only that task; a listener
 * registered through {@link #register(Consumer)} sees every custom task and reward.
 */
public final class QuestEvents {

    /**
     * One custom task submit or reward claim. {@code task} is true when the player pressed a task's button and false
     * for a reward; {@code index} is the task index, or -1 for a reward.
     */
    public static final class Custom {
        private final ServerPlayer player;
        private final ProgressModel.Node node;
        private final JsonObject data;
        private final boolean task;
        private final int index;
        private final CustomTaskResult result;

        Custom(ServerPlayer player, ProgressModel.Node node, JsonObject data, boolean task, int index,
               long current, long max) {
            this.player = player;
            this.node = node;
            this.data = data;
            this.task = task;
            this.index = index;
            this.result = new CustomTaskResult(current, max);
        }

        public ServerPlayer player() {
            return player;
        }

        public ProgressModel.Node node() {
            return node;
        }

        public JsonObject data() {
            return data;
        }

        public boolean task() {
            return task;
        }

        public int index() {
            return index;
        }

        /** The task's progress now (0 for rewards). */
        public long currentProgress() {
            return result.current();
        }

        public long maxProgress() {
            return result.max();
        }

        public void setProgress(long value) {
            result.setProgress(value);
        }

        public void addProgress(long amount) {
            result.addProgress(amount);
        }

        public void complete() {
            result.markComplete();
        }

        CustomTaskResult result() {
            return result;
        }
    }

    private static final List<Consumer<Custom>> ALL = new CopyOnWriteArrayList<>();
    private static final Map<String, List<Consumer<Custom>>> BY_TASK = new ConcurrentHashMap<>();

    private QuestEvents() {
    }

    /** Listens to every custom task and custom reward. */
    public static void register(Consumer<Custom> listener) {
        ALL.add(listener);
    }

    /** Listens to one task, named by {@link #taskKey(ProgressModel.Node, JsonObject, int)}. */
    public static void register(String taskKey, Consumer<Custom> listener) {
        BY_TASK.computeIfAbsent(taskKey, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    public static boolean hasListener(String taskKey) {
        List<Consumer<Custom>> list = BY_TASK.get(taskKey);
        return list != null && !list.isEmpty();
    }

    /** Whether any listener (for every task) is registered; such a listener is asked about every custom task. */
    public static boolean hasAnyListener() {
        return !ALL.isEmpty();
    }

    /** The stable name of one task: its node key and index (FTB Quests custom tasks carry no id of their own). */
    public static String taskKey(ProgressModel.Node node, JsonObject data, int index) {
        return node.key + "#" + index;
    }

    static CustomTaskResult fire(ServerPlayer player, ProgressModel.Node node, JsonObject data, boolean task, int index) {
        return fire(player, node, data, task, index, 0, 1);
    }

    static CustomTaskResult fire(ServerPlayer player, ProgressModel.Node node, JsonObject data, boolean task, int index,
                                 long current, long max) {
        Custom event = new Custom(player, node, data, task, index, current, max);
        List<Consumer<Custom>> one = BY_TASK.get(taskKey(node, data, index));
        if (one != null) {
            for (Consumer<Custom> listener : one) {
                listener.accept(event);
            }
        }
        for (Consumer<Custom> listener : ALL) {
            listener.accept(event);
        }
        return event.result();
    }
}
