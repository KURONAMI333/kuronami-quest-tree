package com.kuronami.questtree.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kuronami.questtree.client.editor.ContentSchema;
import com.kuronami.questtree.client.editor.EditorDoc;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * One reward table (2026-10-21, phase 3): rolls, the empty weight, and entries that open the same per-type form a
 * reward uses. A table is written whole with {@code update_table}; its revision is separate from any chapter's.
 */
final class TableEditScreen extends EditorScreen {

    private final String tableId;
    private final boolean create;
    private final JsonObject before;
    private final JsonObject root;
    /** The table revision this screen's copy came from; the save is refused if another operator moved it. */
    private final long revision;

    TableEditScreen(Screen parent, String tableId, boolean create) {
        super(parent, Component.translatable(PREFIX + "table.title"));
        this.tableId = tableId;
        this.create = create;
        JsonObject raw = ClientProgress.rawTables().has(tableId)
                && ClientProgress.rawTables().get(tableId).isJsonObject()
                ? ClientProgress.rawTables().getAsJsonObject(tableId) : null;
        this.before = raw == null ? ContentSchema.newTable() : raw.deepCopy();
        this.root = this.before.deepCopy();
        this.revision = create ? 0 : EditorSession.tableRevision(tableId);
    }

    @Override
    protected void build() {
        List<EditForm.Line> lines = new ArrayList<>();
        lines.add(new EditForm.HeadLine(Component.literal(tableId)));
        lines.addAll(fieldRows(ContentSchema.tableFields(), root));

        lines.add(new EditForm.HeadLine(Component.translatable(PREFIX + "table.entries")));
        JsonArray entries = EditorDoc.array(root, "entries", false);
        int count = entries == null ? 0 : entries.size();
        for (int i = 0; i < count; i++) {
            int index = i;
            JsonObject entry = objectAt(entries, i);
            lines.add(new EditForm.CardLine(entryName(i, entry), () -> openEntry(entry),
                    List.of(
                            new EditForm.Action(null, "^", () -> move(entries, index, -1), index > 0),
                            new EditForm.Action(null, "v", () -> move(entries, index, 1), index < count - 1),
                            new EditForm.Action("cross", null, () -> remove(entries, index), true)),
                    "entries[" + index + "]"));
        }
        lines.add(new EditForm.AddLine(Component.translatable(PREFIX + "add.entry"),
                () -> addEntry(entriesOrCreate())));
        form.set(lines);
    }

    private JsonArray entriesOrCreate() {
        return EditorDoc.array(root, "entries", true);
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

    private static Component entryName(int index, JsonObject entry) {
        JsonElement reward = entry.get("reward");
        String type = reward != null && reward.isJsonObject()
                ? ContentSchema.rewardType(reward.getAsJsonObject()) : "item";
        String weight = EditorDoc.text(entry, "weight");
        String label = "· " + Component.translatable(PREFIX + "reward." + type).getString();
        return Component.literal((index + 1) + " " + label + (weight.isBlank() ? "" : " · " + weight));
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

    private void openEntry(JsonObject entry) {
        if (minecraft != null) {
            minecraft.setScreen(new ContentEditScreen(this, Component.translatable(PREFIX + "entry.title"), entry,
                    ContentEditScreen.Kind.ENTRY));
        }
    }

    private void addEntry(JsonArray entries) {
        JsonObject entry = ContentSchema.newEntry();
        entries.add(entry);
        build();
        form.scrollToEnd();
        openEntry(entry);
    }

    @Override
    protected void requestClose() {
        if (sending) {
            return;
        }
        if (!create && EditorDoc.patch(before, root).isEmpty()) {
            closeNow();
            return;
        }
        JsonObject args = new JsonObject();
        args.addProperty("id", tableId);
        args.addProperty("create", create);
        args.add("table", root.deepCopy());
        sending = true;
        EditorSession.sendTable(tableId, "update_table", args, revision, reply -> {
            if (reply.ok()) {
                closeNow();
            } else {
                sending = false;
                showReply(reply, tableId, null);
            }
        });
    }
}
