package com.kuronami.questtree.network;

import com.kuronami.questtree.Constants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: whether this player may edit right now (2026-10-20, phase 2).
 * <p>
 * Editing needs both permission level 2 and {@code /quest_tree edit on}. Repeating the pair on join and on every
 * toggle keeps a screen that is already open in step with the server; the ops themselves are still checked one by one,
 * so this payload only decides whether the map offers its edit affordances.
 */
public record EditorStatePayload(boolean canEdit) implements CustomPacketPayload {

    public static final Type<EditorStatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "editor_state"));

    public static final StreamCodec<ByteBuf, EditorStatePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, EditorStatePayload::canEdit,
            EditorStatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
