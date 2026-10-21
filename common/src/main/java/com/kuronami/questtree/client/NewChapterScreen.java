package com.kuronami.questtree.client;

import com.google.gson.JsonObject;
import com.kuronami.questtree.client.editor.EditorDoc;
import com.kuronami.questtree.client.editor.FieldSpec;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * A new chapter (2026-10-21, phase 4): a namespace and an optional title. The server mints the chapter's id, so the
 * author never has to spell a path, and a blank namespace takes the one the open chapter already uses.
 */
final class NewChapterScreen extends EditorScreen {

    private final JsonObject root = new JsonObject();

    NewChapterScreen(Screen parent, String namespace) {
        super(parent, Component.translatable(PREFIX + "chapter.new"));
        root.addProperty("namespace", namespace);
    }

    @Override
    protected void build() {
        List<FieldSpec> fields = List.of(
                FieldSpec.text("namespace", PREFIX + "chapter.namespace"),
                FieldSpec.text("title", PREFIX + "field.title"));
        List<EditForm.Line> lines = new ArrayList<>(fieldRows(fields, root));
        lines.add(new EditForm.AddLine(Component.translatable(PREFIX + "chapter.create"), this::create));
        form.set(lines);
    }

    private void create() {
        if (sending) {
            return;
        }
        String namespace = EditorDoc.text(root, "namespace");
        if (namespace.isBlank()) {
            setStatus(Component.translatable(PREFIX + "chapter.namespace.missing"), true);
            return;
        }
        JsonObject chapter = new JsonObject();
        String title = EditorDoc.text(root, "title");
        if (!title.isBlank()) {
            chapter.addProperty("title", title);
        }
        JsonObject args = new JsonObject();
        args.addProperty("namespace", namespace);
        args.add("chapter", chapter);
        sending = true;
        EditorSession.send("", "create_chapter", args, reply -> {
            if (reply.ok()) {
                closeNow();
            } else {
                sending = false;
                showReply(reply, "", null);
            }
        });
    }

    @Override
    protected void requestClose() {
        create();
    }

    @Override
    protected void cancel() {
        closeNow();
    }
}
