package com.kuronami.questtree.client.edit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A copied group of nodes (2026-10-20, phase 2): positions relative to the group's top-left, and the links inside it.
 * <p>
 * Copying uses the raw chapter JSON the server sent, not the resolved display model, so a paste keeps every field the
 * author wrote (tasks, rewards, colours, repeat rules) without a round trip to ask for it. Links that point inside the
 * group are dropped from the created nodes and sent again once the server has minted the new ids; links that point
 * outside stay as they are. The server mints every id, so a paste never has to guess one.
 */
public final class EditorClipboard {

    /** One copied node: its old id, its position in grid units, and its raw chapter JSON. */
    public record Entry(String id, double x, double y, JsonObject node) {
    }

    /** A link inside the group: {@code from} requires {@code to}. */
    public record Link(String from, String to) {
    }

    private final List<Entry> entries;
    private final Set<String> ids = new LinkedHashSet<>();
    private final double minX;
    private final double minY;

    public EditorClipboard(List<Entry> entries) {
        this.entries = List.copyOf(entries);
        double x = Double.MAX_VALUE;
        double y = Double.MAX_VALUE;
        for (Entry e : entries) {
            ids.add(e.id());
            x = Math.min(x, e.x());
            y = Math.min(y, e.y());
        }
        this.minX = entries.isEmpty() ? 0 : x;
        this.minY = entries.isEmpty() ? 0 : y;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    public double minX() {
        return minX;
    }

    public double minY() {
        return minY;
    }

    public List<Entry> entries() {
        return entries;
    }

    /**
     * The JSON for one create_node op: its own fields, the copied position moved so the group's top-left sits at
     * {@code (atX, atY)}, no id (the server mints it), and no link to another node in the same copy.
     */
    public JsonObject nodeAt(Entry entry, double atX, double atY, boolean snap) {
        JsonObject out = entry.node().deepCopy();
        out.remove("id");
        double x = atX + (entry.x() - minX);
        double y = atY + (entry.y() - minY);
        out.addProperty("x", GridSnap.snap(x, snap));
        out.addProperty("y", GridSnap.snap(y, snap));
        if (out.has("requires") && out.get("requires").isJsonObject()) {
            JsonObject requires = out.getAsJsonObject("requires");
            if (requires.has("nodes") && requires.get("nodes").isJsonArray()) {
                JsonArray kept = new JsonArray();
                for (JsonElement n : requires.getAsJsonArray("nodes")) {
                    if (n.isJsonPrimitive() && !ids.contains(n.getAsString())) {
                        kept.add(n);
                    }
                }
                requires.add("nodes", kept);
            }
        }
        if (out.has("exclusive_with") && out.get("exclusive_with").isJsonArray()) {
            JsonArray kept = new JsonArray();
            for (JsonElement n : out.getAsJsonArray("exclusive_with")) {
                if (n.isJsonPrimitive() && !ids.contains(n.getAsString())) {
                    kept.add(n);
                }
            }
            out.add("exclusive_with", kept);
        }
        return out;
    }

    /** All nodes at a position, in the order they were copied. */
    public List<JsonObject> nodesAt(double atX, double atY, boolean snap) {
        List<JsonObject> out = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            out.add(nodeAt(e, atX, atY, snap));
        }
        return out;
    }

    /** The links inside the copy, by old id; the caller rewrites them after the server replies with the new ids. */
    public List<Link> internalLinks() {
        List<Link> out = new ArrayList<>();
        for (Entry e : entries) {
            JsonObject node = e.node();
            if (!node.has("requires") || !node.get("requires").isJsonObject()) {
                continue;
            }
            JsonElement names = node.getAsJsonObject("requires").get("nodes");
            if (names == null || !names.isJsonArray()) {
                continue;
            }
            for (JsonElement n : names.getAsJsonArray()) {
                if (n.isJsonPrimitive() && ids.contains(n.getAsString())) {
                    out.add(new Link(e.id(), n.getAsString()));
                }
            }
        }
        return out;
    }
}
