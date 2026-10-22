package com.kuronami.questtree.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kuronami.questtree.client.editor.ContentSchema;
import com.kuronami.questtree.client.editor.DecorationGeometry;
import com.kuronami.questtree.client.editor.EditorDoc;
import com.kuronami.questtree.client.editor.FieldSpec;
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
        lines.add(new EditForm.HeadLine(Component.translatable(PREFIX + "chapter.decorations")));
        JsonArray decorations = EditorDoc.array(root, "decorations", false);
        for (int i = 0; decorations != null && i < decorations.size(); i++) {
            if (!decorations.get(i).isJsonObject()) {
                continue;
            }
            JsonObject decoration = decorations.get(i).getAsJsonObject();
            String image = EditorDoc.text(decoration, "image");
            int index = i;
            List<EditForm.Action> actions = new ArrayList<>();
            actions.add(new EditForm.Action(null, "^", () -> moveDecoration(index, -1), true));
            actions.add(new EditForm.Action(null, "v", () -> moveDecoration(index, 1), true));
            actions.add(new EditForm.Action(null, "W", () -> fixAspect(index, true), true));
            actions.add(new EditForm.Action(null, "H", () -> fixAspect(index, false), true));
            actions.add(new EditForm.Action("cross", null, () -> removeDecoration(index), true));
            lines.add(new EditForm.CardLine(image.isEmpty()
                    ? Component.translatable(PREFIX + "chapter.decoration.image")
                    : Component.literal(image), null, actions));
            lines.addAll(fieldRows(withPrefix(ContentSchema.decorationFields(), "decorations[" + i + "]."), root));
        }
        lines.add(new EditForm.CardLine(Component.translatable(PREFIX + "chapter.decoration.add"), this::addDecoration,
                List.of(new EditForm.Action(null, "*", this::pasteDecorationImage, true))));
        lines.add(new EditForm.CardLine(Component.translatable(PREFIX + "chapter.delete"), null,
                List.of(new EditForm.Action("cross", null, this::confirmDelete, true))));
        form.set(lines);
    }

    private static List<FieldSpec> withPrefix(List<FieldSpec> fields, String prefix) {
        List<FieldSpec> out = new ArrayList<>();
        for (FieldSpec spec : fields) {
            out.add(spec.under(prefix));
        }
        return out;
    }

    /** The array to write into; a decorations member of the wrong shape is replaced only when the author adds one. */
    private JsonArray decorations() {
        JsonArray array = EditorDoc.array(root, "decorations", false);
        if (array == null) {
            array = new JsonArray();
            root.add("decorations", array);
        }
        return array;
    }

    private void addDecoration() {
        decorations().add(ContentSchema.newDecoration());
        build();
    }

    /** An image path from the clipboard, the same way the map's paste-image entry adds one. */
    private void pasteDecorationImage() {
        if (minecraft == null) {
            return;
        }
        String image = minecraft.keyboardHandler.getClipboard().trim();
        if (image.isEmpty()) {
            return;
        }
        JsonObject decoration = ContentSchema.newDecoration();
        decoration.addProperty("image", image);
        decorations().add(decoration);
        build();
    }

    private void removeDecoration(int index) {
        JsonArray decorations = decorations();
        if (index < 0 || index >= decorations.size()) {
            return;
        }
        decorations.remove(index);
        build();
    }

    /** Up and down change the draw order: a later decoration covers an earlier one. */
    private void moveDecoration(int index, int delta) {
        JsonArray decorations = decorations();
        int to = index + delta;
        if (index < 0 || to < 0 || index >= decorations.size() || to >= decorations.size()) {
            return;
        }
        JsonElement moved = decorations.get(index);
        decorations.set(index, decorations.get(to));
        decorations.set(to, moved);
        build();
    }

    /**
     * Fixes one side from the file's own pixels (2026-10-22, p6-editor), the same button FTB Quests has on a chapter
     * image: choose whether the width or the height is the side to keep, and the other follows the texture.
     */
    private void fixAspect(int index, boolean keepHeight) {
        JsonArray decorations = decorations();
        if (index < 0 || index >= decorations.size() || !decorations.get(index).isJsonObject()) {
            return;
        }
        JsonObject decoration = decorations.get(index).getAsJsonObject();
        net.minecraft.resources.ResourceLocation path = Tiles.imagePath(EditorDoc.text(decoration, "image"));
        if (path == null) {
            return;
        }
        int[] pixels = Tiles.textureSize(path);
        double height = number(decoration, "h", 2);
        double width = number(decoration, "w", 2);
        decoration.addProperty(keepHeight ? "w" : "h", keepHeight
                ? DecorationGeometry.widthFor(height, pixels[0], pixels[1])
                : DecorationGeometry.heightFor(width, pixels[0], pixels[1]));
        build();
    }

    private static double number(JsonObject object, String key, double fallback) {
        try {
            return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsDouble() : fallback;
        } catch (RuntimeException ex) {
            return fallback;
        }
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
