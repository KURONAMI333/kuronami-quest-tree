package com.kuronami.questtree.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kuronami.questtree.Constants;
import com.kuronami.questtree.network.EditorOpAssembler;
import com.kuronami.questtree.network.EditorOpPayload;
import com.kuronami.questtree.network.EditorReplyPayload;
import com.kuronami.questtree.network.EditorStatePayload;
import com.kuronami.questtree.platform.Services;
import com.kuronami.questtree.team.TeamResolver;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side entry point for the editor (2026-10-20).
 * <p>
 * Owns the two pieces of state that are not data: who has editing mode on, and whether a save still has to be
 * published. An op writes its chapter immediately (so a refusal costs nothing and an accepted one is not lost when the
 * server stops), then marks the book dirty. The actual rebuild and resync happen once per tick, so a drag that sends
 * many moves does not rebuild the model and re-send the whole bundle many times.
 * <p>
 * Editing mode is a session convenience, not a permission: the command already requires permission level 2, and every
 * payload is checked against this set as well, so a client cannot keep editing after {@code /quest_tree edit off} or
 * after its player logged out.
 */
public final class EditorService {

    private static final Set<UUID> editing = new HashSet<>();
    private static final Map<UUID, EditorOpAssembler> assemblers = new HashMap<>();
    private static Path worldRoot;
    private static ProgressionEditor editor;
    private static boolean dirty;

    private EditorService() {
    }

    /** The server started: point the overlay at this world and drop the previous world's editing state. */
    public static void onServerStarted(MinecraftServer server) {
        worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        editor = new ProgressionEditor(worldRoot, GameRegistry.INSTANCE);
        editing.clear();
        assemblers.clear();
        dirty = false;
        ProgressData.setOverlayRoot(worldRoot);
    }

    /** The server stopped: the next world must not write into this one. */
    public static void onServerStopped() {
        editing.clear();
        assemblers.clear();
        dirty = false;
        editor = null;
        worldRoot = null;
        ProgressData.clearOverlayRoot();
    }

    public static boolean isEditing(ServerPlayer player) {
        return editing.contains(player.getUUID());
    }

    public static boolean setEditing(ServerPlayer player, boolean on) {
        if (on) {
            editing.add(player.getUUID());
        } else {
            editing.remove(player.getUUID());
        }
        sendState(player);
        return isEditing(player);
    }

    /** Tell one client whether the map should offer editing (permission level 2 and {@code /quest_tree edit on}). */
    public static void sendState(ServerPlayer player) {
        if (player != null) {
            Services.PLATFORM.sendToPlayer(player, new EditorStatePayload(player.hasPermissions(2) && isEditing(player)));
        }
    }

    public static void onLogout(ServerPlayer player) {
        editing.remove(player.getUUID());
        assemblers.remove(player.getUUID());
    }

    /** One edit request, on the server thread. Every request gets a reply, including a refusal. */
    public static void onAction(ServerPlayer player, EditorOpPayload payload) {
        String chapterId = payload.chapterId() == null ? "" : payload.chapterId();
        if (!player.hasPermissions(2)) {
            sendState(player);
            reply(player, payload, chapterId, false, "", List.of("you do not have permission to edit quests"),
                    payload.chapterRevision());
            return;
        }
        if (!isEditing(player)) {
            sendState(player);
            reply(player, payload, chapterId, false, "", List.of("editing mode is off; use /quest_tree edit on"),
                    payload.chapterRevision());
            return;
        }
        if (editor == null || worldRoot == null) {
            reply(player, payload, chapterId, false, "", List.of("the server has no world loaded"),
                    payload.chapterRevision());
            return;
        }
        try {
            payload = assemblers.computeIfAbsent(player.getUUID(), k -> new EditorOpAssembler()).accept(payload);
        } catch (IllegalArgumentException ex) {
            reply(player, payload, chapterId, false, "", List.of(ex.getMessage()), payload.chapterRevision());
            return;
        }
        if (payload == null) {
            // more parts are still on the way; the reply comes when the last one is applied or refused
            return;
        }
        chapterId = payload.chapterId() == null ? "" : payload.chapterId();

        JsonObject args;
        try {
            args = payload.args() == null || payload.args().isBlank()
                    ? new JsonObject() : JsonParser.parseString(payload.args()).getAsJsonObject();
        } catch (RuntimeException ex) {
            reply(player, payload, chapterId, false, "", List.of("the arguments are not a JSON object: " + ex.getMessage()),
                    payload.chapterRevision());
            return;
        }

        if (payload.op().equals("export") || payload.op().equals("test_chapter")) {
            control(player, payload, chapterId, args);
            return;
        }

        ProgressionEditor.Outcome outcome;
        try {
            outcome = editor.apply(ProgressData.packChapters(), ProgressData.packTables(), ProgressData.packSources(),
                    chapterId, payload.chapterRevision(), payload.op(), args, ProgressData.packUnreadable(),
                    permissionLevel(player));
        } catch (RuntimeException ex) {
            // a bug in one op must not take the tick or the server down; the file is written last, so nothing changed
            Constants.LOG.error("Progression edit {} on {} failed", payload.op(), chapterId, ex);
            reply(player, payload, chapterId, false, "", List.of("the edit failed: " + ex.getMessage()),
                    payload.chapterRevision());
            return;
        }

        if (outcome.changed()) {
            dirty = true;
        }
        String replyChapter = chapterId;
        if (!outcome.createdId().isEmpty()
                && (payload.op().equals("create_chapter") || payload.op().equals("update_table"))) {
            replyChapter = outcome.createdId();
        }
        reply(player, payload, replyChapter, outcome.ok(), outcome.createdId(), outcome.messages(), outcome.revision());
        if (outcome.stale() && !dirty) {
            // another operator wrote this chapter first: give the client the new bundle now, so its next op is not
            // refused against a revision it can no longer see; with a rebuild already pending the tick-end sync sends the
            // fresh bundle, and this one would be the outdated cached copy
            ProgressData.sync(player);
        }
    }

