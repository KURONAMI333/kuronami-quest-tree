package com.kuronami.questtree.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kuronami.questtree.client.editor.EditorDoc;
import com.kuronami.questtree.client.editor.FieldSpec;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Every item and block restriction in one screen (2026-10-22, G6). The file is written whole with
 * {@code update_restrictions}: rules are entries in one array, and a per-rule patch would only move the array indexes.
 * The revision belongs to the file, not to a chapter or a table.
 */
final class RestrictionsEditScreen extends EditorScreen {

    private final JsonObject before;
    private final JsonObject root;
    /** The restrictions revision this screen's copy came from; the save is refused if another operator moved it. */
    private final long revision;

    RestrictionsEditScreen(Screen parent) {
        super(parent, Component.translatable(PREFIX + "restrictions.title"));
        this.before = new JsonObject();
        this.before.add("rules", ClientProgress.rawRestrictions());
        this.root = before.deepCopy();
        this.revision = EditorSession.tableRevision(EditorSession.RESTRICTIONS);
    }

    private static List<FieldSpec> ruleFields() {
        return List.of(
                FieldSpec.text("id", PREFIX + "field.rule_id"),
                new FieldSpec("items", FieldSpec.Kind.IDS, PREFIX + "field.items", List.of(), false),
                new FieldSpec("item_tags", FieldSpec.Kind.IDS, PREFIX + "field.item_tags", List.of(), false),
                new FieldSpec("blocks", FieldSpec.Kind.IDS, PREFIX + "field.blocks", List.of(), false),
                new FieldSpec("block_tags", FieldSpec.Kind.IDS, PREFIX + "field.block_tags", List.of(), false),
                new FieldSpec("actions", FieldSpec.Kind.IDS, PREFIX + "field.actions", List.of(), false),
                new FieldSpec("conditions", FieldSpec.Kind.JSON, PREFIX + "field.conditions", List.of(), false),
                FieldSpec.text("message", PREFIX + "field.rule_message"));
    }

    @Override
    protected void build() {
        List<EditForm.Line> lines = new ArrayList<>();
        JsonArray rules = EditorDoc.array(root, "rules", true);
        lines.add(new EditForm.HeadLine(Component.literal(rules.size() + " · "
                + Component.translatable(PREFIX + "restrictions.rules").getString())));
        for (int i = 0; i < rules.size(); i++) {
            int index = i;
            JsonObject rule = objectAt(rules, i);
            String id = EditorDoc.text(rule, "id");
            lines.add(new EditForm.CardLine(Component.literal(id.isEmpty() ? (index + 1) + " ·" : id), null,
                    List.of(
                            new EditForm.Action(null, "^", () -> move(rules, index, -1), index > 0),
                            new EditForm.Action(null, "v", () -> move(rules, index, 1), index < rules.size() - 1),
                            new EditForm.Action("cross", null, () -> remove(rules, index), true)),
                    "rules[" + index + "]"));
            for (FieldSpec spec : ruleFields()) {
                lines.addAll(fieldRows(List.of(spec.under("rules[" + index + "].")), root));
            }
        }
        lines.add(new EditForm.AddLine(Component.translatable(PREFIX + "restrictions.add"), this::addRule));
        form.set(lines);
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

    private void addRule() {
        JsonArray rules = EditorDoc.array(root, "rules", true);
        JsonObject rule = new JsonObject();
        rules.add(rule);
        build();
        form.scrollToEnd();
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
        args.add("rules", EditorDoc.array(root, "rules", true).deepCopy());
        sending = true;
        EditorSession.sendTable(EditorSession.RESTRICTIONS, "update_restrictions", args, revision, reply -> {
            if (reply.ok()) {
                closeNow();
            } else {
                sending = false;
                showReply(reply, EditorSession.RESTRICTIONS, null);
            }
        });
    }
}
