package com.kuronami.questtree.neoforge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kuronami.questtree.Constants;
import com.kuronami.questtree.api.QuestConditionType;
import com.kuronami.questtree.api.QuestField;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;


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

        /**
         * Declares the fields the editor shows for a task type, as the JSON array {@code _docs/API.md} describes
         * (2026-11-01); a type registered without one edits its own members as one JSON row.
         * <pre>{@code
         * QuestTree.describeTask('example:held_level', '[{"path":"level","kind":"int","label":"Example level"}]')
         * }</pre>
         * An empty array or string clears an earlier declaration.
         */
        public boolean describeTask(String id, String fieldsJson) {
            return QuestTreeApi.describeTaskTypeById(id, parseFields(fieldsJson));
        }

        public boolean describeReward(String id, String fieldsJson) {
            return QuestTreeApi.describeRewardTypeById(id, parseFields(fieldsJson));
        }

        /** Opens a quest or chapter for a player, e.g. from an NPC script. */
        public boolean open(ServerPlayer player, String target) {
            return QuestTreeApi.open(player, target);
        }
    }

    /**
     * Reads the script's JSON array into fields. A script cannot build the record itself, so the binding takes the JSON
     * text; an entry with no path or an unknown kind is reported and skipped rather than failing the whole list.
     */
    private static List<QuestField> parseFields(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonArray()) {
                Constants.LOG.warn("Quest editor fields must be a JSON array: '{}'", json);
                return List.of();
            }
            List<QuestField> out = new ArrayList<>();
            for (JsonElement element : parsed.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject o = element.getAsJsonObject();
                String path = o.has("path") ? o.get("path").getAsString() : "";
                String label = o.has("label") ? o.get("label").getAsString() : path;
                String kindName = o.has("kind") ? o.get("kind").getAsString() : "text";
                List<String> options = new ArrayList<>();
                if (o.has("options") && o.get("options").isJsonArray()) {
                    for (JsonElement option : o.getAsJsonArray("options")) {
                        if (option.isJsonPrimitive()) {
                            options.add(option.getAsString());
                        }
                    }
                }
                QuestField.Kind kind;
                try {
                    kind = QuestField.Kind.valueOf(kindName.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    Constants.LOG.warn("Unknown quest editor field kind '{}' for '{}'; shown as text", kindName, path);
                    kind = QuestField.Kind.TEXT;
                }
                try {
                    out.add(new QuestField(path, kind, label, options));
                } catch (IllegalArgumentException ex) {
                    Constants.LOG.warn("Skipping a quest editor field with no path: {}", element);
                }
            }
            return out;
        } catch (RuntimeException ex) {
            Constants.LOG.warn("Could not read quest editor fields '{}': {}", json, ex.getMessage());
            return List.of();
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
