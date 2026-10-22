package com.kuronami.questtree.client;

import com.google.gson.JsonObject;
import com.kuronami.questtree.client.editor.ContentSchema;
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
        List<FieldSpec> out = new ArrayList<>();
        out.add(FieldSpec.bool("loot_crates.enabled", PREFIX + "field.loot_crates.enabled"));
        out.add(FieldSpec.integer("loot_crates.no_drop.passive", PREFIX + "field.loot_crates.no_drop.passive"));
        out.add(FieldSpec.integer("loot_crates.no_drop.monster", PREFIX + "field.loot_crates.no_drop.monster"));
        out.add(FieldSpec.integer("loot_crates.no_drop.boss", PREFIX + "field.loot_crates.no_drop.boss"));
        // FTB Quests' file settings (p4-readers): defaults a quest keeps when it sets no value of its own
        out.add(FieldSpec.bool("default_reward_team", PREFIX + "field.default_reward_team"));
        out.add(FieldSpec.bool("default_consume_items", PREFIX + "field.default_consume_items"));
        out.add(new FieldSpec("default_autoclaim_rewards", FieldSpec.Kind.ENUM,
                PREFIX + "field.default_autoclaim", List.of("default", "enabled", "disabled"), false));
        out.add(new FieldSpec("default_quest_shape", FieldSpec.Kind.ENUM,
                PREFIX + "field.default_quest_shape", ContentSchema.SHAPES, false));
        out.add(FieldSpec.bool("default_quest_disable_jei", PREFIX + "field.default_quest_disable_jei"));
        out.add(FieldSpec.text("preset", PREFIX + "field.default_preset"));
        out.add(FieldSpec.json("presets", PREFIX + "field.presets"));
        out.add(FieldSpec.text("lock_message", PREFIX + "field.lock_message"));
        out.add(FieldSpec.bool("show_lock_icons", PREFIX + "field.show_lock_icons", true));
        out.add(FieldSpec.bool("hide_excluded_quests", PREFIX + "field.hide_excluded_quests"));
        out.add(FieldSpec.bool("disable_gui", PREFIX + "field.disable_gui"));
        out.add(FieldSpec.decimal("grid_scale", PREFIX + "field.grid_scale"));
        out.add(FieldSpec.bool("pause_game", PREFIX + "field.pause_game"));
        out.add(FieldSpec.integer("detection_delay", PREFIX + "field.detection_delay"));
        out.add(FieldSpec.bool("suppress_all_autoclaiming", PREFIX + "field.suppress_autoclaim"));
        return out;
    }

    @Override
    protected void build() {
        List<EditForm.Line> lines = new ArrayList<>();
        lines.add(new EditForm.HeadLine(Component.translatable(PREFIX + "settings.loot_crates")));
        lines.addAll(fieldRows(fields().subList(0, 4), root));
        lines.add(new EditForm.HeadLine(Component.translatable(PREFIX + "settings.defaults")));
        lines.addAll(fieldRows(fields().subList(4, 7), root));
        lines.add(new EditForm.HeadLine(Component.translatable(PREFIX + "settings.visuals")));
        lines.addAll(fieldRows(fields().subList(7, 11), root));
        lines.add(new EditForm.HeadLine(Component.translatable(PREFIX + "settings.behaviour")));
        lines.addAll(fieldRows(fields().subList(11, fields().size()), root));
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
