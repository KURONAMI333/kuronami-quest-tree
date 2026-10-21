package com.kuronami.questtree.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kuronami.questtree.client.editor.ContentSchema;
import com.kuronami.questtree.client.editor.EditorDoc;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything one quest holds (2026-10-21, phase 3): its own fields, then its tasks and rewards as cards that open the
 * per-type form. Positions and links stay on the map; this screen writes one {@code update_node} patch when it closes,
 * so a typo never becomes a file write per keystroke.
 */
final class NodeEditScreen extends EditorScreen {

    private final String chapterId;
    private final String nodeId;
    private final JsonObject before;
    private final JsonObject root;
    /** The chapter revision this screen's copy came from; the save is refused if another operator moved it. */
    private final long revision;

    NodeEditScreen(Screen parent, String chapterId, String nodeId) {
        super(parent, Component.translatable(PREFIX + "node.title"));
        this.chapterId = chapterId;
        this.nodeId = nodeId;
        JsonObject raw = ClientProgress.rawNode(chapterId, nodeId);
        this.before = raw == null ? new JsonObject() : raw.deepCopy();
        this.root = this.before.deepCopy();
        this.revision = EditorSession.revision(chapterId);
    }

    @Override
    protected void build() {
        List<EditForm.Line> lines = new ArrayList<>();
        lines.add(new EditForm.HeadLine(Component.translatable(PREFIX + "node.fields")));
        lines.addAll(fieldRows(ContentSchema.questFields(), root));

        lines.add(new EditForm.HeadLine(Component.translatable(PREFIX + "tasks")));
        JsonArray tasks = EditorDoc.array(root, "tasks", false);
        int taskCount = tasks == null ? 0 : tasks.size();
        for (int i = 0; i < taskCount; i++) {
            int index = i;
            JsonObject task = objectAt(tasks, i);
            String type = EditorDoc.text(task, "type");
            lines.add(new EditForm.CardLine(cardName(PREFIX + "task.", type, task),
                    () -> openTask(task),
                    List.of(
                            new EditForm.Action(null, "^", () -> move(tasks, index, -1), index > 0),
                            new EditForm.Action(null, "v", () -> move(tasks, index, 1), index < taskCount - 1),
                            new EditForm.Action("cross", null, () -> remove(tasks, index), true)),
                    "tasks[" + index + "]"));
        }
        lines.add(new EditForm.AddLine(Component.translatable(PREFIX + "add.task"),
                () -> addTask(tasksOrCreate())));

        lines.add(new EditForm.HeadLine(Component.translatable(PREFIX + "rewards")));
        JsonArray rewards = EditorDoc.array(root, "rewards", false);
        int rewardCount = rewards == null ? 0 : rewards.size();
        for (int i = 0; i < rewardCount; i++) {
            int index = i;
            JsonObject reward = objectAt(rewards, i);
            String type = ContentSchema.rewardType(reward);
            lines.add(new EditForm.CardLine(cardName(PREFIX + "reward.", type, reward),
                    () -> openReward(reward),
                    List.of(
                            new EditForm.Action(null, "^", () -> move(rewards, index, -1), index > 0),
                            new EditForm.Action(null, "v", () -> move(rewards, index, 1), index < rewardCount - 1),
                            new EditForm.Action("cross", null, () -> remove(rewards, index), true)),
                    "rewards[" + index + "]"));
        }
        lines.add(new EditForm.AddLine(Component.translatable(PREFIX + "add.reward"),
                () -> addReward(rewardsOrCreate())));

        form.set(lines);
    }

    private JsonArray tasksOrCreate() {
        return EditorDoc.array(root, "tasks", true);
    }

    private JsonArray rewardsOrCreate() {
        return EditorDoc.array(root, "rewards", true);
    }

    private JsonObject objectAt(JsonArray array, int index) {
        JsonElement e = array.get(index);
        if (e != null && e.isJsonObject()) {
            return e.getAsJsonObject();
        }
        JsonObject made = new JsonObject();
        array.set(index, made);
        return made;
    }

    private static Component cardName(String prefix, String type, JsonObject object) {
        Component name = Component.translatable(prefix + type);
        String title = EditorDoc.text(object, "title");
        return title.isBlank() ? name : Component.literal(name.getString() + " · " + title);
    }

    private void move(JsonArray array, int index, int delta) {
        if (EditorDoc.move(array, index, delta)) {
            build();
        }
    }

    private void remove(JsonArray array, int index) {
        if (index >= 0 && index < array.size()) {
            array.remove(index);
            build();
        }
    }

    private void openTask(JsonObject task) {
        if (minecraft != null) {
            minecraft.setScreen(new ContentEditScreen(this, Component.translatable(PREFIX + "task.title"), task,
                    ContentEditScreen.Kind.TASK));
        }
    }

    private void openReward(JsonObject reward) {
        if (minecraft != null) {
            minecraft.setScreen(new ContentEditScreen(this, Component.translatable(PREFIX + "reward.title"), reward,
                    ContentEditScreen.Kind.REWARD));
        }
    }

    private void addTask(JsonArray tasks) {
        if (minecraft == null) {
            return;
        }
        minecraft.setScreen(new EditPickerScreen(this, Component.translatable(PREFIX + "type"), typeOptions(false),
                false, type -> {
                    JsonObject task = ContentSchema.newTask(type);
                    tasks.add(task);
                    build();
                    form.scrollToEnd();
                    openTask(task);
                }));
    }

    private void addReward(JsonArray rewards) {
        if (minecraft == null) {
            return;
        }
        minecraft.setScreen(new EditPickerScreen(this, Component.translatable(PREFIX + "type"), typeOptions(true),
                false, type -> {
                    JsonObject reward = ContentSchema.newReward(type);
                    rewards.add(reward);
                    build();
                    form.scrollToEnd();
                    openReward(reward);
                }));
    }

    private static List<EditPickers.Option> typeOptions(boolean rewards) {
        List<EditPickers.Option> out = new ArrayList<>();
        String prefix = PREFIX + (rewards ? "reward." : "task.");
        for (String type : rewards ? ContentSchema.REWARD_TYPES : ContentSchema.TASK_TYPES) {
            out.add(new EditPickers.Option(type, Component.translatable(prefix + type), ItemStack.EMPTY));
        }
        return out;
    }

    @Override
    protected JsonObject content() {
        return root;
    }

    @Override
    protected void requestClose() {
        if (sending) {
            return;
        }
        JsonObject patch = EditorDoc.patch(before, root);
        if (patch.isEmpty()) {
            closeNow();
            return;
        }
        JsonObject args = new JsonObject();
        args.addProperty("id", nodeId);
        args.add("patch", patch);
        sending = true;
        EditorSession.send(chapterId, "update_node", args, revision, reply -> {
            if (reply.ok()) {
                closeNow();
            } else {
                sending = false;
                showReply(reply, chapterId, nodeId);
            }
        });
    }
}
