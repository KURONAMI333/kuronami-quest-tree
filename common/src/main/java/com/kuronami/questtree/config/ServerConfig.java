package com.kuronami.questtree.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.kuronami.questtree.Constants;
import com.kuronami.questtree.platform.Services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Server settings ({@code config/kuronami_quest_tree-server.json}), read once and rewritten nowhere (2026-10-20).
 * Defaults are the product: a player who joins for the first time gets the quest book once, as FTB Quests gives its
 * book; the file is the way out for packs that hand out their own.
 */
public final class ServerConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Boolean giveBook;

    private ServerConfig() {
    }

    public static boolean giveBookOnFirstJoin() {
        if (giveBook == null) {
            giveBook = read();
        }
        return giveBook;
    }

    private static boolean read() {
        Path file = Services.PLATFORM.getGameDir().resolve("config").resolve("kuronami_quest_tree-server.json");
        try {
            if (Files.isRegularFile(file)) {
                JsonObject o = GSON.fromJson(Files.readString(file), JsonObject.class);
                if (o != null && o.has("give_book_on_first_join")) {
                    return o.get("give_book_on_first_join").getAsBoolean();
                }
            } else {
                // write the default so the option is discoverable; the value read stays the default
                JsonObject o = new JsonObject();
                o.addProperty("give_book_on_first_join", true);
                Files.createDirectories(file.getParent());
                Files.writeString(file, GSON.toJson(o));
            }
        } catch (IOException | RuntimeException e) {
            Constants.LOG.warn("Could not read {}; using the defaults", file, e);
        }
        return true;
    }

    /** Data packs or a new world are not a reason to re-read; only the next game start is. */
    public static void reload() {
        giveBook = null;
    }
}
