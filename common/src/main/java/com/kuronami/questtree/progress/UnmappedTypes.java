package com.kuronami.questtree.progress;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kuronami.questtree.api.QuestTreeApi;
import com.kuronami.questtree.data.ProgressModel;

/**
 * Tasks and rewards an import kept although no handler here knows their type: an FTB Quests add-on's kind such as
 * {@code ftbmoney:money}, carried over with its own fields so a mod that registers that type can read it (2026-11-01).
 * <p>
 * While nothing registers the type the entry is skipped rather than failed: a task does not count toward its quest
 * and is not shown, and a reward gives nothing and is not shown. The quest around them stays playable instead of being
 * dropped as a broken node.
 */
public final class UnmappedTypes {

    /** The marker the importer writes on a task or reward it kept without knowing its type. */
    public static final String MARKER = "imported_unmapped";

    /** What the server writes into the synced bundle, so a client does not need the registration state. */
    public static final String SKIP = "skip";

    private UnmappedTypes() {
    }

    /** Whether this task is skipped: kept by an import, and no mod registers its type. */
    public static boolean taskSkipped(JsonObject task) {
        return skipped(task, QuestTreeApi::taskTypeRegistered);
    }

    /** As {@link #taskSkipped}, for a reward. */
    public static boolean rewardSkipped(JsonObject reward) {
        return skipped(reward, QuestTreeApi::rewardTypeRegistered);
    }

    private static boolean skipped(JsonObject o, java.util.function.Predicate<String> registered) {
        if (!ProgressModel.boolOf(o, MARKER)) {
            return false;
        }
        // the server decides once and writes the answer into the bundle it sends; a client reads that instead of its
        // own registry, which only holds what the client side registered
        JsonElement skip = o.get(SKIP);
        if (skip != null && skip.isJsonPrimitive() && skip.getAsJsonPrimitive().isBoolean()) {
            return skip.getAsBoolean();
        }
        return !registered.test(type(o));
    }

    /** The type as written, namespaced; a bare name is a type of the game itself, as everywhere else in the API. */
    static String type(JsonObject o) {
        return o != null && o.has("type") && o.get("type").isJsonPrimitive() ? o.get("type").getAsString() : "";
    }

    /**
     * Writes {@link #SKIP} onto every imported task and reward in the copy of the bundle that goes to clients, saying
     * whether this server has a handler for its type. Callers pass the copy, never the loaded book, so the marker never
     * reaches a data pack.
     */
    public static void markSkipped(JsonObject bundle) {
        if (bundle == null || !bundle.has("chapters") || !bundle.get("chapters").isJsonObject()) {
            return;
        }
        for (JsonElement chapter : bundle.getAsJsonObject("chapters").asMap().values()) {
            if (chapter == null || !chapter.isJsonObject()) {
                continue;
            }
            JsonElement nodes = chapter.getAsJsonObject().get("nodes");
            if (nodes == null || !nodes.isJsonObject()) {
                continue;
            }
            for (JsonElement node : nodes.getAsJsonObject().asMap().values()) {
                if (node == null || !node.isJsonObject()) {
                    continue;
                }
                mark(node.getAsJsonObject().get("tasks"), QuestTreeApi::taskTypeRegistered);
                mark(node.getAsJsonObject().get("rewards"), QuestTreeApi::rewardTypeRegistered);
            }
        }
        if (bundle.has("reward_tables") && bundle.get("reward_tables").isJsonObject()) {
            for (JsonElement table : bundle.getAsJsonObject("reward_tables").asMap().values()) {
                if (table != null && table.isJsonObject()) {
                    mark(table.getAsJsonObject().get("entries"), null);
                }
            }
        }
    }

    /** One list of tasks, rewards, or table entries ({@code registered} is null there, and each entry wraps its reward). */
    private static void mark(JsonElement list, java.util.function.Predicate<String> registered) {
        if (list == null || !list.isJsonArray()) {
            return;
        }
        for (JsonElement e : list.getAsJsonArray()) {
            if (e == null || !e.isJsonObject()) {
                continue;
            }
            JsonObject o = e.getAsJsonObject();
            if (registered == null) {
                JsonElement reward = o.get("reward");
                if (reward != null && reward.isJsonObject()) {
                    markOne(reward.getAsJsonObject(), QuestTreeApi::rewardTypeRegistered);
                }
                continue;
            }
            markOne(o, registered);
        }
    }

    private static void markOne(JsonObject o, java.util.function.Predicate<String> registered) {
        if (ProgressModel.boolOf(o, MARKER)) {
            // write the answer either way: a client may lack the mod that the server has, so it must not decide again
            o.addProperty(SKIP, !registered.test(type(o)));
        }
    }
}
