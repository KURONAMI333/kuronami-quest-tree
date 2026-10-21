package com.kuronami.questtree.neoforge;

import com.kuronami.questtree.api.QuestConditionType;
import com.kuronami.questtree.api.QuestRewardType;
import com.kuronami.questtree.api.QuestTaskType;
import com.kuronami.questtree.api.QuestTreeApi;
import dev.latvian.mods.kubejs.event.EventGroup;
import dev.latvian.mods.kubejs.event.EventGroupRegistry;
import dev.latvian.mods.kubejs.event.EventHandler;
import dev.latvian.mods.kubejs.event.KubeEvent;
import dev.latvian.mods.kubejs.plugin.KubeJSPlugin;
import dev.latvian.mods.kubejs.script.BindingRegistry;
import dev.latvian.mods.kubejs.script.ScriptManager;
import dev.latvian.mods.kubejs.script.ScriptType;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.Set;

/**
 * The KubeJS integration. KubeJS 7 is NeoForge-only on 1.21.1, so this class lives in {@code :neoforge}; the operations
 * it exposes are in {@code :common}'s {@link QuestTreeApi} and name no KubeJS type.
 * <p>
 * KubeJS finds the class through {@code kubejs.plugins.txt} in this module's resources.
 * <h2>Scripts</h2>
 * <pre>{@code
 * QuestTreeEvents.questCompleted(event => console.info(event.quest))
 *
 * QuestTree.registerTask('example:held_level', context => context.player().experienceLevel)
 * QuestTree.registerCondition('example:level_30', context => context.player().experienceLevel >= 30)
 * }</pre>
 * The handler ids are tracked so a server-script reload forgets exactly what the previous scripts registered; a Java
 * addon's own registrations are left alone.
 */
public final class QuestTreeKubeJsPlugin implements KubeJSPlugin {

    private static final EventGroup EVENTS = EventGroup.of("QuestTreeEvents");
    private static final EventHandler QUEST_COMPLETED = EVENTS.server("questCompleted", () -> QuestEvent.class);
    private static final EventHandler TASK_COMPLETED = EVENTS.server("taskCompleted", () -> TaskEvent.class);
    private static final EventHandler REWARD_CLAIMED = EVENTS.server("rewardClaimed", () -> QuestEvent.class);

    private static final Set<String> SCRIPT_TASKS = new HashSet<>();
    private static final Set<String> SCRIPT_REWARDS = new HashSet<>();
    private static final Set<String> SCRIPT_CONDITIONS = new HashSet<>();

    @Override
    public void init() {
        QuestTreeApi.addListener(event -> {
            switch (event.kind()) {
                case COMPLETE -> QUEST_COMPLETED.post(new QuestEvent(event.player(), event.node().key));
                case CLAIM -> REWARD_CLAIMED.post(new QuestEvent(event.player(), event.node().key));
                case TASK -> TASK_COMPLETED.post(new TaskEvent(event.player(), event.node().key, event.task()));
            }
        });
    }

    @Override
    public void registerEvents(EventGroupRegistry registry) {
        registry.register(EVENTS);
    }

    @Override
    public void registerBindings(BindingRegistry event) {
        event.add("QuestTree", new Bindings());
    }

    /** A server reload is a statement about the handlers the scripts left; a client reload is not. */
    @Override
    public void beforeScriptsLoaded(ScriptManager manager) {
        if (manager.scriptType != ScriptType.SERVER) {
            return;
        }
        SCRIPT_TASKS.forEach(QuestTreeApi::unregisterTaskType);
        SCRIPT_REWARDS.forEach(QuestTreeApi::unregisterRewardType);
        SCRIPT_CONDITIONS.forEach(QuestTreeApi::unregisterConditionType);
        SCRIPT_TASKS.clear();
        SCRIPT_REWARDS.clear();
        SCRIPT_CONDITIONS.clear();
    }

    /** The object a script sees as {@code QuestTree}. */
    public static final class Bindings {

        public boolean registerTask(String id, QuestTaskType handler) {
            if (QuestTreeApi.registerTaskType(id, handler)) {
                SCRIPT_TASKS.add(id);
                return true;
            }
            return false;
        }

        public boolean registerReward(String id, QuestRewardType handler) {
            if (QuestTreeApi.registerRewardType(id, handler)) {
                SCRIPT_REWARDS.add(id);
                return true;
            }
            return false;
        }

        public boolean registerCondition(String id, QuestConditionType handler) {
            if (QuestTreeApi.registerConditionType(id, handler)) {
                SCRIPT_CONDITIONS.add(id);
                return true;
            }
            return false;
        }
    }

    /** A quest-level event; public fields are the shape KubeJS reads. */
    public static final class QuestEvent implements KubeEvent {

        public final ServerPlayer player;
        public final String quest;

        QuestEvent(ServerPlayer player, String quest) {
            this.player = player;
            this.quest = quest;
        }
    }

    /** A task event. {@code task} is the index in the node. */
    public static final class TaskEvent implements KubeEvent {

        public final ServerPlayer player;
        public final String quest;
        public final int task;

        TaskEvent(ServerPlayer player, String quest, int task) {
            this.player = player;
            this.quest = quest;
            this.task = task;
        }
    }
}
