package com.kuronami.questtree.network;

import com.kuronami.questtree.Constants;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client to server: link the task screen at {@code pos} to one task, or unlink it when {@code node} is empty
 * (2026-10-22, FTB Quests parity).
 */
public record ScreenBindPayload(BlockPos pos, String node, int index) implements CustomPacketPayload {

    public static final Type<ScreenBindPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "screen_bind"));

    public static final StreamCodec<ByteBuf, ScreenBindPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, ScreenBindPayload::pos,
            ByteBufCodecs.stringUtf8(256), ScreenBindPayload::node,
            ByteBufCodecs.VAR_INT, ScreenBindPayload::index,
            ScreenBindPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
