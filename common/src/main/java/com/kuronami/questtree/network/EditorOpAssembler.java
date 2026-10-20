package com.kuronami.questtree.network;

import java.util.HashMap;
import java.util.Map;

/**
 * Puts a chunked {@link EditorOpPayload} back together (2026-10-20). One instance belongs to one player, so two
 * operators' requests cannot be mixed; the request id separates that player's own concurrent edits.
 * <p>
 * The server applies an op only when every part has arrived. A missing part leaves the entry here until the player
 * logs out ({@link #clear}); that is a small map, and a wrong-sized or mismatched part replaces the old entry rather
 * than corrupting it.
 */
public final class EditorOpAssembler {

    private final EditorOpChunks.Assembler delegate = new EditorOpChunks.Assembler();

    /**
     * One received payload: the complete request once its last part arrives, otherwise null while more are needed.
     *
     * @throws IllegalArgumentException when the parts cannot describe one request; the caller replies with the message
     */
    public EditorOpPayload accept(EditorOpPayload payload) {
        EditorOpChunks.Part complete = delegate.accept(
                new EditorOpChunks.Part(payload.requestId(), payload.chapterId(), payload.chapterRevision(),
                        payload.op(), payload.args(), payload.part(), payload.parts()));
        if (complete == null) {
            return null;
        }
        return new EditorOpPayload(complete.requestId(), complete.chapterId(), complete.chapterRevision(),
                complete.op(), complete.args());
    }

    /** Drops every partial request, for a logout or a server stop. */
    public void clear() {
        delegate.clear();
    }
}
