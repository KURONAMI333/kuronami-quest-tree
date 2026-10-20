package com.kuronami.questtree.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kuronami.questtree.Constants;
import com.kuronami.questtree.data.ProgressModel;
import com.kuronami.questtree.network.ActionPayload;
import com.kuronami.questtree.network.ProgressPayload;
import com.kuronami.questtree.network.SyncPayload;
import com.kuronami.questtree.progress.PlayerProgress;
import com.kuronami.questtree.progress.ProgressJson;
import com.kuronami.questtree.progress.ProgressRules;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

/** Client entry points shared by both loaders: the synced data, the player's progress and the keys. */
public final class ClientProgress {

    public static final KeyMapping OPEN_KEY = new KeyMapping("key.kuronami_quest_tree.open", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K, "key.categories.kuronami_quest_tree");
    /** Shows or hides the pinned quests at the side of the game screen. */
    public static final KeyMapping TRACK_KEY = new KeyMapping("key.kuronami_quest_tree.track", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_J, "key.categories.kuronami_quest_tree");

    private static ClientModel model;
    private static ProgressRules rules;
    private static PlayerProgress progress = new PlayerProgress();
    /** False until the first progress packet after joining, so joining does not replay every change as news. */
    private static boolean haveProgress;
    /** Set by the loader module: sends a payload to the server. */
    public static Consumer<ActionPayload> sender = a -> { };
    private static int receivingGeneration = -1;
    private static ByteArrayOutputStream buffer;
    private static int nextPart;

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
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
                JsonObject bundle = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                // a filter's items come from the tags this server sent, so items worked out for another world are dropped
                com.kuronami.questtree.progress.ItemFilter.clearCache();
                model = new ClientModel(bundle);
                rules = new ProgressRules(ProgressModel.of(bundle));
                Pins.load();
                Ui.loadPalette(net.minecraft.client.Minecraft.getInstance().getResourceManager());
                Constants.LOG.info("Received {} progression chapters", model.chapters.size());
            } catch (Exception ex) {
                Constants.LOG.error("Could not read progression data from server", ex);
            }
            buffer = null;
        }
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
        if (haveProgress) {
            ProgressFeedback.onUpdate(old, next, p.events());
        }
        haveProgress = true;
    }

    public static PlayerProgress progress() {
        return progress;
    }

    public static ProgressRules rules() {
        return rules;
    }

    public static ClientModel model() {
        return model;
    }

    /** State of a node for this player; nodes the rules do not know (should not happen) count as open. */
    /**
     * Whether one of a node's tasks may move now (FTB Quests' {@code require_sequential_tasks}, 2026-10-20). Unknown
     * nodes and tasks count as open, so a data mismatch never hides a task for good.
     */
    public static boolean taskOpen(String key, int index) {
        ProgressModel.Node n = rules == null ? null : rules.model().nodes.get(key);
        return n == null || index < 0 || index >= n.tasks.size() || rules.taskOpen(progress, n, index);
    }

    /**
     * How often a repeatable quest was completed, first run included (2026-10-20): {@code repeats} counts the times
     * it started over after a claim, and a finished quest is one more until that claim resets it. 0 for anything else.
     */
    public static int completions(String key) {
        ProgressModel.Node n = rules == null ? null : rules.model().nodes.get(key);
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
        ProgressModel.Node n = rules == null ? null : rules.model().nodes.get(key);
        return n == null || rules.unlocked(progress, n);
    }

    public static ProgressRules.State state(String key) {
        ProgressModel.Node n = rules == null ? null : rules.model().nodes.get(key);
        return n == null ? ProgressRules.State.OPEN : rules.state(progress, n);
    }

    /** Whether the player may see the node yet (hidden quests); unknown nodes count as visible. */
    public static boolean visible(String key) {
        ProgressModel.Node n = rules == null ? null : rules.model().nodes.get(key);
        if (n == null) {
            return true;
        }
        // asked several times per node per frame; the answer only changes with new progress
        if (visibleFor != progress || visibleRules != rules) {
            VISIBLE.clear();
            visibleFor = progress;
            visibleRules = rules;
        }
        Boolean v = VISIBLE.get(key);
        // the rules fill the map themselves as they work through a node's requirements (one memo per progress)
        return v != null ? v : rules.visible(progress, n, VISIBLE);
    }

    private static final java.util.Map<String, Boolean> VISIBLE = new java.util.HashMap<>();
    private static Object visibleFor;
    private static Object visibleRules;

    /** Chapters hidden from the list as a whole. */
    public static boolean chapterHidden(String id) {
        ProgressModel.Chapter c = rules == null ? null : rules.model().chapters.get(id);
        return c != null && (c.alwaysHidden || !ProgressRules.chapterUnlocked(progress, c));
    }

    /**
     * How a node's tile looks. A quest's "claim" is done with a reward waiting (green + gold); a skill's is "can unlock"
     * (white). Locked nodes carry a lock only when they are the next step (every requirement started or done).
     */
    static Tiles.Look look(String key) {
        ProgressModel.Node n = rules == null ? null : rules.model().nodes.get(key);
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

    public static double fraction(String key) {
        ProgressModel.Node n = rules == null ? null : rules.model().nodes.get(key);
        return n == null ? 0 : rules.fraction(progress, n);
    }

    /** Points that can be spent now in a skill chapter. */
    public static int points(String chapterId) {
        ProgressModel.Chapter c = rules == null ? null : rules.model().chapters.get(chapterId);
        return c == null || !c.isSkills() ? 0 : rules.availablePoints(progress, c);
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
        return progress.claimedRewards.contains(node + "#" + index);
    }

    /**
     * Quests "claim all" would take right now (2026-10-20): completed, not excluded from claim all, and with no
     * choice left to make. Used by the chapter list's collect row and the buttons outside the screen.
     */
    public static int claimableCount() {
        ProgressRules r = rules;
        if (r == null) {
            return 0;
        }
        ClientModel m = model;
        if (m == null) {
            return 0;
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
        return n;
    }

    /** Leaving a server: forget its data so another server never shows it. */
    /**
     * A key pressed in any other screen. The open key over an item (in an inventory, or in the recipe viewer's lists)
     * opens the quest that wants that item; with no such quest the key is left alone.
     */
    public static boolean onScreenKey(net.minecraft.client.gui.screens.Screen screen, int key, int scan) {
        if (screen instanceof ProgressScreen || !OPEN_KEY.matches(key, scan) || model == null) {
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
        model = null;
        rules = null;
        progress = new PlayerProgress();
        haveProgress = false;
        receivingGeneration = -1;
        buffer = null;
        ProgressScreen.clearViews();
        ProgressHud.clear();
        Motion.clear();
    }

    /** Called every client tick by the loader module. The same key opens and closes the screen. */
    public static void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (DevTour.ENABLED) {
            DevTour.tick();
        }
        while (OPEN_KEY.consumeClick()) {
            if (mc.screen == null && mc.player != null) {
                mc.setScreen(new ProgressScreen());
            }
        }
        while (TRACK_KEY.consumeClick()) {
            if (mc.screen == null) {
                ProgressHud.toggleTracker();
            }
        }
    }
}
