package com.kuronami.packprogress.network;

import com.kuronami.packprogress.Constants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Server to client: the player's own progress (ProgressJson, gzip-compressed: node keys repeat a lot, so a player who
 * finished thousands of quests stays far below the 1 MiB payload limit), plus the events that just happened so the
 * client can play the matching motion and sound ({@code kind|node key} per entry).
 *
 * @param data   gzip of the progress JSON
 * @param events events since the last send
 */
public record ProgressPayload(byte[] data, List<String> events) implements CustomPacketPayload {

    /** Vanilla's limit for a clientbound custom payload is 1 MiB; leave room for the events. */
    public static final int MAX = 1000 * 1024;

    public static final Type<ProgressPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "progress"));

    public static final StreamCodec<ByteBuf, ProgressPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.byteArray(MAX), ProgressPayload::data,
            ByteBufCodecs.stringUtf8(1024).apply(ByteBufCodecs.list(64)), ProgressPayload::events,
            ProgressPayload::new);

    public static ProgressPayload of(String json, List<String> events) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return new ProgressPayload(out.toByteArray(), events);
    }

    public String json() {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
