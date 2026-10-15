package com.kuronami.packprogress.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kuronami.packprogress.Constants;
import com.kuronami.packprogress.network.SyncPayload;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/** Client entry points shared by both loaders: receiving the bundle and the open key. */
public final class ClientProgress {

    public static final KeyMapping OPEN_KEY = new KeyMapping("key.pack_progress.open", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_U, "key.categories.pack_progress");

    private static ClientModel model;
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
