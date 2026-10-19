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
import java.util.ArrayList;
import java.util.List;

/**
 * Client settings ({@code config/kuronami_quest_tree-client.json}). Defaults are the product; the file is the way out
 * for players who want something off. Written once with the defaults so it can be found.
 */
public final class ClientConfig {

    /** The "needed for" line on item tooltips. */
    public static boolean itemTooltips = true;

    /** The minimap is drawn as a liquid-glass panel instead of a flat fill. */
    public static boolean liquidGlass = false;

    /** Chapters kept at the top of the chapter list, in pin order. Not per world: it follows the player. */
    public static final List<String> railPinned = new ArrayList<>();

    private static int railPinVersion;

    private static boolean loaded;

    /** The file was there but could not be read: never overwrite it. */
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
                liquidGlass = bool(o, "liquid_glass", liquidGlass);
                if (o.has("rail_pinned") && o.get("rail_pinned").isJsonArray()) {
                    for (JsonElement e : o.getAsJsonArray("rail_pinned")) {
                        if (e.isJsonPrimitive() && !railPinned.contains(e.getAsString())) {
                            railPinned.add(e.getAsString());
                        }
                    }
                }
            }
            write();
        } catch (IOException | RuntimeException ex) {
            // keep the player's file as it is: writing now would replace what could not be read with the defaults
            readFailed = true;
            Constants.LOG.warn("Could not read client settings, they will not be saved this session: {}", ex.toString());
        }
        railPinVersion++;
    }

    private static boolean bool(JsonObject o, String key, boolean fallback) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : fallback;
    }

    /** The pinned chapters in pin order (loads the file first). */
    public static List<String> railPinned() {
        ensureLoaded();
        return railPinned;
    }

    public static boolean liquidGlass() {
        ensureLoaded();
        return liquidGlass;
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

    /** Changes whenever the pins change, so the screen knows to rebuild its rows. */
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
            o.addProperty("liquid_glass", liquidGlass);
            JsonArray a = new JsonArray();
            railPinned.forEach(a::add);
            o.add("rail_pinned", a);
            Files.createDirectories(file().getParent());
            Files.writeString(file(), new GsonBuilder().setPrettyPrinting().create().toJson(o), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            Constants.LOG.warn("Could not write client settings: {}", ex.toString());
        }
    }
}
