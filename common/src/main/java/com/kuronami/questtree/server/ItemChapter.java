package com.kuronami.questtree.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The chapter FTB Quests' {@code generate_chapter} makes: one quest per item, sorted by id, grouped by namespace and
 * laid out 40 to a row with a blank row between namespaces (2026-10-22). Kept without the game so the layout can be
 * tested; the caller supplies the item ids from the creative tabs, a player's inventory or a container.
 */
public final class ItemChapter {

    static final int COLUMNS = 40;

    private ItemChapter() {
    }

    /**
     * One node per distinct item id. Ids are labels for this request only: {@code create_chapter} mints the real node
     * ids and rewrites references, so they never become a permanent translation key.
     */
    public static JsonObject chapter(List<String> itemIds) {
        List<String> items = new ArrayList<>(new LinkedHashSet<>(itemIds));
        items.sort(Comparator.naturalOrder());

        JsonObject chapter = new JsonObject();
        chapter.addProperty("title", "Auto-generated chapter [" + items.size() + " items]");
        JsonObject icon = new JsonObject();
        icon.addProperty("id", "minecraft:book");
        chapter.add("icon", icon);
        JsonObject defaults = new JsonObject();
        defaults.addProperty("shape", "rsquare");
        chapter.add("defaults", defaults);

        JsonObject nodes = new JsonObject();
        int col = 0;
        int row = 0;
        String namespace = null;
        int index = 0;
        for (String id : items) {
            String ns = namespaceOf(id);
            if (namespace == null) {
                namespace = ns;
            }
            if (!namespace.equals(ns)) {
                namespace = ns;
                col = 0;
                row += 2;
            } else if (col >= COLUMNS) {
                col = 0;
                row++;
            }
            JsonObject node = new JsonObject();
            node.addProperty("x", col);
            node.addProperty("y", row);
            JsonArray tasks = new JsonArray();
            JsonObject task = new JsonObject();
            task.addProperty("type", "item");
            JsonObject item = new JsonObject();
            item.addProperty("id", id);
            item.addProperty("count", 1);
            task.add("item", item);
            tasks.add(task);
            node.add("tasks", tasks);
            nodes.add("n" + index++, node);
            col++;
        }
        chapter.add("nodes", nodes);
        return chapter;
    }

    private static String namespaceOf(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(0, colon);
    }
}
