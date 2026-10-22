package com.kuronami.questtree.client;

import com.kuronami.questtree.network.EditorChangePayload;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * The map's changelog of other operators' edits (2026-10-22, p6-editor), the shape FTB Quests' {@code QuestFileChangelog}
 * has: a bounded list of the changes this session has seen, shown for a few seconds after each arrives.
 * <p>
 * Entries hold values only (the op, the id, the history hint), never a rendered sentence: the screen looks the id up in
 * the model it holds and the words come from the translation files, so a client on another language reads the same
 * line in its own language. No chat line is written for an edit, because a drag would fill the chat.
 */
public final class EditorChangelog {

    /** How long a line stays on screen after it arrives. */
    static final long LIFE_MS = 12_000;
    /** The newest entries the map shows at once; the rest stay in the list for a test or a later screen. */
    static final int SHOWN = 4;
    static final int LIMIT = 100;

    /**
     * One accepted change.
     *
     * @param op        the server's op name
     * @param chapterId the chapter, table id or locale the change is about
     * @param node      the node id, or "" when the change is about the chapter or a file
     * @param count     how many nodes a batch touched
     * @param undoOp    what undo would take back next, or "" when there is nothing to undo
     * @param redoOp    what redo would apply next, or "" when there is nothing to redo
     * @param at        {@code Util.getMillis()} when it arrived
     */
    record Entry(String op, String chapterId, String node, int count, String undoOp, String redoOp, long at) {
    }

    private static final Deque<Entry> entries = new ArrayDeque<>();
    /** The list a frame draws; rebuilt once per client tick, never while rendering (2026-10-23, R6 L4). */
    private static List<Entry> snapshot = List.of();

    private EditorChangelog() {
    }

    /**
     * The loaders deliver a payload on their own network thread; the map reads this list while it renders, so the
     * entry is added on the client thread. A test calls {@link #add} directly.
     */
    public static void on(EditorChangePayload p) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        Runnable task = () -> add(p.op(), p.chapterId(), p.node(), p.count(), p.undoOp(), p.redoOp(),
                net.minecraft.Util.getMillis());
        if (mc == null) {
            task.run();
        } else {
            mc.execute(task);
        }
    }

    static void add(String op, String chapterId, String node, int count, String undoOp, String redoOp, long at) {
        entries.addLast(new Entry(op == null ? "" : op, chapterId == null ? "" : chapterId,
                node == null ? "" : node, Math.max(1, count), undoOp == null ? "" : undoOp,
                redoOp == null ? "" : redoOp, at));
        while (entries.size() > LIMIT) {
            entries.removeFirst();
        }
    }

    /** The newest entries still inside {@link #LIFE_MS}, oldest first; drops the ones that have faded. */
    static List<Entry> recent(long now) {
        tick(now);
        return snapshot;
    }

    /**
     * One client tick: drop the faded entries and take the frame's snapshot. Rendering only reads the snapshot, so a
     * full copy and a {@code removeIf} no longer run on every frame.
     */
    static void tick(long now) {
        entries.removeIf(e -> now - e.at() > LIFE_MS);
        snapshot = List.copyOf(entries);
    }

    public static List<Entry> recent() {
        return snapshot;
    }

    public static void clear() {
        entries.clear();
        snapshot = List.of();
    }

    /**
     * The word a line starts with. Every op falls into seven kinds, so the changelog does not need one translation key
     * per op and a new op still shows something sensible: an op it does not know counts as a change.
     */
    static String action(String op) {
        return switch (op == null ? "" : op) {
            case "create_node", "create_chapter", "restore_node", "restore_chapter", "restore_table" -> "add";
            case "delete_node", "delete_nodes", "delete_chapter", "delete_table" -> "delete";
            case "link" -> "link";
            case "unlink" -> "unlink";
            case "undo" -> "undo";
            case "redo" -> "redo";
            default -> "change";
        };
    }
}
