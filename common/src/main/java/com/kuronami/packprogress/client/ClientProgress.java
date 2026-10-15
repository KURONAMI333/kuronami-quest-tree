package com.kuronami.packprogress.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kuronami.packprogress.Constants;
import com.kuronami.packprogress.network.ActionPayload;
import com.kuronami.packprogress.network.ProgressPayload;
import com.kuronami.packprogress.network.SyncPayload;
import com.kuronami.packprogress.progress.PlayerProgress;
import com.kuronami.packprogress.progress.ProgressJson;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

/** Client entry points shared by both loaders: receiving the bundle and the open key. */
public final class ClientProgress {

    public static final KeyMapping OPEN_KEY = new KeyMapping("key.pack_progress.open", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_U, "key.categories.pack_progress");

    private static ClientModel model;
    private static PlayerProgress progress = new PlayerProgress();
    /** Set by the loader module: sends a payload to the server. */
    public static Consumer<ActionPayload> sender = a -> { };
    /** Listeners for progress events (motion and sound); the screen and the HUD register here. */
    public static final List<Consumer<String>> EVENT_LISTENERS = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static int receivingGeneration = -1;
    private static ByteArrayOutputStream buffer;
    private static int nextPart;

    private ClientProgress() {
    }

    /** Called on the client main thread for every slice of the bundle. */
    public static void onSync(SyncPayload p) {
        if (p.part() == 0 || p.generation() != receivingGeneration) {
            receivingGeneration = p.generation();
            buffer = new ByteArrayOutputStream();
            nextPart = 0;
        }
        if (p.part() != nextPart) {
            Constants.LOG.warn("Progression sync out of order (got {}, expected {}); waiting for the next full send", p.part(), nextPart);
            receivingGeneration = -1;
            return;
        }
        buffer.writeBytes(p.data());
        nextPart++;
        if (nextPart == p.total()) {
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
                JsonObject bundle = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                model = new ClientModel(bundle);
                Constants.LOG.info("Received {} progression chapters", model.chapters.size());
            } catch (Exception ex) {
                Constants.LOG.error("Could not read progression data from server", ex);
            }
            buffer = null;
        }
    }

    /** The player's own progress, replaced whole on every update. */
    public static void onProgress(ProgressPayload p) {
        try {
            progress = ProgressJson.read(JsonParser.parseString(p.progressJson()).getAsJsonObject());
        } catch (RuntimeException ex) {
            Constants.LOG.error("Could not read progress from server", ex);
            return;
        }
        for (String e : p.events()) {
            EVENT_LISTENERS.forEach(l -> l.accept(e));
        }
    }

    public static PlayerProgress progress() {
        return progress;
    }

    public static void send(String action, String node, int index) {
        sender.accept(new ActionPayload(action, node == null ? "" : node, index));
    }

    /** Leaving a server: forget its data so another server never shows it. */
    public static void onLogout() {
        model = null;
        progress = new PlayerProgress();
        receivingGeneration = -1;
        buffer = null;
        ProgressScreen.clearViews();
    }

    public static ClientModel model() {
        return model;
    }

    /** Called every client tick by the loader module. The same key opens and closes the screen. */
    public static void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        while (OPEN_KEY.consumeClick()) {
            if (mc.screen == null && mc.player != null) {
                mc.setScreen(new ProgressScreen());
            }
        }
    }
}
