package com.kuronami.questtree.network;

import com.kuronami.questtree.Constants;
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
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Server to client: the player's team and party invitations as JSON (TeamView), gzipped like the progress packet.
 * Small, but the same encoding keeps one shape for the two data packets.
 *
 * @param data gzip of the team JSON
 */
public record TeamPayload(byte[] data) implements CustomPacketPayload {

    public static final int MAX = 64 * 1024;

    public static final Type<TeamPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "team"));

    public static final StreamCodec<ByteBuf, TeamPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.byteArray(MAX), TeamPayload::data,
            TeamPayload::new);

    public static TeamPayload of(String json) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return new TeamPayload(out.toByteArray());
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
