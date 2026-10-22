package com.kuronami.questtree.client;

import com.google.gson.JsonObject;
import com.kuronami.questtree.client.editor.EditorDoc;
import com.kuronami.questtree.client.editor.FieldSpec;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The pack's settings file (2026-10-22, p1-crates): crate drops are the first values the map can change without a
 * data pack. Written whole with {@code update_settings}; the revision is one number for the file.
 */
final class SettingsEditScreen extends EditorScreen {

    private final JsonObject before;
    private final JsonObject root;
    private final long revision;

    SettingsEditScreen(Screen parent) {
        super(parent, Component.translatable(PREFIX + "settings.title"));
        this.before = ClientProgress.rawSettings();
        this.root = before.deepCopy();
        this.revision = EditorSession.tableRevision(EditorSession.SETTINGS);
    }

    private static List<FieldSpec> fields() {
        return List.of(
                FieldSpec.bool("loot_crates.enabled", PREFIX + "field.loot_crates.enabled"),
                FieldSpec.integer("loot_crates.no_drop.passive", PREFIX + "field.loot_crates.no_drop.passive"),
                FieldSpec.integer("loot_crates.no_drop.monster", PREFIX + "field.loot_crates.no_drop.monster"),
                FieldSpec.integer("loot_crates.no_drop.boss", PREFIX + "field.loot_crates.no_drop.boss"));
    }

    @Override
    protected void build() {
        List<EditForm.Line> lines = new ArrayList<>();
        lines.add(new EditForm.HeadLine(Component.translatable(PREFIX + "settings.loot_crates")));
        lines.addAll(fieldRows(fields(), root));
        form.set(lines);
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
        if (EditorDoc.patch(before, root).isEmpty()) {
            closeNow();
            return;
        }
        JsonObject args = new JsonObject();
        args.add("settings", root.deepCopy());
        sending = true;
        EditorSession.sendTable(EditorSession.SETTINGS, "update_settings", args, revision, reply -> {
            if (reply.ok()) {
                closeNow();
            } else {
                sending = false;
                showReply(reply, EditorSession.SETTINGS, null);
            }
        });
    }
}
