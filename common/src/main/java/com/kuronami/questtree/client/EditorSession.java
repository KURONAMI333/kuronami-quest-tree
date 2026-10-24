package com.kuronami.questtree.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kuronami.questtree.Constants;
import com.kuronami.questtree.client.edit.EditorClipboard;
import com.kuronami.questtree.client.edit.PasteJob;
import com.kuronami.questtree.network.EditorOpPayload;
import com.kuronami.questtree.network.EditorReplyPayload;
import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The client's side of editing (2026-10-20, phase 2): the mode flags, the chapter revisions, the ops in flight and the
 * short notices the map shows.
 * <p>
 * Ops are sent one at a time. The server writes a chapter immediately and answers with its new revision, so a batch
 * (many moved nodes, a paste's nodes followed by its links) needs the answer to the one before it to carry the right
 * revision. A drag still reads as instant because the map draws the target positions at once; only the durable write
 * waits a round trip. A refused op shows the validator's own words, never a sentence of ours.
 */
public final class EditorSession {

    private static final long NOTICE_MS = 4000;
    /** How long a settled optimistic position may wait for the synced model to show the same place. */
    private static final long SETTLE_MS = 2500;
    private static final Map<String, Long> settledAt = new HashMap<>();

    /**
     * A short line at the bottom of the map: the validator's message, or a warning it let through.
     *
     * @param text  the message
     * @param error true when the op was refused
     * @param at    {@code Util.getMillis()} when it arrived
     */
    public record Notice(String text, boolean error, long at) {
    }

    private record Pending(long requestId, String chapterId, String op, JsonObject args,
                           Consumer<EditorReplyPayload> then, boolean table, Long revision) {
    }

    private static final Deque<Pending> queue = new ArrayDeque<>();
    private static Pending current;
    private static long nextRequestId = 1;
    /** Screens waiting for a clear queue: see {@link #whenIdle(Runnable)}. */
    private static final List<Runnable> idleTasks = new ArrayList<>();

    private static boolean allowed;
    private static boolean active;

    /** The revision the client last saw per chapter; the bundle sets it, every reply updates it. */
    private static final Map<String, Long> revisions = new HashMap<>();
    /** Reward-table revisions are separate: editing a table does not move a chapter's number. */
    private static final Map<String, Long> tableRevisions = new HashMap<>();
    /** Where nodes are drawn while their move waits for the server: global key to {x, y} in grid units. */
    private static final Map<String, double[]> optimistic = new LinkedHashMap<>();
    private static final List<Notice> notices = new ArrayList<>();
    private static EditorClipboard clipboard;

    private EditorSession() {
    }

    public static boolean allowed() {
        return allowed;
    }

    /** Joining, logging out, or {@code /quest_tree edit off}: editing becomes unavailable and the local mode closes. */
    public static void setAllowed(boolean on) {
        allowed = on;
        if (!on) {
            active = false;
            failPending();
            idleTasks.clear();
            optimistic.clear();
            settledAt.clear();
            EditorChangelog.clear();
        }
    }

    /**
     * Answers every op still queued or in flight as failed and drops it. Editing closing or the server going away means
     * no reply is coming, so a screen waiting on one must be told: otherwise its {@code sending} flag stays set forever.
     */
    private static void failPending() {
        Pending inFlight = current;
        current = null;
        List<Pending> dropped = new ArrayList<>(queue);
        queue.clear();
        if (inFlight != null) {
            dropped.add(0, inFlight);
        }
        for (Pending pending : dropped) {
            if (pending.then() == null) {
                continue;
            }
            try {
                pending.then().accept(new EditorReplyPayload(pending.requestId(), pending.chapterId(), false,
                        "", "", revisionOf(pending)));
            } catch (RuntimeException ex) {
                Constants.LOG.error("An editor reply handler failed", ex);
            }
        }
    }

    public static boolean active() {
        return allowed && active;
    }

    public static void toggle() {
        if (allowed) {
            active = !active;
        }
    }

    public static void setActive(boolean on) {
        active = allowed && on;
    }

    public static long revision(String chapterId) {
        return revisions.getOrDefault(chapterId, 0L);
    }

    public static long tableRevision(String tableId) {
        return tableRevisions.getOrDefault(tableId, 0L);
    }

    /** A new bundle arrived: take its authoritative revisions. */
    /** The target id the restrictions file uses; it has one revision for the whole file. */
    public static final String RESTRICTIONS = "restrictions";
    /** The target id the settings file uses; one revision for the whole file. */
    public static final String SETTINGS = "settings";

    public static void onBundle(JsonObject bundle) {
        revisions.clear();
        tableRevisions.clear();
        readRevisions(bundle, "revisions", revisions);
        readRevisions(bundle, "table_revisions", tableRevisions);
        JsonElement revision = bundle.get("restrictions_revision");
        if (revision != null && revision.isJsonPrimitive() && revision.getAsJsonPrimitive().isNumber()) {
            tableRevisions.put(RESTRICTIONS, revision.getAsLong());
        }
        revision = bundle.get("settings_revision");
        if (revision != null && revision.isJsonPrimitive() && revision.getAsJsonPrimitive().isNumber()) {
            tableRevisions.put(SETTINGS, revision.getAsLong());
        }
    }

    private static void readRevisions(JsonObject bundle, String key, Map<String, Long> into) {
        if (bundle.has(key) && bundle.get(key).isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : bundle.getAsJsonObject(key).entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    try {
                        into.put(e.getKey(), e.getValue().getAsLong());
                    } catch (RuntimeException ignored) {
                        // a malformed revision is the same as none: the server will refuse the next op with a sentence
                    }
                }
            }
        }
    }

    public static Map<String, double[]> optimistic() {
        return optimistic;
    }

    /**
     * Drop optimistic positions that nothing is waiting for. A key stays while the player holds it in a drag
     * ({@code held}) or a move for it is queued or in flight. After that the synced model should already show it; if
     * it has not caught up within {@link #SETTLE_MS} (a refused move, a lost packet) the node goes back to the synced
     * position instead of staying where the player once dropped it.
     */
    public static void pruneOptimistic(ClientModel model, java.util.Set<String> held) {
        long now = net.minecraft.Util.getMillis();
        java.util.Set<String> busy = movesInFlight();
        optimistic.entrySet().removeIf(e -> {
            String key = e.getKey();
            ClientModel.Node n = model == null ? null : model.nodesByKey.get(key);
            if (n == null) {
                settledAt.remove(key);
                return true;
            }
            if (held.contains(key) || busy.contains(key)) {
                settledAt.remove(key);
                return false;
            }
            boolean same = Math.abs(n.x - e.getValue()[0]) < 1e-6 && Math.abs(n.y - e.getValue()[1]) < 1e-6;
            long since = settledAt.computeIfAbsent(key, k -> now);
            if (same || now - since > SETTLE_MS) {
                settledAt.remove(key);
                return true;
            }
            return false;
        });
    }

    private static java.util.Set<String> movesInFlight() {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (current != null) {
            addMove(out, current);
        }
        for (Pending p : queue) {
            addMove(out, p);
        }
        return out;
    }

    private static void addMove(java.util.Set<String> out, Pending p) {
        if (p.op().equals("move_node") && p.args().has("id")) {
            out.add(p.chapterId() + "/" + p.args().get("id").getAsString());
        }
    }

    public static EditorClipboard clipboard() {
        return clipboard;
    }

    public static void setClipboard(EditorClipboard copy) {
        clipboard = copy;
    }

    public static List<Notice> notices() {
        long now = net.minecraft.Util.getMillis();
        notices.removeIf(n -> now - n.at() > NOTICE_MS);
        return notices;
    }

    public static void clearNotices() {
        notices.clear();
    }

    /** One line in the same place refusals appear, for something the client did itself (a copied id, for example). */
    public static void note(String text) {
        if (text != null && !text.isBlank()) {
            notices.add(new Notice(text, false, net.minecraft.Util.getMillis()));
        }
    }

    public static void clearOptimistic() {
        optimistic.clear();
    }

    /** Sends one op, waiting for the previous one's answer first; returns the request id. */
    public static long send(String chapterId, String op, JsonObject args) {
        return send(chapterId, op, args, null);
    }

    public static long send(String chapterId, String op, JsonObject args, Consumer<EditorReplyPayload> then) {
        return send(chapterId, op, args, then, false, null);
    }

    /**
     * Sends an op against the revision a screen was opened with. The screen edited that copy, so a save must not
     * silently apply itself to a newer one another operator wrote meanwhile: the server refuses and sends the new
     * bundle instead.
     */
    public static long send(String chapterId, String op, JsonObject args, long revision,
                            Consumer<EditorReplyPayload> then) {
        return send(chapterId, op, args, then, false, revision);
    }

    /** A reward-table op: its revision comes from the table sidecar, not the chapter the table may belong to. */
    public static long sendTable(String tableId, String op, JsonObject args, Consumer<EditorReplyPayload> then) {
        return send(tableId, op, args, then, true, null);
    }

    /** A reward-table op against the revision its screen was opened with. */
    public static long sendTable(String tableId, String op, JsonObject args, long revision,
                                 Consumer<EditorReplyPayload> then) {
        return send(tableId, op, args, then, true, revision);
    }

    private static long send(String targetId, String op, JsonObject args,
                             Consumer<EditorReplyPayload> then, boolean table, Long revision) {
        long id = nextRequestId++;
        queue.add(new Pending(id, targetId, op, args, then, table, revision));
        pump();
        return id;
    }

    /**
     * Runs a task once no op is queued or waiting for its answer. A screen reads its copy and revision when it is
     * built, so opening one while this client's own save is still in flight would give it the number from before that
     * save; the next reply updates the numbers first and the screen is built afterwards.
     */
    public static void whenIdle(Runnable task) {
        if (task == null) {
            return;
        }
        idleTasks.add(task);
        runIdleTasks();
    }

    private static void runIdleTasks() {
        while (!idleTasks.isEmpty() && current == null && queue.isEmpty()) {
            Runnable task = idleTasks.remove(0);
            try {
                task.run();
            } catch (RuntimeException ex) {
                Constants.LOG.error("An editor task waiting for a clear queue failed", ex);
            }
        }
    }

    /** A paste: every node is created in order, then the links inside the copy are sent with the minted ids. */
    public static void paste(String chapterId, EditorClipboard copy, double atX, double atY, boolean snap) {
        if (copy == null || copy.isEmpty()) {
            return;
        }
        PasteJob job = new PasteJob(copy);
        List<com.google.gson.JsonObject> nodes = copy.nodesAt(atX, atY, snap, chapterId);
        Map<String, JsonObject> madeById = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            EditorClipboard.Entry entry = copy.entries().get(i);
            int index = i;
            JsonObject args = new JsonObject();
            args.add("node", nodes.get(i));
            send(chapterId, "create_node", args, reply -> {
                if (reply.ok()) {
                    job.created(entry.id(), reply.createdId());
                    madeById.put(reply.createdId(), nodes.get(index));
                } else {
                    job.refused(entry.id());
                }
                if (job.complete()) {
                    for (EditorClipboard.Link link : job.resolvedLinks()) {
                        JsonObject linkArgs = new JsonObject();
                        linkArgs.addProperty("from", link.from());
                        linkArgs.addProperty("to", link.to());
                        send(chapterId, "link", linkArgs);
                    }
                    // exclusive_with names ids that did not exist when the nodes were created: patch them in now,
                    // keeping the outside names the create already carried
                    for (Map.Entry<String, List<String>> ex : job.resolvedExclusives().entrySet()) {
                        com.google.gson.JsonArray names = new com.google.gson.JsonArray();
                        JsonObject made = madeById.get(ex.getKey());
                        if (made != null && made.has("exclusive_with") && made.get("exclusive_with").isJsonArray()) {
                            names.addAll(made.getAsJsonArray("exclusive_with"));
                        }
                        ex.getValue().forEach(names::add);
                        JsonObject patch = new JsonObject();
                        patch.add("exclusive_with", names);
                        JsonObject updateArgs = new JsonObject();
                        updateArgs.addProperty("id", ex.getKey());
                        updateArgs.add("patch", patch);
                        send(chapterId, "update_node", updateArgs);
                    }
                }
            });
        }
    }

    /** A loader module calls this for each {@code EditorReplyPayload}. */
    public static void onReply(EditorReplyPayload reply) {
        if (reply.chapterId() != null && !reply.chapterId().isEmpty()) {
            if (current != null && current.requestId() == reply.requestId() && current.table()) {
                tableRevisions.put(reply.chapterId(), reply.revision());
            } else {
                revisions.put(reply.chapterId(), reply.revision());
            }
        }
        showMessages(reply.lines(), !reply.ok());
        if (current != null && current.requestId() == reply.requestId()) {
            Pending done = current;
            current = null;
            if (done.then() != null) {
                try {
                    done.then().accept(reply);
                } catch (RuntimeException ex) {
                    Constants.LOG.error("An editor reply handler failed", ex);
                    showMessages(List.of(Component.translatable("screen.kuronami_quest_tree.editor.notice.reply_failed",
                            String.valueOf(ex.getMessage())).getString()), true);
                }
            }
            pump();
        }
        runIdleTasks();
    }

    private static void pump() {
        if (current != null || queue.isEmpty()) {
            return;
        }
        Pending next = queue.poll();
        current = next;
        try {
            long revision = revisionOf(next);
            List<EditorOpPayload> payloads = EditorOpPayload.split(next.requestId(), next.chapterId(),
                    revision, next.op(), next.args().toString());
            for (EditorOpPayload payload : payloads) {
                ClientProgress.editorSender.accept(payload);
            }
        } catch (RuntimeException ex) {
            // too large to send, or no connection: answer locally so the queue does not stall behind it
            String message = ex.getMessage() == null
                    ? Component.translatable("screen.kuronami_quest_tree.editor.notice.send_failed").getString()
                    : ex.getMessage();
            long revision = revisionOf(next);
            onReply(new EditorReplyPayload(next.requestId(), next.chapterId(), false, "", message, revision));
        }
    }

    /** The revision a pending op names: what its screen opened with, or the newest one this client has seen. */
    private static long revisionOf(Pending pending) {
        if (pending.revision() != null) {
            return pending.revision();
        }
        return pending.table() ? tableRevision(pending.chapterId()) : revision(pending.chapterId());
    }

    private static void showMessages(List<String> lines, boolean error) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        long now = net.minecraft.Util.getMillis();
        for (String line : lines) {
            if (line != null && !line.isBlank()) {
                notices.add(new Notice(line, error, now));
            }
        }
        if (notices.size() > 12) {
            notices.subList(0, notices.size() - 12).clear();
        }
    }

    /** Leaving a server: no revision, clipboard or notice from it may reach the next one. */
    public static void onLogout() {
        allowed = false;
        active = false;
        failPending();
        idleTasks.clear();
        revisions.clear();
        tableRevisions.clear();
        optimistic.clear();
        settledAt.clear();
        notices.clear();
        clipboard = null;
    }
}
