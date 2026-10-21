package com.kuronami.questtree.network;

import com.kuronami.questtree.Constants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client to server: a player action on the screen. The server re-checks everything (state, inventory, points).
 * <ul>
 *   <li>{@code claim} node — take the rewards of a completed node ({@code index} = chosen option for choice rewards)</li>
 *   <li>{@code claim_reward} node, index — take one reward of a completed node ({@code choice} = chosen option)</li>
 *   <li>{@code claim_all} — claim every completed node except the ones excluded from "claim all"</li>
 *   <li>{@code take} node — spend points on a skill node</li>
 *   <li>{@code submit} node, index — hand in items for an item task that consumes them</li>
 *   <li>{@code check} node, index — tick a checkmark task</li>
 *   <li>{@code exchange} chapter — buy one point with vanilla levels ({@code points.exchange})</li>
 *   <li>{@code respec} chapter — give back the chapter's spent points, paying {@code points.respec.cost}</li>
 * </ul>
 */
public record ActionPayload(String action, String node, int index, int choice) implements CustomPacketPayload {

    public static final Type<ActionPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "action"));

    public static final StreamCodec<ByteBuf, ActionPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(32), ActionPayload::action,
            ByteBufCodecs.stringUtf8(512), ActionPayload::node,
            ByteBufCodecs.VAR_INT, ActionPayload::index,
            ByteBufCodecs.VAR_INT, ActionPayload::choice,
            ActionPayload::new);

    /** A payload without a choice option (most actions). */
    public ActionPayload(String action, String node, int index) {
        this(action, node, index, -1);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
