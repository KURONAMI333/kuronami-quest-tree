package com.kuronami.questtree.api.client;

import com.google.gson.JsonObject;
import com.kuronami.questtree.Constants;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How a task or a reward registered with {@link com.kuronami.questtree.api.QuestTreeApi} looks on the quest screen.
 * <h2>What it does</h2>
 * A type another mod registers shows up in the details panel with the built-in row: the task's own item and name, and
 * a {@code have/count} on the right. This class lets that mod say what its type looks like instead, without changing
 * anything the quest itself says.
 * <pre>{@code
 * ResourceLocation level = ResourceLocation.fromNamespaceAndPath("example", "level");
 * QuestTreeClientApi.registerTaskDisplay(level, task -> new QuestTreeClientApi.Display(
 *         new ItemStack(Items.EXPERIENCE_BOTTLE),
 *         Component.literal("Level"),
 *         Component.literal((task.current() >= task.required() ? task.required() : task.current()) + "/" + task.required())));
 *
 * QuestTreeClientApi.registerRewardDisplay(level, reward -> new QuestTreeClientApi.Display(
 *         new ItemStack(Items.DIAMOND), Component.literal("Prize"), null));
 * }</pre>
 * <h2>What is passed and what comes back</h2>
 * The task or reward is taken from the book's JSON as written by the pack. The JSON is a copy: changing it changes
 * nothing in the book. A task also gets its current value and the count it needs.
 * <p>
 * A row leaves out whatever the callback does not fill: an empty icon or a null name keeps the built-in one, and a
 * null progress keeps the built-in {@code have/count}. A reward's name is drawn under its icon in the details panel,
 * where a reward table's own name goes.
 * <h2>When it goes wrong</h2>
 * An exception from a callback is caught: that look is dropped and the built-in row is drawn. The first failure of a
 * type is logged once, and that type is not asked again for the rest of the session, so a broken callback cannot fill
 * the log or slow the screen down.
 * <h2>Loading</h2>
 * Client only, and only touched from the quest screen: nothing on a dedicated server refers to this class, so it is
 * never loaded there. Register from the client side of your mod, once, before the book is opened; registering an id
 * again replaces the previous callback.
 *
 * @see com.kuronami.questtree.api.QuestTreeApi
 */
public final class QuestTreeClientApi {

    /**
     * What one row shows. {@code icon}: empty keeps the built-in icon. {@code name}: null keeps the built-in name.
     * {@code progress}: null keeps the built-in progress; for a reward it is not drawn.
     */
    public record Display(ItemStack icon, Component name, Component progress) {

        /** Keeps every part of the built-in row. */
        public static final Display NONE = new Display(ItemStack.EMPTY, null, null);

        public Display {
            icon = icon == null ? ItemStack.EMPTY : icon;
        }

        /** Only a different icon. */
        public static Display icon(ItemStack icon) {
            return new Display(icon, null, null);
        }
    }

    /** One task as the book writes it, plus the values the row shows. */
    public record TaskView(JsonObject json, long current, long required) {
    }

    /** One reward as the book writes it. */
    public record RewardView(JsonObject json) {
    }

    /** What a task type looks like. Return {@link Display#NONE} to keep the built-in row. */
    @FunctionalInterface
    public interface TaskDisplay {
        Display display(TaskView task);
    }

    /** What a reward type looks like. Return {@link Display#NONE} to keep the built-in row. */
    @FunctionalInterface
    public interface RewardDisplay {
        Display display(RewardView reward);
    }

    private static final Map<String, TaskDisplay> TASKS = new ConcurrentHashMap<>();
    private static final Map<String, RewardDisplay> REWARDS = new ConcurrentHashMap<>();
    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();

    private QuestTreeClientApi() {
    }

    /**
     * Sets how tasks of this type look. {@code id} is the same id the type was registered with on the server; a
     * handler registered again for the same id replaces the old one.
     */
    public static void registerTaskDisplay(ResourceLocation id, TaskDisplay display) {
        register(TASKS, FAILED, id, display, "task");
    }

    /**
     * Sets how rewards of this type look. {@code id} is the same id the type was registered with on the server; a
     * handler registered again for the same id replaces the old one.
     */
    public static void registerRewardDisplay(ResourceLocation id, RewardDisplay display) {
        register(REWARDS, FAILED, id, display, "reward");
    }

    /** Removes a task look, so the built-in row comes back. Returns whether one was there. */
    public static boolean unregisterTaskDisplay(String id) {
        String key = key(id);
        if (key == null) {
            return false;
        }
        FAILED.remove("task:" + key);
        return TASKS.remove(key) != null;
    }

    /** Removes a reward look, so the built-in row comes back. Returns whether one was there. */
    public static boolean unregisterRewardDisplay(String id) {
        String key = key(id);
        if (key == null) {
            return false;
        }
        FAILED.remove("reward:" + key);
        return REWARDS.remove(key) != null;
    }

    /** Whether a reward type has a display, so the screen gives a reward of that type a slot. Called by the screen. */
    public static boolean hasRewardDisplay(String type) {
        String key = REWARDS.isEmpty() ? null : key(type);
        return key != null && REWARDS.containsKey(key) && !FAILED.contains("reward:" + key);
    }

    /**
     * The look of one task row, or null when the type has no display (or its display failed), in which case the
     * screen draws the built-in row. Called by the quest screen; an addon does not need it.
     */
    public static Display taskDisplay(String type, JsonObject json, long current, long required) {
        if (TASKS.isEmpty()) {
            return null;
        }
        String key = key(type);
        TaskDisplay display = key == null || FAILED.contains("task:" + key) ? null : TASKS.get(key);
        if (display == null) {
            return null;
        }
        try {
            return display.display(new TaskView(json, current, required));
        } catch (RuntimeException ex) {
            fail(key, "task", ex);
            return null;
        }
    }

    /**
     * The look of one reward row, or null when the type has no display (or its display failed), in which case the
     * screen draws the built-in row. Called by the quest screen; an addon does not need it.
     */
    public static Display rewardDisplay(String type, JsonObject json) {
        if (REWARDS.isEmpty()) {
            return null;
        }
        String key = key(type);
        RewardDisplay display = key == null || FAILED.contains("reward:" + key) ? null : REWARDS.get(key);
        if (display == null) {
            return null;
        }
        try {
            return display.display(new RewardView(json));
        } catch (RuntimeException ex) {
            fail(key, "reward", ex);
            return null;
        }
    }

    private static <T> void register(Map<String, T> into, Set<String> failed, ResourceLocation id, T display, String kind) {
        String key = id == null ? null : id.toString();
        if (key == null || display == null) {
            Constants.LOG.warn("{} display registration needs an id and a handler (id {}, handler {})", kind, id, display);
            return;
        }
        into.put(key, display);
        // a type whose look failed may be fixed by a new registration, so it is asked again from then on
        failed.remove(kind + ":" + key);
    }

    private static void fail(String key, String kind, RuntimeException ex) {
        if (FAILED.add(kind + ":" + key)) {
            Constants.LOG.warn("{} display for '{}' failed; its rows keep the built-in look: {}", kind, key, ex.toString());
        }
    }

    /** The same namespaced form the server's registry uses, so {@code level} and {@code minecraft:level} are one id. */
    private static String key(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl == null ? null : rl.toString();
    }
}
