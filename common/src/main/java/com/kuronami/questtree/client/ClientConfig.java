package com.kuronami.questtree.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kuronami.questtree.Constants;
import com.kuronami.questtree.platform.Services;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Client settings ({@code config/kuronami_quest_tree-client.json}). Defaults are the product; the file is the way out
 * for players who want something off. Written once with the defaults so it can be found.
 */
public final class ClientConfig {

    /** The "needed for" line on item tooltips. */
    public static boolean itemTooltips = true;

    private static boolean loaded;

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
                if (o.has("item_tooltips")) {
                    itemTooltips = o.get("item_tooltips").getAsBoolean();
                }
            }
            JsonObject o = new JsonObject();
            o.addProperty("item_tooltips", itemTooltips);
            Files.createDirectories(file().getParent());
            Files.writeString(file(), new GsonBuilder().setPrettyPrinting().create().toJson(o), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ex) {
            Constants.LOG.warn("Could not read client settings: {}", ex.toString());
        }
    }
}
