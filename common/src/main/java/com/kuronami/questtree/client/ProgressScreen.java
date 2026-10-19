package com.kuronami.questtree.client;

import com.kuronami.questtree.progress.PlayerProgress;
import com.kuronami.questtree.progress.ProgressRules.State;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The progression screen (layout of the accepted v12-v14 mocks, see _research/progression_ui/).
 * <ul>
 *   <li>"Now" first: what can be claimed or unlocked now, then what is moving, then recent events. The chapter list is
 *       docked open on this screen.</li>
 *   <li>A chapter gives the tree the whole screen. The chapter list becomes a rail that opens on hover; node details
 *       slide in from the right only while a node is selected; zoom buttons and the minimap sit bottom-right.</li>
 * </ul>
 * State is a filled surface: yellow = one click to claim or unlock, green = done, a yellow fill from the bottom =
 * progress. Everything is drawn in GUI units with the vanilla font, so it follows the player's GUI scale.
 */
public class ProgressScreen extends Screen {

    /**
     * The scrim over the blurred world: even, and light enough that a closed node and a closed line still read a step
     * above it (2026-10-17, picked from renders of the same 196-quest chapter at five grounds: the old near-black left
     * the zoomed-out map almost black). Since 2026-10-18 it is Discord's main surface (see {@link Ui}).
     */
    private static int SCRIM = Ui.GROUND;
    /** The band behind the chapter title, in the ground's own tone so it covers the map without a seam. */
    private static int FADE = 0xCC000000 | (Ui.GROUND & 0xFFFFFF);
    /** What a node the search does not match wears, so it sinks into the ground instead of going dark. */
    private static int SINK = 0xC0000000 | (Ui.GROUND & 0xFFFFFF);

    private static final int UNIT = 24;      // GUI px per grid unit (FTB Quests positions)
    private static final int NODE = 20;      // GUI px of a size-1 node
    private static final int RAIL = 34;
    private static final int RAIL_OPEN_MIN = 150;
    /** Open width of the chapter list: as wide as its longest name needs (ATM10's names were cut to "Chapter 2:..."). */
    private int railOpenW = RAIL_OPEN_MIN;
    private int railWidest;
    /** Height of a flat control (the search field, the zoom buttons). */
    private static final int BTN = 28;
    /** Height of the search field's EditBox; its box is drawn taller around it. */
    private static final int EDIT_H = 9;
    /** Height of a chapter row and its pitch; 20/22 read cramped next to the panel's own padding. */
    private static final int RAIL_ROW = 24;
    private static final int RAIL_PITCH = 26;
    /** Pin slot at the right end of a chapter row: reserved in the open list, so hovering never shifts a row. */
    private static final int RAIL_PIN_W = 16;
    /** FTB Quests' default zoom (16): 28 GUI px per grid step, against our UNIT of 24. */
    private static final float FTB_SCALE = 28f / 24f;
    /** Extra room left of a fitted map, beyond the closed chapter list. */
    private static final int FIT_LEFT = 40;
    private static final int NOW_TILE = 32;
    private static final int NOW_GAP = 6;
    /** How many of a quest's rewards a Now cell shows at its right edge; the rest are in the detail sheet. */
    private static final int NOW_REWARDS = 2;
    /** Narrowest Now cell: the tile and room for about 20 characters of title beside it (15 read cramped, review 2026-10-16). */
    private static final int NOW_CELL_MIN = 180;
    /** Gap between the Now grid and the 最新 log column that holds the right edge. */
    private static final int LOG_GAP = 12;
    /** In-progress quests Now lists: it answers "what can I do now", and forty of them buried the claimable ones
     *  (the player, 2026-10-19). A chapter still shows every quest. */
    private static final int NOW_MOVING_MAX = 6;
    private static final int DOCK_MIN_WIDTH = 560;
    private static final float FAR = 0.6f;   // below this zoom, arrowheads and optional outlines are left out (icons stay)
    private static final float OVERLAY_Z = Tiles.OVERLAY_Z;

    /** Per-chapter camera, kept for the session (players asked for the position to be remembered). */
    private static final Map<String, float[]> VIEWS = new HashMap<>();
    private static int lastChapter = -1;

    /** Called on logout so another server starts with fresh cameras. */
    public static void clearViews() {
        VIEWS.clear();
        lastChapter = -1;
        query = "";
    }

    // ------------------------------------------------------------------ search (EXTERNAL_REFERENCES 1-1)

    /** The search text, kept while the game runs so reopening the screen keeps the search. */
    private static String query = "";
    private net.minecraft.client.gui.components.EditBox searchBox;
    private String matchedFor;
    private ClientModel matchedModel;
    /** Visible nodes matching the search, in chapter order, and the count per chapter. */
    private final List<String> matches = new ArrayList<>();
    private final java.util.Set<String> matchSet = new java.util.HashSet<>();
    private final Map<String, Integer> matchCount = new HashMap<>();
    private int matchCursor = -1;
    /**
     * Chapter decorations (FTB Quests' chapter images) name a texture from the pack they came from, so a pack used
     * without that pack's resource pack draws the magenta "missing texture" across the map. Record what this client
     * can actually load. This is worked out once per screen and dropped in {@link #init()}, which a resource reload
     * calls again for the screen it finds open, so a texture that arrives with a resource pack is picked up then.
     */
    private static final Map<ResourceLocation, Boolean> imageCache = new HashMap<>();
    private static boolean tourResting;

    @Override
    protected void init() {
        Ui.loadPalette(net.minecraft.client.Minecraft.getInstance().getResourceManager());
        imageCache.clear();
        searchBox = new net.minecraft.client.gui.components.EditBox(font, 0, 8, 120 - 2 * Ui.S2, EDIT_H, Component.translatable("screen.kuronami_quest_tree.search"));
        // drawn as one of the screen's flat controls (renderSearch), not vanilla's black box with a grey border
        searchBox.setBordered(false);
        searchBox.setHint(Component.translatable("screen.kuronami_quest_tree.search").withStyle(net.minecraft.ChatFormatting.GRAY));
        searchBox.setMaxLength(64);
        searchBox.setValue(query);
        searchBox.setResponder(v -> {
            query = v;
            matchCursor = -1;
        });
        // input only: drawn by render() above the scrim
        addWidget(searchBox);
        if (pendingGoTo != null && model() != null) {
            goTo(pendingGoTo);
            pendingGoTo = null;
        }
    }

    /** Opens the screen on one quest (the open key over an item that a quest wants). */
    static void openAt(String key) {
        ProgressScreen screen = new ProgressScreen();
        screen.pendingGoTo = key;
        query = "";
        net.minecraft.client.Minecraft.getInstance().setScreen(screen);
    }

    private boolean searching() {
        return !query.isBlank();
    }

    /** Title, task targets and reward item names, in the player's language, case-insensitive. */
    private void refreshMatches(ClientModel m) {
        if (query.equals(matchedFor) && m == matchedModel && cachedFor == matchedProgress) {
            return;
        }
        matchedFor = query;
        matchedModel = m;
        matchedProgress = cachedFor;
        matches.clear();
        matchSet.clear();
        matchCount.clear();
        if (!searching()) {
            return;
        }
        String q = query.toLowerCase(java.util.Locale.ROOT).trim();
        for (ClientModel.Chapter c : m.chapters) {
            if (ClientProgress.chapterHidden(c.id)) {
                continue;
            }
            for (ClientModel.Node n : c.nodes.values()) {
                if (ClientProgress.visible(n.key) && nodeMatches(n, q)) {
                    matches.add(n.key);
                    matchSet.add(n.key);
                    matchCount.merge(c.id, 1, Integer::sum);
                }
            }
        }
    }

    private Object matchedProgress;

    // ------------------------------------------------------------------ path to the selected node (EXTERNAL_REFERENCES 1-2)

    private PathFinder.Path path = PathFinder.Path.NONE;
    private java.util.Set<String> pathSet = java.util.Set.of();
    private String pathFor;
    private Object pathProgress;

    private PathFinder.Path path() {
        if (selected == null || ClientProgress.rules() == null) {
            return PathFinder.Path.NONE;
        }
        if (!selected.equals(pathFor) || ClientProgress.progress() != pathProgress) {
            pathFor = selected;
            pathProgress = ClientProgress.progress();
            path = PathFinder.find(ClientProgress.rules(), ClientProgress.progress(), selected);
            pathSet = new java.util.HashSet<>(path.nodes());
        }
        return path;
    }

    private static boolean nodeMatches(ClientModel.Node n, String q) {
        if (n.title.getString().toLowerCase(java.util.Locale.ROOT).contains(q)) {
            return true;
        }
        for (ClientModel.Task t : n.tasks) {
            if (t.name().getString().toLowerCase(java.util.Locale.ROOT).contains(q)) {
                return true;
            }
        }
        for (ItemStack r : n.rewards) {
            if (r.getHoverName().getString().toLowerCase(java.util.Locale.ROOT).contains(q)) {
                return true;
            }
        }
        for (ItemStack r : n.choice) {
            if (r.getHoverName().getString().toLowerCase(java.util.Locale.ROOT).contains(q)) {
                return true;
            }
        }
        return false;
    }

    /** Enter: the next match (in the open chapter first, then across chapters), centred and selected. */
    private void nextMatch() {
        if (matches.isEmpty()) {
            return;
        }
        ClientModel.Chapter ch = chapter();
        int start = matchCursor;
        for (int step = 1; step <= matches.size(); step++) {
            int i = (start + step) % matches.size();
            String key = matches.get(i);
            if (ch == null || model().chapterOf.get(key) == ch || step == matches.size()) {
                matchCursor = i;
                goTo(key);
                return;
            }
        }
        matchCursor = (start + 1) % matches.size();
        goTo(matches.get(matchCursor));
    }

