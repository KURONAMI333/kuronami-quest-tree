package com.kuronami.questtree.network;

import com.kuronami.questtree.Constants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client to server: one edit request for one chapter (2026-10-20).
 * <p>
 * The client sends intent, never a file. The server checks the sender's permission and editing mode, reads its own
 * overlay, applies the op to a candidate chapter, validates it, and only then writes. That is why the payload carries
 * no authority and no whole chapter: a modified client can ask for any op and still gets the same refusal.
 * <p>
 * {@code chapterRevision} is the revision the client last saw. A mismatch is rejected rather than merged, so two
 * operators cannot silently overwrite each other. {@code args} stays JSON text so an op this build does not know is
 * refused with a sentence instead of failing to decode; it is also the shape the editor tests use directly.
 *
 * @param requestId       echoed in the reply; the client's ids start at 1, so 0 means "no id"
 * @param chapterId       {@code <namespace>:<chapter>}, as the bundle names it
 * @param chapterRevision the revision the client edited against (0 for a chapter with no overlay yet)
 * @param op              {@code create_node}, {@code update_node}, {@code move_node}, ... (see {@code ProgressionEditor})
 * @param args            a JSON object with the op's arguments
 */
public record EditorOpPayload(long requestId, String chapterId, long chapterRevision, String op, String args)
        implements CustomPacketPayload {

    public static final Type<EditorOpPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "editor_op"));

    public static final StreamCodec<ByteBuf, EditorOpPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, EditorOpPayload::requestId,
            ByteBufCodecs.stringUtf8(128), EditorOpPayload::chapterId,
            ByteBufCodecs.VAR_LONG, EditorOpPayload::chapterRevision,
            ByteBufCodecs.stringUtf8(48), EditorOpPayload::op,
            // one field of a node or a whole pasted node, not a chapter: 256 KiB is generous for either, and a
            // smaller ceiling would turn a legitimate paste into a click that dies on encode with no message
            ByteBufCodecs.stringUtf8(262144), EditorOpPayload::args,
            EditorOpPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
