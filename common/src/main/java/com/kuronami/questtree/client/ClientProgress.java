package com.kuronami.questtree.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kuronami.questtree.Constants;
import com.kuronami.questtree.data.ProgressModel;
import com.kuronami.questtree.network.ActionPayload;
import com.kuronami.questtree.network.EditorOpPayload;
import com.kuronami.questtree.network.OpenPayload;
import com.kuronami.questtree.network.ProgressPayload;
import com.kuronami.questtree.network.SyncPayload;
import com.kuronami.questtree.progress.PlayerProgress;
import com.kuronami.questtree.progress.ProgressJson;
import com.kuronami.questtree.progress.ProgressRules;
import com.kuronami.questtree.client.viewer.QuestViewerData;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

/** Client entry points shared by both loaders: the synced data, the player's progress and the keys. */
public final class ClientProgress {

    public static final KeyMapping OPEN_KEY = new KeyMapping("key.kuronami_quest_tree.open", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K, "key.categories.kuronami_quest_tree");
    /** Shows or hides the pinned quests at the side of the game screen. */
    public static final KeyMapping TRACK_KEY = new KeyMapping("key.kuronami_quest_tree.track", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_J, "key.categories.kuronami_quest_tree");

    private static SyncState<ClientModel, ProgressRules> sync = SyncState.empty();
    private static PlayerProgress progress = new PlayerProgress();
    /** False until the first progress packet after joining, so joining does not replay every change as news. */
    private static boolean haveProgress;
    /** Set by the loader module: sends a payload to the server. */
    public static Consumer<ActionPayload> sender = a -> { };
    /** Set by the loader module: sends one editor op to the server. */
    public static Consumer<EditorOpPayload> editorSender = p -> { };
    /** The chapter JSON exactly as the server sent it, for copying nodes without a round trip. */

    /** Tombstones for the restore list: deleted chapters and each live chapter's deleted node ids. */

    private static int receivingGeneration = -1;
    private static ByteArrayOutputStream buffer;
    private static int nextPart;
    /** The merged stage set for {@link #barrierProg}, so a barrier check per frame allocates nothing. */
    private static PlayerProgress barrierProg;
    private static java.util.Set<String> barrierStages = java.util.Set.of();
    /** Quests "claim all" would take; read every frame, so it is worked out once per progress/rules pair. */
    private static final ProgressCountCache CLAIMABLE = new ProgressCountCache();
    /** Item and block ids an unmet restriction covers, to their reason; replaced with each progress packet. */
    private static Map<String, String> lockedHints = Map.of();

    private ClientProgress() {
    }

