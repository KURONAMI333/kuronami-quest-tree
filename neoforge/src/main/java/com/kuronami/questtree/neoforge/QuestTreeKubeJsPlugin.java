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
import net.minecraft.server.level.ServerPlayer;


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
 * QuestTree.open(player, 'example:basics/start')
 * }</pre>
 * A registered condition joins the five built-in kinds in the same {@code conditions} array; no built-in kind needs
 * registering.
 * The handler ids are tracked by script type, so reloading one kind of script forgets exactly what that kind registered;
 * a Java addon's own registrations and the other script types' handlers are left alone.
 */
public final class QuestTreeKubeJsPlugin implements KubeJSPlugin {

    private static final EventGroup EVENTS = EventGroup.of("QuestTreeEvents");
    private static final EventHandler QUEST_COMPLETED = EVENTS.server("questCompleted", () -> QuestEvent.class);
    private static final EventHandler TASK_COMPLETED = EVENTS.server("taskCompleted", () -> TaskEvent.class);
    private static final EventHandler REWARD_CLAIMED = EVENTS.server("rewardClaimed", () -> QuestEvent.class);

    private static final QuestTreeScriptTypes TYPES = new QuestTreeScriptTypes();

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
        event.add("QuestTree", new Bindings(event.type().name));
    }

    /** Reloading one kind of script forgets the handlers that kind registered, and nothing else. */
    @Override
    public void beforeScriptsLoaded(ScriptManager manager) {
        QuestTreeScriptTypes.Cleared cleared = TYPES.clear(manager.scriptType.name);
        cleared.tasks().forEach(QuestTreeApi::unregisterTaskType);
        cleared.rewards().forEach(QuestTreeApi::unregisterRewardType);
        cleared.conditions().forEach(QuestTreeApi::unregisterConditionType);
    }

    /** The object a script sees as {@code QuestTree}. */
    public static final class Bindings {

        private final String scriptType;

        Bindings(String scriptType) {
            this.scriptType = scriptType;
        }

        public boolean registerTask(String id, QuestTaskType handler) {
            if (QuestTreeApi.registerTaskTypeById(id, handler)) {
                TYPES.task(scriptType, id);
                return true;
            }
            return false;
        }

        public boolean registerReward(String id, QuestRewardType handler) {
            if (QuestTreeApi.registerRewardTypeById(id, handler)) {
                TYPES.reward(scriptType, id);
                return true;
            }
            return false;
        }

        public boolean registerCondition(String id, QuestConditionType handler) {
            if (QuestTreeApi.registerConditionTypeById(id, handler)) {
                TYPES.condition(scriptType, id);
                return true;
            }
            return false;
        }

        /** Opens a quest or chapter for a player, e.g. from an NPC script. */
        public boolean open(ServerPlayer player, String target) {
            return QuestTreeApi.open(player, target);
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
