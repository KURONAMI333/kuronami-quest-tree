package com.kuronami.questtree.network;

import com.kuronami.questtree.Constants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: a gameplay notice that must not use chat (2026-10-22). {@code kind} picks the client's presentation
 * and {@code a}/{@code b} carry only what that presentation needs (a language key and an icon, or a name).
 */
public record CuePayload(String kind, String a, String b) implements CustomPacketPayload {

    public static final Type<CuePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "cue"));

    public static final StreamCodec<ByteBuf, CuePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(32), CuePayload::kind,
            ByteBufCodecs.stringUtf8(256), CuePayload::a,
            ByteBufCodecs.stringUtf8(256), CuePayload::b,
            CuePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
