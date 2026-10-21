package com.kuronami.questtree.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kuronami.questtree.Constants;
import com.kuronami.questtree.platform.Services;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Quests the player pinned for the in-game tracker. Kept on the client per world or server
 * ({@code config/kuronami_quest_tree_pins.json}), so pinning needs no server support and survives restarts.
 */
public final class Pins {

    public static final int MAX = 5;
    private static final Set<String> PINNED = new LinkedHashSet<>();
    private static String world = "";

    private Pins() {
    }

    private static Path file() {
        return Services.PLATFORM.getGameDir().resolve("config").resolve("kuronami_quest_tree_pins.json");
    }

    private static String worldKey() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() != null) {
            return "local:" + mc.getSingleplayerServer().getWorldData().getLevelName();
        }
        var server = mc.getCurrentServer();
        return server != null ? "server:" + server.ip : "unknown";
    }

    static void load() {
        world = worldKey();
        PINNED.clear();
        PINNED.addAll(keysOf(readAll(), world));
    }

    /** The string entries of one world's pin list; numbers and objects from a hand-edited file are skipped. */
    static java.util.List<String> keysOf(JsonObject all, String world) {
        java.util.List<String> keys = new java.util.ArrayList<>();
        JsonElement list = all.get(world);
        if (list != null && list.isJsonArray()) {
            for (JsonElement e : list.getAsJsonArray()) {
                if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
                    keys.add(e.getAsString());
                }
            }
        }
        return keys;
    }

    private static int version;

    /** Changes whenever pins change (caches built from pins compare it). */
    public static int version() {
        return version;
    }

    public static boolean has(String key) {
        return PINNED.contains(key);
    }

    public static Set<String> all() {
        return PINNED;
    }

    /** Pins or unpins; the oldest pin drops out when more than {@link #MAX} are pinned. */
    public static void toggle(String key) {
        version++;
        if (!PINNED.remove(key)) {
            PINNED.add(key);
            while (PINNED.size() > MAX) {
                PINNED.remove(PINNED.iterator().next());
            }
        }
        save();
    }

    private static JsonObject readAll() {
        try {
            if (Files.exists(file())) {
                return JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8)).getAsJsonObject();
            }
        } catch (IOException | RuntimeException ex) {
            Constants.LOG.warn("Could not read pinned quests: {}", ex.toString());
        }
        return new JsonObject();
    }

    private static void save() {
        JsonObject all = readAll();
        JsonArray a = new JsonArray();
        PINNED.forEach(a::add);
        all.add(world, a);
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), new Gson().toJson(all), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            Constants.LOG.warn("Could not save pinned quests: {}", ex.toString());
        }
    }
}
