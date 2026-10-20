package com.kuronami.questtree.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The packet-size rules for an editor request, kept free of Minecraft types so both sides and the tests can use them
 * (2026-10-20). {@link EditorOpPayload} carries these fields over the wire and {@link EditorOpAssembler} delegates
 * here; the split and join behaviour lives in one place.
 */
public final class EditorOpChunks {

    /**
     * Characters per chunk. A char is at most 4 UTF-8 bytes, so a chunk is at most 28000 bytes: the remaining ~4.7 KB
     * covers the payload id and the other fields without reaching the 32767-byte packet limit.
     */
    public static final int MAX_PART_CHARS = 7000;

    /** Enough for the 262144-character ceiling at {@link #MAX_PART_CHARS}; a larger request is refused before send. */
    public static final int MAX_PARTS = 64;

    /** The largest args text a request may carry at all, split or not. */
    public static final int MAX_ARGS_CHARS = 262144;

    private EditorOpChunks() {
    }

    /** One part of a request: the wire fields before they are packed into {@link EditorOpPayload#chunk()}. */
    public record Part(long requestId, String chapterId, long chapterRevision, String op, String args, int part,
                       int parts) {

        /** The packed chunk field: 0 for an un-split request, otherwise {@code (parts << 8) | (part + 1)}. */
        public int chunk() {
            return parts <= 1 ? 0 : (parts << 8) | (part + 1);
        }

        /** This chunk's index, from 0; 0 for an un-split request. */
        public int partIndex() {
            int chunk = chunk();
            return chunk == 0 ? 0 : (chunk & 0xff) - 1;
        }

        /** How many chunks the request has; 1 for an un-split request. */
        public int partCount() {
            int chunk = chunk();
            return chunk == 0 ? 1 : chunk >>> 8;
        }
    }

    /**
     * Splits one op into parts that each fit a packet. A request that fits stays one part. Throws
     * {@link IllegalArgumentException} with a sentence a UI can show when the args are too large for any packet, or
     * when a chunked request has no request id to key reassembly on.
     */
    public static List<Part> split(long requestId, String chapterId, long chapterRevision, String op, String args) {
        String text = args == null ? "{}" : args;
        if (text.length() > MAX_ARGS_CHARS) {
            throw new IllegalArgumentException("the edit is larger than " + MAX_ARGS_CHARS + " characters; "
                    + "split it into smaller edits");
        }
        int count = Math.max(1, (text.length() + MAX_PART_CHARS - 1) / MAX_PART_CHARS);
        if (count > MAX_PARTS) {
            throw new IllegalArgumentException("the edit needs " + count + " packets, more than the "
                    + MAX_PARTS + " a request may use; split it into smaller edits");
        }
        if (count == 1) {
            return List.of(new Part(requestId, chapterId, chapterRevision, op, text, 0, 1));
        }
        if (requestId == 0) {
            throw new IllegalArgumentException("a chunked edit needs a request id so the server can put it back together");
        }
        List<Part> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int from = i * MAX_PART_CHARS;
            int to = Math.min(text.length(), from + MAX_PART_CHARS);
            out.add(new Part(requestId, chapterId, chapterRevision, op, text.substring(from, to), i, count));
        }
        return out;
    }

    /**
     * Puts a split request back together. One instance belongs to one player, so two operators' requests cannot be
     * mixed; the request id separates that player's own concurrent edits.
     */
    public static final class Assembler {

        private static final class Pending {
            final String chapterId;
            final long revision;
            final String op;
            final int parts;
            final String[] args;
            final boolean[] seen;
            int received;
            int chars;

            Pending(Part first, int parts) {
                this.chapterId = first.chapterId();
                this.revision = first.chapterRevision();
                this.op = first.op();
                this.parts = parts;
                this.args = new String[parts];
                this.seen = new boolean[parts];
            }
        }

        private final Map<Long, Pending> pending = new HashMap<>();

        /**
         * One received part: the complete request once its last part arrives, otherwise null while more are needed.
         *
         * @throws IllegalArgumentException when the parts cannot describe one request; the caller replies with it
         */
        public Part accept(Part payload) {
            int parts = payload.partCount();
            int part = payload.partIndex();
            if (parts < 1 || part < 0 || part >= parts) {
                throw new IllegalArgumentException("the edit has an invalid packet count");
            }
            if (parts == 1) {
                return payload;
            }
            if (parts > MAX_PARTS) {
                throw new IllegalArgumentException("the edit arrived in more pieces than a request may use");
            }
            if (payload.requestId() == 0) {
                throw new IllegalArgumentException("a chunked edit needs a request id");
            }
            Pending p = pending.get(payload.requestId());
            if (p == null || p.parts != parts || !p.chapterId.equals(payload.chapterId())
                    || p.revision != payload.chapterRevision() || !p.op.equals(payload.op())) {
                p = new Pending(payload, parts);
                pending.put(payload.requestId(), p);
            }
            if (p.seen[part]) {
                return null;
            }
            String args = payload.args() == null ? "" : payload.args();
            p.chars += args.length();
            if (p.chars > MAX_ARGS_CHARS) {
                pending.remove(payload.requestId());
                throw new IllegalArgumentException("the edit is larger than " + MAX_ARGS_CHARS
                        + " characters; split it into smaller edits");
            }
            p.args[part] = args;
            p.seen[part] = true;
            p.received++;
            if (p.received < p.parts) {
                return null;
            }
            pending.remove(payload.requestId());
            StringBuilder joined = new StringBuilder(p.chars);
            for (String piece : p.args) {
                joined.append(piece);
            }
            return new Part(payload.requestId(), p.chapterId, p.revision, p.op, joined.toString(), 0, 1);
        }

        /** Drops every partial request, for a logout or a server stop. */
        public void clear() {
            pending.clear();
        }
    }
}
