package com.kuronami.questtree.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.kuronami.questtree.Constants;
import com.kuronami.questtree.platform.Services;
import com.kuronami.questtree.progress.ProgressRules.State;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Which quests the player has looked at, so newly startable ones can carry an unread mark until opened (2026-10-18,
 * after Thaumaturge's research book; the look follows Discord's unread dot). Kept on the client per world or server
 * ({@code config/kuronami_quest_tree_seen/<world>.json}). The first time a world is opened every quest already startable
 * counts as seen, so a new install does not mark the whole book.
 */
public final class Seen {

    private static final Set<String> SEEN = new HashSet<>();
    private static String world = "";
    private static boolean baselinePending;
    private static boolean dirty;
    private static int version;

    private Seen() {
    }

    private static Path file(String key) {
        String name = key.replaceAll("[^A-Za-z0-9._-]", "_").toLowerCase(Locale.ROOT);
        return Services.PLATFORM.getGameDir().resolve("config").resolve("kuronami_quest_tree_seen").resolve(name + ".json");
    }

    private static String worldKey() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() != null) {
            return "local_" + mc.getSingleplayerServer().getWorldData().getLevelName();
        }
        var server = mc.getCurrentServer();
        return server != null ? "server_" + server.ip : "unknown";
    }

    static void load() {
        world = worldKey();
        SEEN.clear();
        version++;
        Path f = file(world);
        baselinePending = !Files.exists(f);
        if (baselinePending) {
            return;
        }
        try {
            for (JsonElement e : JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonArray()) {
                SEEN.add(e.getAsString());
            }
        } catch (IOException | RuntimeException ex) {
            Constants.LOG.warn("Could not read seen quests: {}", ex.toString());
        }
    }

    /** Once both the book and the player's progress have arrived, a world seen for the first time takes its baseline. */
    static void onProgress() {
        if (!baselinePending || ClientProgress.model() == null) {
            return;
        }
        baselinePending = false;
        for (ClientModel.Chapter c : ClientProgress.model().chapters) {
            for (ClientModel.Node n : c.nodes.values()) {
                if (available(n.key)) {
                    SEEN.add(n.key);
                }
            }
        }
        dirty = true;
        flush();
    }

    /** Changes whenever the set changes (cached counts compare it). */
    public static int version() {
        return version;
    }

    /** Startable now, shown, and not yet opened. Skill trees are left out: their nodes are bought, not discovered. */
    public static boolean isNew(String key) {
        return !baselinePending && !SEEN.contains(key) && available(key);
    }

    private static boolean available(String key) {
        ClientModel m = ClientProgress.model();
        ClientModel.Chapter c = m == null ? null : m.chapterOf.get(key);
        if (c == null || c.skills || !ClientProgress.visible(key) || !ClientProgress.unlocked(key)) {
            return false;
        }
        State s = ClientProgress.state(key);
        return s == State.OPEN || s == State.PROGRESS;
    }

    static void markSeen(String key) {
        if (key != null && SEEN.add(key)) {
            version++;
            dirty = true;
        }
    }

    /** Writes the set if it changed; called when the screen closes and on leaving the world. */
    static void flush() {
        if (!dirty || world.isEmpty()) {
            return;
        }
        dirty = false;
        JsonArray a = new JsonArray();
        SEEN.forEach(a::add);
        Path f = file(world);
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, new Gson().toJson(a), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            Constants.LOG.warn("Could not save seen quests: {}", ex.toString());
        }
    }
}
