package com.kuronami.questtree.progress;

import com.google.gson.JsonElement;
import com.kuronami.questtree.data.ProgressModel;
import com.google.gson.JsonObject;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;

import java.util.Locale;

/**
 * What a task screen may insert (2026-10-22, parity with FTB Quests' task screens): only the three task types that
 * hold a resource. Item screens use the same component-aware matcher as a hand-in; a fluid task takes its fluid, and
 * an energy task takes FE.
 */
public final class ScreenRules {

    private ScreenRules() {
    }

    /** The task type a screen can serve: {@code item}, {@code fluid} or {@code energy}. */
    public static boolean screenCapable(JsonObject task) {
        String type = type(task);
        return type.equals("item") || type.equals("fluid") || type.equals("energy");
    }

    /**
     * FTB Quests' {@code task_screen_only}: the inventory scan and the crafting result leave this item task alone, so
     * only a task screen moves it.
     */
    public static boolean screenOnly(JsonObject task) {
        return task != null && ProgressModel.boolOf(task, "task_screen_only");
    }

    /** A screen serves an item task when the task consumes what is inserted, or when it is screen-only. */
    public static boolean takesItem(ProgressModel.Node node, JsonObject task) {
        return type(task).equals("item")
                && (ProgressRules.consumes(node, task) || screenOnly(task));
    }

    public static boolean acceptsItem(JsonObject task, ItemStack stack, HolderLookup.Provider registries) {
        return type(task).equals("item") && ItemRequirement.of(task).matches(stack, registries);
    }

    /** A blank fluid spec takes any fluid; an id takes that fluid, and {@code #tag} takes the tag. */
    public static boolean acceptsFluid(JsonObject task, Fluid fluid) {
        if (!type(task).equals("fluid")) {
            return false;
        }
        String spec = fluidSpec(task);
        if (spec.isEmpty()) {
            return true;
        }
        if (spec.startsWith("#")) {
            ResourceLocation id = ResourceLocation.tryParse(spec.substring(1));
            return id != null && BuiltInRegistries.FLUID.wrapAsHolder(fluid).is(TagKey.create(Registries.FLUID, id));
        }
        return BuiltInRegistries.FLUID.getKey(fluid).toString().equals(spec);
    }

    public static boolean acceptsEnergy(JsonObject task) {
        return type(task).equals("energy");
    }

    /**
     * FTB Quests' {@code max_input}: the most one transfer may add to an energy task. 0 (the value FTB Quests writes
     * when it is not set, and the default when the key is missing) means no cap.
     */
    public static long maxInput(JsonObject task) {
        if (task == null || !task.has("max_input") || !task.get("max_input").isJsonPrimitive()) {
            return 0;
        }
        try {
            return Math.max(0, task.get("max_input").getAsLong());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** The fluid a task names, as written by the importer ({@code fluid} for FTB Quests). */
    public static String fluidSpec(JsonObject task) {
        JsonElement e = task == null ? null : task.get("fluid");
        if (e == null || e.isJsonNull()) {
            return "";
        }
        if (e.isJsonPrimitive()) {
            return e.getAsString();
        }
        if (e.isJsonObject() && e.getAsJsonObject().has("id")) {
            return e.getAsJsonObject().get("id").getAsString();
        }
        return "";
    }

    private static String type(JsonObject task) {
        return task != null && task.has("type") && task.get("type").isJsonPrimitive()
                ? task.get("type").getAsString().toLowerCase(Locale.ROOT) : "";
    }
}
