package com.kuronami.questtree.network;

import com.kuronami.questtree.Constants;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: open the task picker for the screen at {@code pos} (2026-10-22). The server checks the permission;
 * the client only chooses which task the screen shows.
 */
public record ScreenBindOpenPayload(BlockPos pos) implements CustomPacketPayload {

    public static final Type<ScreenBindOpenPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "screen_bind_open"));

    public static final StreamCodec<ByteBuf, ScreenBindOpenPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, ScreenBindOpenPayload::pos,
            ScreenBindOpenPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
