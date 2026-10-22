package com.kuronami.questtree.network;

import com.kuronami.questtree.Constants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: an accepted edit by another operator, for the map's changelog (2026-10-22, p6-editor).
 * <p>
 * Only the values travel: the op, the chapter (or table or locale) it names, the node it is about and how many nodes a
 * batch touched. The client already holds the book, so it turns the op into a word and the id into a title itself; a
 * client on another language reads the same change in its own words. {@code undoOp}/{@code redoOp} are the next
 * history steps in the same chapter ("" when that stack is empty), which is the hint the map shows under the line.
 * <p>
 * The author of the edit does not receive this for its own op: its reply already carries the revision and any refusal.
 *
 * @param op       the op name the server applied ({@code update_node}, {@code undo}, {@code update_table}, ...)
 * @param chapterId the chapter, reward table id, or locale the change is about
 * @param node     the node id the change is about, or "" for a chapter, table or locale change
 * @param count    how many nodes a batch op touched, 1 for a single change
 * @param undoOp   the op undo would take back next, or "" when there is nothing to undo
 * @param redoOp   the op redo would apply next, or "" when there is nothing to redo
 */
public record EditorChangePayload(String op, String chapterId, String node, int count, String undoOp, String redoOp)
        implements CustomPacketPayload {

    public static final Type<EditorChangePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "editor_change"));

    public static final StreamCodec<ByteBuf, EditorChangePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(32), EditorChangePayload::op,
            ByteBufCodecs.stringUtf8(128), EditorChangePayload::chapterId,
            ByteBufCodecs.stringUtf8(128), EditorChangePayload::node,
            ByteBufCodecs.VAR_INT, EditorChangePayload::count,
            ByteBufCodecs.stringUtf8(32), EditorChangePayload::undoOp,
            ByteBufCodecs.stringUtf8(32), EditorChangePayload::redoOp,
            EditorChangePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
