package com.kuronami.questtree.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.kuronami.questtree.client.editor.EditorDoc;
import com.kuronami.questtree.client.editor.FieldSpec;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * One size for every selected node (2026-10-22, p6-editor), FTB Quests' bulk {@code change_size}. Positions and ids do
 * not travel: the map already holds them, so the op is {@code resize_nodes} with the ids and the number, and the whole
 * selection is one revision and one undo step.
 */
final class BulkSizeScreen extends EditorScreen {

    private final String chapterId;
    private final List<String> ids;
    private final JsonObject root = new JsonObject();
    private final long revision;

    BulkSizeScreen(Screen parent, String chapterId, List<String> ids) {
        super(parent, Component.translatable(PREFIX + "size.title"));
        this.chapterId = chapterId;
        this.ids = List.copyOf(ids);
        this.revision = EditorSession.revision(chapterId);
        root.addProperty("size", 1);
    }

    @Override
    protected void build() {
        List<EditForm.Line> lines = new ArrayList<>();
        lines.add(new EditForm.HeadLine(Component.literal(Integer.toString(ids.size()))));
        lines.addAll(fieldRows(List.of(FieldSpec.decimal("size", PREFIX + "field.size")), root));
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
        double size;
        try {
            size = Double.parseDouble(EditorDoc.text(root, "size"));
        } catch (RuntimeException ex) {
            setStatus(Component.translatable(PREFIX + "size.invalid"), true);
            return;
        }
        if (!Double.isFinite(size) || size <= 0) {
            setStatus(Component.translatable(PREFIX + "size.invalid"), true);
            return;
        }
        JsonArray nodes = new JsonArray();
        for (String id : ids) {
            JsonObject node = new JsonObject();
            node.addProperty("id", id);
            node.addProperty("size", size);
            nodes.add(node);
        }
        JsonObject args = new JsonObject();
        args.add("nodes", nodes);
        sending = true;
        EditorSession.send(chapterId, "resize_nodes", args, revision, reply -> {
            if (reply.ok()) {
                closeNow();
            } else {
                sending = false;
                showReply(reply, chapterId, null);
            }
        });
    }
}
