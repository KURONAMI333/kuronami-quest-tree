package com.kuronami.questtree.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kuronami.questtree.Constants;
import com.kuronami.questtree.platform.Services;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Client settings ({@code config/kuronami_quest_tree-client.json}). Defaults are the product; the file is the way out
 * for players who want something off. Written once with the defaults so it can be found.
 */
public final class ClientConfig {

    /** Where a quest or skill completion is announced (FTB Quests' completion style, 2026-10-20). */
    public enum NotificationStyle {
        TOAST("toast"), CHAT("chat"), ACTION_BAR("action_bar"), NONE("none");

        final String id;

        NotificationStyle(String id) {
            this.id = id;
        }

        static NotificationStyle byId(String id) {
            for (NotificationStyle s : values()) {
                if (s.id.equals(id)) {
                    return s;
                }
            }
            return TOAST;
        }

        NotificationStyle next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /** What the side tracker shows (FTB Quests' pinned tracker visibility). */
    public enum TrackerMode {
        ALL("all"), QUESTS_ONLY("quests_only"), HIDDEN("hidden");

        final String id;

        TrackerMode(String id) {
            this.id = id;
        }

        static TrackerMode byId(String id) {
            for (TrackerMode m : values()) {
                if (m.id.equals(id)) {
                    return m;
                }
            }
            return HIDDEN;
        }

        TrackerMode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /** The "needed for" line on item tooltips. */
    public static boolean itemTooltips = true;

    /** Liquid Glass on the screen's controls and panels; off draws them as flat panels (less to look through). */
    public static boolean glass = true;

    /** Completion notice: toast, chat, action bar, or nothing. */
    public static NotificationStyle notificationStyle = NotificationStyle.TOAST;
    /** The mod's completion, claim, unlock and level-up sounds. */
    public static boolean completionSounds = true;
    /** The pinned-quest tracker on the right edge instead of the left. */
    public static boolean trackerRight = false;
    /** What the tracker shows; hidden until its key is pressed, as before. */
    public static TrackerMode trackerMode = TrackerMode.HIDDEN;
    /** Fine position of the tracker in GUI px, independent of the side setting. */
    public static int trackerOffsetX = 0;
    public static int trackerOffsetY = 0;
    /** Scale of the pinned-quest tracker (0.5 to 2.0). */
    public static float trackerScale = 1f;
    /** Keep the chapter list open instead of opening it on hover. */
    public static boolean keepRailOpen = false;
    /** Add the current chapter's next open quest to the tracker (2026-10-22). */
    public static boolean autoPin = true;
    /** Screen text scale: separate from Minecraft's GUI scale (2026-10-22). */
    public static float textScale = 1f;
    /** White-on-black text and stronger marks over any resource pack palette (2026-10-22). */
    public static boolean highContrast = false;

    /** Chapters kept at the top of the chapter list, in pin order. Not per world: it follows the player. */
    public static final List<String> railPinned = new ArrayList<>();

    /** Groups folded shut in the chapter list (group ids, {@code #pinned} for the pinned block), as in FTB Quests. */
    public static final List<String> railFolded = new ArrayList<>();

    private static int railPinVersion;

    private static boolean loaded;

    /** The file could not be read nor moved aside: never overwrite it. */
    private static boolean readFailed;

    private ClientConfig() {
    }

    private static Path file() {
        return Services.PLATFORM.getGameDir().resolve("config").resolve(Constants.MOD_ID + "-client.json");
    }