    /** Called on the client main thread for every slice of the bundle. */
    public static void onSync(SyncPayload p) {
        if (p.part() == 0 || p.generation() != receivingGeneration) {
            receivingGeneration = p.generation();
            buffer = new ByteArrayOutputStream();
            nextPart = 0;
        }
        if (p.part() != nextPart) {
            Constants.LOG.warn("Progression sync out of order (got {}, expected {}); waiting for the next full send", p.part(), nextPart);
            receivingGeneration = -1;
            return;
        }
        buffer.writeBytes(p.data());
        nextPart++;
        if (nextPart == p.total()) {
            boolean synced = false;
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
                JsonObject bundle = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                // build everything first: a throw halfway must not leave a new model next to the old rules
                ClientModel nextModel = new ClientModel(bundle);
                ProgressRules nextRules = new ProgressRules(ProgressModel.of(bundle));
                JsonObject nextChapters = bundle.has("chapters") && bundle.get("chapters").isJsonObject()
                        ? bundle.getAsJsonObject("chapters").deepCopy() : new JsonObject();
                JsonObject nextTables = bundle.has("reward_tables") && bundle.get("reward_tables").isJsonObject()
                        ? bundle.getAsJsonObject("reward_tables").deepCopy() : new JsonObject();
                JsonObject nextDeleted = bundle.has("deleted") && bundle.get("deleted").isJsonObject()
                        ? bundle.getAsJsonObject("deleted").deepCopy() : new JsonObject();
                com.google.gson.JsonArray nextRestrictions = bundle.has("restrictions")
                        && bundle.get("restrictions").isJsonArray()
                        ? bundle.getAsJsonArray("restrictions").deepCopy() : new com.google.gson.JsonArray();
                com.google.gson.JsonObject nextSettings = bundle.has("settings")
                        && bundle.get("settings").isJsonObject()
                        ? bundle.getAsJsonObject("settings").deepCopy() : new com.google.gson.JsonObject();
                // an editor's bundle carries every locale for the translation screen; a reader's carries two
                com.google.gson.JsonObject nextLangs = bundle.has("lang") && bundle.get("lang").isJsonObject()
                        ? bundle.getAsJsonObject("lang").deepCopy() : new com.google.gson.JsonObject();
                com.google.gson.JsonObject nextLangRevisions = bundle.has("lang_revisions")
                        && bundle.get("lang_revisions").isJsonObject()
                        ? bundle.getAsJsonObject("lang_revisions").deepCopy() : new com.google.gson.JsonObject();
                // a filter's items come from the tags this server sent, so items worked out for another world are dropped
                com.kuronami.questtree.progress.ItemFilter.clearCache();
                sync = new SyncState<>(nextModel, nextRules, nextChapters, nextTables, nextDeleted);
                restrictions = nextRestrictions;
                settings = nextSettings;
                langs = nextLangs;
                langRevisions = nextLangRevisions;
                EditorSession.onBundle(bundle);
                Pins.load();
                Ui.loadPalette(net.minecraft.client.Minecraft.getInstance().getResourceManager());
                synced = true;
                Constants.LOG.info("Received {} progression chapters", sync.model().chapters.size());
            } catch (Exception ex) {
                Constants.LOG.error("Could not read progression data from server", ex);
            }
            // a viewer failure must not make a good sync look like a broken bundle
            if (synced) {
                try {
                    QuestViewerData.rebuildAll();
                } catch (RuntimeException ex) {
                    Constants.LOG.error("Could not rebuild the recipe-viewer index", ex);
                }
            }
            buffer = null;
        }
    }

    /** A crate was opened: show its loot in the same kind of screen FTB Quests uses. */
    public static void onCrateLoot(com.kuronami.questtree.network.CrateLootPayload p) {
        Minecraft.getInstance().setScreen(new CrateLootScreen(p.items()));
    }

    /** A quest giver, a quest block or an addon asked the server to open the book on one target. */
    public static void onOpen(OpenPayload p) {
        String target = p.target() == null ? "" : p.target();
        if (target.isEmpty()) {
            ProgressScreen.open();
        } else {
            ProgressScreen.openTarget(target);
        }
    }

    /** Whether the team's book is closed (FTB Quests' {@code locked}). */
    public static boolean bookLocked() {
        return progress.bookLocked();
    }

    /** The player's own progress, replaced whole on every update. */
    public static void onProgress(ProgressPayload p) {
        PlayerProgress next;
        try {
            next = ProgressJson.read(JsonParser.parseString(p.json()).getAsJsonObject());
        } catch (RuntimeException ex) {
            Constants.LOG.error("Could not read progress from server", ex);
            return;
        }
        PlayerProgress old = progress;
        progress = next;
        Map<String, String> hints = new HashMap<>();
        for (String raw : p.hints()) {
            int cut = raw.indexOf('|');
            if (cut > 0) {
                hints.putIfAbsent(raw.substring(0, cut), raw.substring(cut + 1));
            }
        }
        lockedHints = Map.copyOf(hints);
        barrierProg = null;
        if (haveProgress) {
            ProgressFeedback.onUpdate(old, next, p.events());
        }
        haveProgress = true;
        QuestViewerData.refreshVisibility();
    }

    public static PlayerProgress progress() {
        return progress;
    }

    /** The reason an unmet restriction puts on this item's tooltip, or null when it is allowed. */
    public static String restrictionHint(ItemStack stack) {
        if (lockedHints.isEmpty() || stack == null || stack.isEmpty()) {
            return null;
        }
        String hint = lockedHints.get(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        if (hint == null && stack.getItem() instanceof BlockItem blockItem) {
            hint = lockedHints.get(BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString());
        }
        return hint;
    }

    /** The restrictions array of the last bundle; replaced whole, so no other sync state has to know about it. */
    private static com.google.gson.JsonArray restrictions = new com.google.gson.JsonArray();
    private static com.google.gson.JsonObject settings = new com.google.gson.JsonObject();
    /** Every locale the bundle carried (all of them for an editor), keyed by folder name. */
    private static com.google.gson.JsonObject langs = new com.google.gson.JsonObject();
    /** Locale to the overlay's revision; 0 for a locale the overlay never wrote. */
    private static com.google.gson.JsonObject langRevisions = new com.google.gson.JsonObject();

    public static ProgressRules rules() {
        return sync.rules();
    }

    public static ClientModel model() {
        return sync.model();
    }

    /** One node's raw chapter JSON, or null; editing copies this so a paste keeps fields the display model drops. */
    public static JsonObject rawNode(String chapterId, String nodeId) {
        if (sync.chapters() == null || !sync.chapters().has(chapterId)) {
            return null;
        }
        com.google.gson.JsonElement chapter = sync.chapters().get(chapterId);
        if (!chapter.isJsonObject()) {
            return null;
        }
        com.google.gson.JsonElement nodes = chapter.getAsJsonObject().get("nodes");
        if (nodes == null || !nodes.isJsonObject() || !nodes.getAsJsonObject().has(nodeId)) {
            return null;
        }
        com.google.gson.JsonElement node = nodes.getAsJsonObject().get(nodeId);
        return node != null && node.isJsonObject() ? node.getAsJsonObject().deepCopy() : null;
    }

    /** Every chapter's raw JSON, as the server last sent it; the table screen counts reward-table references here. */
    public static JsonObject rawChapters() {
        return sync.chapters() == null ? new JsonObject() : sync.chapters().deepCopy();
    }

    /** Every locale of the last bundle, keyed by folder name; a non-editor's bundle holds English and its own. */
    public static com.google.gson.JsonObject langs() {
        return langs.deepCopy();
    }

    /** The revision a translation save for {@code locale} must name. */
    public static long langRevision(String locale) {
        com.google.gson.JsonElement e = langRevisions.get(locale);
        return e == null || !e.isJsonPrimitive() ? 0 : e.getAsLong();
    }

    /** One chapter's raw JSON, or null; the chapter editor patches this so unknown fields survive. */
    public static JsonObject rawChapter(String chapterId) {
        if (sync.chapters() == null || !sync.chapters().has(chapterId) || !sync.chapters().get(chapterId).isJsonObject()) {
            return null;
        }
        return sync.chapters().getAsJsonObject(chapterId).deepCopy();
    }

    /** Every reward table as the server last sent it (pack plus the writable table overlay). */
    public static JsonObject rawTables() {
        return sync.tables().deepCopy();
    }

    /** The restriction rules as the server last sent them (pack plus the writable restrictions file). */
    public static com.google.gson.JsonObject rawSettings() {
        return settings.deepCopy();
    }

    public static com.google.gson.JsonArray rawRestrictions() {
        return restrictions.deepCopy();
    }

    /** The restore list: {@code chapters} (ids) and {@code nodes} (chapter id to node ids). */
    public static JsonObject rawDeleted() {
        return sync.deleted().deepCopy();
    }

    /** State of a node for this player; nodes the rules do not know (should not happen) count as open. */
    /**
     * Whether one of a node's tasks may move now (FTB Quests' {@code require_sequential_tasks}, 2026-10-20). Unknown
     * nodes and tasks count as open, so a data mismatch never hides a task for good.
     */
    public static boolean taskOpen(String key, int index) {
        ProgressModel.Node n = sync.rules() == null ? null : sync.rules().model().nodes.get(key);
        return n == null || index < 0 || index >= n.tasks.size() || sync.rules().taskOpen(progress, n, index);
    }

    /**
     * How often a repeatable quest was completed, first run included (2026-10-20): {@code repeats} counts the times
     * it started over after a claim, and a finished quest is one more until that claim resets it. 0 for anything else.
     */
    public static int completions(String key) {
        ProgressModel.Node n = sync.rules() == null ? null : sync.rules().model().nodes.get(key);
        if (n == null || !n.json.has("repeat")) {
            return 0;
        }
        if (!ProgressRules.repeatable(n)) {
            return 0;
        }
        return progress.repeats.getOrDefault(key, 0) + (progress.completed.contains(key) ? 1 : 0);
    }

    /** Whether a node's requirements are met; a "flexible" node can be open without it. */
    public static boolean unlocked(String key) {
        ProgressModel.Node n = sync.rules() == null ? null : sync.rules().model().nodes.get(key);
        return n == null || sync.rules().unlocked(progress, n);
    }

    /** Pack and team stages merged once per progress object; read by every barrier check. */
    private static java.util.Set<String> stages() {
        if (barrierProg != progress) {
            java.util.Set<String> merged = new java.util.HashSet<>(progress.stages);
            merged.addAll(progress.teamStages);
            barrierStages = java.util.Set.copyOf(merged);
            barrierProg = progress;
        }
        return barrierStages;
    }

    /** The client's copy of a quest barrier: the same rule the server uses, from synced progress. */
    public static boolean barrierAllows(String mode, String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        java.util.Set<String> stages = stages();
        if ("stage".equals(mode) && !stages.contains(key)
                && com.kuronami.questtree.progress.PackStagesClient.has(key)) {
            stages = new java.util.HashSet<>(stages);
            stages.add(key);
        }
        return com.kuronami.questtree.progress.BarrierRule.allows(mode, key, progress.completed, stages);
    }

    public static ProgressRules.State state(String key) {
        ProgressModel.Node n = sync.rules() == null ? null : sync.rules().model().nodes.get(key);
        return n == null ? ProgressRules.State.OPEN : sync.rules().state(progress, n);
    }

    /** Whether the player may see the node yet (hidden quests); unknown nodes count as visible. */
    public static boolean visible(String key) {
        ProgressModel.Node n = sync.rules() == null ? null : sync.rules().model().nodes.get(key);
        if (n == null) {
            return true;
        }
        // asked several times per node per frame; the answer only changes with new progress
        if (visibleFor != progress || visibleRules != sync.rules()) {
            VISIBLE.clear();
            visibleFor = progress;
            visibleRules = sync.rules();
        }
        Boolean v = VISIBLE.get(key);
        // the rules fill the map themselves as they work through a node's requirements (one memo per progress)
        boolean shown = v != null ? v : sync.rules().visible(progress, n, VISIBLE);
        if (shown && model() != null && model().file.hideExcludedQuests
                && sync.rules().state(progress, n) == ProgressRules.State.EXCLUDED) {
            // hide_excluded_quests: a quest closed by the other choice in its group leaves the map entirely
            return false;
        }
        return shown;
    }

    private static final java.util.Map<String, Boolean> VISIBLE = new java.util.HashMap<>();
    private static Object visibleFor;
    private static Object visibleRules;

    /** Chapters hidden from the list as a whole. */
    public static boolean chapterHidden(String id) {
        ProgressModel.Chapter c = sync.rules() == null ? null : sync.rules().model().chapters.get(id);
        return c != null && (c.alwaysHidden || !ProgressRules.chapterUnlocked(progress, c));
    }

    /** A chapter the pack marks {@code visibility.always_hidden}: not even a locked row is shown for it. */
    public static boolean chapterAlwaysHidden(String id) {
        ProgressModel.Chapter c = sync.rules() == null ? null : sync.rules().model().chapters.get(id);
        return c != null && c.alwaysHidden;
    }

    /** A chapter the player has not unlocked yet: shown greyed in the list, not opened. */
    public static boolean chapterLocked(String id) {
        ProgressModel.Chapter c = sync.rules() == null ? null : sync.rules().model().chapters.get(id);
        return c != null && !c.alwaysHidden && !ProgressRules.chapterUnlocked(progress, c);
    }

    /** Exchange purchases already made on the chapter's pool. */
    public static int exchangeUsed(ClientModel.Chapter c) {
        return c == null ? 0 : progress.exchange.getOrDefault(ProgressRules.key(c.id, c.pool), 0);
    }

    /**
     * How a node's tile looks. A quest's "claim" is done with a reward waiting (green + gold); a skill's is "can unlock"
     * (white). Locked nodes carry a lock only when they are the next step (every requirement started or done).
     */
    static Tiles.Look look(String key) {
        ProgressModel.Node n = sync.rules() == null ? null : sync.rules().model().nodes.get(key);
        ProgressRules.State s = state(key);
        boolean skill = n != null && n.chapter.isSkills() && n.tasks.isEmpty();
        return switch (s) {
            case DONE -> Tiles.Look.DONE;
            case CLAIM -> skill ? Tiles.Look.AFFORDABLE : Tiles.Look.DONE_REWARD;
            case PROGRESS -> Tiles.Look.PROGRESS;
            case OPEN -> Tiles.Look.OPEN;
            case EXCLUDED -> Tiles.Look.EXCLUDED;
            case LOCKED -> {
                if (n == null) {
                    yield Tiles.Look.LOCKED_NEAR;
                }
                // the next step: with "all", every requirement is reachable; with "any", one of them is
                boolean any = n.requireMode.startsWith("any");
                boolean someNear = false;
                boolean someFar = false;
                for (String r : n.requires) {
                    boolean far = state(r) == ProgressRules.State.LOCKED || state(r) == ProgressRules.State.EXCLUDED;
                    someFar |= far;
                    someNear |= !far;
                }
                yield (any ? someNear || n.requires.isEmpty() : !someFar) ? Tiles.Look.LOCKED_NEAR : Tiles.Look.LOCKED_FAR;
            }
        };
    }

    /** The icon a node's tile shows (its own; claiming is shown by the tile, not by swapping the icon). */
    static net.minecraft.world.item.ItemStack iconFor(ClientModel.Node n) {
        if (n.iconTask != null) {
            net.minecraft.world.item.ItemStack shown = n.iconTask.icon();
            if (!shown.isEmpty()) {
                return shown;
            }
        }
        return n.icon;
    }

    /** What a node's tile shows: its icon item, or the drawn/entity icon the importer kept. */
    static Tiles.Glyph glyphFor(ClientModel.Node n) {
        if (n.iconTexture != null || n.iconEntity != null) {
            return new Tiles.Glyph(net.minecraft.world.item.ItemStack.EMPTY, n.iconTexture, n.iconEntity);
        }
        return Tiles.Glyph.item(iconFor(n));
    }

    public static double fraction(String key) {
        ProgressModel.Node n = sync.rules() == null ? null : sync.rules().model().nodes.get(key);
        return n == null ? 0 : sync.rules().fraction(progress, n);
    }

    /** Points that can be spent now in a skill chapter. */
    public static int points(String chapterId) {
        ProgressModel.Chapter c = sync.rules() == null ? null : sync.rules().model().chapters.get(chapterId);
        return c == null || !c.isSkills() ? 0 : sync.rules().availablePoints(progress, c);
    }

    public static void send(String action, String node, int index) {
        sender.accept(new ActionPayload(action, node == null ? "" : node, index));
    }

    /** An action that also carries a choice option (one reward of a choice table). */
    public static void send(String action, String node, int index, int choice) {
        sender.accept(new ActionPayload(action, node == null ? "" : node, index, choice));
    }

    /** Whether one reward of a node was taken (per-reward claim, 2026-10-20). */
    public static boolean rewardClaimed(String node, int index) {
        String key = node + "#" + index;
        return progress.claimedRewards.contains(key) || progress.teamClaimedRewards.contains(key);
    }

    /**
     * Quests "claim all" would take right now (2026-10-20): completed, not excluded from claim all, and with no
     * choice left to make. Used by the chapter list's collect row and the buttons outside the screen.
     */
    public static int claimableCount() {
        ProgressRules r = sync.rules();
        if (r == null) {
            return 0;
        }
        ClientModel m = sync.model();
        if (m == null) {
            return 0;
        }
        if (CLAIMABLE.valid(progress, r)) {
            return CLAIMABLE.value();
        }
        int n = 0;
        for (ClientModel.Node node : m.nodesByKey.values()) {
            ClientModel.Chapter chapter = m.chapterOf.get(node.key);
            if ((chapter != null && chapter.skills) || node.needsChoice() || node.excludeFromClaimAll) {
                continue;
            }
            ProgressModel.Node serverNode = r.model().nodes.get(node.key);
            if (serverNode != null && r.state(progress, serverNode) == ProgressRules.State.CLAIM) {
                n++;
            }
        }
        CLAIMABLE.store(progress, r, n);
        return n;
    }

    /** Leaving a server: forget its data so another server never shows it. */
    /**
     * A key pressed in any other screen. The open key over an item (in an inventory, or in the recipe viewer's lists)
     * opens the quest that wants that item; with no such quest the key is left alone.
     */
    public static boolean onScreenKey(net.minecraft.client.gui.screens.Screen screen, int key, int scan) {
        if (screen instanceof ProgressScreen || !OPEN_KEY.matches(key, scan) || sync.model() == null) {
            return false;
        }
        // a key typed into a text field is a letter, not a shortcut (the creative search, an anvil, the viewer's search)
        if (screen.getFocused() instanceof net.minecraft.client.gui.components.EditBox
                || screen.getFocused() instanceof net.minecraft.client.gui.components.MultiLineEditBox) {
            return false;
        }
        net.minecraft.world.item.ItemStack stack = net.minecraft.world.item.ItemStack.EMPTY;
        RecipeViewer viewer = RecipeViewer.Holder.get();
        if (viewer != null && viewer.typing()) {
            return false;
        }
        if (viewer != null) {
            stack = viewer.underMouse();
        }
        if (stack.isEmpty() && screen instanceof com.kuronami.questtree.mixin.AbstractContainerScreenAccessor a) {
            var slot = a.questTree$hoveredSlot();
            if (slot != null && slot.hasItem()) {
                stack = slot.getItem();
            }
        }
        String quest = ItemTooltips.questFor(stack);
        if (quest == null) {
            return false;
        }
        ProgressScreen.openAt(quest);
        return true;
    }

    public static void onLogout() {
        sync = SyncState.empty();
        barrierProg = null;
        barrierStages = java.util.Set.of();
        progress = new PlayerProgress();
        lockedHints = Map.of();
        haveProgress = false;
        receivingGeneration = -1;
        buffer = null;
        CLAIMABLE.clear();
        EditorSession.onLogout();
        ClientTeam.onLogout();
        ClientCues.clear();
        ProgressScreen.clearViews();
        ProgressHud.clear();
        Motion.clear();
        QuestViewerData.clear();
    }

    /** Called every client tick by the loader module. The same key opens and closes the screen. */
    public static void onClientTick() {
        QuestViewerData.onClientTick();
        EditorChangelog.tick(net.minecraft.Util.getMillis());
        Minecraft mc = Minecraft.getInstance();
        if (DevTour.ENABLED) {
            DevTour.tick();
        }
        while (OPEN_KEY.consumeClick()) {
            if (mc.screen == null && mc.player != null) {
                ProgressScreen.open();
            }
        }
        while (TRACK_KEY.consumeClick()) {
            if (mc.screen == null) {
                ProgressHud.toggleTracker();
            }
        }
    }
}
