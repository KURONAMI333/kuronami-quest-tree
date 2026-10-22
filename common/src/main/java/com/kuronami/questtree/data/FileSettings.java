package com.kuronami.questtree.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The pack-wide settings written by the FTB Quests importer ({@code progression/settings.json}, the source book's
 * {@code data.snbt}). Reads both the file as FTB Quests serialises it ({@code default_quest_disable_jei}, {@code preset},
 * {@code lock_message}, ...) and the friendlier names the editor writes under {@code defaults} (2026-10-22).
 */
public final class FileSettings {

    /** One named visual preset: a shape and a size (FTB Quests' {@code VisualPresetRecord}). */
    public record Preset(String shape, double size) {
    }

    public static final FileSettings EMPTY = of(new JsonObject());

    /** {@code show_lock_icons}; default true. */
    public final boolean lockIcons;
    /** {@code lock_message}: the book's own refusal text, "" when the pack sets none. */
    public final String lockMessage;
    /** {@code hide_excluded_quests}. */
    public final boolean hideExcludedQuests;
    /** {@code disable_gui}: the book refuses to open. */
    public final boolean disableGui;
    /** {@code grid_scale}; FTB Quests' default is 0.5. */
    public final double gridScale;
    /** {@code pause_game}: single-player pauses while the book is open. */
    public final boolean pauseGame;
    /** {@code detection_delay} in ticks. */
    public final int detectionDelay;
    /** {@code default_reward_team}. */
    public final boolean rewardTeam;
    /** {@code default_consume_items}. */
    public final boolean consumeItems;
    /** {@code default_autoclaim_rewards}: "default", "enabled" or "disabled". */
    public final String autoClaim;
    /** {@code default_quest_shape}: "" when the pack leaves nodes to their role. */
    public final String questShape;
    /** {@code default_quest_disable_jei}: the book hides quests from recipe viewers unless a quest says otherwise. */
    public final boolean questDisableJei;
    /** {@code preset}: the file's default visual preset, "" when unset. */
    public final String defaultPreset;
    public final Map<String, Preset> presets;
    /** {@code suppress_all_autoclaiming}. */
    public final boolean suppressAllAutoclaiming;

    private FileSettings(JsonObject o) {
        JsonObject defaults = obj(o, "defaults");
        this.lockIcons = bool(o, "show_lock_icons", true);
        this.lockMessage = text(o, "lock_message");
        this.hideExcludedQuests = bool(o, "hide_excluded_quests", false);
        this.disableGui = bool(o, "disable_gui", false);
        this.gridScale = clamp(number(o, "grid_scale", 0.5), 0.25, 3.0);
        this.pauseGame = bool(o, "pause_game", false);
        this.detectionDelay = Math.max(0, Math.min(200, integer(o, "detection_delay", 20)));
        this.rewardTeam = bool(o, "default_reward_team", bool(defaults, "team", false));
        this.consumeItems = bool(o, "default_consume_items", bool(defaults, "consume_items", false));
        String auto = text(o, "default_autoclaim_rewards");
        this.autoClaim = auto.isEmpty() ? choice(defaults, "auto", "default") : auto;
        String shape = text(o, "default_quest_shape");
        this.questShape = !shape.isEmpty() ? shape : "default".equals(text(defaults, "shape")) ? ""
                : text(defaults, "shape");
        this.questDisableJei = bool(o, "default_quest_disable_jei",
                bool(defaults, "disable_recipe_viewer", false));
        String preset = text(o, "preset");
        this.defaultPreset = !preset.isEmpty() ? preset : text(defaults, "preset");
        this.presets = readPresets(obj(o, "presets"));
        this.suppressAllAutoclaiming = bool(o, "suppress_all_autoclaiming", false);
    }

    public static FileSettings of(JsonObject settings) {
        return new FileSettings(settings == null ? new JsonObject() : settings);
    }

    /** The named preset, or null when the name is empty or unknown (FTB Quests then keeps the node's own values). */
    public Preset preset(String name) {
        return name == null || name.isEmpty() ? null : presets.get(name);
    }

    /** Whether a reward is claimed on completion ({@code auto}), with the file's default and all-off switch applied. */
    public boolean auto(JsonObject reward) {
        if (suppressAllAutoclaiming) {
            return false;
        }
        JsonElement e = reward.get("auto");
        if (e != null && e.isJsonPrimitive()) {
            if (e.getAsJsonPrimitive().isBoolean()) {
                return e.getAsBoolean();
            }
            String v = e.getAsString();
            if (v.equals("enabled") || v.equals("true")) {
                return true;
            }
            if (v.equals("disabled") || v.equals("false")) {
                return false;
            }
        }
        return Boolean.TRUE.equals(autoClaimDefault());
    }

    /** Whether a reward is team-wide ({@code team}), falling back to {@code default_reward_team}. */
    public boolean team(JsonObject reward) {
        JsonElement e = reward.get("team");
        return e != null && e.isJsonPrimitive() ? e.getAsBoolean() : rewardTeam;
    }

    /** Whether a node that sets no {@code hide_from_recipe_viewer} of its own stays out of recipe viewers. */
    public boolean hidesFromRecipeViewers() {
        return questDisableJei;
    }

    /** The auto-claim default: true/false when the file decides, null when every reward keeps its own value. */
    public Boolean autoClaimDefault() {
        return switch (autoClaim) {
            case "enabled", "true" -> Boolean.TRUE;
            case "disabled", "false" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static Map<String, Preset> readPresets(JsonObject o) {
        if (o.isEmpty()) {
            return Map.of();
        }
        Map<String, Preset> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            if (!e.getValue().isJsonObject()) {
                continue;
            }
            JsonObject p = e.getValue().getAsJsonObject();
            out.put(e.getKey(), new Preset(text(p, "shape"), number(p, "size", 1)));
        }
        return Collections.unmodifiableMap(out);
    }

    private static JsonObject obj(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : new JsonObject();
    }

    private static boolean bool(JsonObject o, String key, boolean fallback) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            return fallback;
        }
        if (e.getAsJsonPrimitive().isBoolean()) {
            return e.getAsBoolean();
        }
        if (e.getAsJsonPrimitive().isNumber()) {
            return e.getAsInt() != 0;
        }
        return Boolean.parseBoolean(e.getAsString());
    }

    private static double number(JsonObject o, String key, double fallback) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        return e.getAsDouble();
    }

    private static int integer(JsonObject o, String key, int fallback) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        return e.getAsInt();
    }

    private static String text(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static String choice(JsonObject o, String key, String fallback) {
        String v = text(o, key);
        return v.isEmpty() ? fallback : v;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
