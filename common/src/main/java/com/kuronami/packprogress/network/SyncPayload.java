package com.kuronami.packprogress.network;

import com.kuronami.packprogress.Constants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * One slice of the gzip-compressed progression bundle (chapters, groups, translations) sent from server to client.
 * Large packs (ATM10: 66 chapters, 4,790 quests, 15 locales) exceed the custom payload size limit, so the bundle is
 * split into parts and reassembled on the client by {@code generation}.
 */
public record SyncPayload(int generation, int part, int total, byte[] data) implements CustomPacketPayload {

    public static final Type<SyncPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "sync"));

    public static final StreamCodec<ByteBuf, SyncPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SyncPayload::generation,
            ByteBufCodecs.VAR_INT, SyncPayload::part,
            ByteBufCodecs.VAR_INT, SyncPayload::total,
            ByteBufCodecs.byteArray(SyncPayload.MAX_PART), SyncPayload::data,
            SyncPayload::new);

    /** Bytes per part. Clientbound custom payloads are limited to 1 MiB; leave headroom for the header. */
    public static final int MAX_PART = 900 * 1024;

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
