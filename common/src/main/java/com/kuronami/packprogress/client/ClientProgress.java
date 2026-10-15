package com.kuronami.packprogress.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kuronami.packprogress.Constants;
import com.kuronami.packprogress.data.ProgressModel;
import com.kuronami.packprogress.network.ActionPayload;
import com.kuronami.packprogress.network.ProgressPayload;
import com.kuronami.packprogress.network.SyncPayload;
import com.kuronami.packprogress.progress.PlayerProgress;
import com.kuronami.packprogress.progress.ProgressJson;
import com.kuronami.packprogress.progress.ProgressRules;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

/** Client entry points shared by both loaders: the synced data, the player's progress and the keys. */
public final class ClientProgress {

    public static final KeyMapping OPEN_KEY = new KeyMapping("key.pack_progress.open", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_U, "key.categories.pack_progress");
    /** Shows or hides the pinned quests at the side of the game screen. */
    public static final KeyMapping TRACK_KEY = new KeyMapping("key.pack_progress.track", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_Y, "key.categories.pack_progress");

    private static ClientModel model;
    private static ProgressRules rules;
    private static PlayerProgress progress = new PlayerProgress();
    /** False until the first progress packet after joining, so joining does not replay every change as news. */
    private static boolean haveProgress;
    /** Set by the loader module: sends a payload to the server. */
    public static Consumer<ActionPayload> sender = a -> { };
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
                rules = new ProgressRules(ProgressModel.of(bundle));
                Pins.load();
                Constants.LOG.info("Received {} progression chapters", model.chapters.size());
            } catch (Exception ex) {
                Constants.LOG.error("Could not read progression data from server", ex);
            }
            buffer = null;
        }
    }

    /** The player's own progress, replaced whole on every update. */
    public static void onProgress(ProgressPayload p) {
        PlayerProgress next;
        try {
            next = ProgressJson.read(JsonParser.parseString(p.progressJson()).getAsJsonObject());
        } catch (RuntimeException ex) {
            Constants.LOG.error("Could not read progress from server", ex);
            return;
        }
        PlayerProgress old = progress;
        progress = next;
        if (haveProgress) {
            ProgressFeedback.onUpdate(old, next, p.events());
        }
        haveProgress = true;
    }

    public static PlayerProgress progress() {
        return progress;
    }

    public static ProgressRules rules() {
        return rules;
    }

    public static ClientModel model() {
        return model;
    }

    /** State of a node for this player; nodes the rules do not know (should not happen) count as open. */
    public static ProgressRules.State state(String key) {
        ProgressModel.Node n = rules == null ? null : rules.model().nodes.get(key);
        return n == null ? ProgressRules.State.OPEN : rules.state(progress, n);
    }

    public static double fraction(String key) {
        ProgressModel.Node n = rules == null ? null : rules.model().nodes.get(key);
        return n == null ? 0 : rules.fraction(progress, n);
    }

    /** Points that can be spent now in a skill chapter. */
    public static int points(String chapterId) {
        ProgressModel.Chapter c = rules == null ? null : rules.model().chapters.get(chapterId);
        return c == null || !c.isSkills() ? 0 : rules.availablePoints(progress, c);
    }

    public static void send(String action, String node, int index) {
        sender.accept(new ActionPayload(action, node == null ? "" : node, index));
    }

    /** Leaving a server: forget its data so another server never shows it. */
    public static void onLogout() {
        model = null;
        rules = null;
        progress = new PlayerProgress();
        haveProgress = false;
        receivingGeneration = -1;
        buffer = null;
        ProgressScreen.clearViews();
        ProgressHud.clear();
    }

    /** Called every client tick by the loader module. The same key opens and closes the screen. */
    public static void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        while (OPEN_KEY.consumeClick()) {
            if (mc.screen == null && mc.player != null) {
                mc.setScreen(new ProgressScreen());
            }
        }
        while (TRACK_KEY.consumeClick()) {
            if (mc.screen == null) {
                ProgressHud.toggleTracker();
            }
        }
    }
}
