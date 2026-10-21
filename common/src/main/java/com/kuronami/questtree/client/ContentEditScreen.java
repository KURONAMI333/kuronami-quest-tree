package com.kuronami.questtree.client;

import com.google.gson.JsonObject;
import com.kuronami.questtree.client.editor.ContentSchema;
import com.kuronami.questtree.client.editor.EditorDoc;
import com.kuronami.questtree.client.editor.FieldSpec;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * One task, one reward, or one reward-table entry (2026-10-21, phase 3). The type is a card at the top; the fields
 * under it are the schema's for that type, so changing the type drops exactly the keys the old type owned and keeps
 * everything else. Edits land in the object the caller already holds, and the call is saved with its owner.
 */
final class ContentEditScreen extends EditorScreen {

    /** What the object is, which decides its type list, its field list, and what a type change removes. */
    enum Kind {
        TASK, REWARD, ENTRY
    }

    private final JsonObject root;
    private final JsonObject typeTarget;
    private final Kind kind;

    ContentEditScreen(Screen parent, Component title, JsonObject root, Kind kind) {
        super(parent, title);
        this.root = root;
        this.kind = kind;
        this.typeTarget = kind == Kind.ENTRY ? rewardOf(root) : root;
    }

    private static JsonObject rewardOf(JsonObject entry) {
        com.google.gson.JsonElement reward = entry.get("reward");
        if (reward == null || !reward.isJsonObject()) {
            JsonObject made = new JsonObject();
            entry.add("reward", made);
            return made;
        }
        return reward.getAsJsonObject();
    }

    private String type() {
        return switch (kind) {
            case TASK -> EditorDoc.text(root, "type");
            case REWARD, ENTRY -> ContentSchema.rewardType(typeTarget);
        };
    }

    private List<String> types() {
        return kind == Kind.TASK ? ContentSchema.TASK_TYPES : ContentSchema.REWARD_TYPES;
    }

    private String typePrefix() {
        return PREFIX + (kind == Kind.TASK ? "task." : "reward.");
    }

    private List<FieldSpec> fields(String type) {
        return switch (kind) {
            case TASK -> ContentSchema.taskFields(type);
            case REWARD -> ContentSchema.rewardFields(type);
            case ENTRY -> ContentSchema.entryFields(type);
        };
    }

    private void retype(String type) {
        switch (kind) {
            case TASK -> ContentSchema.retypeTask(root, type);
            case REWARD -> ContentSchema.retypeReward(root, type);
            case ENTRY -> ContentSchema.retypeReward(typeTarget, type);
        }
    }

    private String tableId() {
        return kind == Kind.ENTRY ? EditorDoc.text(root, "reward.table") : EditorDoc.text(root, "table");
    }

    @Override
    protected void build() {
        String type = type();
        List<EditForm.Line> lines = new ArrayList<>();
        lines.add(new EditForm.CardLine(Component.translatable(typePrefix() + type), this::openTypePicker,
                List.of()));
        lines.addAll(fieldRows(fields(type), root));
        if ((kind != Kind.TASK) && (type.equals("table") || type.equals("choice"))) {
            String table = tableId().isBlank() ? "-" : tableId();
            lines.add(new EditForm.CardLine(
                    Component.translatable(PREFIX + "table.open", table), this::openTable, List.of()));
        }
        form.set(lines);
    }

    private void openTypePicker() {
        List<EditPickers.Option> options = new ArrayList<>();
        for (String type : types()) {
            options.add(new EditPickers.Option(type, Component.translatable(typePrefix() + type), ItemStack.EMPTY));
        }
        if (minecraft != null) {
            minecraft.setScreen(new EditPickerScreen(this, Component.translatable(PREFIX + "type"), options, false,
                    picked -> {
                        retype(picked);
                        build();
                    }));
        }
    }

    private void openTable() {
        String id = tableId();
        if (id.isBlank() || minecraft == null) {
            return;
        }
        boolean create = !ClientProgress.rawTables().has(id);
        EditorSession.whenIdle(() -> {
            if (minecraft != null && minecraft.screen == this) {
                minecraft.setScreen(new TableEditScreen(this, id, create));
            }
        });
    }
}
