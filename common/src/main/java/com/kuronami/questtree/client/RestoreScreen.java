package com.kuronami.questtree.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * What can be brought back (2026-10-21, phase 4): the chapters whose tombstone is on disk, and the deleted nodes of
 * the chapters still in the book. A row is the id (the only name a tombstone reliably keeps) and a tick.
 */
final class RestoreScreen extends EditorScreen {

    private final List<String> chapters = new ArrayList<>();
    private final List<String> tables = new ArrayList<>();
    private final List<String[]> nodes = new ArrayList<>();

    RestoreScreen(Screen parent) {
        super(parent, Component.translatable(PREFIX + "deleted.title"));
        JsonObject deleted = ClientProgress.rawDeleted();
        JsonElement chapterList = deleted.get("chapters");
        if (chapterList != null && chapterList.isJsonArray()) {
            for (JsonElement e : chapterList.getAsJsonArray()) {
                if (e.isJsonPrimitive()) {
                    chapters.add(e.getAsString());
                }
            }
        }
        JsonElement tableList = deleted.get("tables");
        if (tableList != null && tableList.isJsonArray()) {
            for (JsonElement e : tableList.getAsJsonArray()) {
                if (e.isJsonPrimitive()) {
                    tables.add(e.getAsString());
                }
            }
        }
        JsonElement nodeMap = deleted.get("nodes");
        if (nodeMap != null && nodeMap.isJsonObject()) {
            for (var entry : nodeMap.getAsJsonObject().entrySet()) {
                if (!entry.getValue().isJsonArray()) {
                    continue;
                }
                for (JsonElement e : entry.getValue().getAsJsonArray()) {
                    if (e.isJsonPrimitive()) {
                        nodes.add(new String[]{entry.getKey(), e.getAsString()});
                    }
                }
            }
        }
    }

    @Override
    protected void build() {
        List<EditForm.Line> lines = new ArrayList<>();
        if (chapters.isEmpty() && tables.isEmpty() && nodes.isEmpty()) {
            lines.add(new EditForm.HeadLine(Component.translatable(PREFIX + "option.none")));
        }
        if (!chapters.isEmpty()) {
            lines.add(new EditForm.HeadLine(Component.translatable(PREFIX + "deleted.chapters")));
            for (String chapter : new ArrayList<>(chapters)) {
                lines.add(new EditForm.CardLine(Component.literal(chapter), null,
                        List.of(new EditForm.Action("tick", null, () -> restoreChapter(chapter), true))));
            }
        }
        if (!tables.isEmpty()) {
            lines.add(new EditForm.HeadLine(Component.translatable(PREFIX + "deleted.tables")));
            for (String table : new ArrayList<>(tables)) {
                lines.add(new EditForm.CardLine(Component.literal(table), null,
                        List.of(new EditForm.Action("tick", null, () -> restoreTable(table), true))));
            }
        }
        if (!nodes.isEmpty()) {
            lines.add(new EditForm.HeadLine(Component.translatable(PREFIX + "deleted.nodes")));
            for (String[] node : new ArrayList<>(nodes)) {
                lines.add(new EditForm.CardLine(Component.literal(node[0] + " / " + node[1]), null,
                        List.of(new EditForm.Action("tick", null, () -> restoreNode(node[0], node[1]), true))));
            }
        }
        form.set(lines);
    }

    private void restoreChapter(String chapterId) {
        EditorSession.send(chapterId, "restore_chapter", new JsonObject(), reply -> {
            if (reply.ok()) {
                chapters.remove(chapterId);
                build();
            } else {
                showReply(reply, chapterId, null);
            }
        });
    }

    private void restoreTable(String tableId) {
        JsonObject args = new JsonObject();
        args.addProperty("id", tableId);
        EditorSession.sendTable(tableId, "restore_table", args, EditorSession.tableRevision(tableId), reply -> {
            if (reply.ok()) {
                tables.remove(tableId);
                build();
            } else {
                showReply(reply, tableId, null);
            }
        });
    }

    private void restoreNode(String chapterId, String nodeId) {
        JsonObject args = new JsonObject();
        args.addProperty("id", nodeId);
        EditorSession.send(chapterId, "restore_node", args, reply -> {
            if (reply.ok()) {
                nodes.removeIf(n -> n[0].equals(chapterId) && n[1].equals(nodeId));
                build();
            } else {
                showReply(reply, chapterId, nodeId);
            }
        });
    }

    @Override
    protected void cancel() {
        closeNow();
    }
}
