package com.kuronami.questtree.client.viewer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kuronami.questtree.Constants;
import com.kuronami.questtree.client.ClientModel;
import com.kuronami.questtree.client.ClientProgress;
import com.kuronami.questtree.client.viewer.QuestViewerIndex.Index;
import com.kuronami.questtree.client.viewer.QuestViewerIndex.Kind;
import com.kuronami.questtree.client.viewer.QuestViewerIndex.Need;
import com.kuronami.questtree.client.viewer.QuestViewerIndex.Query;
import com.kuronami.questtree.client.viewer.QuestViewerIndex.Quest;
import com.kuronami.questtree.client.viewer.QuestViewerIndex.Spec;
import com.kuronami.questtree.client.viewer.QuestViewerIndex.Visibility;
import com.kuronami.questtree.progress.ItemFilter;
import com.kuronami.questtree.progress.PackNumbers;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the synced quest bundle into the item index the viewers read. The expensive part (walking every node and
 * its raw reward JSON) runs only when the bundle changes; a progress packet only re-applies visibility, because a
 * hidden quest can become visible without any quest data changing.
 */
public final class QuestViewerData {

    private static volatile List<Quest> all = List.of();
    private static volatile Index index = Index.empty();
    /** Set by the EMI plugin: EMI is the one viewer that cannot answer a lookup from a live source. */
    private static volatile Runnable refreshHook;

    private QuestViewerData() {
    }

    public static Index index() {
        return index;
    }

    public static Query query(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return new Query() {
            @Override
            public String itemId() {
                return id;
            }

            @Override
            public boolean inTag(String tagId) {
                ResourceLocation tag = ResourceLocation.tryParse(tagId);
                return tag != null && stack.is(TagKey.create(Registries.ITEM, tag));
            }

            @Override
            public boolean inFilter(String expression) {
                var level = net.minecraft.client.Minecraft.getInstance().level;
                return ItemFilter.parse(expression).test(stack, level == null ? null : level.registryAccess());
            }
        };
    }

    /** Rebuilds from the bundle. Called after a sync (and on logout, with no model). */
    public static void rebuildAll() {
        ClientModel model = ClientProgress.model();
        if (model == null) {
            clear();
            return;
        }
        JsonObject tables = ClientProgress.rawTables();
        List<Quest> quests = new ArrayList<>();
        for (ClientModel.Chapter chapter : model.chapters) {
            for (ClientModel.Node node : chapter.nodes.values()) {
                Quest quest = questOf(chapter, node, tables);
                if (quest != null) {
                    quests.add(quest);
                }
            }
        }
        all = List.copyOf(quests);
        apply();
    }

    /** Re-applies hidden/secret rules after progress moved, without walking the bundle again. */
    public static void refreshVisibility() {
        apply();
    }

    public static void clear() {
        all = List.of();
        Index previous = index;
        index = Index.empty();
        if (!previous.signature().equals(index.signature())) {
            notifyViewers();
        }
    }

    /** EMI calls this from its plugin; JEI and REI read {@link #index()} at lookup time and need no hook. */
    public static void setRefreshHook(Runnable hook) {
        refreshHook = hook;
    }

    private static void apply() {
        Index previous = index;
        index = QuestViewerIndex.build(all, visibility());
        if (!previous.signature().equals(index.signature())) {
            notifyViewers();
        }
    }

    private static Visibility visibility() {
        return quest -> !ClientProgress.chapterHidden(quest.chapterId()) && ClientProgress.visible(quest.key());
    }

    private static void notifyViewers() {
        Runnable hook = refreshHook;
        if (hook == null) {
            return;
        }
        try {
            hook.run();
        } catch (RuntimeException ex) {
            Constants.LOG.warn("Could not refresh the quest entries in EMI", ex);
        }
    }

