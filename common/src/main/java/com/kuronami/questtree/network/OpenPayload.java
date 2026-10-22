package com.kuronami.questtree.network;

import com.kuronami.questtree.Constants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: open the book on this quest or chapter, because the player clicked a quest giver. The target is
 * in {@link com.kuronami.questtree.giver.GiverTarget#encode()} form ({@code node|...} or {@code chapter|...}); an
 * empty target opens the book where it would normally open. The client applies its own hidden and locked rules, so a
 * link never shows a quest the player may not see.
 */
public record OpenPayload(String target) implements CustomPacketPayload {

    public static final Type<OpenPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "open"));

    public static final StreamCodec<ByteBuf, OpenPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(512), OpenPayload::target,
            OpenPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
