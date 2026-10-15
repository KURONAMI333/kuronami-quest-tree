package com.kuronami.packprogress.network;

import com.kuronami.packprogress.Constants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: the player's own progress as JSON (ProgressJson), plus the events that just happened so the
 * client can play the matching motion and sound ({@code kind|node key} per entry).
 */
public record ProgressPayload(String progressJson, java.util.List<String> events) implements CustomPacketPayload {

    public static final Type<ProgressPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "progress"));

    public static final StreamCodec<ByteBuf, ProgressPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(4 * 1024 * 1024), ProgressPayload::progressJson,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(64)), ProgressPayload::events,
            ProgressPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