    private static Quest questOf(ClientModel.Chapter chapter, ClientModel.Node node, JsonObject tables) {
        List<Need> needs = new ArrayList<>();
        for (int i = 0; i < node.tasks.size(); i++) {
            ClientModel.Task task = node.tasks.get(i);
            if (task.filter != null) {
                // one row per task, not one per example: the filter itself answers whether a stack counts
                needs.add(new Need(Kind.TASK, Spec.filter(task.filter.expression()), task.count,
                        task.name().getString(), i, false));
            } else {
                addSpec(needs, Kind.TASK, task.itemSpec(), task.count, task.name().getString(), i, false);
            }
        }
        JsonObject raw = ClientProgress.rawNode(chapter.id, node.id);
        JsonArray rewards = arr(raw, "rewards");
        for (int i = 0; i < rewards.size(); i++) {
            if (!rewards.get(i).isJsonObject()) {
                continue;
            }
            JsonObject reward = rewards.get(i).getAsJsonObject();
            String type = string(reward, "type");
            if (type.equals("table")) {
                boolean choice = string(reward, "mode").equals("choice");
                JsonElement table = tables.get(string(reward, "table"));
                JsonArray entries = table != null && table.isJsonObject()
                        ? arr(table.getAsJsonObject(), "entries") : new JsonArray();
                for (JsonElement entry : entries) {
                    if (!entry.isJsonObject()) {
                        continue;
                    }
                    JsonObject row = entry.getAsJsonObject();
                    JsonObject inner = row.has("reward") && row.get("reward").isJsonObject()
                            ? row.getAsJsonObject("reward") : row;
                    addReward(needs, inner, i, choice);
                }
            } else {
                addReward(needs, reward, i, false);
            }
        }
        if (needs.isEmpty()) {
            return null;
        }
        ItemStack icon = !node.icon.isEmpty() ? node.icon
                : node.iconTask != null ? node.iconTask.shown() : ItemStack.EMPTY;
        return new Quest(node.key, chapter.id, node.title.getString(), chapter.title.getString(),
                icon.isEmpty() ? "" : itemId(icon), needs);
    }

    private static void addReward(List<Need> needs, JsonObject reward, int index, boolean choice) {
        String type = string(reward, "type");
        if (!type.isEmpty() && !type.equals("item")) {
            return;
        }
        JsonElement item = reward.get("item");
        if (item == null || item.isJsonNull()) {
            return;
        }
        Spec parsed = specOf(item);
        addSpec(needs, Kind.REWARD, parsed, countOf(item), labelOf(parsed, item), index, choice);
    }

    private static void addSpec(List<Need> needs, Kind kind, String spec, long count, String label,
                                int index, boolean choice) {
        if (spec == null || spec.isEmpty()) {
            return;
        }
        Spec parsed = spec.startsWith("#") ? Spec.tag(spec.substring(1))
                : spec.startsWith("~") ? Spec.filter(spec.substring(1)) : Spec.item(spec);
        addSpec(needs, kind, parsed, count, label, index, choice);
    }

    private static void addSpec(List<Need> needs, Kind kind, Spec parsed, long count, String label,
                                int index, boolean choice) {
        if (parsed == null || parsed.id().isEmpty()) {
            return;
        }
        needs.add(new Need(kind, parsed, Math.max(1, count), label, index, choice));
    }

    private static Spec specOf(JsonElement item) {
        if (item == null || item.isJsonNull()) {
            return Spec.item("");
        }
        if (item.isJsonPrimitive()) {
            String value = item.getAsString();
            return value.startsWith("#") ? Spec.tag(value.substring(1)) : Spec.item(value);
        }
        if (!item.isJsonObject()) {
            return Spec.item("");
        }
        JsonObject object = item.getAsJsonObject();
        if (object.has("tag") && object.get("tag").isJsonPrimitive()) {
            return Spec.tag(object.get("tag").getAsString());
        }
        return object.has("id") && object.get("id").isJsonPrimitive()
                ? Spec.item(object.get("id").getAsString()) : Spec.item("");
    }

    private static long countOf(JsonElement item) {
        return item != null && item.isJsonObject()
                ? Math.max(1, PackNumbers.intOr(item.getAsJsonObject().get("count"), 1)) : 1;
    }

    private static String labelOf(Spec spec, JsonElement item) {
        if (spec.tag()) {
            return spec.id();
        }
        ItemStack stack = stackOf(spec.id());
        if (!stack.isEmpty()) {
            return stack.getHoverName().getString();
        }
        int colon = spec.id().indexOf(':');
        return colon >= 0 ? spec.id().substring(colon + 1) : spec.id();
    }

    private static ItemStack stackOf(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null) {
            return ItemStack.EMPTY;
        }
        var item = BuiltInRegistries.ITEM.get(location);
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static String string(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsString() : "";
    }

    private static JsonArray arr(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonArray()
                ? object.getAsJsonArray(key) : new JsonArray();
    }
}
