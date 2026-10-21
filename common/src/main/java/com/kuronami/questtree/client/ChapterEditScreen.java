package com.kuronami.questtree.client;

import com.google.gson.JsonObject;
import com.kuronami.questtree.client.editor.ContentSchema;
import com.kuronami.questtree.client.editor.EditorDoc;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * One chapter's settings (2026-10-21, phase 3): its name and icon, its defaults, its visibility, its skill points.
 * Nodes are not here, because they are the map's job; a save is one {@code update_chapter} patch, and delete is a
 * tombstone the restore list can bring back.
 */
final class ChapterEditScreen extends EditorScreen {

    private final String chapterId;
    private final JsonObject before;
    private final JsonObject root;
    /** The chapter revision this screen's copy came from; a save or delete is refused if it moved meanwhile. */
    private final long revision;

    ChapterEditScreen(Screen parent, String chapterId) {
        super(parent, Component.translatable(PREFIX + "chapter.title"));
        this.chapterId = chapterId;
        JsonObject raw = ClientProgress.rawChapter(chapterId);
        this.before = raw == null ? new JsonObject() : raw.deepCopy();
        this.root = this.before.deepCopy();
        this.revision = EditorSession.revision(chapterId);
    }

    @Override
    protected void build() {
        List<EditForm.Line> lines = new ArrayList<>();
        lines.add(new EditForm.HeadLine(Component.translatable(PREFIX + "chapter.fields")));
        lines.addAll(fieldRows(ContentSchema.chapterFields(), root));
        lines.add(new EditForm.HeadLine(Component.translatable(PREFIX + "chapter.skill")));
        lines.addAll(fieldRows(ContentSchema.pointsFields(), root));
        lines.add(new EditForm.CardLine(Component.translatable(PREFIX + "chapter.delete"), null,
                List.of(new EditForm.Action("cross", null, this::confirmDelete, true))));
        form.set(lines);
    }

    private void confirmDelete() {
        if (minecraft == null) {
            return;
        }
        minecraft.setScreen(new ConfirmScreen(confirmed -> {
            minecraft.setScreen(this);
            if (confirmed) {
                delete();
            }
        }, Component.translatable(PREFIX + "chapter.delete"), Component.literal(chapterId),
                Component.translatable(PREFIX + "delete"), Component.translatable(PREFIX + "cancel")));
    }

    private void delete() {
        if (sending) {
            return;
        }
        sending = true;
        EditorSession.send(chapterId, "delete_chapter", new JsonObject(), revision, reply -> {
            if (reply.ok()) {
                closeNow();
            } else {
                sending = false;
                showReply(reply, chapterId, null);
            }
        });
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
        args.add("patch", patch);
        sending = true;
        EditorSession.send(chapterId, "update_chapter", args, revision, reply -> {
            if (reply.ok()) {
                closeNow();
            } else {
                sending = false;
                showReply(reply, chapterId, null);
            }
        });
    }
}
