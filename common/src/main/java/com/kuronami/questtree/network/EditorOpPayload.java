package com.kuronami.questtree.network;

import com.kuronami.questtree.Constants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

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
 * <p>
 * <strong>Size.</strong> A vanilla serverbound custom payload is at most 32767 bytes and Fabric does not split a C2S
 * payload for us. An encoded field over that limit fails on the client with no reply, so a large op is sent as evenly
 * sized chunks ({@link #split}) and put back together by {@link EditorOpAssembler} on the server. A request that fits
 * keeps the old single-part shape ({@code part 0, parts 1}).
 *
 * @param requestId       echoed in the reply; the client's ids start at 1, so 0 means "no id"
 * @param chapterId       {@code <namespace>:<chapter>}, as the bundle names it
 * @param chapterRevision the revision the client edited against (0 for a chapter with no overlay yet)
 * @param op              {@code create_node}, {@code update_node}, {@code move_node}, ... (see {@code ProgressionEditor})
 * @param args            a JSON object with the op's arguments, or one chunk of it
 * @param chunk           the chunk position packed into one varint: 0 for an un-split request, otherwise
 *                        {@code (parts << 8) | (part + 1)} ({@link #part()} and {@link #parts()} unpack it)
 */
public record EditorOpPayload(long requestId, String chapterId, long chapterRevision, String op, String args,
                              int chunk) implements CustomPacketPayload {

    public static final Type<EditorOpPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "editor_op"));

    /** Characters per chunk: see {@link EditorOpChunks#MAX_PART_CHARS}. */
    public static final int MAX_PART_CHARS = EditorOpChunks.MAX_PART_CHARS;

    /** See {@link EditorOpChunks#MAX_PARTS}. */
    public static final int MAX_PARTS = EditorOpChunks.MAX_PARTS;

    /** See {@link EditorOpChunks#MAX_ARGS_CHARS}. */
    public static final int MAX_ARGS_CHARS = EditorOpChunks.MAX_ARGS_CHARS;

    public static final StreamCodec<ByteBuf, EditorOpPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, EditorOpPayload::requestId,
            ByteBufCodecs.stringUtf8(128), EditorOpPayload::chapterId,
            ByteBufCodecs.VAR_LONG, EditorOpPayload::chapterRevision,
            ByteBufCodecs.stringUtf8(48), EditorOpPayload::op,
            ByteBufCodecs.stringUtf8(MAX_PART_CHARS), EditorOpPayload::args,
            ByteBufCodecs.VAR_INT, EditorOpPayload::chunk,
            EditorOpPayload::new);

    /** One un-split request. */
    public EditorOpPayload(long requestId, String chapterId, long chapterRevision, String op, String args) {
        this(requestId, chapterId, chapterRevision, op, args, 0);
    }

    /** One chunk of a split request. */
    public EditorOpPayload(long requestId, String chapterId, long chapterRevision, String op, String args,
                           int part, int parts) {
        this(requestId, chapterId, chapterRevision, op, args, parts <= 1 ? 0 : (parts << 8) | (part + 1));
    }

    /** This chunk's index, from 0; 0 for an un-split request. */
    public int part() {
        return chunk == 0 ? 0 : (chunk & 0xff) - 1;
    }

    /** How many chunks the request has; 1 for an un-split request. */
    public int parts() {
        return chunk == 0 ? 1 : chunk >>> 8;
    }

    /**
     * Splits one op into payloads that each fit a packet. A request that fits stays one payload. Throws
     * {@link IllegalArgumentException} with a sentence a UI can show when the args are too large for any packet, or
     * when a chunked request has no request id to key reassembly on.
     */
    public static List<EditorOpPayload> split(long requestId, String chapterId, long chapterRevision, String op,
                                              String args) {
        List<EditorOpChunks.Part> parts = EditorOpChunks.split(requestId, chapterId, chapterRevision, op, args);
        List<EditorOpPayload> out = new ArrayList<>(parts.size());
        for (EditorOpChunks.Part part : parts) {
            out.add(new EditorOpPayload(part.requestId(), part.chapterId(), part.chapterRevision(), part.op(),
                    part.args(), part.chunk()));
        }
        return out;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
