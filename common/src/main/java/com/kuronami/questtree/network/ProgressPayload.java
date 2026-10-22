package com.kuronami.questtree.network;

import com.kuronami.questtree.Constants;
import com.kuronami.questtree.progress.HintLimits;
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
 * @param hints  item and block ids the player's unmet restrictions cover, as {@code id|reason} (empty reason when
 *               the pack wrote none), so the client can put the requirement on the tooltip without guessing
 */
public record ProgressPayload(byte[] data, List<String> events, List<String> hints) implements CustomPacketPayload {

    /** Vanilla's limit for a clientbound custom payload is 1 MiB; leave room for events and hints. */
    public static final int MAX = 1000 * 1024;

    public static final Type<ProgressPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "progress"));

    public static final StreamCodec<ByteBuf, ProgressPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.byteArray(MAX), ProgressPayload::data,
            ByteBufCodecs.stringUtf8(1024).apply(ByteBufCodecs.list(64)), ProgressPayload::events,
            ByteBufCodecs.stringUtf8(512).apply(ByteBufCodecs.list(4096)), ProgressPayload::hints,
            ProgressPayload::new);

    public static ProgressPayload of(String json, List<String> events, List<String> hints) {
        byte[] data = compress(json);
        HintLimits.Result limited = HintLimits.limit(hints, data.length, HintLimits.eventBytes(events), MAX);
        return new ProgressPayload(data, events, limited.hints());
    }

    /** Gzip of the progress JSON, the part of the packet that repeats a lot of node keys. */
    public static byte[] compress(String json) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return out.toByteArray();
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