    private record Button(int x, int y, int w, int h, Runnable run) {
        boolean hit(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    /** A tile on the Now screen: a node, or a skill chapter with points to spend. {@code allSkills} marks the one cell
     *  that stands for every skill chapter at once (its value is the total, it opens the first). */
    private record NowEntry(String key, ClientModel.Node node, ClientModel.Chapter pointsChapter, int points, boolean allSkills) {
        NowEntry(String key, ClientModel.Node node, ClientModel.Chapter pointsChapter) {
            this(key, node, pointsChapter, 0, false);
        }
    }

    /**
     * A rail entry: a group's header (title while open, a divider while closed; never clickable) or a chapter row.
     * {@code chapter} is null on a header. Mixed heights (chapter rows use railPitch(), headers HEADER_H) keep groups
     * readable the way FTB Quests' chapter list does.
     */
    private record RailRow(ClientModel.Chapter chapter, Component header) {
        boolean isHeader() {
            return chapter == null;
        }
    }

    private int chapterIndex = lastChapter;
    /** Selected node, global key. */
    private String selected;
    private int choicePick = -1;
    /** Chapter list scroll in GUI px: shown value easing to the target the wheel sets. */
    private float railScroll;
    private float railScrollTarget;
    private int nowScroll;
    private float railT;
    private float sheetT;
    /** Details sheet scroll: the shown value easing to the wheel's target, and the max of the last drawn frame. */
    private float sheetScroll;
    private float sheetScrollTarget;
    private int sheetContentMax;
    /** The node the current scroll belongs to; a new node starts at the top. */
    private String sheetScrollFor;
    private long lastFrame;
    /** A tooltip asked for while drawing (icon buttons), drawn last so nothing covers it. */
    private Component pendingTip;
    /** The node under the mouse last frame; lines are drawn before nodes, so they use this one. */
    private String hoveredNode;
    /** Per node, how far its hover growth has run (0..1). */
    private final Map<String, Float> hoverGrow = new HashMap<>();
    private static final float RAIL_MS = 180;
    private static final float SHEET_MS = 200;
    private static final float HOVER_MS = 120;
    private boolean dragging;
    private boolean moved;
    private double dragStartX;
    private double dragStartY;
    private final List<Button> buttons = new ArrayList<>();
    private final Map<String, Long> flashes = new HashMap<>();
    private final Map<String, Long> shakes = new HashMap<>();

    // derived from the progress; rebuilt when a new progress object arrives
    private PlayerProgress cachedFor;
    private ClientModel cachedModel;
    /** Pin version the rows were built from: pinning reorders them without any progress changing. */
    private int railPins = -1;
    /** The item under the pointer in the detail panel (a task or a reward), for the recipe viewer's keys. */
    private ItemStack hoverStack = ItemStack.EMPTY;
    private int lastMouseX;
    private int lastMouseY;
    /** A quest to show once the screen is laid out (opened from an item, see {@link #openAt}). */
    private String pendingGoTo;
    private final List<NowEntry> nowEntries = new ArrayList<>();
    private final Map<String, Integer> badges = new LinkedHashMap<>();
    /** done, visible, hidden node counts per chapter. */
    private final Map<String, int[]> chapterCounts = new LinkedHashMap<>();
    /** Chapters listed in the rail (chapters hidden as a whole are left out). */
    private final List<ClientModel.Chapter> railChapters = new ArrayList<>();
    /** The rail's rows: a header before the first chapter of each titled group, then the chapter rows. */
    private final List<RailRow> railRows = new ArrayList<>();
    private int claimableNoChoice;
    private int doneTotal;
    private int questTotal;

    public ProgressScreen() {
        super(Component.translatable("screen.kuronami_quest_tree.title"));
        Motion.resetFills();
    }

    private ClientModel model() {
        return ClientProgress.model();
    }

    private ClientModel.Chapter chapter() {
        ClientModel m = model();
        if (m == null || chapterIndex < 0 || chapterIndex >= m.chapters.size()) {
            return null;
        }
        return m.chapters.get(chapterIndex);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ derived data

    private void refresh(ClientModel m) {
        PlayerProgress p = ClientProgress.progress();
        if (p == cachedFor && m == cachedModel && railPins == ClientConfig.railPinVersion()) {
            return;
        }
        cachedFor = p;
        cachedModel = m;
        railPins = ClientConfig.railPinVersion();
        nowEntries.clear();
        badges.clear();
        chapterCounts.clear();
        claimableNoChoice = 0;
        doneTotal = 0;
        questTotal = 0;
        List<NowEntry> pinned = new ArrayList<>();
        List<NowEntry> claim = new ArrayList<>();
        List<NowEntry> points = new ArrayList<>();
        List<NowEntry> moving = new ArrayList<>();
        // chapters the player has started or finished something in: their startable quests come first in Now
        java.util.Set<String> started = new java.util.HashSet<>();
        railChapters.clear();
        railRows.clear();
        railWidest = 0;
        for (ClientModel.Chapter c : m.chapters) {
            if (!ClientProgress.chapterHidden(c.id)) {
                railChapters.add(c);
                railWidest = Math.max(railWidest, font.width(c.title));
            }
            int done = 0;
            int badge = 0;
            int shown = 0;
            int hidden = 0;
            for (ClientModel.Node n : c.nodes.values()) {
                if (!ClientProgress.visible(n.key)) {
                    hidden++;
                    continue;
                }
                shown++;
                State s = ClientProgress.state(n.key);
                if (s == State.DONE || s == State.CLAIM) {
                    // completed, rewards waiting or not
                    done++;
                }
                if (s == State.DONE || s == State.CLAIM || s == State.PROGRESS) {
                    started.add(c.id);
                }
                if (c.skills) {
                    continue;
                }
                if (!n.optional) {
                    questTotal++;
                    if (s == State.DONE || s == State.CLAIM) {
                        doneTotal++;
                    }
                }
                NowEntry e = new NowEntry(n.key, n, null);
                if (Pins.has(n.key) && s != State.DONE) {
                    pinned.add(e);
                } else if (s == State.CLAIM || flashes.containsKey(n.key)) {
                    // a just-claimed tile stays where it was until its animation ends, then the list closes up
                    claim.add(e);
                } else if (s == State.PROGRESS) {
                    moving.add(e);
                }
                if (s == State.CLAIM) {
                    badge++;
                    if (n.choice.isEmpty()) {
                        claimableNoChoice++;
                    }
                }
            }
            if (c.skills) {
                badge = ClientProgress.points(c.id);
                if (badge > 0 && hasTakeable(c)) {
                    points.add(new NowEntry(c.id, null, c, badge, false));
                } else {
                    badge = 0;
                }
            }
            badges.put(c.id, badge);
            chapterCounts.put(c.id, new int[]{done, shown, hidden});
        }
        // pinned chapters form their own block at the top, in pin order: quick access to the few a player works in
        List<ClientModel.Chapter> pinnedRail = new ArrayList<>();
        for (String id : ClientConfig.railPinned) {
            for (ClientModel.Chapter c : railChapters) {
                if (c.id.equals(id)) {
                    pinnedRail.add(c);
                    break;
                }
            }
        }
        if (!pinnedRail.isEmpty()) {
            railRows.add(new RailRow(null, Component.translatable("screen.kuronami_quest_tree.pin")));
            for (ClientModel.Chapter c : pinnedRail) {
                railRows.add(new RailRow(c, null));
            }
        }
        // chapters are sorted by group order, so a group's chapters are contiguous: drop its header before the first.
        // A group without a title (or chapters with no group) simply gets no header, keeping the old rail.
        String lastGroup = null;
        for (ClientModel.Chapter c : railChapters) {
            if (ClientConfig.railPinned(c.id)) {
                continue;
            }
            if (c.group != null && !c.group.equals(lastGroup)) {
                Component header = m.groupTitle(c);
                if (header != null) {
                    railRows.add(new RailRow(null, header));
                }
            }
            lastGroup = c.group;
            railRows.add(new RailRow(c, null));
        }
        // many skill chapters (ATM-style, Artisan Life's 11 jobs) would fill the grid with "N points" tiles: one cell
        // stands for all of them, its value their total, and it opens the first (review 2026-10-16)
        if (points.size() >= 2) {
            int total = 0;
            for (NowEntry e : points) {
                total += e.points;
            }
            ClientModel.Chapter first = points.get(0).pointsChapter;
            points.clear();
            points.add(new NowEntry(first.id, null, first, total, true));
        }
        // the required path first, optional quests after it, each closest-to-done first
        moving.sort(Comparator.comparing((NowEntry e) -> e.node.optional).thenComparingDouble(e -> -ClientProgress.fraction(e.key)));
        // what can be started next: chapters the player is already in first, then chapter order and map position. A
        // new world's Now was almost empty without these; six is enough to point the way without a second list.
        List<NowEntry> open = new ArrayList<>();
        java.util.Set<String> listed = new java.util.HashSet<>();
        for (NowEntry e : pinned) {
            listed.add(e.key);
        }
        for (NowEntry e : claim) {
            listed.add(e.key);
        }
        for (NowEntry e : moving) {
            listed.add(e.key);
        }
        for (ClientModel.Chapter c : m.chapters) {
            if (c.skills || ClientProgress.chapterHidden(c.id)) {
                continue;
            }
            for (ClientModel.Node n : c.nodes.values()) {
                if (n.optional || listed.contains(n.key) || !ClientProgress.visible(n.key) || ClientProgress.state(n.key) != State.OPEN
                        || !ClientProgress.unlocked(n.key)) {
                    continue;
                }
                open.add(new NowEntry(n.key, n, null));
            }
        }
        open.sort(Comparator.comparingInt((NowEntry e) -> started.contains(m.chapterOf.get(e.key).id) ? 0 : 1)
                .thenComparingInt(e -> m.chapters.indexOf(m.chapterOf.get(e.key)))
                .thenComparingDouble(e -> e.node.x)
                .thenComparingDouble(e -> e.node.y));
        nowEntries.addAll(pinned);
        nowEntries.addAll(claim);
        nowEntries.addAll(moving.subList(0, Math.min(moving.size(), NOW_MOVING_MAX)));
        nowEntries.addAll(open.subList(0, Math.min(open.size(), 6)));
        nowEntries.addAll(points);
    }

    private static boolean hasTakeable(ClientModel.Chapter c) {
        for (ClientModel.Node n : c.nodes.values()) {
            if (ClientProgress.state(n.key) == State.CLAIM) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        hoverStack = ItemStack.EMPTY;
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        if (tourResting) {
            mouseX = width + 8;
            mouseY = height + 8;
        }
        super.render(g, mouseX, mouseY, partialTick);
        g.fill(0, 0, width, height, SCRIM);
        buttons.clear();
        pendingTip = null;
        Motion.tick();
        // claim animations of tiles that were never drawn (scrolled away) end here, so their tiles leave the list
        long nowMs = Util.getMillis();
        if (flashes.values().removeIf(at -> nowMs - at > 2500)) {
            cachedFor = null;
        }
        ClientModel m = model();
        if (m == null || m.chapters.isEmpty()) {
            Ui.textCenter(g, font, Component.translatable("screen.kuronami_quest_tree.empty"), width / 2, height / 2 - 4, Ui.DIM);
            return;
        }
        refresh(m);
        refreshMatches(m);
        // icon, gaps, a count like "12/127" and the pin slot beside the longest name, within a third of the screen
        railOpenW = Mth.clamp(railWidest + 38 + 34 + RAIL_PIN_W, RAIL_OPEN_MIN, Math.max(RAIL_OPEN_MIN, width / 3));
        ClientModel.Chapter ch = chapter();
        // the chapter list stays open on Now, except on small screens (GUI scale 4 on 1080p is 480 wide) where the tiles need the room
        boolean docked = ch == null && width >= DOCK_MIN_WIDTH;
        boolean overRail = mouseX < (railT > 0.5f ? railOpenW : RAIL) + 8;
        // timed, not stepped per frame: the same smooth 180ms slide at any frame rate (it jumped in 4 frames before)
        float dt = lastFrame == 0 ? 0 : Math.min(100, nowMs - lastFrame);
        lastFrame = nowMs;
        railT = docked ? 1 : Mth.clamp(railT + (overRail ? dt : -dt) / RAIL_MS, 0, 1);
        sheetT = Mth.clamp(sheetT + (selected != null ? dt : -dt) / SHEET_MS, 0, 1);
        hoverGrow.replaceAll((k, t) -> Mth.clamp(t + (k.equals(hoveredNode) ? dt : -dt) / HOVER_MS, 0, 1));
        hoverGrow.values().removeIf(t -> t <= 0);

        if (ch == null) {
            renderNow(g, m, mouseX, mouseY);
        } else {
            renderChapter(g, ch, mouseX, mouseY);
        }
        g.pose().pushPose();
        g.pose().translate(0, 0, OVERLAY_Z + 50);
        if (sheetT > 0) {
            renderSheet(g, m, mouseX, mouseY, dt);
        }
        g.pose().popPose();
        g.pose().pushPose();
        g.pose().translate(0, 0, OVERLAY_Z + 100);
        renderRail(g, m, mouseX, mouseY, docked, dt);
        renderSearch(g, mouseX, mouseY, partialTick);
        g.pose().popPose();
        if (pendingTip != null) {
            g.pose().pushPose();
            g.pose().translate(0, 0, OVERLAY_Z + 300);
            Ui.tooltip(g, font, List.of(pendingTip), mouseX, mouseY);
            g.pose().popPose();
        }
    }

    /**
     * The search box sits in a corner nothing else uses, so it never covers a title, a button or the details sheet:
     * bottom left on chapter maps (zoom buttons and minimap are bottom right), bottom right on Now (its recent list is
     * bottom left). The match count is on the box's open side.
     */
    private void renderSearch(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        boolean map = chapter() != null;
        int w = searchBox.getWidth() + 2 * Ui.S2;
        int y = height - Ui.S2 - BTN;
        // on maps it moves aside with the chapter list, so it never sits on the list's rows
        int x = map ? railRight() + Ui.S2 : nowRight() - w;
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + BTN;
        Ui.surface(g, x, y, w, BTN, Ui.R3, hover ? Ui.OVERLAY_1 : Ui.PANEL_TRANSLUCENT);
        Ui.outline(g, x, y, w, BTN, Ui.R3, searchBox.isFocused() ? Ui.FOCUS : Ui.HAIRLINE);
        searchBox.setX(x + Ui.S2);
        searchBox.setY(y + (BTN - EDIT_H) / 2);
        searchBox.render(g, mouseX, mouseY, partialTick);
        if (searching()) {
            String n = matches.isEmpty() ? "0" : (matchCursor >= 0 ? (matchCursor + 1) + "/" : "") + matches.size();
            int nx = map ? x + w + 6 : x - font.width(n) - 6;
            Ui.text(g, font, n, nx, y + (BTN - 9) / 2 + 1, matches.isEmpty() ? Ui.TEXT_ERROR : Ui.MUTED);
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // vanilla blur of the world behind, then our own scrim in render()
        this.renderBlurredBackground(partialTick);
    }

    private static final int RAIL_LIST_TOP = 36;
    /** Height of a group header row in the rail; chapter rows use {@link #railPitch()} instead. */
    private static final int HEADER_H = 14;

    private int railListBottom() {
        return height - 8;
    }

    /**
     * Row pitch of the chapter list. When it overflows, the pitch is stretched a little so the last visible row is cut
     * in half: a half row says "there is more" (NN/g's illusion of completeness: lists that end on a whole row look
     * finished). When everything fits, rows keep their normal {@link #RAIL_PITCH}.
     */
    private float railPitch() {
        float h = railListBottom() - RAIL_LIST_TOP;
        float content = 0;
        for (RailRow row : railRows) {
            content += row.isHeader() ? HEADER_H : RAIL_PITCH;
        }
        if (content <= h) {
            return RAIL_PITCH;
        }
        int k = Math.max(1, (int) Math.floor(h / (float) RAIL_PITCH - 0.5f));
        return h / (k + 0.5f);
    }

    private float railMaxScroll() {
        float content = 0;
        for (RailRow row : railRows) {
            content += row.isHeader() ? HEADER_H : railPitch();
        }
        return Math.max(0, content - (railListBottom() - RAIL_LIST_TOP) + 4);
    }

    private void renderRail(GuiGraphics g, ClientModel m, int mouseX, int mouseY, boolean docked, float dt) {
        int w = railWidth();
        int bg = docked ? Ui.PANEL_TRANSLUCENT
                : railT > 0.01f ? lerpColor(Ui.RAIL_CLOSED, Ui.PANEL_TRANSLUCENT, railT) : Ui.RAIL_CLOSED;
        Ui.surface(g, 4, 4, w, height - 8, Ui.R4, bg);
        Ui.outline(g, 4, 4, w, height - 8, Ui.R4, Ui.HAIRLINE);
        g.enableScissor(4, 4, 4 + w, height - 4);
        int nowBadge = 0;
        for (int b : badges.values()) {
            nowBadge += b;
        }
        rowRail(g, 8, -1, null, Component.translatable("screen.kuronami_quest_tree.now"), w, mouseX, mouseY, nowBadge, null, null);
        g.disableScissor();
        // the chapter list scrolls smoothly under the Now row
        float max = railMaxScroll();
        railScrollTarget = Mth.clamp(railScrollTarget, 0, max);
        railScroll = Mth.clamp(railScroll + (railScrollTarget - railScroll) * Math.min(1, dt / 70f), 0, max);
        float pitch = railPitch();
        int top = RAIL_LIST_TOP;
        int bottom = railListBottom();
        g.enableScissor(4, top, 4 + w, bottom);
        // rows have mixed heights (chapter pitch, header HEADER_H), so walk a running y instead of indexing by pitch
        float ry = top - railScroll;
        for (RailRow row : railRows) {
            float hgt = row.isHeader() ? HEADER_H : pitch;
            if (ry >= bottom) {
                break;
            }
            if (row.isHeader()) {
                drawRailHeader(g, ry, row.header(), w);
            } else if (ry + hgt > top) {
                ClientModel.Chapter c = row.chapter();
                int y = Math.round(ry);
                int[] cnt = chapterCounts.getOrDefault(c.id, new int[]{0, 0, 0});
                String right = c.skills ? Component.translatable("screen.kuronami_quest_tree.level_short",
                        ClientProgress.progress().level.getOrDefault(c.id, 0)).getString() : cnt[0] + "/" + cnt[1];
                if (searching()) {
                    // while searching, the rail shows where the matches are instead
                    int hits = matchCount.getOrDefault(c.id, 0);
                    rowRail(g, y, m.chapters.indexOf(c), c.displayIcon(), c.title, w, mouseX, mouseY, 0, String.valueOf(hits), c.id);
                } else {
                    rowRail(g, y, m.chapters.indexOf(c), c.displayIcon(), c.title, w, mouseX, mouseY, badges.getOrDefault(c.id, 0),
                            !c.skills && cnt[1] > 0 && cnt[0] == cnt[1] && cnt[2] == 0 ? "" : right, c.id);
                }
            }
            ry += hgt;
        }
        if (max > 0) {
            g.pose().pushPose();
            g.pose().translate(0, 0, OVERLAY_Z + 1);
            // soft edges where rows run under, and a scrollbar that is always there while the list overflows
            if (railScroll > 1) {
                g.fillGradient(4, top, 4 + w, top + 10, 0xCC000000 | (Ui.PANEL & 0xFFFFFF), Ui.PANEL & 0xFFFFFF);
            }
            if (railScroll < max - 1) {
                g.fillGradient(4, bottom - 14, 4 + w, bottom, Ui.PANEL & 0xFFFFFF, 0xCC000000 | (Ui.PANEL & 0xFFFFFF));
            }
            float h = bottom - top;
            float content = h + max;
            int thumb = Math.max(12, Math.round(h * h / content));
            int ty = top + Math.round((h - thumb) * (railScroll / max));
            boolean over = mouseX < 4 + w && mouseX >= 4 && mouseY >= top && mouseY < bottom;
            Ui.surface(g, 4 + w - 7, ty, 3, thumb, 2, over ? Ui.SCROLLBAR_HOVER : Ui.SCROLLBAR);
            g.pose().popPose();
        }
        g.disableScissor();
    }

    /**
     * One rail row. {@code badge} > 0 shows a yellow count (claimable quests, points to spend); otherwise {@code right}
     * is a grey count, and an empty string means the chapter is complete (a green tick). {@code pinId} adds the pin
     * slot at the row's right end: a gold pin while pinned, a grey one while the row is hovered (clicking toggles it).
     */
    private void rowRail(GuiGraphics g, int y, int index, ItemStack icon, Component label, int w, int mouseX, int mouseY, int badge, String right, String pinId) {
        boolean on = index == chapterIndex;
        boolean hover = mouseX >= 4 && mouseX < 4 + w && mouseY >= y && mouseY < y + RAIL_ROW;
        // the row is inset inside the panel and rounded, so the selection reads as a row and not as a slice of the panel
        if (on || hover) {
            Ui.surface(g, 4 + Ui.S1, y, w - 2 * Ui.S1, RAIL_ROW, Ui.R2, on ? Ui.OVERLAY_2 : Ui.OVERLAY_1);
        }
        if (on) {
            // Discord's mark for the open server: a short pill on the panel's left edge
            Ui.surface(g, 4, y + 4, 3, RAIL_ROW - 8, 2, Ui.ACCENT);
        }
        if (icon == null) {
            Tiles.icon(g, "now", 12, y + 6, 12, on ? Ui.TEXT : Ui.MUTED);
        } else {
            g.renderItem(icon, 12, y + 4);
        }
        g.pose().pushPose();
        g.pose().translate(0, 0, OVERLAY_Z);
        if (railT <= 0.6f && badge > 0) {
            // closed rail: a dot on the icon's corner
            Tiles.icon(g, "dot", 24, y + 5, 7, Tiles.GOLD);
        }
        if (railT > 0.6f) {
            int alpha = (int) (255 * Mth.clamp((railT - 0.6f) / 0.4f, 0, 1)) << 24;
            // the pin slot is reserved whether or not a pin is drawn, so a hovering pin never moves the count or label
            int slot = pinId == null ? 0 : RAIL_PIN_W;
            int edge = w - Ui.S1 - slot;
            int rightW = 0;
            if (badge > 0) {
                rightW = Ui.badge(g, font, String.valueOf(badge), edge, y + (RAIL_ROW - Ui.BADGE_H) / 2);
            } else if (right != null && right.isEmpty()) {
                rightW = 10;
                Tiles.icon(g, "tick", edge - 10, y + 8, 8, Tiles.GREEN_TEXT);
            } else if (right != null) {
                rightW = font.width(right) + 4;
                Ui.textRight(g, font, right, edge, y + 8, (Ui.DIM & 0xFFFFFF) | alpha);
            }
            Ui.text(g, font, trim(label.getString(), w - 40 - rightW - slot), 32, y + 8, ((on ? Ui.BRIGHT : Ui.MUTED) & 0xFFFFFF) | alpha);
            if (pinId != null) {
                boolean pinned = ClientConfig.railPinned(pinId);
                if (pinned || hover) {
                    Tiles.icon(g, "pin", pinSlotLeft(w) + 2, y + 8, 9, ((pinned ? Tiles.GOLD : Ui.MUTED) & 0xFFFFFF) | alpha);
                }
            }
        }
        g.pose().popPose();
    }

    /**
     * A group header. Open rail: the title in grey, at the same fade the chapter labels use. Closed rail: a thin
     * divider so the groups still read as separate blocks while only icons are shown.
     */
    private void drawRailHeader(GuiGraphics g, float y, Component title, int w) {
        if (railT > 0.6f) {
            int alpha = (int) (255 * Mth.clamp((railT - 0.6f) / 0.4f, 0, 1)) << 24;
            String s = trim(title.getString(), w - 24);
            Ui.text(g, font, s, 16, Math.round(y + (HEADER_H - 8) / 2f), (Ui.DIM & 0xFFFFFF) | alpha);
        } else {
            int my = Math.round(y + HEADER_H / 2f);
            g.fill(8, my, 4 + w - 4, my + 1, Ui.HAIRLINE);
        }
    }

    // ------------------------------------------------------------------ Now

    private int nowLeft() {
        return (width >= DOCK_MIN_WIDTH ? railOpenW : RAIL) + 20;
    }

    /**
     * Where the 最新 log column starts, which is exactly the room the details sheet opens into. Now used to shrink to
     * the open sheet, so opening a quest moved every cell in the grid and could push the one just clicked off the edge
     * (the player, 2026-10-19). The sheet covers the log instead, and the grid stays where it was.
     */
    private int nowLogLeft() {
        return width - sheetWidth();
    }

    private int nowLogRight() {
        return width - 12;
    }

    private int nowRight() {
        return nowLogLeft() - LOG_GAP;
    }

    /**
     * Now is a grid of cells: the tile, its title in white and its chapter in grey beside it, so what each tile is can
     * be read without hovering (2026-10-15 review: a row of same-coloured tiles said nothing about which quest is which).
     */
    private int nowCols() {
        return Math.max(1, (nowRight() - nowLeft() + NOW_GAP) / (NOW_CELL_MIN + NOW_GAP));
    }

    private int nowCellWidth() {
        int cols = nowCols();
        return (nowRight() - nowLeft() + NOW_GAP) / cols - NOW_GAP;
    }

    private void renderNow(GuiGraphics g, ClientModel m, int mouseX, int mouseY) {
        int left = nowLeft();
        // the title at the font's own size, like the chapter header (drawn twice as large it crowded the grid)
        Component title = Component.translatable("screen.kuronami_quest_tree.now");
        Ui.text(g, font, title, left, 11, Ui.TEXT);
        int tx = left + font.width(title) + Ui.S2;
        if (questTotal > 0) {
            Ui.text(g, font, doneTotal + "/" + questTotal, tx, 22, Ui.MUTED);
        }
        if (claimableNoChoice > 0) {
            Component ca = Component.translatable("screen.kuronami_quest_tree.claim_all");
            int bw = font.width(ca) + 2 * Ui.S2;
            button(g, ca, nowRight() - bw, 8, bw, Tiles.GOLD, true, mouseX, mouseY, () -> {
                ClientProgress.send("claim_all", "", -1);
                long start = Util.getMillis();
                int i = 0;
                for (NowEntry e : nowEntries) {
                    if (e.node != null && ClientProgress.state(e.key) == State.CLAIM && e.node.choice.isEmpty()) {
                        // one tile after another, 140ms apart
                        flashes.put(e.key, start + 140L * i++);
                    }
                }
            });
        }
        int top = 44;
        // the log column has room of its own now, so the grid runs to the bottom edge (2026-10-19)
        int bottom = height - 34;
        int cols = nowCols();
        int cellW = nowCellWidth();
        int rows = (nowEntries.size() + cols - 1) / cols;
        int rowH = NOW_TILE + NOW_GAP;
        nowScroll = Mth.clamp(nowScroll, 0, Math.max(0, rows * rowH - (bottom - top)));
        g.enableScissor(left - 4, top - 4, nowRight() + 4, bottom);
        // a hairline under every row but the last: the grid's rows read as rows, as in FTB Quests' reward list
        // (the player compared the two lists, 2026-10-19). Drawn before the cells so a hover card covers it.
        for (int r = 0; r < rows - 1; r++) {
            int ly = top + r * rowH + NOW_TILE + NOW_GAP / 2 - nowScroll;
            if (ly >= top && ly < bottom) {
                g.fill(left, ly, nowRight(), ly + 1, Ui.HAIRLINE);
            }
        }
        long now = Util.getMillis();
        for (int i = 0; i < nowEntries.size(); i++) {
            NowEntry e = nowEntries.get(i);
            int x = left + (i % cols) * (cellW + NOW_GAP);
            int y = top + (i / cols) * rowH - nowScroll;
            if (y + NOW_TILE < top - 4 || y > bottom) {
                continue;
            }
            boolean hover = mouseX >= x && mouseX < x + cellW && mouseY >= y && mouseY < y + NOW_TILE && mouseY < bottom;
            if (e.key.equals(selected) || hover) {
                // the whole cell is the target; selected = a lighter card behind it, hover = a fainter one
                Ui.surface(g, x - 3, y - 3, cellW + 6, NOW_TILE + 6, Ui.R3, e.key.equals(selected) ? Ui.OVERLAY_2 : Ui.OVERLAY_1);
            }
            renderNowRewards(g, e, x + cellW, y);
            renderNowText(g, e, x + NOW_TILE + 8, y, cellW - NOW_TILE - 8 - nowRewardsWidth(e));
            if (e.pointsChapter != null) {
                Tiles.tile(g, "rsquare", x, y, NOW_TILE, Tiles.Look.AFFORDABLE, 1, e.pointsChapter.displayIcon(), false);
                g.pose().pushPose();
                g.pose().translate(0, 0, OVERLAY_Z);
                String pts = String.valueOf(e.points);
                Ui.textRight(g, font, pts, x + NOW_TILE - 3, y + NOW_TILE - 10, Tiles.GOLD);
                g.pose().popPose();
            } else {
                Tiles.tile(g, e.node.shape, x, y, NOW_TILE, ClientProgress.look(e.key), Motion.fill(e.key, ClientProgress.fraction(e.key)), ClientProgress.iconFor(e.node), false);
                if (Pins.has(e.key)) {
                    g.pose().pushPose();
                    g.pose().translate(0, 0, OVERLAY_Z);
                    g.fill(x + 3, y + 3, x + 7, y + 7, Ui.MARK);
                    g.pose().popPose();
                }
            }
            flash(g, e.key, x, y, NOW_TILE, now);
        }
        g.disableScissor();
        int gridH = bottom - top;
        if (rows * rowH > gridH) {
            // a track under the thumb as well: a bare 3px thumb on the screen's edge was not read as a scrollbar, and
            // nothing said the grid went on below (the player, 2026-10-19)
            Ui.surface(g, nowRight() + 4, top, 5, gridH, 3, Ui.OVERLAY_1);
            int barH = Math.max(12, gridH * gridH / (rows * rowH));
            int barY = top + (gridH - barH) * nowScroll / Math.max(1, rows * rowH - gridH);
            Ui.surface(g, nowRight() + 4, barY, 5, barH, 3, Ui.SCROLLBAR);
            g.pose().pushPose();
            g.pose().translate(0, 0, OVERLAY_Z);
            g.fillGradient(left - 4, bottom - 24, nowRight() + 6, bottom, 0x00000000, Ui.GROUND);
            g.pose().popPose();
        }
        renderLog(g, m, mouseX, mouseY);
    }

    /**
     * What a quest gives, at the right edge of its cell. FTB Quests' reward list shows the reward's own item beside the
     * title (the player compared the two lists, 2026-10-19); our cells only said what the quest is, never what it gives.
     * Two at most: a third would eat the title. A choice reward is one chest in the model, and its options are in the
     * detail sheet where they can be picked.
     */
    private void renderNowRewards(GuiGraphics g, NowEntry e, int right, int y) {
        List<ItemStack> rs = e.node == null ? List.of() : e.node.rewards;
        int n = Math.min(NOW_REWARDS, rs.size());
        for (int i = 0; i < n; i++) {
            ItemStack s = rs.get(i);
            int ix = right - (n - i) * 18;
            int iy = y + (NOW_TILE - 16) / 2;
            g.renderItem(s, ix, iy);
            g.renderItemDecorations(font, s, ix, iy);
        }
    }

    private int nowRewardsWidth(NowEntry e) {
        List<ItemStack> rs = e.node == null ? List.of() : e.node.rewards;
        int n = Math.min(NOW_REWARDS, rs.size());
        return n == 0 ? 0 : n * 18 + 4;
    }

    /** Title (white) and chapter (grey) beside a Now tile; a skill chapter shows its points to spend in gold. */
    private void renderNowText(GuiGraphics g, NowEntry e, int x, int y, int w) {
        Component title;
        Component sub;
        int subColor = Ui.MUTED;
        if (e.pointsChapter != null) {
            // the one cell for every skill chapter cannot show a single chapter's title
            title = e.allSkills ? Component.translatable("screen.kuronami_quest_tree.now.skill_points") : e.pointsChapter.title;
            sub = pointsLabel(e.points);
            subColor = Tiles.GOLD;
        } else {
            title = e.node.title;
            ClientModel.Chapter c = model().chapterOf.get(e.key);
            sub = c == null ? Component.empty() : c.title;
        }
        Ui.text(g, font, trim(plain(title), w), x, y + 5, Ui.TEXT);
        Ui.text(g, font, trimChapter(plain(sub), w), x, y + 18, subColor);
    }

    /**
     * A title or chapter as plain text: imported titles carry FTB Quests colour codes, and a grid of them read as
     * scattered colours rather than as a list (the player, comparing our list with FTB Quests', 2026-10-19). The colour
     * still shows on the map's chapter title, where one title at a time is read.
     */
    private static String plain(Component c) {
        return net.minecraft.ChatFormatting.stripFormatting(c.getString());
    }

    /** "%s points", but "%s point" in the singular: "1 points" read wrong (review 2026-10-16). */
    private static Component pointsLabel(int n) {
        return Component.translatable(n == 1 ? "screen.kuronami_quest_tree.points_one" : "screen.kuronami_quest_tree.points", n);
    }

    /** A chapter name in a narrow cell: "Chapter 1: The Beginning" reads as "The Beginning" rather than "Chapter 1: T...". */
    private String trimChapter(String s, int w) {
        if (font.width(s) <= w) {
            return s;
        }
        int colon = s.indexOf(": ");
        return trim(colon >= 0 ? s.substring(colon + 2) : s, w);
    }

    /** Rows the 最新 log holds: its own column, not the strip it used to share with the grid. */
    private int logRows() {
        return Math.max(3, (height - 60) / 16);
    }

    /**
     * The 最新 log in the column at the right of Now: one line per event, tight rows, the time in its own dim column at
     * the left and the event in its own colour after it. On the strip under the grid the time and the event were drawn
     * in the same grey and read alike (the player, 2026-10-19). A completion the claim follows straight away collapses
     * into the claim line: with automatic claiming, one quest filled two rows with the same name.
     */
    private void renderLog(GuiGraphics g, ClientModel m, int mouseX, int mouseY) {
        PlayerProgress p = ClientProgress.progress();
        List<String> recent = new ArrayList<>(p.recent);
        int x = nowLogLeft();
        int right = nowLogRight();
        int top = 44;
        Ui.text(g, font, Component.translatable("screen.kuronami_quest_tree.recent"), x, top - 14, Ui.DIM);
        g.fill(x - 6, top - 16, x - 5, height - 10, Ui.HAIRLINE);
        int ry = top + 6;
        int shown = 0;
        long now = System.currentTimeMillis();
        for (int i = 0; i < recent.size() && shown < logRows(); i++) {
            String[] parts = recent.get(i).split("\\|");
            if (parts.length < 3) {
                continue;
            }
            if (parts[0].equals("complete") && i > 0 && isClaimOf(recent.get(i - 1), parts[1])) {
                continue;
            }
            // the same event over and over (a skill levelling in bursts) filled the whole column with one line: collapse
            // a run of identical rows into one with a count, the way a log repeats itself (2026-10-19)
            int same = 1;
            while (i + same < recent.size() && sameRow(recent.get(i + same), parts)) {
                same++;
            }
            ClientModel.Node n = m.nodesByKey.get(parts[1]);
            ClientModel.Chapter c = n != null ? m.chapterOf.get(parts[1])
                    : chapterById(m, parts[1].endsWith("/") ? parts[1].substring(0, parts[1].length() - 1) : "");
            Component name = n != null ? n.title : c != null ? c.title : Component.literal(parts[1]);
            Component verb = Component.translatable("event.kuronami_quest_tree." + parts[0]);
            int vw = font.width(verb);
            String count = same > 1 ? "\u00d7" + same : "";
            int cw = count.isEmpty() ? 0 : font.width(count) + 5;
            String nameS = trim(name.getString(), right - x - 44 - vw - cw);
            boolean hover = mouseX >= x - 4 && mouseX < right && mouseY >= ry - 2 && mouseY < ry + 14;
            if (hover) {
                Ui.surface(g, x - 4, ry - 2, right - x + 4, 16, Ui.R2, Ui.OVERLAY_1);
            }
            g.pose().pushPose();
            g.pose().translate(0, 0, OVERLAY_Z);
            Ui.textRight(g, font, ago(now - parseLong(parts[2])), x + 26, ry + 4, Ui.DIM);
            Ui.text(g, font, verb, x + 34, ry + 4, verbColour(parts[0]));
            Ui.text(g, font, nameS, x + 40 + vw, ry + 4, Ui.TEXT);
            if (!count.isEmpty()) {
                Ui.text(g, font, count, x + 40 + vw + font.width(nameS) + 5, ry + 4, Ui.DIM);
            }
            g.pose().popPose();
            ry += 16;
            shown++;
            i += same - 1;
        }
        if (sheetT > 0) {
            // the details sheet opens over this column: hide the log under it, or two sets of rows read through each
            // other on the translucent sheet (the detail shot, 2026-10-19)
            g.fill(sheetLeft(), 0, width, height, Ui.GROUND);
        }
    }

    /** True when a later row is the same event for the same quest. */
    private static boolean sameRow(String row, String[] first) {
        String[] parts = row.split("\\|");
        return parts.length >= 3 && parts[0].equals(first[0]) && parts[1].equals(first[1]);
    }

    /** True when {@code newer} is the claim of the same quest as {@code key}. */
    private static boolean isClaimOf(String newer, String key) {
        String[] parts = newer.split("\\|");
        return parts.length >= 3 && parts[0].equals("claim") && parts[1].equals(key);
    }

    /** The colour of an event's own word in 最新: what happened, told apart at a glance from the grey time beside it. */
    private static int verbColour(String kind) {
        return switch (kind) {
            case "claim" -> Tiles.GOLD;
            case "complete" -> Tiles.GREEN_TEXT;
            case "submit" -> Tiles.BLUE;
            default -> Ui.MUTED;
        };
    }

    private static ClientModel.Chapter chapterById(ClientModel m, String id) {
        for (ClientModel.Chapter c : m.chapters) {
            if (c.id.equals(id)) {
                return c;
            }
        }
        return null;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String ago(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 60) {
            return Component.translatable("screen.kuronami_quest_tree.ago_s", s).getString();
        }
        if (s < 3600) {
            return Component.translatable("screen.kuronami_quest_tree.ago_m", s / 60).getString();
        }
        if (s < 86400) {
            return Component.translatable("screen.kuronami_quest_tree.ago_h", s / 3600).getString();
        }
        return Component.translatable("screen.kuronami_quest_tree.ago_d", s / 86400).getString();
    }

    // ------------------------------------------------------------------ chapter map

    private float[] view(ClientModel.Chapter ch) {
        return VIEWS.computeIfAbsent(ch.id, k -> fitView(ch));
    }

    private float[] fitView(ClientModel.Chapter ch) {
        double[] b = bounds(ch);
        // a margin on the left: a pointer that overshoots a tile at the very edge would open the chapter list over it
        float availW = width - RAIL - 24 - FIT_LEFT;
        float availH = height - 40;
        float fit = (float) Math.min(availW / (b[2] - b[0] + 2 * UNIT), availH / (b[3] - b[1] + 2 * UNIT));
        // never smaller than FTB Quests' default (a big book opens at the size its author laid it out, centred, as in FTB
        // Quests; shrinking ATM10's 127-quest chapter to fit made every tile tiny); small chapters still grow to fill
        float z = Mth.clamp(fit, FTB_SCALE, 1.5f);
        float cx = (float) (b[0] + b[2]) / 2f;
        float cy = (float) (b[1] + b[3]) / 2f;
        return new float[]{RAIL + 12 + FIT_LEFT + availW / 2f - cx * z, 30 + availH / 2f - cy * z, z};
    }

    private static double[] bounds(ClientModel.Chapter ch) {
        double minX = 0;
        double minY = 0;
        double maxX = 0;
        double maxY = 0;
        boolean first = true;
        for (ClientModel.Node n : ch.nodes.values()) {
            double x = n.x * UNIT;
            double y = n.y * UNIT;
            if (first) {
                minX = maxX = x;
                minY = maxY = y;
                first = false;
            }
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
        return new double[]{minX, minY, maxX, maxY};
    }

    /** Lines, all opaque and a step above the scrim, so the tree's shape reads even where everything is closed. */
    /**
     * Lines (2026-10-16 rebuild, the player picked proposal C of lines_v1.html): one width for every state, the state in
     * the colour only, and one moving part: a solid triangular arrowhead (an open chevron read as a forked twig). Every line carries it at its middle, so direction is
     * always readable; on the node under the mouse (or the selected one) its lines brighten and the arrowheads slide
     * towards the later node; when a way opens, the colour runs in behind the arrowhead.
     */
    private static int LINE_WALKED = Tiles.GREEN;
    static int LINE_OPEN = 0xFF9C9CA3;
    static int LINE_CLOSED = 0xFF797E89;
    /** The closed line on a node under the mouse: brighter than {@link #LINE_OPEN}, which is brighter than closed. */
    static int LINE_CLOSED_ON = 0xFFA9AFB9;
    /**
     * One width for every state (proposal C), one step up from the 2 units of the 2026-10-16 rebuild: FTB Quests' line
     * on ATM10 measured 6-8 px against our 4-5 px at the same zoom (the two screenshots the player compared, 2026-10-19).
     * The map is drawn in map units, so the width grows with the zoom along with the tiles.
     */
    private static final float LINE_W = 3;
    private static final float LINE_W_ON = 4.5f;
    /** Arrowhead size (map units) and how long one slide along a highlighted line takes. */
    private static final float ARROW = 7;
    private static final float ARROW_ON = 8;
    private static final float SLIDE_MS = 1400;
    /** How far an arrowhead stays from the edge of a tile it stands between, in map units. */
    private static final float ARROW_PAD = 1.5f;
    /** Zoomed out no arrowhead is drawn, so no free stretch is measured either. */
    private static final float[] NO_RUNS = new float[0];

    /** Colours worked out from the palette, refreshed when it is read again. */
    static void paletteChanged() {
        SCRIM = Ui.GROUND;
        FADE = 0xCC000000 | (Ui.GROUND & 0xFFFFFF);
        SINK = 0xC0000000 | (Ui.GROUND & 0xFFFFFF);
        LINE_WALKED = Tiles.GREEN;
    }

    private static int lineColor(State parent, State child) {
        boolean parentDone = parent == State.DONE || parent == State.CLAIM;
        if (parentDone && (child == State.DONE || child == State.CLAIM)) {
            return LINE_WALKED;
        }
        if (parentDone && child != State.EXCLUDED && child != State.LOCKED) {
            return LINE_OPEN;
        }
        return LINE_CLOSED;
    }

    /** Pixel size of each background picture, read once from its file (null = missing or unreadable). */
    private static final Map<ResourceLocation, int[]> TEXTURE_SIZES = new HashMap<>();

    private static int[] textureSize(ResourceLocation id) {
        return TEXTURE_SIZES.computeIfAbsent(id, k -> {
            var res = net.minecraft.client.Minecraft.getInstance().getResourceManager().getResource(k);
            if (res.isEmpty()) {
                com.kuronami.questtree.Constants.LOG.warn("Chapter background {} does not exist", k);
                return null;
            }
            try (var in = res.get().open(); var img = com.mojang.blaze3d.platform.NativeImage.read(in)) {
                return new int[]{img.getWidth(), img.getHeight()};
            } catch (java.io.IOException ex) {
                com.kuronami.questtree.Constants.LOG.warn("Could not read chapter background {}: {}", k, ex.toString());
                return null;
            }
        });
    }

    /**
     * The chapter's background pictures, bottom first, under everything on the map. Each moves and zooms with the map
     * by its own share ({@link ClientModel.BackgroundLayer#speed()}), so layers at different speeds read as depth.
     */
    private void renderBackground(GuiGraphics g, ClientModel.Chapter ch, float[] v) {
        if (ch.background.isEmpty()) {
            return;
        }
        RenderSystem.enableBlend();
        for (ClientModel.BackgroundLayer l : ch.background) {
            int[] size = textureSize(l.texture());
            if (size == null || size[0] <= 0 || size[1] <= 0) {
                continue;
            }
            g.setColor(1, 1, 1, l.alpha());
            float zoom = 1 + (v[2] - 1) * l.speed();
            if (!l.tile()) {
                float cover = Math.max(width / (float) size[0], height / (float) size[1]) * zoom;
                float w = size[0] * cover;
                float h = size[1] * cover;
                float x = (width - w) / 2f + (v[0] - width / 2f) * l.speed() * 0.1f;
                float y = (height - h) / 2f + (v[1] - height / 2f) * l.speed() * 0.1f;
                blitScaled(g, l.texture(), x, y, w, h, size);
            } else {
                float tw = (l.size() > 0 ? l.size() : size[0]) * zoom;
                float th = tw * size[1] / size[0];
                float startX = floorMod(v[0] * l.speed(), tw) - tw;
                float startY = floorMod(v[1] * l.speed(), th) - th;
                for (float x = startX; x < width; x += tw) {
                    for (float y = startY; y < height; y += th) {
                        blitScaled(g, l.texture(), x, y, tw, th, size);
                    }
                }
            }
        }
        g.setColor(1, 1, 1, 1);
    }

    private static float floorMod(float a, float m) {
        float r = a % m;
        return r < 0 ? r + m : r;
    }

    private static void blitScaled(GuiGraphics g, ResourceLocation tex, float x, float y, float w, float h, int[] size) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(w / size[0], h / size[1], 1);
        g.blit(tex, 0, 0, 0, 0, size[0], size[1], size[0], size[1]);
        g.pose().popPose();
    }

    private void renderChapter(GuiGraphics g, ClientModel.Chapter ch, int mouseX, int mouseY) {
        float[] v = view(ch);
        stepPan(v);
        renderBackground(g, ch, v);
        boolean far = v[2] < FAR;
        long now = Util.getMillis();
        g.pose().pushPose();
        g.pose().translate(v[0], v[1], 0);
        g.pose().scale(v[2], v[2], 1);
        // decorations behind everything (FTB Quests chapter images)
        for (ClientModel.Decoration d : ch.decorations) {
            if (!decorationVisible(d)) {
                continue;
            }
            int dw = (int) (d.w * UNIT);
            int dh = (int) (d.h * UNIT);
            g.pose().pushPose();
            g.pose().translate(d.x * UNIT, d.y * UNIT, 0);
            if (d.rotation != 0) {
                g.pose().mulPose(Axis.ZP.rotationDegrees((float) d.rotation));
            }
            RenderSystem.enableBlend();
            g.setColor(((d.color >> 16) & 255) / 255f, ((d.color >> 8) & 255) / 255f, (d.color & 255) / 255f, d.alpha);
            g.blit(d.image, -dw / 2, -dh / 2, dw, dh, 0, 0, 1, 1, 1, 1);
            g.setColor(1, 1, 1, 1);
            g.pose().popPose();
        }
        // lines; authors hide some to keep busy maps readable (chapter default, or per node in either direction)
        ClientModel cm = model();
        String focus = hoveredNode != null ? hoveredNode : selected;
        Map<String, Integer> depth = depths(ch);
        // the tiles hide whatever passes behind them, so the lines know where they are actually visible
        float[] occ = far ? NO_RUNS : occluderBoxes(ch, cm);
        for (ClientModel.Node n : ch.nodes.values()) {
            if (ch.hideLines || n.hideLinesToRequired || !ClientProgress.visible(n.key)) {
                continue;
            }
            State cs = ClientProgress.state(n.key);
            for (String r : n.requireKeys) {
                ClientModel.Node p = cm.nodesByKey.get(r);
                if (p == null || cm.chapterOf.get(r) != ch || p.hideLinesToDependents || !ClientProgress.visible(p.key)) {
                    continue;
                }
                if (p.requireKeys.contains(n.key) && !p.hideLinesToRequired && !n.hideLinesToDependents) {
                    // a two-way link (skill trees from Pufferfish's Skills, whose connections have no direction): draw it
                    // once, from the side nearer the tree's start, so its arrows run outward and not both ways
                    int dp = depth.getOrDefault(p.key, Integer.MAX_VALUE);
                    int dn = depth.getOrDefault(n.key, Integer.MAX_VALUE);
                    if (dp > dn || (dp == dn && p.key.compareTo(n.key) > 0)) {
                        continue;
                    }
                }
                int lc = lineColor(ClientProgress.state(p.key), cs);
                boolean on = focus != null && (n.key.equals(focus) || p.key.equals(focus));
                // the way just opened: colour runs in from the earlier node with the arrow at its head (300ms)
                float grow = Motion.progress(Motion.UNLOCKED, p.key, Motion.LINE_GROW);
                if (ELBOW_LINES) {
                    elbowLine(g, p, n, on ? brighter(lc) : lc, on, grow >= 0 ? grow : -1, far, ch.skills);
                } else {
                    arrowLine(g, p, n, on ? brighter(lc) : lc, on, grow >= 0 ? grow : -1, far, now, occ);
                }
            }
        }
        // nodes
        String hovered = null;
        for (ClientModel.Node n : ch.nodes.values()) {
            if (!ClientProgress.visible(n.key)) {
                continue;
            }
            int s = (int) Math.round(NODE * n.size);
            int x = (int) Math.round(n.x * UNIT) - s / 2 + shake(n.key, now);
            int y = (int) Math.round(n.y * UNIT) - s / 2;
            String shape = Tiles.shapeOf(n.shape);
            // the node under the mouse grows a little (120ms), so the map answers the pointer
            int grow = Math.round(s * 0.12f * ease(hoverGrow.getOrDefault(n.key, 0f)) / 2) * 2;
            if (n.key.equals(selected)) {
                // the selection ring hugs the tile at its current size, so the gap stays even while it grows and shrinks
                int rs = Tiles.drawnSize(shape, s + grow);
                int ro = (rs - s) / 2;
                Tiles.ring(g, shape, x - ro - 3, y - ro - 3, rs + 6, rs + 6, Ui.SELECTION);
            }
            if (n.optional && !far) {
                // optional quests: a broken outline, so they read as "extra" next to the required path
                dashedOutline(g, x - 2, y - 2, s + 4, s + 4, Ui.OPTIONAL_OUTLINE);
            }
            Tiles.tile(g, shape, x - grow / 2, y - grow / 2, s + grow, ClientProgress.look(n.key), Motion.fill(n.key, ClientProgress.fraction(n.key)),
                    ClientProgress.iconFor(n), false);
            flash(g, n.key, x, y, s, now);
            resultMotion(g, n.key, shape, x, y, s);
            if (path().nodes().size() > 1 && pathSet.contains(n.key) && !n.key.equals(selected)) {
                // the unfinished requirements on the way to the selected node
                g.pose().pushPose();
                g.pose().translate(0, 0, OVERLAY_Z + 15);
                dashedOutline(g, x - 3, y - 3, s + 6, s + 6, Ui.SELECTION);
                g.pose().popPose();
            }
            if (searching() && !matchSet.contains(n.key)) {
                g.pose().pushPose();
                g.pose().translate(0, 0, OVERLAY_Z + 20);
                Tiles.shape(g, shape, x - 1, y - 1, s + 2, s + 2, SINK);
                g.pose().popPose();
            }
            double sx = v[0] + n.x * UNIT * v[2];
            double sy = v[1] + n.y * UNIT * v[2];
            if (Math.abs(mouseX - sx) < s * v[2] / 2 && Math.abs(mouseY - sy) < s * v[2] / 2 && mouseX > railRight()
                    && mouseX < (sheetT > 0 ? sheetLeft() : width)) {
                hovered = n.key;
            }
        }
        // quest links: the linked quest's tile, with an arrow in the corner (click goes to its chapter)
        for (ClientModel.Link l : ch.links) {
            ClientModel.Node target = cm.nodesByKey.get(l.node);
            if (target == null || !ClientProgress.visible(target.key)) {
                continue;
            }
            int s = (int) Math.round(NODE * l.size);
            int x = (int) Math.round(l.x * UNIT) - s / 2;
            int y = (int) Math.round(l.y * UNIT) - s / 2;
            String shape = Tiles.shapeOf(l.shape.isEmpty() ? target.shape : l.shape);
            Tiles.tile(g, shape, x, y, s, ClientProgress.look(target.key), Motion.fill(target.key, ClientProgress.fraction(target.key)), ClientProgress.iconFor(target), false);
            if (!far) {
                g.pose().pushPose();
                g.pose().translate(0, 0, OVERLAY_Z);
                Tiles.icon(g, "link", x + s - 7, y + 1, 6, Ui.MARK);
                g.pose().popPose();
            }
            double sx = v[0] + l.x * UNIT * v[2];
            double sy = v[1] + l.y * UNIT * v[2];
            if (Math.abs(mouseX - sx) < s * v[2] / 2 && Math.abs(mouseY - sy) < s * v[2] / 2 && mouseX > railRight()
                    && mouseX < (sheetT > 0 ? sheetLeft() : width)) {
                hovered = target.key;
            }
        }
        g.pose().popPose();
        hoveredNode = dragging ? null : hovered;
        if (hoveredNode != null) {
            hoverGrow.putIfAbsent(hoveredNode, 0f);
        }
        List<Component> decoTip = hovered == null && !dragging ? decorationAt(ch, v, mouseX, mouseY, true) : null;

        // everything below sits above the map's item icons
        g.pose().pushPose();
        g.pose().translate(0, 0, OVERLAY_Z);
        // a band behind the header: the title never sits directly on map tiles (fades out by y=40)
        g.fillGradient(railRight(), 0, sheetT > 0 ? sheetLeft() : width, 40, FADE, FADE & 0xFFFFFF);
        // the header moves aside with the chapter list when it opens, instead of showing through it
        int hx0 = railRight() + Ui.S2;
        // the title at the font's own size: drawn twice as large it crowded big maps (the player, ATM10 2026-10-16)
        Ui.text(g, font, ch.title, hx0, 11, Ui.TEXT);
        if (ch.skills) {
            renderSkillHeader(g, ch, hx0, 23);
        } else {
            int[] cnt = chapterCounts.getOrDefault(ch.id, new int[]{0, ch.nodes.size(), 0});
            String count = cnt[0] + "/" + cnt[1];
            Ui.text(g, font, count, hx0, 23, Ui.MUTED);
            if (cnt[2] > 0) {
                // more is hidden (Steam's "n hidden achievements remaining"): the count, never what it is. The lock
                // icon is used for "not open yet" elsewhere, so a word is unambiguous.
                int hx = hx0 + font.width(count) + Ui.S1;
                Ui.text(g, font, Component.translatable("screen.kuronami_quest_tree.hidden", cnt[2]), hx, 23, Ui.DIM);
            }
        }
        renderMinimap(g, ch, v);
        renderZoomButtons(g, ch, v, mouseX, mouseY);
        g.pose().popPose();
        if (hovered != null && !dragging && selected == null) {
            g.pose().pushPose();
            g.pose().translate(0, 0, OVERLAY_Z + 200);
            ClientModel.Node hn = model().nodesByKey.get(hovered);
            List<Component> tip = new ArrayList<>();
            tip.add(hn.title);
            if (hn.subtitle != null) {
                // the subtitle one step under the title, the same grey the details sheet uses
                tip.add(hn.subtitle);
            }
            Ui.tooltip(g, font, tip, mouseX, mouseY);
            g.pose().popPose();
        } else if (decoTip != null && !decoTip.isEmpty()) {
            g.pose().pushPose();
            g.pose().translate(0, 0, OVERLAY_Z + 200);
            Ui.tooltip(g, font, decoTip, mouseX, mouseY);
            g.pose().popPose();
        }
    }

    /** Level, experience bar and points to spend, under a skill chapter's title. */
    private void renderSkillHeader(GuiGraphics g, ClientModel.Chapter ch, int x, int y) {
        PlayerProgress p = ClientProgress.progress();
        int level = p.level.getOrDefault(ch.id, 0);
        String lv = Component.translatable("screen.kuronami_quest_tree.level_short", level).getString();
        Ui.text(g, font, lv, x, y, Ui.TEXT);
        int bx = x + font.width(lv) + Ui.S1;
        var rules = ClientProgress.rules();
        var mc = rules == null ? null : rules.model().chapters.get(ch.id);
        double need = mc == null ? 0 : rules.experienceForLevel(mc, level);
        double frac = need <= 0 ? 0 : Mth.clamp(p.xp.getOrDefault(ch.id, 0.0) / need, 0, 1);
        Ui.surface(g, bx, y + 2, 60, 4, 2, Ui.OVERLAY_2);
        Ui.surface(g, bx, y + 2, frac <= 0 ? 0 : Math.max(1, (int) (60 * frac)), 4, 2, Tiles.GREEN_TEXT);
        int pts = ClientProgress.points(ch.id);
        if (pts > 0) {
            Component t = pointsLabel(pts);
            Ui.text(g, font, t, bx + 68, y, Tiles.GOLD);
        }
    }

    /** Right edge of the rail as currently drawn (it opens to railOpenW on hover). */
    private int railRight() {
        return railWidth() + 4;
    }

    /**
     * The rail's drawn width, following the hover slide. Drawing and hit tests share this, so the pin slot at a row's
     * right end is where it looks, also mid-slide.
     */
    private int railWidth() {
        return (int) Mth.lerp(ease(railT), RAIL, railOpenW);
    }

    /** Left edge of a row's pin slot (0 when {@code null} is passed, i.e. on rows without a chapter). */
    private int pinSlotLeft(int w) {
        return w - Ui.S1 - RAIL_PIN_W;
    }

    private int sheetWidth() {
        // wider than before (ATM10's texts need the room), but never narrower than the old width on small screens
        // (GUI scale 4 on 1080p is 480 wide), where the map still needs most of the room
        int before = Math.min(200, Math.max(150, (int) (width * 0.34f)));
        return Math.max(before, Math.min(280, (int) (width * 0.4f)));
    }

    private int sheetLeft() {
        return width - (int) (sheetWidth() * ease(sheetT));
    }

    // ------------------------------------------------------------------ details sheet

    private void renderSheet(GuiGraphics g, ClientModel m, int mouseX, int mouseY, float dt) {
        int sw = sheetWidth();
        int x0 = sheetLeft();
        // a panel inset from the screen's edges, so the sheet floats over the map instead of walling off its side
        int pw = Math.min(sw, width - Ui.S1 - x0);
        if (pw <= 0) {
            return;
        }
        Ui.floating(g, x0, Ui.S1, pw, height - 2 * Ui.S1, Ui.PANEL_TRANSLUCENT);
        ClientModel.Node n = selected == null ? null : m.nodesByKey.get(selected);
        if (n == null) {
            return;
        }
        if (!n.key.equals(sheetScrollFor)) {
            // a newly selected quest starts at its top (the content's height is only known after it is drawn)
            sheetScrollFor = n.key;
            sheetScroll = 0;
            sheetScrollTarget = 0;
        }
        ClientModel.Chapter ch = m.chapterOf.get(n.key);
        State state = ClientProgress.state(n.key);
        boolean working = state == State.OPEN || state == State.PROGRESS;
        int x = x0 + Ui.S3;
        int w = pw - 2 * Ui.S3;
        int y = 14;
        Tiles.icon(g, "cross", x0 + pw - 15, 9, 7, Ui.MUTED);
        buttons.add(new Button(x0 + pw - 20, 4, 18, 18, () -> selected = null));
        Ui.text(g, font, trim(ch.title.getString(), w - 16), x, y, Ui.DIM);
        y += 12;
        // the title at the font's own size, like the chapter header (drawn twice as large it crowded the sheet)
        for (FormattedCharSequence line : font.split(n.title, w)) {
            Ui.text(g, font, line, x, y, Ui.TEXT);
            y += 10;
        }
        if (n.subtitle != null) {
            // the pack's subtitle, one step under the title, two lines at most
            List<FormattedCharSequence> sub = font.split(n.subtitle, w);
            for (int i = 0; i < sub.size() && i < 2; i++) {
                Ui.text(g, font, sub.get(i), x, y, Ui.MUTED);
                y += 10;
            }
        }
        // the content scrolls between the header and the fixed action row; the scissor clips it to the sheet
        int contentTop = Math.max(y + 4, Ui.S1 + Ui.S2);
        int contentBottom = height - BTN - 2 * Ui.S2;
        sheetScrollTarget = Mth.clamp(sheetScrollTarget, 0, sheetContentMax);
        sheetScroll = Mth.clamp(sheetScroll + (sheetScrollTarget - sheetScroll) * Math.min(1, dt / 70f), 0, sheetContentMax);
        int scroll = Math.round(sheetScroll);
        y = contentTop - scroll;
        g.enableScissor(x0, contentTop, x0 + sw, contentBottom);
        // pack authors can keep a quest's details secret until it can be started, and its text until it is done
        boolean details = !(n.hideDetailsUntilStartable && state == State.LOCKED);
        boolean text = details && !(n.hideTextUntilComplete && state != State.DONE && state != State.CLAIM);
        for (String d : text ? n.description : List.<String>of()) {
            if (d.isBlank()) {
                // an empty source line: a paragraph break (font.split("") returns no lines, which dropped it)
                y += 6;
                continue;
            }
            for (FormattedCharSequence line : font.split(Component.literal(d), w)) {
                Ui.text(g, font, line, x, y, Ui.TEXT);
                y += 12;
            }
        }
        if (!n.requires.isEmpty() && state == State.LOCKED) {
            y += 8;
            Ui.text(g, font, Component.translatable("screen.kuronami_quest_tree.needs"), x, y, Ui.DIM);
            y += 12;
            for (String r : n.requireKeys) {
                ClientModel.Node p = m.nodesByKey.get(r);
                if (p == null) {
                    continue;
                }
                if (!ClientProgress.visible(r)) {
                    // a hidden requirement: counted, never named
                    Tiles.tile(g, "square", x, y, 16, State.LOCKED, 0, ItemStack.EMPTY, false);
                    g.pose().pushPose();
                    g.pose().translate(0, 0, OVERLAY_Z);
                    Ui.text(g, font, "???", x + 22, y + 4, Ui.DIM);
                    g.pose().popPose();
                    y += 20;
                    continue;
                }
                Tiles.tile(g, p.shape, x, y, 16, ClientProgress.look(p.key), ClientProgress.fraction(p.key), p.icon, false);
                g.pose().pushPose();
                g.pose().translate(0, 0, OVERLAY_Z);
                ClientModel.Chapter other = m.chapterOf.get(r);
                if (other != ch) {
                    // a requirement in another chapter: its chapter name above, and the row opens it
                    Ui.text(g, font, trim(p.title.getString(), w - 22), x + 22, y, Ui.TEXT);
                    Ui.text(g, font, trim(other.title.getString(), w - 22), x + 22, y + 9, Ui.DIM);
                    String key = r;
                    // only a row still inside the sheet may catch a click (a scrolled-away row must not)
                    if (y + 18 > contentTop && y < contentBottom) {
                        buttons.add(new Button(x, y, w, 18, () -> goTo(key)));
                    }
                } else {
                    Ui.text(g, font, trim(p.title.getString(), w - 22), x + 22, y + 4, Ui.TEXT);
                }
                g.pose().popPose();
                y += 20;
            }
        }
        if (!n.tasks.isEmpty() && details) {
            y += 8;
            Ui.text(g, font, Component.translatable("screen.kuronami_quest_tree.tasks"), x, y, Ui.DIM);
            y += 12;
            PlayerProgress p = ClientProgress.progress();
            for (int i = 0; i < n.tasks.size(); i++) {
                ClientModel.Task t = n.tasks.get(i);
                long have = Math.min(p.task(n.key, i), t.count);
                boolean met = have >= t.count || state == State.DONE || state == State.CLAIM;
                Ui.divider(g, x, y - 2, w);
                if (!t.item.isEmpty() && lastMouseX >= x && lastMouseX < x + 16 && lastMouseY >= y + 2 && lastMouseY < y + 18) {
                    hoverStack = t.item;
                    RecipeViewer viewer = RecipeViewer.Holder.get();
                    if (viewer != null) {
                        ItemStack it = t.item;
                        buttons.add(new Button(x, y + 2, 16, 16, () -> viewer.showRecipes(it)));
                    }
                }
                if (!t.icon().isEmpty()) {
                    g.renderItem(t.icon(), x, y + 2);
                } else if (t.type.equals("checkmark")) {
                    Tiles.icon(g, "tick", x + 3, y + 5, 11, met ? Tiles.GREEN_TEXT : Ui.MUTED);
                }
                if (t.optional) {
                    // optional tasks: the same broken outline as optional quests on the map
                    dashedOutline(g, x - 1, y + 1, 18, 18, Ui.OPTIONAL_OUTLINE);
                }
                g.pose().pushPose();
                g.pose().translate(0, 0, OVERLAY_Z);
                String cnt = (met ? t.count : have) + "/" + t.count;
                int right = x + w;
                if (!met && working && (t.consume || t.type.equals("checkmark")) && y + 20 > contentTop && y < contentBottom) {
                    Component label = Component.translatable(t.type.equals("checkmark") ? "screen.kuronami_quest_tree.check" : "screen.kuronami_quest_tree.submit");
                    int bw = font.width(label) + 2 * Ui.S2;
                    int index = i;
                    button(g, label, right - bw, y + 1, bw, 0, true, mouseX, mouseY, () -> {
                        ClientProgress.send(t.type.equals("checkmark") ? "check" : "submit", n.key, index);
                        ProgressFeedback.click();
                    });
                    right -= bw + Ui.S1;
                }
                float bump = Motion.progress(Motion.SUBMITTED, n.key + "#" + i, 200);
                if (bump >= 0) {
                    // hand-in: the count turns green and bumps (200ms)
                    float k = 1 + 0.35f * (1 - bump);
                    g.pose().pushPose();
                    g.pose().translate(right - font.width(cnt) / 2f, y + 10, 0);
                    g.pose().scale(k, k, 1);
                    Ui.text(g, font, cnt, -font.width(cnt) / 2, -4, Tiles.GREEN_TEXT);
                    g.pose().popPose();
                } else {
                    Ui.textRight(g, font, cnt, right, y + 6, met ? Tiles.GREEN_TEXT : Ui.DIM);
                }
                Ui.text(g, font, trim(ProgressFeedback.taskLine(t).getString(), right - font.width(cnt) - x - 26), x + 20, y + 6,
                        met || t.optional ? Ui.DIM : Ui.TEXT);
                g.pose().popPose();
                y += 20;
            }
        }
        if ((!n.rewards.isEmpty() || !n.rewardLines.isEmpty()) && details) {
            y += 8;
            Ui.text(g, font, Component.translatable("screen.kuronami_quest_tree.rewards"), x, y, Ui.DIM);
            y += 12;
            int rx = x;
            for (ItemStack r : n.rewards) {
                slot(g, r, rx, y, false);
                rx += 28;
                if (rx + 22 > x + w) {
                    break;
                }
            }
            if (!n.rewards.isEmpty()) {
                y += 30;
            }
            for (String line : n.rewardLines) {
                Ui.text(g, font, line, x, y, Ui.TEXT);
                y += 10;
            }
        }
        if (!n.choice.isEmpty() && details) {
            y += 8;
            Ui.text(g, font, Component.translatable("screen.kuronami_quest_tree.choose"), x, y, Ui.DIM);
            y += 12;
            int rx = x;
            for (int i = 0; i < n.choice.size(); i++) {
                int index = i;
                boolean pickable = state == State.CLAIM;
                slot(g, n.choice.get(i), rx, y, index == choicePick);
                if (pickable && y + 22 > contentTop && y < contentBottom) {
                    buttons.add(new Button(rx, y, 22, 22, () -> {
                        choicePick = index;
                        ProgressFeedback.click();
                    }));
                }
                rx += 28;
                if (rx + 22 > x + w) {
                    rx = x;
                    y += 28;
                }
            }
        }
        g.disableScissor();
        int contentEnd = y + scroll;
        sheetContentMax = Math.max(0, (contentEnd - contentTop) - (contentBottom - contentTop));
        boolean overflow = sheetContentMax > 0;
        if (overflow) {
            // the scrollbar on the sheet's right edge, in the chapter list's look (only while the content overflows)
            int trackH = contentBottom - contentTop;
            int thumb = Math.max(12, Math.round((float) trackH * trackH / (trackH + sheetContentMax)));
            int ty = contentTop + Math.round((trackH - thumb) * (sheetScroll / Math.max(1, sheetContentMax)));
            boolean over = mouseX >= x0 && mouseX < x0 + sw && mouseY >= contentTop && mouseY < contentBottom;
            Ui.surface(g, x0 + pw - Ui.S1 - 3, ty, 3, thumb, 2, over ? Ui.SCROLLBAR_HOVER : Ui.SCROLLBAR);
        }
        // the actions sit just under short content instead of pinned to the bottom; while the content overflows they
        // stay at the bottom, where the scrollbar ends
        int actionsY = height - BTN - Ui.S2;
        int actionsTop = overflow ? actionsY : Math.min(contentEnd + Ui.S3, actionsY);
        renderActions(g, n, ch, state, x, actionsTop, w, mouseX, mouseY);
    }

    private void renderActions(GuiGraphics g, ClientModel.Node n, ClientModel.Chapter ch, State state, int x, int y, int w, int mouseX, int mouseY) {
        int bx = x;
        if (ch.skills && n.tasks.isEmpty()) {
            if (state == State.CLAIM) {
                Component take = n.cost > 0 ? Component.translatable("screen.kuronami_quest_tree.unlock_cost", n.cost)
                        : Component.translatable("screen.kuronami_quest_tree.unlock");
                int bw = font.width(take) + 2 * Ui.S2;
                button(g, take, bx, y, bw, Ui.ACCENT, true, mouseX, mouseY, () -> {
                    ClientProgress.send("take", n.key, 0);
                    flashes.put(n.key, Util.getMillis());
                });
            } else {
                PathFinder.Path p = path();
                int points = ClientProgress.points(ch.id);
                if (p.nodes().size() > 1 && p.batchable() > 0) {
                    // the way there in one go (stops before an exclusive choice, which stays the player's pick)
                    Component all = Component.translatable("screen.kuronami_quest_tree.unlock_path", p.batchable(), p.batchCost());
                    int bw = font.width(all) + 2 * Ui.S2;
                    boolean afford = points >= p.batchCost();
                    List<String> batch = p.nodes().subList(0, p.batchable());
                    button(g, all, bx, y, bw, Ui.ACCENT, afford, mouseX, mouseY, () -> {
                        long t = Util.getMillis();
                        int i = 0;
                        for (String k : batch) {
                            ClientProgress.send("take", k, 0);
                            flashes.put(k, t + 110L * i++);
                        }
                    });
                    if (!afford) {
                        String need = p.batchCost() + "";
                        Ui.text(g, font, Component.translatable("screen.kuronami_quest_tree.cost", need), bx + bw + Ui.S2, y + (BTN - 8) / 2, Ui.DIM);
                    }
                } else if (state == State.OPEN && n.cost > 0) {
                    Ui.text(g, font, Component.translatable("screen.kuronami_quest_tree.cost", n.cost), bx, y + (BTN - 8) / 2, Ui.DIM);
                }
            }
            return;
        }
        if (state == State.CLAIM) {
            boolean ready = n.choice.isEmpty() || choicePick >= 0;
            Component claim = Component.translatable("screen.kuronami_quest_tree.claim");
            int bw = font.width(claim) + 2 * Ui.S2;
            button(g, claim, bx, y, bw, Tiles.GOLD, ready, mouseX, mouseY, () -> {
                ClientProgress.send("claim", n.key, n.choice.isEmpty() ? -1 : choicePick);
                flashes.put(n.key, Util.getMillis());
                choicePick = -1;
            });
            bx += bw + Ui.S2;
        }
        if (state != State.DONE && state != State.EXCLUDED) {
            // a pin mark instead of the word; gold while pinned, the name in a tooltip
            boolean pinned = Pins.has(n.key);
            boolean hover = mouseX >= bx && mouseX < bx + BTN && mouseY >= y && mouseY < y + BTN;
            Ui.surface(g, bx, y, BTN, BTN, Ui.R2, pinned || hover ? Ui.OVERLAY_2 : Ui.OVERLAY_1);
            Ui.outline(g, bx, y, BTN, BTN, Ui.R2, Ui.HAIRLINE);
            Tiles.icon(g, "pin", bx + (BTN - 12) / 2, y + (BTN - 12) / 2, 12, pinned ? Tiles.GOLD : Ui.MUTED);
            buttons.add(new Button(bx, y, BTN, BTN, () -> {
                Pins.toggle(n.key);
                ProgressFeedback.click();
                cachedFor = null;
            }));
            if (hover) {
                pendingTip = Component.translatable(pinned ? "screen.kuronami_quest_tree.pinned" : "screen.kuronami_quest_tree.pin");
            }
        }
    }

    private void slot(GuiGraphics g, ItemStack r, int x, int y, boolean picked) {
        if (lastMouseX >= x && lastMouseX < x + 22 && lastMouseY >= y && lastMouseY < y + 22) {
            hoverStack = r;
        }
        Ui.surface(g, x, y, 22, 22, Ui.R2, picked ? Ui.OVERLAY_3 : Ui.OVERLAY_1);
        if (picked) {
            Ui.outline(g, x, y, 22, 22, Ui.R2, Ui.SELECTION);
        }
        g.renderItem(r, x + 3, y + 3);
        g.renderItemDecorations(font, r, x + 3, y + 3);
    }

    /**
     * Flat button. {@code accent}: gold with dark text for taking a reward (claim), the accent with white text for
     * spending (unlock), 0 for the quiet ones.
     */
    private void button(GuiGraphics g, Component label, int x, int y, int w, int accent, boolean enabled, int mouseX, int mouseY, Runnable run) {
        int h = BTN;
        boolean primary = accent != 0;
        boolean hover = enabled && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        if (primary) {
            Ui.surface(g, x, y, w, h, Ui.R2, enabled ? (hover ? lerpColor(accent, 0xFFFFFFFF, 0.25f) : accent) : Ui.DISABLED);
        } else {
            Ui.surface(g, x, y, w, h, Ui.R2, enabled ? (hover ? Ui.OVERLAY_2 : Ui.OVERLAY_1) : Ui.DISABLED);
            Ui.outline(g, x, y, w, h, Ui.R2, Ui.HAIRLINE);
        }
        Ui.textCenter(g, font, label, x + w / 2, y + (h - 8) / 2, !enabled ? Ui.DIM : accent == Ui.ACCENT ? Ui.BRIGHT : primary ? Tiles.INK : Ui.TEXT);
        if (enabled) {
            buttons.add(new Button(x, y, w, h, run));
        }
    }

    /**
     * Claim feedback (MOTION_SOUND.md): a short press and a white flash on the tile, then the reward icons rise out of
     * it one after another and shrink away. Nothing loops; "claim all" staggers the tiles instead of repeating sounds.
     */
    private void flash(GuiGraphics g, String key, int x, int y, int s, long now) {
        Long at = flashes.get(key);
        if (at == null || now < at) {
            return;
        }
        long t = now - at;
        ClientModel.Node n = model().nodesByKey.get(key);
        List<ItemStack> icons = n == null ? List.of() : n.rewards.subList(0, Math.min(4, n.rewards.size()));
        long end = 380 + 70L * icons.size() + 620;
        if (t > end) {
            flashes.remove(key);
            cachedFor = null;
            return;
        }
        g.pose().pushPose();
        g.pose().translate(0, 0, OVERLAY_Z);
        if (t < 380) {
            // press 160ms, then a white flash that fades over 220ms
            float a = t < 160 ? 0.15f : 0.85f * (1 - (t - 160) / 220f);
            g.fill(x, y, x + s, y + s, Tiles.withAlpha(Ui.FLASH, a));
        }
        for (int i = 0; i < icons.size(); i++) {
            long ti = t - 160 - 70L * i;
            if (ti < 0 || ti > 620) {
                continue;
            }
            float p = ti / 620f;
            float ease = 1 - (1 - p) * (1 - p) * (1 - p);
            float k = Math.max(0.05f, 1 - p * p);
            g.pose().pushPose();
            g.pose().translate(x + s / 2f + (i - (icons.size() - 1) / 2f) * 10, y + s / 2f - ease * (s * 0.9f + 8), 50);
            g.pose().scale(k, k, 1);
            g.renderItem(icons.get(i), -8, -8);
            g.pose().popPose();
        }
        g.pose().popPose();
    }

    /** The unlock chain's light-up of newly opened nodes, and the stamp on nodes an exclusive pick closed. */
    private void resultMotion(GuiGraphics g, String key, String shape, int x, int y, int s) {
        float open = Motion.progress(Motion.OPENED, key, 260);
        float stamp = Motion.progress(Motion.STAMPED, key, 180);
        if (open < 0 && stamp < 0) {
            return;
        }
        g.pose().pushPose();
        g.pose().translate(0, 0, OVERLAY_Z + 10);
        if (open >= 0) {
            Tiles.shape(g, shape, x, y, s, s, Tiles.withAlpha(Ui.FLASH, 0.6f * (1 - open)));
        }
        if (stamp >= 0) {
            // the cross lands: from twice its size down to its place
            float c = s * 0.36f * (2 - stamp);
            Tiles.icon(g, "cross", x + (s - c) / 2f, y + (s - c) / 2f, c, Tiles.withAlpha(Ui.FLASH, 0.4f + 0.6f * stamp));
        }
        g.pose().popPose();
    }

    private int shake(String key, long now) {
        Long at = shakes.get(key);
        if (at == null) {
            return 0;
        }
        long t = now - at;
        if (t > 180) {
            shakes.remove(key);
            return 0;
        }
        return (int) Math.round(Math.sin(t / 180.0 * Math.PI * 4) * 2 * (1 - t / 180.0));
    }

    private void renderZoomButtons(GuiGraphics g, ClientModel.Chapter ch, float[] v, int mouseX, int mouseY) {
        int right = (sheetT > 0 ? sheetLeft() : width) - Ui.S2;
        String[] glyphs = {"center", "minus", "plus"};
        int y = height - BTN - Ui.S2;
        int x = right - 3 * BTN;
        // one control with three cells, rather than three buttons that happen to sit next to each other
        Ui.surface(g, x, y, 3 * BTN, BTN, Ui.R2, Ui.PANEL_TRANSLUCENT);
        Ui.outline(g, x, y, 3 * BTN, BTN, Ui.R2, Ui.HAIRLINE);
        for (int i = 0; i < 3; i++) {
            int cx = x + i * BTN;
            if (i > 0) {
                g.fill(cx, y + 7, cx + 1, y + BTN - 7, Ui.DIVIDER);
            }
            boolean hover = mouseX >= cx && mouseX < cx + BTN && mouseY >= y && mouseY < y + BTN;
            if (hover) {
                g.fill(cx, y + 3, cx + BTN, y + BTN - 3, Ui.OVERLAY_1);
            }
            Tiles.icon(g, glyphs[i], cx + (BTN - 12) / 2, y + (BTN - 12) / 2, 12, Ui.TEXT);
            int which = i;
            buttons.add(new Button(cx, y, BTN, BTN, () -> zoomButton(ch, v, which)));
        }
    }

    /** Minimap only when the chapter does not fit on screen at the current zoom. */
    private int[] minimapRect(ClientModel.Chapter ch, float[] v) {
        double[] b = bounds(ch);
        double cw = (b[2] - b[0] + 2 * UNIT) * v[2];
        double chh = (b[3] - b[1] + 2 * UNIT) * v[2];
        if (cw <= width - RAIL - 24 && chh <= height - 40) {
            return null;
        }
        int mw = 84;
        int mh = 54;
        int right = (sheetT > 0 ? sheetLeft() : width) - Ui.S2;
        return new int[]{right - mw, height - BTN - 16 - mh, mw, mh};
    }

    private void renderMinimap(GuiGraphics g, ClientModel.Chapter ch, float[] v) {
        int[] r = minimapRect(ch, v);
        // shown while the map moves (drag, zoom) and for a moment after, then gone: it covered large maps all the time
        if (r == null || !minimapShown()) {
            return;
        }
        if (ClientConfig.liquidGlass()) {
            GlassPanel.render(g, r[0], r[1], r[2], r[3]);
        } else {
            Ui.floating(g, r[0], r[1], r[2], r[3], Ui.PANEL_TRANSLUCENT);
        }
        double[] b = bounds(ch);
        double s = Math.min((r[2] - 8) / Math.max(1, b[2] - b[0]), (r[3] - 8) / Math.max(1, b[3] - b[1]));
        for (ClientModel.Node n : ch.nodes.values()) {
            if (!ClientProgress.visible(n.key)) {
                continue;
            }
            int px = r[0] + 4 + (int) ((n.x * UNIT - b[0]) * s);
            int py = r[1] + 4 + (int) ((n.y * UNIT - b[1]) * s);
            g.fill(px - 1, py - 1, px + 1, py + 1, minimapColor(ClientProgress.state(n.key)));
        }
        // the part of the chapter that is on screen
        double vx0 = (-v[0]) / v[2];
        double vy0 = (-v[1]) / v[2];
        double vx1 = vx0 + width / v[2];
        double vy1 = vy0 + height / v[2];
        int x0 = Mth.clamp(r[0] + 4 + (int) ((vx0 - b[0]) * s), r[0], r[0] + r[2]);
        int y0 = Mth.clamp(r[1] + 4 + (int) ((vy0 - b[1]) * s), r[1], r[1] + r[3]);
        int x1 = Mth.clamp(r[0] + 4 + (int) ((vx1 - b[0]) * s), r[0], r[0] + r[2]);
        int y1 = Mth.clamp(r[1] + 4 + (int) ((vy1 - b[1]) * s), r[1], r[1] + r[3]);
        g.renderOutline(x0, y0, Math.max(2, x1 - x0), Math.max(2, y1 - y0), Ui.MINIMAP_VIEW);
    }

    private static int minimapColor(State s) {
        return switch (s) {
            case DONE -> Tiles.GREEN;
            case CLAIM -> Tiles.GOLD;
            case OPEN, PROGRESS -> Ui.MINIMAP_OPEN;
            default -> Tiles.TILE_LOCKED;
        };
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        ClientModel m = model();
        if (searchBox != null && mx >= searchBox.getX() - Ui.S2 && mx < searchBox.getX() + searchBox.getWidth() + Ui.S2
                && my >= searchBox.getY() - EDIT_H && my < searchBox.getY() - EDIT_H + BTN) {
            setFocused(searchBox);
            searchBox.setFocused(true);
            return searchBox.mouseClicked(mx, my, button);
        }
        if (searchBox != null && searchBox.isFocused()) {
            searchBox.setFocused(false);
            setFocused(null);
        }
        if (m == null || button != 0) {
            return super.mouseClicked(mx, my, button);
        }
        for (int i = buttons.size() - 1; i >= 0; i--) {
            if (buttons.get(i).hit(mx, my)) {
                buttons.get(i).run.run();
                return true;
            }
        }
        int railW = railWidth();
        if (mx < railW + 4) {
            if (my < RAIL_LIST_TOP) {
                if (my >= 8) {
                    select(-1);
                }
            } else if (my < railListBottom()) {
                float rowY = RAIL_LIST_TOP - railScroll;
                for (RailRow row : railRows) {
                    float hgt = row.isHeader() ? HEADER_H : railPitch();
                    if (my >= rowY && my < rowY + hgt) {
                        // a header is a label, not a target
                        if (!row.isHeader()) {
                            // the pin slot toggles the pin; anywhere else on the row opens the chapter
                            if (railT > 0.6f && mx >= pinSlotLeft(railW)) {
                                ClientConfig.toggleRailPin(row.chapter().id);
                                ProgressFeedback.click();
                            } else {
                                select(m.chapters.indexOf(row.chapter()));
                            }
                        }
                        break;
                    }
                    rowY += hgt;
                }
            }
            return true;
        }
        if (sheetT > 0 && selected != null && mx >= sheetLeft()) {
            return true;
        }
        ClientModel.Chapter ch = chapter();
        if (ch == null) {
            return clickNow(m, mx, my);
        }
        float[] v = view(ch);
        int[] r = minimapShown() ? minimapRect(ch, v) : null;
        if (r != null && mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
            double[] b = bounds(ch);
            double s = Math.min((r[2] - 8) / Math.max(1, b[2] - b[0]), (r[3] - 8) / Math.max(1, b[3] - b[1]));
            double wx = (mx - r[0] - 4) / s + b[0];
            double wy = (my - r[1] - 4) / s + b[1];
            v[0] = (float) (width / 2.0 - wx * v[2]);
            v[1] = (float) (height / 2.0 - wy * v[2]);
            return true;
        }
        dragging = true;
        moved = false;
        dragStartX = mx;
        dragStartY = my;
        return true;
    }

    private boolean clickNow(ClientModel m, double mx, double my) {
        int left = nowLeft();
        int top = 40;
        int bottom = height - 34;
        if (mx < left || my < top || my >= bottom) {
            selected = null;
            return true;
        }
        int cols = nowCols();
        int cellW = nowCellWidth();
        int col = (int) ((mx - left) / (cellW + NOW_GAP));
        int row = (int) ((my - top + nowScroll) / (NOW_TILE + NOW_GAP));
        double inX = (mx - left) - col * (cellW + NOW_GAP);
        double inY = (my - top + nowScroll) - row * (NOW_TILE + NOW_GAP);
        int index = row * cols + col;
        if (col >= cols || inX >= cellW || inY >= NOW_TILE || index >= nowEntries.size()) {
            selected = null;
            return true;
        }
        NowEntry e = nowEntries.get(index);
        if (e.pointsChapter != null) {
            ProgressFeedback.click();
            select(m.chapters.indexOf(e.pointsChapter));
            return true;
        }
        if (ClientProgress.state(e.key) == State.CLAIM && e.node.choice.isEmpty()) {
            ClientProgress.send("claim", e.key, -1);
            flashes.put(e.key, Util.getMillis());
            if (e.key.equals(selected)) {
                selected = null;
            }
            return true;
        }
        if (!e.key.equals(selected)) {
            choicePick = -1;
        }
        selected = e.key;
        return true;
    }

    /** Dev tour only ({@link DevTour}): open on a chapter (-1 = Now). */
    void tourChapter(int index) {
        select(index);
    }

    /** Dev tour only: select the first node in the current view that has {@code state}. */
    void tourSelectFirst(State state) {
        ClientModel m = model();
        ClientModel.Chapter ch = chapter();
        Iterable<ClientModel.Node> nodes = ch != null ? ch.nodes.values()
                : nowEntries.stream().filter(e -> e.node != null).map(e -> e.node).toList();
        for (ClientModel.Node n : nodes) {
            if (ClientProgress.state(n.key) == state) {
                selected = n.key;
                if (ch != null) {
                    keepVisible(view(ch), n);
                }
                return;
            }
        }
    }

    /** Dev tour only: one zoom step, so the zoom buttons and the minimap (it shows while the map moves) are in a photo. */
    void tourZoom() {
        ClientModel.Chapter ch = chapter();
        if (ch != null) {
            zoomButton(ch, view(ch), 2);
        }
    }

    /** Dev tour only: put text in the search field, so what the search matches and what it sinks are in a photo. */
    void tourSearch(String text) {
        if (searchBox != null) {
            searchBox.setValue(text);
        }
    }

    /** Dev tour only: pin a couple of chapters, so the pinned block at the top of the list is in a photo. */
    void tourPin(int count) {
        ClientModel m = model();
        if (m == null) {
            return;
        }
        int n = 0;
        for (int i = 1; i < m.chapters.size() && n < count; i += 2) {
            ClientModel.Chapter c = m.chapters.get(i);
            if (!ClientProgress.chapterHidden(c.id)) {
                ClientConfig.setRailPin(c.id, true);
                n++;
            }
        }
    }

    /**
     * Dev tour only: draw the resting layout. A tour runs in an unfocused window whose pointer still sits wherever it
     * was, and hover would then hold the chapter rail open in every photo. Turned on for the photos and off again when
     * the tour ends, so a hand-driven session in the same client keeps its hover.
     */
    static void tourResting(boolean resting) {
        tourResting = resting;
    }


    private boolean decorationVisible(ClientModel.Decoration d) {
        if (d.dev || d.image == null || !imageOnClient(d.image)) {
            return false;
        }
        return d.visibleAfter == null || com.kuronami.questtree.progress.ProgressRules.doneOnce(ClientProgress.progress(), d.visibleAfter);
    }

    /** Whether this client can load the decoration's image: an image it cannot load is skipped rather than drawn magenta. */
    private boolean imageOnClient(ResourceLocation image) {
        return imageCache.computeIfAbsent(image, rl -> minecraft.getResourceManager().getResource(rl).isPresent());
    }

    /**
     * The topmost visible decoration under the mouse (rotation ignored: bounds are axis-aligned). Returns its hover
     * text when {@code tooltip}, else null; {@link #clickDecoration} uses the same hit test.
     */
    private List<Component> decorationAt(ClientModel.Chapter ch, float[] v, double mx, double my, boolean tooltip) {
        ClientModel.Decoration hit = decorationHit(ch, v, mx, my);
        return hit == null ? null : hit.hover;
    }

    private ClientModel.Decoration decorationHit(ClientModel.Chapter ch, float[] v, double mx, double my) {
        if (mx < railRight() || (sheetT > 0 && mx >= sheetLeft())) {
            return null;
        }
        double wx = (mx - v[0]) / v[2];
        double wy = (my - v[1]) / v[2];
        for (int i = ch.decorations.size() - 1; i >= 0; i--) {
            ClientModel.Decoration d = ch.decorations.get(i);
            if (!decorationVisible(d) || (d.hover.isEmpty() && d.clickNode == null && d.clickUrl == null)) {
                continue;
            }
            if (Math.abs(wx - d.x * UNIT) <= d.w * UNIT / 2 && Math.abs(wy - d.y * UNIT) <= d.h * UNIT / 2) {
                return d;
            }
        }
        return null;
    }

    /** Opens a node's chapter and selects it, centring the map on it. */
    private void goTo(String key) {
        if (!ClientProgress.visible(key)) {
            // hidden quests stay hidden, also through links, images and requirement rows
            ProgressFeedback.locked();
            return;
        }
        ClientModel m = model();
        ClientModel.Chapter c = m.chapterOf.get(key);
        ClientModel.Node n = m.nodesByKey.get(key);
        if (c == null || n == null) {
            return;
        }
        ProgressFeedback.click();
        select(m.chapters.indexOf(c));
        float[] v = view(c);
        v[0] = (float) ((width - sheetWidth()) / 2.0 - n.x * UNIT * v[2]);
        v[1] = (float) (height / 2.0 - n.y * UNIT * v[2]);
        selected = key;
    }

    private static void dashedOutline(GuiGraphics g, int x, int y, int w, int h, int argb) {
        for (int i = 0; i < w; i += 4) {
            g.fill(x + i, y, x + Math.min(i + 2, w), y + 1, argb);
            g.fill(x + i, y + h - 1, x + Math.min(i + 2, w), y + h, argb);
        }
        for (int i = 0; i < h; i += 4) {
            g.fill(x, y + i, x + 1, y + Math.min(i + 2, h), argb);
            g.fill(x + w - 1, y + i, x + w, y + Math.min(i + 2, h), argb);
        }
    }

    private void select(int index) {
        panView = null;
        chapterIndex = index;
        lastChapter = index;
        selected = null;
        choicePick = -1;
        sheetT = 0;
    }

    private void zoomButton(ClientModel.Chapter ch, float[] v, int which) {
        lastMapMove = Util.getMillis();
        switch (which) {
            case 0 -> VIEWS.put(ch.id, fitView(ch));
            case 1 -> zoomAt(v, width / 2.0, height / 2.0, 1 / 1.25f);
            default -> zoomAt(v, width / 2.0, height / 2.0, 1.25f);
        }
    }

    private long lastMapMove = -100_000;

    private boolean minimapShown() {
        return (dragging && moved) || Util.getMillis() - lastMapMove <= MINIMAP_HOLD;
    }
    private static final long MINIMAP_HOLD = 1200;

    private static void zoomAt(float[] v, double cx, double cy, float f) {
        float z = Mth.clamp(v[2] * f, 0.3f, 3f);
        float r = z / v[2];
        v[0] = (float) (cx - (cx - v[0]) * r);
        v[1] = (float) (cy - (cy - v[1]) * r);
        v[2] = z;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        ClientModel.Chapter ch = chapter();
        if (dragging && ch != null) {
            if (!moved && Math.hypot(mx - dragStartX, my - dragStartY) > 3) {
                moved = true;
            }
            if (moved) {
                float[] v = view(ch);
                v[0] += (float) dx;
                v[1] += (float) dy;
                lastMapMove = Util.getMillis();
            }
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        ClientModel.Chapter ch = chapter();
        if (dragging && ch != null && !moved) {
            float[] v = view(ch);
            ClientModel.Node hit = null;
            for (ClientModel.Node n : ch.nodes.values()) {
                if (!ClientProgress.visible(n.key)) {
                    continue;
                }
                double sx = v[0] + n.x * UNIT * v[2];
                double sy = v[1] + n.y * UNIT * v[2];
                double half = NODE * n.size * v[2] / 2;
                if (Math.abs(mx - sx) <= half && Math.abs(my - sy) <= half) {
                    hit = n;
                }
            }
            if (hit == null) {
                for (ClientModel.Link l : ch.links) {
                    double sx = v[0] + l.x * UNIT * v[2];
                    double sy = v[1] + l.y * UNIT * v[2];
                    double half = NODE * l.size * v[2] / 2;
                    if (Math.abs(mx - sx) <= half && Math.abs(my - sy) <= half) {
                        goTo(l.node);
                        dragging = false;
                        return true;
                    }
                }
                ClientModel.Decoration d = decorationHit(ch, v, mx, my);
                if (d != null && d.clickNode != null) {
                    goTo(d.clickNode);
                    dragging = false;
                    return true;
                }
                if (d != null && d.clickUrl != null) {
                    dragging = false;
                    // vanilla's "open this link?" screen, then back here
                    net.minecraft.client.gui.screens.ConfirmLinkScreen.confirmLinkNow(this, d.clickUrl);
                    return true;
                }
            }
            String key = hit == null ? null : hit.key;
            if (key != null && !key.equals(selected)) {
                choicePick = -1;
            }
            selected = key;
            if (hit != null) {
                State s = ClientProgress.state(hit.key);
                if (s == State.LOCKED || s == State.EXCLUDED) {
                    shakes.put(hit.key, Util.getMillis());
                    ProgressFeedback.locked();
                }
                keepVisible(v, hit);
            }
        }
        dragging = false;
        return super.mouseReleased(mx, my, button);
    }

    /**
     * Slide the map just far enough that the selected node clears the sheet (240ms). It used to jump the node to the
     * middle of the free space, which threw the whole tree left on a click (the player found that jarring).
     */
    private void keepVisible(float[] v, ClientModel.Node n) {
        double half = NODE * n.size * v[2] / 2;
        double sx = v[0] + n.x * UNIT * v[2];
        double limit = width - sheetWidth() - 16;
        if (sx + half > limit) {
            panView = v;
            panDistance = (float) -(sx + half - limit);
            panDone = 0;
            panStart = Util.getMillis();
        }
    }

    private float[] panView;
    /** Total slide and the part already applied: the slide is added frame by frame, so zoom, scroll and minimap input
     *  during it combine with it instead of being overwritten. */
    private float panDistance;
    private float panDone;
    private long panStart;
    private static final float PAN_MS = 240;

    /** Advances a running keepVisible slide; dragging the map stops it. */
    private void stepPan(float[] v) {
        if (panView != v) {
            return;
        }
        if (dragging) {
            panView = null;
            return;
        }
        float t = Math.min(1, (Util.getMillis() - panStart) / PAN_MS);
        float target = panDistance * ease(t);
        v[0] += target - panDone;
        panDone = target;
        if (t >= 1) {
            panView = null;
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        ClientModel m = model();
        if (m == null) {
            return super.mouseScrolled(mx, my, scrollX, scrollY);
        }
        if (mx < railRight()) {
            railScrollTarget = Mth.clamp(railScrollTarget - (float) scrollY * railPitch() * 1.5f, 0, railMaxScroll());
            return true;
        }
        if (sheetT > 0 && selected != null && mx >= sheetLeft()) {
            sheetScrollTarget = Mth.clamp(sheetScrollTarget - (float) scrollY * 24, 0, sheetContentMax);
            return true;
        }
        ClientModel.Chapter ch = chapter();
        if (ch == null) {
            nowScroll = Math.max(0, nowScroll - (int) (scrollY * 20));
            return true;
        }
        float[] v = view(ch);
        lastMapMove = Util.getMillis();
        if (scrollX != 0) {
            // trackpads send both axes: pan (FTB Quests issue #2059 asked for this)
            v[0] += (float) (scrollX * 12);
            v[1] += (float) (scrollY * 12);
        } else {
            zoomAt(v, mx, my, (float) Math.pow(1.15, scrollY));
        }
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (searchBox != null && searchBox.isFocused()) {
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
                nextMatch();
                return true;
            }
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                searchBox.setFocused(false);
                setFocused(null);
                return true;
            }
            // typing goes to the box (the open key must not close the screen mid-word)
            return searchBox.keyPressed(key, scan, modifiers) || true;
        }
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_F && Screen.hasControlDown()) {
            setFocused(searchBox);
            searchBox.setFocused(true);
            return true;
        }
        if ((key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) && searching()) {
            nextMatch();
            return true;
        }
        RecipeViewer viewer = RecipeViewer.Holder.get();
        if (viewer != null && !hoverStack.isEmpty()
                && (key == org.lwjgl.glfw.GLFW.GLFW_KEY_R || key == org.lwjgl.glfw.GLFW.GLFW_KEY_U)) {
            // the viewer's own keys: R for how it is made, U for what it is used in
            ItemStack it = hoverStack;
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_R) {
                viewer.showRecipes(it);
            } else {
                viewer.showUses(it);
            }
            return true;
        }
        if (ClientProgress.OPEN_KEY.matches(key, scan)) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override
    public void removed() {
        lastChapter = chapterIndex;
        // release the toasts queued while this screen was up; the flush bypasses the suppression (the screen is
        // still the current one when removed() runs)
        PendingToasts.beginFlush();
        try {
            QuestToast.flushPending();
            ExperienceToast.flushPending();
        } finally {
            PendingToasts.endFlush();
        }
        super.removed();
    }

    // ------------------------------------------------------------------ drawing helpers

    private final Map<String, Map<String, Integer>> depthCache = new HashMap<>();

    /** Steps from the chapter's start (root nodes, else nodes needing nothing), walking links in either direction. */
    private Map<String, Integer> depths(ClientModel.Chapter ch) {
        return depthCache.computeIfAbsent(ch.id, k -> {
            Map<String, java.util.List<String>> links = new HashMap<>();
            java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
            Map<String, Integer> d = new HashMap<>();
            boolean anyRoot = ch.nodes.values().stream().anyMatch(n -> n.root);
            for (ClientModel.Node n : ch.nodes.values()) {
                for (String r : n.requireKeys) {
                    links.computeIfAbsent(n.key, x -> new ArrayList<>()).add(r);
                    links.computeIfAbsent(r, x -> new ArrayList<>()).add(n.key);
                }
                if (anyRoot ? n.root : n.requireKeys.isEmpty()) {
                    d.put(n.key, 0);
                    queue.add(n.key);
                }
            }
            while (!queue.isEmpty()) {
                String at = queue.poll();
                for (String next : links.getOrDefault(at, List.of())) {
                    if (!d.containsKey(next)) {
                        d.put(next, d.get(at) + 1);
                        queue.add(next);
                    }
                }
            }
            return d;
        });
    }

    private static int brighter(int lc) {
        return lc == LINE_WALKED ? Tiles.GREEN_TEXT : lc == LINE_OPEN ? Ui.LINE_OPEN_HOVER : LINE_CLOSED_ON;
    }

    /**
     * One line from {@code p} (earlier) to {@code n}. The lines and the arrowheads are drawn under the tiles, so an
     * arrowhead that walked over a tile showed through its edge: it now stands only on a stretch of the line that no
     * tile covers, and a line whose length is covered end to end gets none. While highlighted it slides along those
     * free stretches, so the parts a tile hides are stepped over rather than travelled. Zoomed out it is left out, and
     * nothing is measured for it.
     */
    private static void arrowLine(GuiGraphics g, ClientModel.Node p, ClientModel.Node n, int argb, boolean on, float open,
                                  boolean far, long now, float[] occ) {
        double x1 = p.x * UNIT;
        double y1 = p.y * UNIT;
        double dx = n.x * UNIT - x1;
        double dy = n.y * UNIT - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1) {
            return;
        }
        float w = on ? LINE_W_ON : LINE_W;
        float a = on || open >= 0 ? ARROW_ON : ARROW;
        float[] runs = far ? NO_RUNS : visibleRuns(x1, y1, dx, dy, len, occ);
        g.pose().pushPose();
        g.pose().translate(x1, y1, 0);
        g.pose().mulPose(Axis.ZP.rotation((float) Math.atan2(dy, dx)));
        float head;
        if (open >= 0) {
            float e = ease(open);
            fillLine(g, 0, len, w, LINE_CLOSED);
            fillLine(g, 0, len * e, w, argb);
            head = !far && fitsRun(runs, len * e, a) ? len * e : -1;
        } else {
            fillLine(g, 0, len, w, argb);
            head = far ? -1
                    : on ? slideHead(runs, ((now % 100_000L) / SLIDE_MS) % 1f, a)
                    : longestMiddle(runs, a);
        }
        if (head >= 0) {
            Tiles.icon(g, "arrow", head - a / 2, -a / 2, a, argb);
        }
        g.pose().popPose();
    }

    /**
     * Quest lines drawn as elbows (2026-10-18 trial, after Thaumaturge's research book): leave the earlier node along
     * one axis, turn once on a rounded corner, and enter the later node along the axis it is mostly reached by, with
     * an arrowhead at the line's end, just outside that node's edge, like any arrow (2026-10-18, the player: the open
     * chevron only read as a twig while it sat mid-line). Nodes that share a parent share its first leg, so a fan reads
     * as one trunk. Skill trees keep straight lines with the same end arrowhead: their layouts are free-form, often radial.
     * A quest link that is a diagonal (its two offsets of similar size) is a straight line too: there the elbow only
     * walked around a corner (the player, 2026-10-19).
     */
    static final boolean ELBOW_LINES = !Boolean.getBoolean("quest_tree.straightLines");
    /** The largest corner radius, in map units: one grid unit, as in a book laid out on a grid. */
    private static final float ELBOW_R = UNIT;
    /** Neighbours closer than this (map units) keep a straight line: an elbow between touching tiles is only a curl. */
    private static final float ELBOW_MIN = 1.5f * UNIT;
    /** Below this ratio between the two offsets a link is a diagonal, not a step along one axis, and is drawn straight. */
    private static final float DIAGONAL_RATIO = 0.5f;
    /**
     * Where the chevron's tip and its inner corner sit, as fractions of the icon from its centre (icon/chevron is 64px:
     * tip at 57, inner corner at 37). The tip lands on the line's end and the line stops inside the inner corner, so no
     * line shows past the point.
     */
    private static final float CHEVRON_TIP = 0.39f;
    private static final float CHEVRON_NOTCH = 0.12f;
    /**
     * The straight run a line keeps after its corner, beyond the tile's edge and the arrowhead, so the arrowhead sits on
     * a straight piece and points the way the line arrives instead of along the end of the curve.
     */
    private static final float ELBOW_RUN_IN = 4;
    /** Segments per rounded corner; enough that the joins do not show at the widest zoom. */
    private static final int ELBOW_ARC_STEPS = 8;

    private static void elbowLine(GuiGraphics g, ClientModel.Node p, ClientModel.Node n, int argb, boolean on, float open,
                                  boolean far, boolean straight) {
        float x1 = (float) (p.x * UNIT);
        float y1 = (float) (p.y * UNIT);
        float x2 = (float) (n.x * UNIT);
        float y2 = (float) (n.y * UNIT);
        float dx = x2 - x1;
        float dy = y2 - y1;
        if (Math.abs(dx) < 1 && Math.abs(dy) < 1) {
            return;
        }
        float a = on || open >= 0 ? ARROW_ON : ARROW;
        float half = (float) (NODE * n.size) / 2f * (Tiles.shapeOf(n.shape).equals("diamond") ? 1.25f : 1f);
        float stop = half + ARROW_PAD;
        float[] pts = straight ? new float[]{x1, y1, x2, y2} : elbowPath(x1, y1, dx, dy, stop + a + ELBOW_RUN_IN);
        int last = pts.length - 2;
        float ex = pts[last] - pts[last - 2];
        float ey = pts[last + 1] - pts[last - 1];
        float el = (float) Math.sqrt(ex * ex + ey * ey);
        float ux = ex / el;
        float uy = ey / el;
        float w = on ? LINE_W_ON : LINE_W;
        float total = polylineLength(pts) - stop;
        boolean head = !far && total > a;
        if (head) {
            total -= CHEVRON_TIP * a - CHEVRON_NOTCH * a;
        }
        if (open >= 0) {
            float e = ease(open);
            polyline(g, pts, total, w, LINE_CLOSED);
            polyline(g, pts, total * e, w, argb);
        } else {
            polyline(g, pts, total, w, argb);
        }
        if (head) {
            g.pose().pushPose();
            g.pose().translate(x2 - ux * (stop + CHEVRON_TIP * a), y2 - uy * (stop + CHEVRON_TIP * a), 0);
            g.pose().mulPose(Axis.ZP.rotation((float) Math.atan2(uy, ux)));
            Tiles.icon(g, "chevron", -a / 2, -a / 2, a, argb);
            g.pose().popPose();
        }
    }

    /**
     * The elbow's points: a straight pair when the nodes share a row or column, otherwise first leg, a quarter circle
     * sampled into short chords, and the leg into the later node. The leg into the node runs along the larger offset, and
     * the corner shrinks so that leg keeps at least {@code straightIn} straight.
     */
    private static float[] elbowPath(float x1, float y1, float dx, float dy, float straightIn) {
        if (Math.abs(dx) < 1 || Math.abs(dy) < 1 || dx * dx + dy * dy < ELBOW_MIN * ELBOW_MIN) {
            return new float[]{x1, y1, x1 + dx, y1 + dy};
        }
        float ax = Math.abs(dx);
        float ay = Math.abs(dy);
        // A link that leaves on both axes by a similar amount reads better as a straight diagonal: the elbow is then a
        // detour around a corner nobody needed (the player, 2026-10-19), while a link that is mostly along one axis
        // keeps its elbow, where the small turn keeps a fan of siblings reading as one trunk.
        if (Math.min(ax, ay) >= ELBOW_MIN && Math.min(ax, ay) >= DIAGONAL_RATIO * Math.max(ax, ay)) {
            return new float[]{x1, y1, x1 + dx, y1 + dy};
        }
        boolean enterSideways = Math.abs(dx) >= Math.abs(dy);
        float sx = Math.signum(dx);
        float sy = Math.signum(dy);
        float cx = enterSideways ? x1 : x1 + dx;
        float cy = enterSideways ? y1 + dy : y1;
        float d1x = enterSideways ? 0 : sx;
        float d1y = enterSideways ? sy : 0;
        float d2x = enterSideways ? sx : 0;
        float d2y = enterSideways ? 0 : sy;
        float r = Math.min(ELBOW_R, Math.min(Math.abs(dx), Math.abs(dy)));
        float secondLeg = enterSideways ? Math.abs(dx) : Math.abs(dy);
        r = Math.max(0, Math.min(r, secondLeg - straightIn));
        float[] pts = new float[(ELBOW_ARC_STEPS + 3) * 2];
        int k = 0;
        pts[k++] = x1;
        pts[k++] = y1;
        float ox = cx - d1x * r + d2x * r;
        float oy = cy - d1y * r + d2y * r;
        for (int i = 0; i <= ELBOW_ARC_STEPS; i++) {
            double t = Math.PI / 2 * i / ELBOW_ARC_STEPS;
            float c = (float) Math.cos(t);
            float s = (float) Math.sin(t);
            pts[k++] = ox - d2x * r * c + d1x * r * s;
            pts[k++] = oy - d2y * r * c + d1y * r * s;
        }
        pts[k++] = x1 + dx;
        pts[k] = y1 + dy;
        return pts;
    }

    private static float polylineLength(float[] pts) {
        float len = 0;
        for (int i = 0; i + 3 < pts.length; i += 2) {
            float ax = pts[i + 2] - pts[i];
            float ay = pts[i + 3] - pts[i + 1];
            len += (float) Math.sqrt(ax * ax + ay * ay);
        }
        return len;
    }

    /** Draws a polyline up to {@code upTo} map units of its length; each chord overlaps the next so joins stay closed. */
    private static void polyline(GuiGraphics g, float[] pts, float upTo, float w, int argb) {
        float done = 0;
        for (int i = 0; i + 3 < pts.length && done < upTo; i += 2) {
            float ax = pts[i + 2] - pts[i];
            float ay = pts[i + 3] - pts[i + 1];
            float l = (float) Math.sqrt(ax * ax + ay * ay);
            if (l < 0.01f) {
                continue;
            }
            float draw = Math.min(l, upTo - done);
            g.pose().pushPose();
            g.pose().translate(pts[i], pts[i + 1], 0);
            g.pose().mulPose(Axis.ZP.rotation((float) Math.atan2(ay, ax)));
            fillLine(g, done == 0 ? 0 : -w / 2, draw + (draw < l ? 0 : w / 2), w, argb);
            g.pose().popPose();
            done += l;
        }
    }

    /** The footprints of a chapter's tiles in map units. A line knows these so it can keep its arrowhead off them. */
    private static float[] occluderBoxes(ClientModel.Chapter ch, ClientModel cm) {
        float[] boxes = new float[(ch.nodes.size() + ch.links.size()) * 3];
        int k = 0;
        for (ClientModel.Node n : ch.nodes.values()) {
            if (ClientProgress.visible(n.key)) {
                k = putBox(boxes, k, n.x, n.y, n.shape, n.size);
            }
        }
        for (ClientModel.Link l : ch.links) {
            ClientModel.Node target = cm.nodesByKey.get(l.node);
            if (target != null && ClientProgress.visible(target.key)) {
                k = putBox(boxes, k, l.x, l.y, l.shape.isEmpty() ? target.shape : l.shape, l.size);
            }
        }
        return Arrays.copyOf(boxes, k);
    }

    /** Adds one tile footprint (centre and half extent, map units) to a flat box list and returns the next slot. */
    private static int putBox(float[] boxes, int k, double x, double y, String shape, double size) {
        // a diamond is drawn wider than its box, so its footprint is widened the same way the tile is
        float half = (float) (NODE * size) / 2f * (Tiles.shapeOf(shape).equals("diamond") ? 1.25f : 1f);
        boxes[k] = (float) x * UNIT;
        boxes[k + 1] = (float) y * UNIT;
        boxes[k + 2] = half;
        return k + 3;
    }

    /**
     * The stretches of one line that no tile covers, in map units from {@code p}'s centre. The stretch inside a tile is
     * what the tile hides, so the line keeps its arrowhead in what is left.
     */
    private static float[] visibleRuns(double x1, double y1, double dx, double dy, float len, float[] occ) {
        float[] cov = new float[occ.length / 3 * 2];
        int c = 0;
        for (int i = 0; i + 2 < occ.length; i += 3) {
            double[] t = tileSpan(x1, y1, dx, dy, occ[i], occ[i + 1], occ[i + 2]);
            if (t != null) {
                cov[c++] = (float) t[0] * len;
                cov[c++] = (float) t[1] * len;
            }
        }
        for (int i = 2; i < c; i += 2) {   // order the covered stretches
            float s0 = cov[i];
            float s1 = cov[i + 1];
            int j = i - 2;
            while (j >= 0 && cov[j] > s0) {
                cov[j + 2] = cov[j];
                cov[j + 3] = cov[j + 1];
                j -= 2;
            }
            cov[j + 2] = s0;
            cov[j + 3] = s1;
        }
        float[] runs = new float[c + 2];
        int r = 0;
        float at = 0;
        for (int i = 0; i < c; i += 2) {   // merge them, then keep what is left of the line
            if (cov[i] > at) {
                runs[r++] = at;
                runs[r++] = cov[i];
            }
            at = Math.max(at, cov[i + 1]);
        }
        if (at < len) {
            runs[r++] = at;
            runs[r++] = len;
        }
        return Arrays.copyOf(runs, r);
    }

    /** Where a line crosses one tile box, as the 0..1 span along the line, or null when it misses the box. */
    private static double[] tileSpan(double x1, double y1, double dx, double dy, float cx, float cy, float half) {
        double[] xs = slab(x1, dx, cx - half, cx + half);
        double[] ys = slab(y1, dy, cy - half, cy + half);
        if (xs == null || ys == null) {
            return null;
        }
        double from = Math.max(xs[0], ys[0]);
        double to = Math.min(xs[1], ys[1]);
        return to > from ? new double[] {from, to} : null;
    }

    /** The 0..1 span where one axis of the line lies within {@code [lo, hi]}, or null when it never does. */
    private static double[] slab(double o, double d, double lo, double hi) {
        if (d == 0) {
            return o < lo || o > hi ? null : new double[] {0, 1};
        }
        double a = (lo - o) / d;
        double b = (hi - o) / d;
        if (a > b) {
            double s = a;
            a = b;
            b = s;
        }
        a = Math.max(a, 0);
        b = Math.min(b, 1);
        return b > a ? new double[] {a, b} : null;
    }

    /**
     * Where the arrowhead rests on a line nobody is pointing at: the middle of its widest free stretch, when that
     * stretch is wide enough to hold an arrowhead of length {@code a} and keep {@link #ARROW_PAD} from each tile.
     */
    private static float longestMiddle(float[] runs, float a) {
        float best = a + 2 * ARROW_PAD;
        float mid = -1;
        for (int i = 0; i + 1 < runs.length; i += 2) {
            if (runs[i + 1] - runs[i] >= best) {
                best = runs[i + 1] - runs[i];
                mid = (runs[i] + runs[i + 1]) / 2;
            }
        }
        return mid;
    }

    /**
     * Where the arrowhead is while it slides: {@code t} runs 0..1 over the free stretches together, so the parts a
     * tile covers are stepped over instead of travelled, and never so far that the arrowhead or its padding reaches
     * the tile hiding that part. -1 when no stretch is wide enough to hold it.
     */
    private static float slideHead(float[] runs, float t, float a) {
        float pad = a / 2 + ARROW_PAD;
        float total = 0;
        for (int i = 0; i + 1 < runs.length; i += 2) {
            total += Math.max(0, runs[i + 1] - runs[i] - 2 * pad);
        }
        if (total <= 0) {
            return -1;
        }
        float at = t * total;
        for (int i = 0; i + 1 < runs.length; i += 2) {
            float span = runs[i + 1] - runs[i] - 2 * pad;
            if (span <= 0) {
                continue;
            }
            if (at <= span) {
                return runs[i] + pad + at;
            }
            at -= span;
        }
        return -1;
    }

    /** Whether an arrowhead of length a centred at s lies inside one free stretch, padding included. */
    private static boolean fitsRun(float[] runs, float s, float a) {
        float pad = a / 2 + ARROW_PAD - 0.001f;
        for (int i = 0; i + 1 < runs.length; i += 2) {
            if (s - pad >= runs[i] && s + pad <= runs[i + 1]) {
                return true;
            }
        }
        return false;
    }

    /** A straight run along the x axis of the current pose, centred on it. */
    private static void fillLine(GuiGraphics g, float from, float to, float w, int argb) {
        if (to <= from) {
            return;
        }
        g.pose().pushPose();
        g.pose().translate(from, -w / 2f, 0);
        g.pose().scale((to - from) / 64f, w / 4f, 1);
        g.fill(0, 0, 64, 4, argb);
        g.pose().popPose();
    }

    private String trim(String s, int w) {
        if (font.width(s) <= w) {
            return s;
        }
        return font.plainSubstrByWidth(s, Math.max(0, w - font.width("..."))) + "...";
    }

    private static float ease(float t) {
        float u = 1 - t;
        return 1 - u * u * u;
    }

    private static int lerpColor(int a, int b, float t) {
        int r = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int ca = (a >>> shift) & 255;
            int cb = (b >>> shift) & 255;
            r |= ((int) (ca + (cb - ca) * t) & 255) << shift;
        }
        return r;
    }
}