    static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            if (Files.exists(file())) {
                JsonObject o = JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8)).getAsJsonObject();
                itemTooltips = bool(o, "item_tooltips", itemTooltips);
                glass = bool(o, "glass", glass);
                if (o.has("notification_style") && o.get("notification_style").isJsonPrimitive()) {
                    notificationStyle = NotificationStyle.byId(o.get("notification_style").getAsString());
                }
                completionSounds = bool(o, "completion_sounds", completionSounds);
                trackerRight = bool(o, "tracker_right", trackerRight);
                if (o.has("tracker_mode") && o.get("tracker_mode").isJsonPrimitive()) {
                    trackerMode = TrackerMode.byId(o.get("tracker_mode").getAsString());
                }
                trackerOffsetX = offset(o, "tracker_offset_x", trackerOffsetX);
                trackerOffsetY = offset(o, "tracker_offset_y", trackerOffsetY);
                trackerScale = number(o, "tracker_scale", trackerScale);
                keepRailOpen = bool(o, "keep_rail_open", keepRailOpen);
                autoPin = bool(o, "auto_pin", autoPin);
                if (o.has("text_scale") && o.get("text_scale").isJsonPrimitive() && o.get("text_scale").getAsJsonPrimitive().isNumber()) {
                    textScale = net.minecraft.util.Mth.clamp(o.get("text_scale").getAsFloat(), 0.8f, 1.2f);
                }
                highContrast = bool(o, "high_contrast", highContrast);
                if (o.has("rail_pinned") && o.get("rail_pinned").isJsonArray()) {
                    for (JsonElement e : o.getAsJsonArray("rail_pinned")) {
                        if (e.isJsonPrimitive() && !railPinned.contains(e.getAsString())) {
                            railPinned.add(e.getAsString());
                        }
                    }
                }
                if (o.has("rail_folded") && o.get("rail_folded").isJsonArray()) {
                    for (JsonElement e : o.getAsJsonArray("rail_folded")) {
                        if (e.isJsonPrimitive() && !railFolded.contains(e.getAsString())) {
                            railFolded.add(e.getAsString());
                        }
                    }
                }
            }
            write();
        } catch (IOException | RuntimeException ex) {
            // keep what could not be read next to it, then start again from the defaults: an empty or cut-off file
            // (a crash while writing) would otherwise stop the settings from ever being saved again
            try {
                Files.move(file(), file().resolveSibling(file().getFileName() + ".broken"), StandardCopyOption.REPLACE_EXISTING);
                Constants.LOG.warn("Could not read client settings, moved them to {}.broken: {}", file().getFileName(), ex.toString());
                write();
            } catch (IOException moveEx) {
                readFailed = true;
                Constants.LOG.warn("Could not read client settings, they will not be saved this session: {}", ex.toString());
            }
        }
        railPinVersion++;
    }

    private static boolean bool(JsonObject o, String key, boolean fallback) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : fallback;
    }

    static int offset(JsonObject o, String key, int fallback) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()
                ? net.minecraft.util.Mth.clamp(e.getAsInt(), -64, 64) : fallback;
    }

    private static float number(JsonObject o, String key, float fallback) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()
                ? net.minecraft.util.Mth.clamp(e.getAsFloat(), 0.5f, 2f) : fallback;
    }

    /** The pinned chapters in pin order (loads the file first). */
    public static List<String> railPinned() {
        ensureLoaded();
        return railPinned;
    }

    public static boolean glass() {
        ensureLoaded();
        return glass;
    }

    public static NotificationStyle notificationStyle() {
        ensureLoaded();
        return notificationStyle;
    }

    public static boolean completionSounds() {
        ensureLoaded();
        return completionSounds;
    }

    public static boolean trackerRight() {
        ensureLoaded();
        return trackerRight;
    }

    public static TrackerMode trackerMode() {
        ensureLoaded();
        return trackerMode;
    }

    /** Sets the tracker's mode and saves it, so the next session and the dev tour start from a known state. */
    public static void setTrackerMode(TrackerMode mode) {
        ensureLoaded();
        trackerMode = mode;
        write();
    }

    public static void cycleTrackerMode() {
        setTrackerMode(trackerMode().next());
    }

    public static int trackerOffsetX() {
        ensureLoaded();
        return trackerOffsetX;
    }

    public static int trackerOffsetY() {
        ensureLoaded();
        return trackerOffsetY;
    }

    public static float trackerScale() {
        ensureLoaded();
        return net.minecraft.util.Mth.clamp(trackerScale, 0.5f, 2f);
    }

    public static boolean keepRailOpen() {
        ensureLoaded();
        return keepRailOpen;
    }

    public static boolean autoPin() {
        ensureLoaded();
        return autoPin;
    }

    public static float textScale() {
        ensureLoaded();
        return textScale;
    }

    public static boolean highContrast() {
        ensureLoaded();
        return highContrast;
    }

    /** Saves the settings after a screen changed them. */
    public static void save() {
        ensureLoaded();
        write();
    }

    public static boolean railPinned(String id) {
        ensureLoaded();
        return railPinned.contains(id);
    }

    /** Pins or unpins a chapter; returns true when it ends up pinned. */
    public static boolean setRailPin(String id, boolean pinned) {
        ensureLoaded();
        if (pinned) {
            if (!railPinned.contains(id)) {
                railPinned.add(id);
            }
        } else {
            railPinned.remove(id);
        }
        railPinVersion++;
        write();
        return pinned;
    }

    public static boolean railFolded(String group) {
        ensureLoaded();
        return railFolded.contains(group);
    }

    /** Folds a group of the chapter list shut, or opens it again. */
    public static void toggleRailFold(String group) {
        ensureLoaded();
        if (!railFolded.remove(group)) {
            railFolded.add(group);
        }
        railPinVersion++;
        write();
    }

    /** Puts back a saved set of folded groups (the dev tour leaves the config as it found it). */
    public static void setRailFolded(List<String> groups) {
        ensureLoaded();
        railFolded.clear();
        railFolded.addAll(groups);
        railPinVersion++;
        write();
    }

    /** Changes whenever the pins or folds change, so the screen knows to rebuild its rows. */
    public static int railPinVersion() {
        return railPinVersion;
    }

    public static void toggleRailPin(String id) {
        setRailPin(id, !railPinned(id));
    }

    static void write() {
        if (readFailed) {
            return;
        }
        try {
            JsonObject o = new JsonObject();
            o.addProperty("item_tooltips", itemTooltips);
            o.addProperty("glass", glass);
            o.addProperty("notification_style", notificationStyle.id);
            o.addProperty("completion_sounds", completionSounds);
            o.addProperty("tracker_right", trackerRight);
            o.addProperty("tracker_mode", trackerMode.id);
            o.addProperty("tracker_offset_x", trackerOffsetX);
            o.addProperty("tracker_offset_y", trackerOffsetY);
            o.addProperty("tracker_scale", trackerScale);
            o.addProperty("keep_rail_open", keepRailOpen);
            o.addProperty("auto_pin", autoPin);
            o.addProperty("text_scale", textScale);
            o.addProperty("high_contrast", highContrast);
            JsonArray a = new JsonArray();
            railPinned.forEach(a::add);
            o.add("rail_pinned", a);
            JsonArray f = new JsonArray();
            railFolded.forEach(f::add);
            o.add("rail_folded", f);
            Files.createDirectories(file().getParent());
            // written next to it and moved over, so a crash mid-write never leaves a cut-off file
            Path tmp = file().resolveSibling(file().getFileName() + ".tmp");
            Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().create().toJson(o), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(tmp, file(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            Constants.LOG.warn("Could not write client settings: {}", ex.toString());
        }
    }
}
