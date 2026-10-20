package com.kuronami.questtree.network;

import com.kuronami.questtree.Constants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Server to client: what happened to one edit (2026-10-20).
 * <p>
 * An edit has more than one interesting outcome, and two of them are things only the server knows: a new id it minted,
 * and the validator's own words for a refusal. The chapter still arrives separately through the normal bundle sync;
 * this payload deliberately carries none of it, so there is only one description of the book.
 * <p>
 * {@code revision} is the chapter's revision after the attempt. On a stale request it is the revision the client should
 * reload, which is why a refusal still answers with a number rather than only a sentence.
 *
 * @param requestId echoed from the request, or 0 when there was none
 * @param chapterId the chapter the reply is about, so a client with two open can tell them apart
 * @param ok        whether the op was validated and written
 * @param createdId the node or chapter id the server minted, or "" when the op created none
 * @param messages  the validator's messages or the refusal reason, one per line, or "" when there are none
 * @param revision  the chapter's revision after the attempt
 */
public record EditorReplyPayload(long requestId, String chapterId, boolean ok, String createdId, String messages,
                                 long revision) implements CustomPacketPayload {

    public static final Type<EditorReplyPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "editor_reply"));

    public static final StreamCodec<ByteBuf, EditorReplyPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, EditorReplyPayload::requestId,
            ByteBufCodecs.stringUtf8(128), EditorReplyPayload::chapterId,
            ByteBufCodecs.BOOL, EditorReplyPayload::ok,
            ByteBufCodecs.stringUtf8(64), EditorReplyPayload::createdId,
            // a validator can produce a paragraph; one string with newlines, because chat shows them one after another
            ByteBufCodecs.stringUtf8(4096), EditorReplyPayload::messages,
            ByteBufCodecs.VAR_LONG, EditorReplyPayload::revision,
            EditorReplyPayload::new);

    /** The messages as a list, which is how a screen or chat shows them. */
    public List<String> lines() {
        return messages == null || messages.isEmpty() ? List.of() : List.of(messages.split("\n"));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