    /** The level a writer holds, 0-4; command rewards need level 4 to be written (2026-10-22 release review). */
    private static int permissionLevel(ServerPlayer player) {
        for (int level = 4; level > 0; level--) {
            if (player.hasPermissions(level)) {
                return level;
            }
        }
        return 0;
    }

    /** End of a server tick: publish one rebuild for every edit made since the last tick. */
    public static void onServerTick(MinecraftServer server) {
        // A rebuild may have moved reward indices (an editor reorder, insert or delete): move the saved claim records
        // once, before the new book is sent (2026-10-22 release review).
        java.util.Map<String, java.util.Map<Integer, Integer>> rewardRemap = ProgressData.takeRewardRemap();
        if (!rewardRemap.isEmpty()) {
            ProgressStore.get(server).remapRewardIndices(rewardRemap);
        }
        if (!dirty) {
            return;
        }
        dirty = false;
        try {
            ProgressData.rebuildOverlay();
        } catch (RuntimeException ex) {
            Constants.LOG.error("Could not publish the progression overlay", ex);
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ProgressData.sync(player);
        }
    }

    /**
     * Export and test drive the commands rather than reimplementing them. Both echo the request's revision: they do
     * not write a chapter, and a control reply must not move the client's revision for the one it named.
     */
    private static void control(ServerPlayer player, EditorOpPayload request, String chapterId, JsonObject args) {
        long revision = request.chapterRevision();
        if (request.op().equals("export")) {
            String namespace = EditorOps.exportNamespace(args, chapterId);
            String command = EditorOps.exportCommand(namespace == null ? "" : namespace);
            if (command == null) {
                reply(player, request, chapterId, false, "", List.of("no namespace to export"), revision);
                return;
            }
            boolean force = EditorOps.forceRequested(args);
            Path folder = player.server.getWorldPath(LevelResource.DATAPACK_DIR)
                    .resolve(ProgressExport.packFolder(namespace));
            String refusal = EditorOps.exportRefusal(namespace, force, Files.exists(folder));
            if (refusal != null) {
                reply(player, request, chapterId, false, "", List.of(refusal), revision);
                return;
            }
            command = EditorOps.exportCommand(namespace, force);
            int result = ServerCommands.run(player, command, 2, false);
            // the reply carries the pack folder's name, not a path on the server: the author already knows their world
            EditorOps.ControlOutcome outcome = EditorOps.controlOutcome("export", result,
                    ProgressExport.packFolder(namespace));
            reply(player, request, chapterId, outcome.ok(), "", messages(outcome), revision);
            return;
        }
        String command = EditorOps.testCommand(chapterId);
        JsonObject chapters = ProgressData.mergedBundle().getAsJsonObject("chapters");
        if (command == null || chapters == null || !chapters.has(chapterId)) {
            reply(player, request, chapterId, false, "", List.of("this chapter is not in the book"), revision);
            return;
        }
        String partyRefusal = EditorOps.testRefusal(
                TeamResolver.members(player.server, TeamResolver.teamId(player.server, player.getUUID())));
        if (partyRefusal != null) {
            reply(player, request, chapterId, false, "", List.of(partyRefusal), revision);
            return;
        }
        int result = ServerCommands.run(player, command, 2, false);
        EditorOps.ControlOutcome outcome = EditorOps.controlOutcome("test_chapter", result, "");
        if (outcome.leaveEditing()) {
            // the point of the button is to see the chapter as a player: the map must stop offering editing at once
            setEditing(player, false);
        }
        reply(player, request, chapterId, outcome.ok(), "", messages(outcome), revision);
    }

    private static List<String> messages(EditorOps.ControlOutcome outcome) {
        return outcome.message().isEmpty() ? List.of() : List.of(outcome.message());
    }

    private static void reply(ServerPlayer player, EditorOpPayload request, String chapterId, boolean ok,
                              String createdId, List<String> messages, long revision) {
        StringBuilder joined = new StringBuilder();
        for (String m : messages) {
            if (m == null || m.isEmpty()) {
                continue;
            }
            if (!joined.isEmpty()) {
                joined.append('\n');
            }
            joined.append(m);
            if (joined.length() >= 4000) {
                // the codec allows 4096; one validator line more is not worth losing the whole reply
                break;
            }
        }
        String text = joined.length() > 4000 ? joined.substring(0, 4000) : joined.toString();
        Services.PLATFORM.sendToPlayer(player, new EditorReplyPayload(request.requestId(), chapterId, ok,
                createdId == null ? "" : createdId, text, revision));
    }
}
