package com.kuronami.questtree.client;

import com.google.gson.JsonObject;
import com.kuronami.questtree.client.edit.EditorClipboard;
import com.kuronami.questtree.client.edit.EditorSelection;
import com.kuronami.questtree.client.edit.GridSnap;
import com.kuronami.questtree.client.edit.MoveBatch;
import com.kuronami.questtree.network.EditorReplies;
import com.kuronami.questtree.progress.PlayerProgress;
import com.kuronami.questtree.progress.ProgressRules;
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The progression screen (layout of the accepted v12-v14 mocks, see _research/progression_ui/).
 * <ul>
 *   <li>It opens on a chapter: the "Now" overview was dropped on 2026-10-20 (the player: all of it was wrong).</li>
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
    /** What a node the search does not match wears, so it sinks into the ground instead of going dark. */
    private static int SINK = 0xC0000000 | (Ui.GROUND & 0xFFFFFF);

    private static final int UNIT = 24;      // GUI px per grid unit (FTB Quests positions)
    private static final int NODE = 20;      // GUI px of a size-1 node
    /** Closed width of the chapter list: one column of icons. */
    private static final int RAIL = 24;
    private static final int RAIL_OPEN_MIN = 150;
    /** Open width of the chapter list: as wide as its longest name needs (ATM10's names were cut to "Chapter 2:..."). */
    private int railOpenW = RAIL_OPEN_MIN;
    private int railWidest;
    /** Height of a flat control (the search field, the zoom buttons). */
    private static final int BTN = 28;
    /** Side of the title row's icon buttons (pin, search) and glyphs inside them. */
    private static final int TITLE_BTN = BTN - 8;
    private static final int TITLE_GLYPH = 10;
    /** Width of the quest search field that takes the title's place in the title row. */
    private static final int SEARCH_FIELD_W = 160;
    /** Height of the search field's EditBox; its box is drawn taller around it. */
    private static final int EDIT_H = 9;
    /**
     * Height of a chapter row (rows touch, so it is also the pitch). 26 until 2026-10-20 fitted 8 rows on a 1080p screen
     * at GUI scale 4, where FTB Quests' list fits 18 and cut no names (the player): 14 fits 16 and the names get the
     * room the pin slot and the big icons took.
     */
    private static final int RAIL_ROW = 14;
    /** Icon size in a row: 0.75 of an item, whole screen pixels per item pixel at GUI scale 4. */
    private static final int RAIL_ICON = 12;
    /** Left edge of a row's icon and of its label. */
    private static final int RAIL_ICON_X = 10;
    private static final int RAIL_TEXT_X = RAIL_ICON_X + RAIL_ICON + 6;
    /** The smallest zoom a chapter opens at: a size-1 tile is 9 GUI px, still a readable icon. */
    private static final float FIT_MIN = 0.45f;
    private static final float FAR = 0.6f;   // below this zoom, optional outlines are left out (icons stay)
    private static final float OVERLAY_Z = Tiles.OVERLAY_Z;

    /** Per-chapter camera, kept for the session (players asked for the position to be remembered). */
    private static final Map<String, float[]> VIEWS = new HashMap<>();
    private static int lastChapter = -1;

    /** Called on logout so another server starts with fresh cameras. */
    public static void clearViews() {
        VIEWS.clear();
        lastChapter = -1;
        query = "";
        railQuery = "";
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
    /** The quest search field's hit area (x, y, w, h), set every frame by {@link #renderHeader}. */
    private int[] searchRect = new int[4];
    /**
     * The chapter list's own filter (2026-10-20, the player: the field at the top of the list looked like it searched
     * the list, so it now does). Kept apart from the quest search: it only narrows the rows shown there.
     */
    private static String railQuery = "";
    private net.minecraft.client.gui.components.EditBox railBox;
    /** The rail field's hit area (x, y, w, h), set every frame by {@link #renderRailField}. */
    private int[] railRect = new int[4];
    /** The chapters the rail lists after its filter (all of them when the field is empty). */
    private final List<ClientModel.Chapter> railShown = new ArrayList<>();
    /** The filter text the rows were last built for, part of the rebuild key. */
    private String railBuiltFilter = "";
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
        // a resource reload calls init on the open screen: shapes a pack replaced are measured again (only then, not on
        // every resize or return from another screen)
        Tiles.reloadIfPacksChanged();
        imageCache.clear();
        // a resource pack change can add, drop or resize background pictures
        TEXTURE_SIZES.clear();
        searchBox = new net.minecraft.client.gui.components.EditBox(font, 0, 8, 120 - 2 * Ui.S2, EDIT_H, Component.translatable("screen.kuronami_quest_tree.search"));
        // drawn as one of the screen's flat controls (renderHeader), not vanilla's black box with a grey border
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
        railBox = new net.minecraft.client.gui.components.EditBox(font, 0, 8, 120 - 2 * Ui.S2, EDIT_H,
                Component.translatable("screen.kuronami_quest_tree.find_chapter"));
        railBox.setBordered(false);
        railBox.setHint(Component.translatable("screen.kuronami_quest_tree.find_chapter").withStyle(net.minecraft.ChatFormatting.GRAY));
        railBox.setMaxLength(64);
        railBox.setValue(railQuery);
        railBox.setResponder(v -> {
            railQuery = v;
            railScrollTarget = 0;
        });
        addWidget(railBox);
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

    /**
     * What hovering a quest says (FTB Quests' {@code QuestButton.addMouseOverText}, 2026-10-20): the title and its
     * subtitle, then only the lines that apply -- progress, optional, repeat count, a pick-one choice, the exclusive
     * mark, or why it cannot start. No sentence is added when there is nothing to say.
     */
    private List<Component> nodeTooltip(ClientModel.Node n) {
        List<Component> tip = new ArrayList<>();
        tip.add(n.title);
        if (n.subtitle != null) {
            // the subtitle one step under the title, the same grey the details sheet uses
            tip.add(n.subtitle);
        }
        State s = ClientProgress.state(n.key);
        if (s == State.PROGRESS) {
            int pct = (int) Math.floor(ClientProgress.fraction(n.key) * 100);
            if (pct > 0) {
                tip.add(Component.literal(pct + "%"));
            }
        }
        if (n.optional) {
            tip.add(Component.translatable("screen.kuronami_quest_tree.optional"));
        }
        if (n.repeatable) {
            int done = ClientProgress.completions(n.key);
            tip.add(done > 0
                    ? Component.translatable("screen.kuronami_quest_tree.can_repeat_count", done)
                    : Component.translatable("screen.kuronami_quest_tree.can_repeat"));
        }
        if (!n.choice.isEmpty()) {
            tip.add(Component.translatable("screen.kuronami_quest_tree.choose"));
        }
        if (n.exclusive) {
            tip.add(Component.translatable("screen.kuronami_quest_tree.exclusive"));
        }
        if (s == State.LOCKED) {
            List<String> missing = new ArrayList<>();
            for (String req : n.requireKeys) {
                if (!ClientProgress.progress().completed.contains(req)) {
                    ClientModel.Node r = model().nodesByKey.get(req);
                    missing.add(r != null && ClientProgress.visible(req)
                            ? r.title.getString() : Component.translatable("screen.kuronami_quest_tree.unknown").getString());
                }
            }
            if (!missing.isEmpty()) {
                tip.add(Component.translatable("screen.kuronami_quest_tree.needs_list",
                        String.join(", ", missing.subList(0, Math.min(4, missing.size())))));
            } else {
                tip.add(Component.translatable("screen.kuronami_quest_tree.locked"));
            }
        } else if (s == State.EXCLUDED) {
            tip.add(Component.translatable("screen.kuronami_quest_tree.excluded"));
        }
        return tip;
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

    /**
     * A rail entry: a group's header (title while open, a divider while closed) or a chapter row. {@code chapter} is
     * null on a header, and {@code fold} is the key a click on the header folds the group under, as FTB Quests' chapter
     * list does (2026-10-20, the player). Mixed heights (chapter rows RAIL_ROW, headers HEADER_H) keep groups readable.
     */
    private record RailRow(ClientModel.Chapter chapter, Component header, String fold) {
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
    private float railT;
    private float sheetT;
    /** Details sheet scroll: the shown value easing to the wheel's target, and the max of the last drawn frame. */
    private float sheetScroll;
    private float sheetScrollTarget;
    private int sheetContentMax;
    /** The node the current scroll belongs to; a new node starts at the top. */
    private String sheetScrollFor;
    private long lastFrame;
    /** Tooltip lines asked for while drawing (icon buttons), drawn last so nothing covers it. */
    private List<Component> pendingTip;
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

    // ------------------------------------------------------------------ editing (phase 2)
    private enum EditDrag { NONE, MOVE, BOX, LINK }

    private final EditorSelection editSelection = new EditorSelection();
    private EditDrag editDrag = EditDrag.NONE;
    private double editStartMx;
    private double editStartMy;
    private boolean editAdditive;
    /** Original positions of the nodes in the current drag, by global key. */
    private Map<String, double[]> editOriginal;
    private boolean editMoved;
    /** A press on an already picked node: the pick is narrowed to it on release unless the pointer moved (group drag). */
    private String editPressedKey;
    /** The node of the previous plain click and when it happened, for a double-click that opens the editor. */
    private String editClickKey;
    private long editClickAt;
    private double editBoxX0;
    private double editBoxY0;
    private double editBoxX1;
    private double editBoxY1;
    private String linkFrom;
    private boolean linkKeyHeld;
    private boolean linkArmed;
    private EditorMenu editorMenu;

    /** One row of the right-click menu. */
    private record EditorMenuEntry(Component label, boolean enabled, Runnable run) {
    }

    private static final class EditorMenu {
        final int x;
        final int y;
        final int w;
        final int h;
        final List<EditorMenuEntry> entries;

        EditorMenu(int x, int y, int w, List<EditorMenuEntry> entries) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = entries.size() * MENU_ROW + 2 * Ui.S1;
            this.entries = entries;
        }
    }

    private static final int MENU_ROW = 18;
    private static final int EDIT_NOTICE_MS = 4000;

    // derived from the progress; rebuilt when a new progress object arrives
    private PlayerProgress cachedFor;
    private ClientModel cachedModel;
    /** Pin version the rows were built from: pinning reorders them without any progress changing. */
    private int railPins = -1;
    /** Whether the rows were built while searching: a search opens every folded group so its matches show. */
    private boolean railBuiltSearching;
    /** The item under the pointer in the detail panel (a task or a reward), for the recipe viewer's keys. */
    private ItemStack hoverStack = ItemStack.EMPTY;
    private int lastMouseX;
    private int lastMouseY;
    /** A quest to show once the screen is laid out (opened from an item, see {@link #openAt}). */
    private String pendingGoTo;
    /** Back through the quests and chapters looked at, newest first (Backspace, 2026-10-20). */
    private final Deque<String> nodeHistory = new ArrayDeque<>();
    private final Deque<Integer> chapterHistory = new ArrayDeque<>();
    private final Map<String, Integer> badges = new LinkedHashMap<>();
    /** done, visible, hidden node counts per chapter. */
    private final Map<String, int[]> chapterCounts = new LinkedHashMap<>();
    /** Chapters listed in the rail (chapters hidden as a whole are left out). */
    private final List<ClientModel.Chapter> railChapters = new ArrayList<>();
    /** The rail's rows: a header before the first chapter of each titled group, then the chapter rows. */
    private final List<RailRow> railRows = new ArrayList<>();
    /** Per chapter, quests "claim all" would take: rewards waiting, no choice to make, not excluded by the pack. */
    private final Map<String, Integer> claimable = new HashMap<>();
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
        if (p == cachedFor && m == cachedModel && railPins == ClientConfig.railPinVersion() && railBuiltSearching == searching()
                && railBuiltFilter.equals(railQuery)) {
            return;
        }
        cachedFor = p;
        cachedModel = m;
        railPins = ClientConfig.railPinVersion();
        railBuiltSearching = searching();
        railBuiltFilter = railQuery;
        badges.clear();
        chapterCounts.clear();
        claimable.clear();
        doneTotal = 0;
        questTotal = 0;
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
                if (c.skills) {
                    continue;
                }
                if (!n.optional) {
                    questTotal++;
                    if (s == State.DONE || s == State.CLAIM) {
                        doneTotal++;
                    }
                }
                if (s == State.CLAIM) {
                    badge++;
                    var pn = ClientProgress.rules() == null ? null : ClientProgress.rules().model().nodes.get(n.key);
                    if (!n.needsChoice() && (pn == null || !com.kuronami.questtree.data.ProgressModel.excludedFromClaimAll(pn))) {
                        claimable.merge(c.id, 1, Integer::sum);
                    }
                }
            }
            if (c.skills) {
                badge = ClientProgress.points(c.id);
                if (badge <= 0 || !hasTakeable(c)) {
                    badge = 0;
                }
            }
            badges.put(c.id, badge);
            chapterCounts.put(c.id, new int[]{done, shown, hidden});
        }
        // the rail's filter: a chapter stays when its own name or its group's name contains the text; a group with no
        // chapter left simply never gets its header, and while filtering no group is folded shut (the player)
        railShown.clear();
        String filter = railQuery.toLowerCase(java.util.Locale.ROOT).trim();
        for (ClientModel.Chapter c : railChapters) {
            if (filter.isEmpty() || chapterMatchesFilter(m, c, filter)) {
                railShown.add(c);
            }
        }
        // pinned chapters form their own block at the top, in pin order: quick access to the few a player works in
        List<ClientModel.Chapter> pinnedRail = new ArrayList<>();
        for (String id : ClientConfig.railPinned()) {
            for (ClientModel.Chapter c : railShown) {
                if (c.id.equals(id)) {
                    pinnedRail.add(c);
                    break;
                }
            }
        }
        if (!pinnedRail.isEmpty()) {
            railRows.add(new RailRow(null, Component.translatable("screen.kuronami_quest_tree.pin"), PINNED_FOLD));
            if (!folded(PINNED_FOLD)) {
                for (ClientModel.Chapter c : pinnedRail) {
                    railRows.add(new RailRow(c, null, null));
                }
            }
        }
        // chapters are sorted by group order, so a group's chapters are contiguous: drop its header before the first.
        // A group without a title (or chapters with no group) simply gets no header, keeping the old rail.
        String lastGroup = null;
        boolean shut = false;
        for (ClientModel.Chapter c : railShown) {
            if (ClientConfig.railPinned(c.id)) {
                continue;
            }
            if (c.group != null && !c.group.equals(lastGroup)) {
                Component header = m.groupTitle(c);
                shut = header != null && folded(c.group);
                if (header != null) {
                    railRows.add(new RailRow(null, header, c.group));
                }
            } else if (c.group == null) {
                shut = false;
            }
            lastGroup = c.group;
            if (!shut) {
                railRows.add(new RailRow(c, null, null));
            }
        }
    }

    /** Fold key of the pinned block (group ids come from the pack and never start with '#'). */
    private static final String PINNED_FOLD = "#pinned";

    /** A group stays open while searching, so every match can be seen; a filter also shows its matching chapters. */
    private boolean folded(String fold) {
        return !searching() && !railFiltering() && ClientConfig.railFolded(fold);
    }

    private boolean railFiltering() {
        return !railQuery.isBlank();
    }

    /** Whether a chapter stays listed for a lower-cased filter: its own title or its group's title contains it. */
    private boolean chapterMatchesFilter(ClientModel m, ClientModel.Chapter c, String filter) {
        if (c.title.getString().toLowerCase(java.util.Locale.ROOT).contains(filter)) {
            return true;
        }
        Component group = c.group == null ? null : m.groupTitle(c);
        return group != null && group.getString().toLowerCase(java.util.Locale.ROOT).contains(filter);
    }

    /** Enter in the rail field: open the first chapter the filter leaves listed. */
    private void openFirstRailMatch() {
        ClientModel m = model();
        if (!railFiltering() || m == null || railShown.isEmpty()) {
            return;
        }
        select(m.chapters.indexOf(railShown.get(0)));
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
        EditorSession.pruneOptimistic(m, editDrag == EditDrag.MOVE && editOriginal != null
                ? editOriginal.keySet() : Set.of());
        if (!EditorSession.active() && (editorMenu != null || editDrag != EditDrag.NONE || linkFrom != null
                || !editSelection.isEmpty())) {
            // the server turned editing off under an open map: leave no half-finished gesture behind
            editSelection.clear();
            cancelEditGestures();
        }
        // icon, gaps and a count like "12/127" beside the longest name, within two fifths of the screen
        railOpenW = Mth.clamp(railWidest + RAIL_TEXT_X + Ui.S1 + 34 + Ui.S2, RAIL_OPEN_MIN, Math.max(RAIL_OPEN_MIN, width * 2 / 5));
        if (chapter() == null || ClientProgress.chapterHidden(chapter().id)) {
            // no overview screen any more: open on the last chapter, or the first one listed
            if (railChapters.isEmpty()) {
                Ui.textCenter(g, font, Component.translatable("screen.kuronami_quest_tree.empty"), width / 2, height / 2 - 4, Ui.DIM);
                return;
            }
            select(m.chapters.indexOf(railChapters.get(0)));
        }
        ClientModel.Chapter ch = chapter();
        boolean overRail = mouseX < (railT > 0.5f ? railOpenW : RAIL) + 8;
        // timed, not stepped per frame: the same smooth 180ms slide at any frame rate (it jumped in 4 frames before)
        float dt = lastFrame == 0 ? 0 : Math.min(100, nowMs - lastFrame);
        lastFrame = nowMs;
        railT = tourRailOpen || ClientConfig.keepRailOpen() ? 1
                : Mth.clamp(railT + (overRail || (railBox != null && railBox.isFocused()) ? dt : -dt) / RAIL_MS, 0, 1);
        sheetT = Mth.clamp(sheetT + (selected != null ? dt : -dt) / SHEET_MS, 0, 1);
        hoverGrow.replaceAll((k, t) -> Mth.clamp(t + (k.equals(hoveredNode) ? dt : -dt) / HOVER_MS, 0, 1));
        hoverGrow.values().removeIf(t -> t <= 0);

        renderChapter(g, ch, mouseX, mouseY, partialTick);
        g.pose().pushPose();
        g.pose().translate(0, 0, OVERLAY_Z + 50);
        if (sheetT > 0) {
            renderSheet(g, m, mouseX, mouseY, dt);
        }
        g.pose().popPose();
        g.pose().pushPose();
        g.pose().translate(0, 0, OVERLAY_Z + 100);
        renderRail(g, m, mouseX, mouseY, dt);
        renderRailField(g, mouseX, mouseY, partialTick);
        g.pose().popPose();
        renderEditorNotices(g);
        renderEditorMenu(g, mouseX, mouseY);
        if (pendingTip != null) {
            g.pose().pushPose();
            g.pose().translate(0, 0, OVERLAY_Z + 300);
            Ui.tooltip(g, font, pendingTip, mouseX, mouseY);
            g.pose().popPose();
        }
    }

    /**
     * The field at the top of the chapter list now filters the list itself (2026-10-20, the player: it looked like a
     * list search, so it is one). Closed, one magnifier in the icon column; open, a rounded field over the list's width,
     * its count the number of chapters left. The EditBox is placed every frame so it fits the drawn rail.
     */
    private void renderRailField(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int w = railWidth();
        int y = RAIL_TOP;
        boolean open = railT > 0.6f;
        if (!open) {
            boolean hover = mouseX >= 4 && mouseX < 4 + w && mouseY >= y && mouseY < y + SEARCH_H;
            Tiles.icon(g, "search", RAIL_ICON_X, y + (SEARCH_H - RAIL_ICON) / 2f, RAIL_ICON,
                    railFiltering() ? Ui.ACCENT : hover ? Ui.TEXT : Ui.DIM);
            // the box is hidden with the open rail, but stays where a click can reach it without jumping
            railBox.setX(RAIL_ICON_X);
            railBox.setY(y + (SEARCH_H - EDIT_H) / 2);
            railBox.setWidth(RAIL_ICON);
            railRect = new int[]{4, y, w, SEARCH_H};
            return;
        }
        int fx = 4 + Ui.S1;
        int fw = w - 2 * Ui.S1;
        Ui.surface(g, fx, y, fw, SEARCH_H, SEARCH_H / 2, Ui.OVERLAY_1);
        if (railBox.isFocused()) {
            Ui.outline(g, fx, y, fw, SEARCH_H, SEARCH_H / 2, Ui.FOCUS);
        }
        int ix = fx + Ui.S1;
        Tiles.icon(g, "search", ix, y + (SEARCH_H - RAIL_ICON) / 2f, RAIL_ICON,
                railBox.isFocused() ? Ui.TEXT : Ui.MUTED);
        String n = railFiltering() ? String.valueOf(railShown.size()) : null;
        int countW = n == null ? 0 : font.width(n) + Ui.S1;
        int ex = ix + RAIL_ICON + Ui.S1;
        railBox.setX(ex);
        railBox.setY(y + (SEARCH_H - EDIT_H) / 2);
        railBox.setWidth(Math.max(10, fx + fw - Ui.S2 - countW - ex));
        railBox.render(g, mouseX, mouseY, partialTick);
        if (n != null) {
            Ui.textRight(g, font, n, fx + fw - Ui.S2, y + (SEARCH_H - 8) / 2 + 1,
                    railShown.isEmpty() ? Ui.TEXT_ERROR : Ui.MUTED);
        }
        railRect = new int[]{fx, y, fw, SEARCH_H};
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // vanilla blur of the world behind, then our own scrim in render()
        this.renderBlurredBackground(partialTick);
    }

    /** Top of the search row: inside the rail's panel (which starts at 4) by the same inset as the rows' sides. */
    private static final int RAIL_TOP = 8;
    /** The rail's first row is the search; the chapter list starts under it. */
    private static final int SEARCH_H = 18;
    private static final int RAIL_LIST_TOP = RAIL_TOP + SEARCH_H + Ui.S1;
    /** Height of a group header row in the rail; chapter rows are {@link #RAIL_ROW}. */
    private static final int HEADER_H = 14;

    /** Two rows kept under the list: "collect all" and the gear (2026-10-20). */
    private static final int RAIL_FOOT_ROW = 18;
    private static final int RAIL_FOOT_H = 2 * RAIL_FOOT_ROW + 4;

    private int railListBottom() {
        return height - 8 - RAIL_FOOT_H;
    }

    private float railMaxScroll() {
        return Math.max(0, railContentHeight() - (railListBottom() - RAIL_LIST_TOP) + 4);
    }

    private int railContentHeight() {
        int content = 0;
        for (RailRow row : railRows) {
            content += row.isHeader() ? HEADER_H : RAIL_ROW;
        }
        return content;
    }

    /**
     * The scroll as drawn: rounded to whole screen pixels. Every row sits at a whole GUI pixel inside the list and the
     * list moves as one, so rows never round on their own. Until 2026-10-20 each row rounded its own fractional
     * position (the pitch was stretched to cut the last row in half), so the gaps changed by a pixel while scrolling and
     * the names shook (the player).
     */
    private float railScrollShown() {
        double gs = net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScale();
        return (float) (Math.round(railScroll * gs) / gs);
    }

    private void renderRail(GuiGraphics g, ClientModel m, int mouseX, int mouseY, float dt) {
        int w = railWidth();
        int bg = railT > 0.01f ? lerpColor(Ui.RAIL_CLOSED, Ui.PANEL_TRANSLUCENT, railT) : Ui.RAIL_CLOSED;
        if (GlassPanel.on()) {
            // the glass's lit rim is its edge; a hairline on top would draw it twice
            GlassPanel.render(g, GlassPanel.RAIL, GlassPanel.Kind.REGULAR, 4, 4, w, height - 8, Ui.R4, mouseX, mouseY);
        } else {
            Ui.surface(g, 4, 4, w, height - 8, Ui.R4, bg);
            Ui.outline(g, 4, 4, w, height - 8, Ui.R4, Ui.HAIRLINE);
        }
        float max = railMaxScroll();
        railScrollTarget = Mth.clamp(railScrollTarget, 0, max);
        railScroll = Mth.clamp(railScroll + (railScrollTarget - railScroll) * Math.min(1, dt / 70f), 0, max);
        float shown = railScrollShown();
        int top = RAIL_LIST_TOP;
        int bottom = railListBottom();
        g.enableScissor(4, top, 4 + w, bottom);
        g.pose().pushPose();
        g.pose().translate(0, top - shown, 0);
        int my = mouseY >= top && mouseY < bottom ? (int) Math.floor(mouseY - top + shown) : Integer.MIN_VALUE;
        int ry = 0;
        for (RailRow row : railRows) {
            int hgt = row.isHeader() ? HEADER_H : RAIL_ROW;
            if (ry - shown >= bottom - top) {
                break;
            }
            if (ry + hgt - shown > 0) {
                if (row.isHeader()) {
                    boolean hover = !searching() && !railFiltering() && mouseX >= 4 && mouseX < 4 + w && my >= ry && my < ry + HEADER_H;
                    drawRailHeader(g, ry, row.header(), w, hover, folded(row.fold()));
                } else {
                    ClientModel.Chapter c = row.chapter();
                    int[] cnt = chapterCounts.getOrDefault(c.id, new int[]{0, 0, 0});
                    String right = c.skills ? Component.translatable("screen.kuronami_quest_tree.level_short",
                            ClientProgress.progress().level.getOrDefault(ProgressRules.key(c.id, c.pool), 0)).getString() : cnt[0] + "/" + cnt[1];
                    boolean hover = mouseX >= 4 && mouseX < 4 + w && my >= ry && my < ry + RAIL_ROW;
                    if (searching()) {
                        // while searching, the rail shows where the matches are instead
                        int hits = matchCount.getOrDefault(c.id, 0);
                        rowRail(g, ry, m.chapters.indexOf(c), c.displayIcon(), c.title, w, hover, 0, String.valueOf(hits));
                    } else {
                        rowRail(g, ry, m.chapters.indexOf(c), c.displayIcon(), c.title, w, hover, badges.getOrDefault(c.id, 0),
                                !c.skills && cnt[1] > 0 && cnt[0] == cnt[1] && cnt[2] == 0 ? "" : right);
                    }
                }
            }
            ry += hgt;
        }
        g.pose().popPose();
        if (max > 0) {
            g.pose().pushPose();
            g.pose().translate(0, 0, OVERLAY_Z + 1);
            // soft edges where rows run under, and a scrollbar that is always there while the list overflows
            if (railScroll > 1) {
                g.fillGradient(4, top, 4 + w, top + 8, 0xCC000000 | (Ui.PANEL & 0xFFFFFF), Ui.PANEL & 0xFFFFFF);
            }
            if (railScroll < max - 1) {
                g.fillGradient(4, bottom - 10, 4 + w, bottom, Ui.PANEL & 0xFFFFFF, 0xCC000000 | (Ui.PANEL & 0xFFFFFF));
            }
            if (railT > 0.6f) {
                float h = bottom - top;
                float content = h + max;
                int thumb = Math.max(12, Math.round(h * h / content));
                int ty = top + Math.round((h - thumb) * (railScroll / max));
                boolean over = mouseX < 4 + w && mouseX >= 4 && mouseY >= top && mouseY < bottom;
                Ui.surface(g, 4 + w - 5, ty, 2, thumb, 1, over ? Ui.SCROLLBAR_HOVER : Ui.SCROLLBAR);
            }
            g.pose().popPose();
        }
        g.disableScissor();
        renderRailFooter(g, mouseX, mouseY);
    }

    /**
     * The two fixed rows under the chapter list: collect every claimable reward, and the client settings (2026-10-20).
     * Closed, they are icons with the same gold dot the chapters use for waiting rewards.
     */
    private void renderRailFooter(GuiGraphics g, int mouseX, int mouseY) {
        int w = railWidth();
        int left = 4 + Ui.S1;
        int rowW = w - 2 * Ui.S1;
        int top = height - 8 - RAIL_FOOT_H;
        int waiting = ClientProgress.claimableCount();
        boolean open = railT > 0.6f;

        int cy = top + 2;
        boolean collectHover = mouseX >= left && mouseX < left + rowW && mouseY >= cy && mouseY < cy + RAIL_FOOT_ROW;
        if (!open) {
            collectHover = mouseX >= 4 && mouseX < 4 + w && mouseY >= cy && mouseY < cy + RAIL_FOOT_ROW;
        }
        if (collectHover && waiting > 0) {
            Ui.surface(g, left, cy, rowW, RAIL_FOOT_ROW, RAIL_FOOT_ROW / 2, Ui.OVERLAY_1);
        }
        int giftColor = waiting > 0 ? Tiles.GOLD : Ui.DIM;
        Tiles.icon(g, "gift", RAIL_ICON_X, cy + (RAIL_FOOT_ROW - RAIL_ICON) / 2f, RAIL_ICON,
                collectHover && waiting > 0 ? Ui.TEXT : giftColor);
        if (waiting > 0) {
            Tiles.icon(g, "dot", RAIL_ICON_X + RAIL_ICON - 4, cy, 6, Tiles.GOLD);
        }
        if (open) {
            int countW = waiting > 0 ? Ui.badge(g, font, String.valueOf(waiting), left + rowW - Ui.S1, cy + (RAIL_FOOT_ROW - Ui.BADGE_H) / 2) + 4 : 0;
            Ui.text(g, font, trim(Component.translatable("screen.kuronami_quest_tree.collect_all").getString(),
                    rowW - RAIL_TEXT_X - countW), RAIL_TEXT_X, cy + (RAIL_FOOT_ROW - 8) / 2,
                    waiting > 0 ? Ui.TEXT : Ui.DIM);
        }
        if (waiting > 0) {
            buttons.add(new Button(left, cy, rowW, RAIL_FOOT_ROW, () -> {
                ClientProgress.send("claim_all", "", -1);
                ProgressFeedback.click();
            }));
            if (collectHover) {
                pendingTip = List.of(Component.translatable("screen.kuronami_quest_tree.collect_all"));
            }
        }

        int sy = cy + RAIL_FOOT_ROW + 4;
        boolean settingsHover = mouseX >= left && mouseX < left + rowW && mouseY >= sy && mouseY < sy + RAIL_FOOT_ROW;
        if (!open) {
            settingsHover = mouseX >= 4 && mouseX < 4 + w && mouseY >= sy && mouseY < sy + RAIL_FOOT_ROW;
        }
        if (settingsHover) {
            Ui.surface(g, left, sy, rowW, RAIL_FOOT_ROW, RAIL_FOOT_ROW / 2, Ui.OVERLAY_1);
        }
        Tiles.icon(g, "gear", RAIL_ICON_X, sy + (RAIL_FOOT_ROW - RAIL_ICON) / 2f, RAIL_ICON,
                settingsHover ? Ui.TEXT : Ui.DIM);
        if (open) {
            Ui.text(g, font, trim(Component.translatable("screen.kuronami_quest_tree.settings").getString(),
                    rowW - RAIL_TEXT_X), RAIL_TEXT_X, sy + (RAIL_FOOT_ROW - 8) / 2, settingsHover ? Ui.TEXT : Ui.DIM);
        }
        buttons.add(new Button(left, sy, rowW, RAIL_FOOT_ROW, () -> {
            if (minecraft != null) {
                minecraft.setScreen(new SettingsScreen(this));
            }
        }));
        if (settingsHover) {
            pendingTip = List.of(Component.translatable("screen.kuronami_quest_tree.settings"));
        }
    }

    /**
     * One rail row at list position {@code y}. {@code badge} > 0 shows a yellow count (claimable quests, points to
     * spend); otherwise {@code right} is a grey count, and an empty string means the chapter is complete (a tick). The
     * open chapter is one solid accent pill with bright text, the one mark of selection (2026-10-20, the player: the pale
     * grey box together with a blue bar on the left read cheap and said the same thing twice); hover is a faint pill.
     */
    private void rowRail(GuiGraphics g, int y, int index, ItemStack icon, Component label, int w, boolean hover, int badge, String right) {
        boolean on = index == chapterIndex;
        if (on || hover) {
            Ui.surface(g, 4 + Ui.S1, y, w - 2 * Ui.S1, RAIL_ROW, RAIL_ROW / 2, on ? Ui.ACCENT : Ui.OVERLAY_1);
        }
        g.pose().pushPose();
        g.pose().translate(RAIL_ICON_X, y + (RAIL_ROW - RAIL_ICON) / 2f, 0);
        g.pose().scale(RAIL_ICON / 16f, RAIL_ICON / 16f, 1);
        g.renderItem(icon, 0, 0);
        g.pose().popPose();
        g.pose().pushPose();
        g.pose().translate(0, 0, OVERLAY_Z);
        if (railT <= 0.6f && badge > 0) {
            // closed rail: a dot on the icon's corner
            Tiles.icon(g, "dot", RAIL_ICON_X + RAIL_ICON - 4, y, 6, Tiles.GOLD);
        }
        if (railT > 0.6f) {
            int alpha = (int) (255 * Mth.clamp((railT - 0.6f) / 0.4f, 0, 1)) << 24;
            int edge = w - Ui.S2;
            int rightW = 0;
            int ty = y + (RAIL_ROW - 8) / 2;
            if (badge > 0) {
                rightW = Ui.badge(g, font, String.valueOf(badge), edge + 2, y + (RAIL_ROW - Ui.BADGE_H) / 2) + 4;
            } else if (right != null && right.isEmpty()) {
                rightW = 12;
                Tiles.icon(g, "tick", edge - 8, y + (RAIL_ROW - 8) / 2f, 8, ((on ? Ui.BRIGHT : Tiles.GREEN_TEXT) & 0xFFFFFF) | alpha);
            } else if (right != null) {
                rightW = font.width(right) + 6;
                Ui.textRight(g, font, right, edge, ty, ((on ? Ui.BRIGHT : Ui.DIM) & 0xFFFFFF) | alpha);
            }
            Ui.text(g, font, trim(label.getString(), w - RAIL_TEXT_X - Ui.S1 - rightW), RAIL_TEXT_X, ty,
                    ((on ? Ui.BRIGHT : Ui.MUTED) & 0xFFFFFF) | alpha);
        }
        g.pose().popPose();
    }

    /**
     * A group header. Open rail: a fold mark (pointing down while open, right while folded) and the title in grey, at
     * the same fade the chapter labels use; hover is the chapter rows' faint pill, since a click folds the group.
     * Closed rail: a thin divider so the groups still read as separate blocks while only icons are shown, with the
     * fold mark on it while the group is folded, so a group whose chapters are hidden is not mistaken for an empty one.
     */
    private void drawRailHeader(GuiGraphics g, int y, Component title, int w, boolean hover, boolean shut) {
        if (railT > 0.6f) {
            int alpha = (int) (255 * Mth.clamp((railT - 0.6f) / 0.4f, 0, 1)) << 24;
            if (hover) {
                Ui.surface(g, 4 + Ui.S1, y, w - 2 * Ui.S1, HEADER_H, HEADER_H / 2, Ui.OVERLAY_1);
            }
            int color = ((hover ? Ui.TEXT : Ui.DIM) & 0xFFFFFF) | alpha;
            foldMark(g, RAIL_ICON_X + (RAIL_ICON - FOLD) / 2f, y + (HEADER_H - FOLD) / 2f, shut, color);
            String s = trim(title.getString(), w - RAIL_TEXT_X - Ui.S2);
            // the same row centre as the fold mark and as a chapter row's label: both are at y + (row - 8) / 2
            Ui.text(g, font, s, RAIL_TEXT_X, y + (HEADER_H - 8) / 2, color);
        } else {
            int my = y + HEADER_H / 2;
            if (shut) {
                g.fill(8, my, RAIL_ICON_X, my + 1, Ui.HAIRLINE);
                foldMark(g, RAIL_ICON_X + (RAIL_ICON - FOLD) / 2f, y + (HEADER_H - FOLD) / 2f, true, Ui.DIM);
                g.fill(RAIL_ICON_X + RAIL_ICON, my, 4 + w - 4, my + 1, Ui.HAIRLINE);
            } else {
                g.fill(8, my, 4 + w - 4, my + 1, Ui.HAIRLINE);
            }
        }
    }

    private static final int FOLD = 6;

    /** The fold mark ({@code icon/fold}, drawn pointing right), turned to point down while the group is open. */
    private static void foldMark(GuiGraphics g, float x, float y, boolean shut, int argb) {
        g.pose().pushPose();
        g.pose().translate(x + FOLD / 2f, y + FOLD / 2f, 0);
        if (!shut) {
            g.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(90));
        }
        Tiles.icon(g, "fold", -FOLD / 2f, -FOLD / 2f, FOLD, argb);
        g.pose().popPose();
    }

    // ------------------------------------------------------------------ map

    private float[] view(ClientModel.Chapter ch) {
        return VIEWS.computeIfAbsent(ch.id, k -> fitView(ch));
    }

    /**
     * The view a chapter opens at: the whole tree, its pictures included, in the room between the closed chapter list,
     * the header and the controls at the bottom, centred. Until 2026-10-20 it never went below FTB Quests' default
     * zoom, so a big chapter (ATM10's star, 127 quests) opened on its middle at full size and a small one at a size
     * that had nothing to do with it (the player: open at a scale that shows the tree, per chapter). Small trees still
     * stop growing at 1.5, and huge ones at {@link #FIT_MIN}, where the tiles still read.
     */
    private float[] fitView(ClientModel.Chapter ch) {
        double[] b = bounds(ch);
        float left = RAIL + 4 + Ui.S3;
        float top = Ui.S2 + BTN + Ui.S2;
        float right = width - Ui.S3;
        float bottom = height - BTN - Ui.S2 - Ui.S2;
        float availW = Math.max(40, right - left);
        float availH = Math.max(40, bottom - top);
        float fit = (float) Math.min(availW / Math.max(1, b[2] - b[0]), availH / Math.max(1, b[3] - b[1]));
        float z = Mth.clamp(fit, FIT_MIN, 1.5f);
        float cx = (float) (b[0] + b[2]) / 2f;
        float cy = (float) (b[1] + b[3]) / 2f;
        return new float[]{left + availW / 2f - cx * z, top + availH / 2f - cy * z, z};
    }

    /**
     * What a chapter covers in map units: every visible tile with its own size, and every picture the chapter shows,
     * so a fitted view and the minimap take in the whole page as its author laid it out.
     */
    private double[] bounds(ClientModel.Chapter ch) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (ClientModel.Node n : ch.nodes.values()) {
            if (!ClientProgress.visible(n.key)) {
                continue;
            }
            double half = NODE * n.size / 2.0;
            minX = Math.min(minX, n.x * UNIT - half);
            minY = Math.min(minY, n.y * UNIT - half);
            maxX = Math.max(maxX, n.x * UNIT + half);
            maxY = Math.max(maxY, n.y * UNIT + half);
        }
        for (ClientModel.Decoration d : ch.decorations) {
            if (!decorationVisible(d)) {
                continue;
            }
            minX = Math.min(minX, (d.x - d.w / 2) * UNIT);
            minY = Math.min(minY, (d.y - d.h / 2) * UNIT);
            maxX = Math.max(maxX, (d.x + d.w / 2) * UNIT);
            maxY = Math.max(maxY, (d.y + d.h / 2) * UNIT);
        }
        if (minX > maxX) {
            return new double[]{0, 0, 0, 0};
        }
        return new double[]{minX, minY, maxX, maxY};
    }

    /**
     * Line colours: one width for every state, the state in the colour only. Around the node under the mouse (or the
     * selected one) what it needs and what it opens take their own colours and the rest step back; when a way opens,
     * the colour runs in from the earlier tile. The shape of a line is {@link #drawLine}.
     */
    private static int LINE_WALKED = Tiles.GREEN;
    static int LINE_OPEN = 0xFF9C9CA3;
    static int LINE_CLOSED = 0xFF797E89;
    /** The closed line on a node under the mouse: brighter than {@link #LINE_OPEN}, which is brighter than closed. */
    static int LINE_CLOSED_ON = 0xFFA9AFB9;
    /** Into the focused node (what it needs) and out of it (what it opens): the 2026-10-16 pair, cyan and yellow. */
    static int LINE_REQUIRES = 0xFF5BC8E8;
    static int LINE_UNLOCKS = 0xFFF2C230;
    /** The dark edge on both sides of every line and arrowhead, in the ground's colour. */
    static int LINE_CASING_COLOR = 0xE6313338;
    /**
     * Belt and casing widths in map units (they grow with the zoom along with the tiles), and the least each may shrink
     * to on screen in GUI pixels, so zoomed out the chevrons still read. Widened on 2026-10-20 (the player: the belt
     * read thin, and the texture looked cheap): the casing is a hairline again now that the chevron reaches the edges.
     */
    private static final float LINE_W = 6f;
    private static final float LINE_CASING = 0.75f;
    private static final float LINE_MIN_PX = 5f;
    private static final float CASING_MIN_PX = 1f;
    /** How fast the chevrons move, in belt widths per second (only around the node in focus). */
    private static final float BELT_SPEED = 0.8f;

    /** Colours worked out from the palette, refreshed when it is read again. */
    static void paletteChanged() {
        SCRIM = Ui.GROUND;
        SINK = 0xC0000000 | (Ui.GROUND & 0xFFFFFF);
        LINE_WALKED = Tiles.GREEN;
        LINE_CASING_COLOR = 0xE6000000 | (Ui.GROUND & 0xFFFFFF);
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

    /** Pixel size of each background picture, read once per screen from its file ({0, 0} = missing or unreadable). */
    private static final Map<ResourceLocation, int[]> TEXTURE_SIZES = new HashMap<>();

    private static int[] textureSize(ResourceLocation id) {
        return TEXTURE_SIZES.computeIfAbsent(id, k -> {
            var res = net.minecraft.client.Minecraft.getInstance().getResourceManager().getResource(k);
            if (res.isEmpty()) {
                com.kuronami.questtree.Constants.LOG.warn("Chapter background {} does not exist", k);
                return new int[]{0, 0};
            }
            try (var in = res.get().open(); var img = com.mojang.blaze3d.platform.NativeImage.read(in)) {
                return new int[]{img.getWidth(), img.getHeight()};
            } catch (java.io.IOException ex) {
                com.kuronami.questtree.Constants.LOG.warn("Could not read chapter background {}: {}", k, ex.toString());
                return new int[]{0, 0};
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
            // a zoom at or below 0 would make the tiling loop below never end
            float zoom = Math.max(0.05f, 1 + (v[2] - 1) * l.speed());
            if (!l.tile()) {
                float cover = Math.max(width / (float) size[0], height / (float) size[1]) * zoom;
                float w = size[0] * cover;
                float h = size[1] * cover;
                float x = (width - w) / 2f + (v[0] - width / 2f) * l.speed() * 0.1f;
                float y = (height - h) / 2f + (v[1] - height / 2f) * l.speed() * 0.1f;
                blitScaled(g, l.texture(), x, y, w, h, size);
            } else {
                // at least 16 GUI px a picture, so a tiny size cannot turn into thousands of draws a frame
                float tw = Math.max(16, (l.size() > 0 ? l.size() : size[0]) * zoom);
                float th = Math.max(16, tw * size[1] / size[0]);
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

    private void renderChapter(GuiGraphics g, ClientModel.Chapter ch, int mouseX, int mouseY, float partialTick) {
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
        List<LineDraw> lines = new ArrayList<>();
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
                // around the node under the pointer (or the selected one): what it needs in one colour, what it opens in
                // another, everything else stepped back, so crossing and converging lines can be told apart
                int role = focus == null ? 0 : n.key.equals(focus) ? 1 : p.key.equals(focus) ? 2 : 3;
                // the way just opened: colour runs in from the earlier node, the arrowhead at its front (300ms)
                float grow = Motion.progress(Motion.UNLOCKED, p.key, Motion.LINE_GROW);
                LineDraw l = lineGeometry(p, n, lc, role, grow >= 0 ? grow : -1, v[2]);
                if (l != null) {
                    lines.add(l);
                }
            }
        }
        // casings under every core, so a line crossing another keeps a dark gap on both sides; the lines around the
        // focus go last, on top of the rest
        for (int pass = 0; pass < 2; pass++) {
            for (LineDraw l : lines) {
                if ((l.role == 1 || l.role == 2) == (pass == 1)) {
                    drawLine(g, l, true);
                }
            }
            for (LineDraw l : lines) {
                if ((l.role == 1 || l.role == 2) == (pass == 1)) {
                    drawLine(g, l, false);
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
            int x = (int) Math.round(editX(n) * UNIT) - s / 2 + shake(n.key, now);
            int y = (int) Math.round(editY(n) * UNIT) - s / 2;
            String shape = Tiles.shapeOf(n.shape);
            // the node under the mouse grows a little (120ms), so the map answers the pointer
            int grow = Math.round(s * 0.12f * ease(hoverGrow.getOrDefault(n.key, 0f)) / 2) * 2;
            if (n.key.equals(selected)) {
                // the selection ring hugs the tile at its current size, so the gap stays even while it grows and shrinks
                int rs = Tiles.drawnSize(shape, s + grow);
                int ro = (rs - s) / 2;
                Tiles.ring(g, shape, x - ro - 3, y - ro - 3, rs + 6, rs + 6, Ui.SELECTION);
            }
            if (editActive() && editSelection.contains(n.key)) {
                // the editor's own selection: the same ring shape, in the one accent the chrome uses
                int rs = Tiles.drawnSize(shape, s + grow);
                int ro = (rs - s) / 2;
                g.pose().pushPose();
                g.pose().translate(0, 0, OVERLAY_Z + 5);
                Tiles.ring(g, shape, x - ro - 3, y - ro - 3, rs + 6, rs + 6, Ui.ACCENT);
                g.pose().popPose();
            }
            if (n.optional && !far) {
                // optional quests: a faint outline that follows the tile's shape (a dashed square until 2026-10-20 sat
                // around circles and diamonds as a box and looked like a stray frame)
                int os = Tiles.drawnSize(shape, s + grow);
                int oo = (os - s) / 2;
                Tiles.ring(g, shape, x - oo - 2, y - oo - 2, os + 4, os + 4, Ui.OPTIONAL_OUTLINE);
            }
            Tiles.tile(g, shape, x - grow / 2, y - grow / 2, s + grow, ClientProgress.look(n.key), Motion.fill(n.key, ClientProgress.fraction(n.key)),
                    ClientProgress.iconFor(n), false);
            flash(g, n.key, x, y, s, now);
            resultMotion(g, n.key, shape, x, y, s);
            if (path().nodes().size() > 1 && pathSet.contains(n.key) && !n.key.equals(selected)) {
                // the unfinished requirements on the way to the selected node
                g.pose().pushPose();
                g.pose().translate(0, 0, OVERLAY_Z + 15);
                int ps = Tiles.drawnSize(shape, s);
                int po = (ps - s) / 2;
                Tiles.ring(g, shape, x - po - 3, y - po - 3, ps + 6, ps + 6, Tiles.withAlpha(Ui.SELECTION, 0.55f));
                g.pose().popPose();
            }
            if (searching() && !matchSet.contains(n.key)) {
                g.pose().pushPose();
                g.pose().translate(0, 0, OVERLAY_Z + 20);
                Tiles.shape(g, shape, x - 1, y - 1, s + 2, s + 2, SINK);
                g.pose().popPose();
            }
            double sx = v[0] + editX(n) * UNIT * v[2];
            double sy = v[1] + editY(n) * UNIT * v[2];
            if (Math.abs(mouseX - sx) < s * v[2] / 2 && Math.abs(mouseY - sy) < s * v[2] / 2 && overMap(mouseX, mouseY)) {
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
            if (Math.abs(mouseX - sx) < s * v[2] / 2 && Math.abs(mouseY - sy) < s * v[2] / 2 && overMap(mouseX, mouseY)) {
                hovered = target.key;
            }
        }
        g.pose().popPose();
        renderEditorMapOverlay(g, ch, v, mouseX, mouseY);
        if (tourHoverKey != null) {
            hovered = tourHoverKey;
        }
        hoveredNode = dragging || editDrag == EditDrag.MOVE ? null : hovered;
        if (hoveredNode != null) {
            hoverGrow.putIfAbsent(hoveredNode, 0f);
        }
        List<Component> decoTip = hovered == null && !dragging ? decorationAt(ch, v, mouseX, mouseY, true) : null;

        // everything below sits above the map's item icons. The map's depth is dropped first: a block icon on a big
        // tile is a 3D model as deep as the tile is wide, and it poked through the title row and the search field
        // (2026-10-20, ATM10). The sheet and the rail come later at their own depths, still over these.
        g.flush();
        com.mojang.blaze3d.systems.RenderSystem.clear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT, net.minecraft.client.Minecraft.ON_OSX);
        g.pose().pushPose();
        g.pose().translate(0, 0, OVERLAY_Z);
        renderHeader(g, ch, mouseX, mouseY, partialTick);
        renderMinimap(g, ch, v, mouseX, mouseY);
        renderZoomButtons(g, ch, v, mouseX, mouseY);
        g.pose().popPose();
        if (hovered != null && !dragging) {
            g.pose().pushPose();
            g.pose().translate(0, 0, OVERLAY_Z + 200);
            ClientModel.Node hn = model().nodesByKey.get(hovered);
            Ui.tooltip(g, font, nodeTooltip(hn), mouseX, mouseY);
            g.pose().popPose();
        } else if (decoTip != null && !decoTip.isEmpty()) {
            g.pose().pushPose();
            g.pose().translate(0, 0, OVERLAY_Z + 200);
            Ui.tooltip(g, font, decoTip, mouseX, mouseY);
            g.pose().popPose();
        }
    }

    /** Whether the pointer is over the map itself, not over the chapter list, the details sheet or the title row. */
    private boolean overMap(double mx, double my) {
        return mx > railRight() && mx < (sheetT > 0 ? sheetLeft() : width) && !overHeader(mx, my);
    }

    /** The title+pin and claim button areas (x, y, w, h): clicks on either must not reach the map under them. */
    private int[] titleRect = new int[4];
    private int[] claimRect = new int[4];

    private boolean overHeader(double mx, double my) {
        return inRect(titleRect, mx, my) || (claimRect[2] > 0 && inRect(claimRect, mx, my));
    }

    private static boolean inRect(int[] r, double mx, double my) {
        return mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
    }

    /** How far down the soft scrim behind the chapter title runs. */
    private static final int TITLE_SCRIM_H = 40;

    /**
     * The chapter's name and its pin, written plainly across the top (2026-10-20, the player: the glass box at the top
     * right spent its room on the done/total and hidden counts and cut the title). The title now keeps the chapter's own
     * colours and is shown in full, centred over the map's area — from the closed chapter list's edge to the screen's,
     * not the drawn rail or sheet — so opening either never moves it. A soft scrim under it keeps it readable over the
     * pack's pictures and bright tiles; it and the title sit under the rail, which is drawn later at a higher z. The pin
     * marks the chapter for the top of the list, and a magnifier beside it turns the row into the quest search (2026-10-20,
     * the player: the search belongs to the title, not to the chapter list, which now has its own filter). "Claim all"
     * stays in the top right corner while anything without a choice waits.
     */
    private void renderHeader(GuiGraphics g, ClientModel.Chapter ch, int mouseX, int mouseY, float partialTick) {
        claimRect[2] = 0;
        int pin = TITLE_BTN;
        boolean canEdit = EditorSession.allowed();
        int editBtn = canEdit ? TITLE_BTN : 0;
        int icon = 12;
        int ty = Ui.S2 + 6;
        int left = RAIL + 4;
        int avail = width - left;
        int waiting = claimable.getOrDefault(ch.id, 0);
        Component claimLabel = Component.translatable("screen.kuronami_quest_tree.claim_all");
        int bh = 20;
        int bw = waiting > 0 ? font.width(claimLabel) + Ui.S1 + font.width(String.valueOf(waiting)) + 2 * Ui.S2 + 4 : 0;
        int limitRight = width - Ui.S2 - (waiting > 0 ? bw + Ui.S1 : 0);
        int skillW = ch.skills ? Ui.S2 + skillHeaderWidth(ch) : 0;
        // the quest search takes the title's place while it is focused or has text (2026-10-20, the player: the search
        // had moved into the chapter list, which now filters itself, so it comes back up to the title row)
        boolean field = searchBox != null && (searchBox.isFocused() || !query.isBlank());
        int searchBtn = field ? 0 : TITLE_BTN;
        int maxTitleW = Math.max(0, limitRight - left - icon - Ui.S1 - Ui.S1 - editBtn - pin - searchBtn - skillW - Ui.S2);
        Component title = ch.title;
        int titleW = font.width(title);
        int contentW;
        if (field) {
            // about 160 GUI px, or all the room the row has on a narrow screen
            contentW = Math.min(SEARCH_FIELD_W, maxTitleW);
        } else {
            if (titleW > maxTitleW) {
                // last resort: a title wider than the map's room is cut, as the box used to cut it, but only then
                title = Component.literal(trim(ch.title.getString(), maxTitleW));
                titleW = font.width(title);
            }
            contentW = titleW;
        }
        int gw = icon + Ui.S1 + contentW + Ui.S1 + editBtn + pin + searchBtn + skillW;
        int x = left + (avail - gw) / 2;
        if (x + gw > limitRight) {
            x = limitRight - gw;
        }
        x = Math.max(left + Ui.S2, x);
        int tx = x + icon + Ui.S1;
        int ex = tx + contentW + Ui.S1;
        int px = ex + editBtn;
        // the magnifier sits directly right of the pin; the field already holds one at its left
        int sx = px + pin;
        int skillX = (field ? px : sx) + pin + Ui.S2;
        int py = Ui.S2;
        titleRect = new int[]{x, py, (field ? px + pin : sx + pin) - x, pin};
        // the details sheet slides over the row: whatever it covers neither lights up nor takes clicks
        int open = sheetT > 0 ? sheetLeft() : width;
        // Depth, from the caller's OVERLAY_Z (the map's tiles and overlays reach up to it): scrim +10, title and its
        // controls +20, the chapter icon ends about +35, so all of it stays under the details sheet (+50) and rail (+100)
        g.pose().pushPose();
        g.pose().translate(0, 0, 10);
        // a soft landing for the title over whatever the chapter draws at the top of the map
        g.fillGradient(0, 0, width, TITLE_SCRIM_H, 0xB0000000 | (Ui.GROUND & 0xFFFFFF), Ui.GROUND & 0xFFFFFF);
        g.pose().popPose();
        g.pose().pushPose();
        // renderItem lifts the item by 150: 35 - 150 puts the icon's front at about +35 over the caller
        g.pose().translate(x, ty - 2, -115);
        g.pose().scale(0.75f, 0.75f, 1);
        g.renderItem(ch.displayIcon(), 0, 0);
        g.pose().popPose();
        g.pose().pushPose();
        g.pose().translate(0, 0, 20);
        if (field) {
            renderQuestSearch(g, tx, py, contentW, pin, mouseX, mouseY, partialTick);
            searchRect = new int[]{tx, py, contentW, pin};
        } else {
            // the component itself, shadowed: FTB Quests titles carry their own colours
            g.drawString(font, title, tx, ty, Ui.BRIGHT, true);
            boolean sover = mouseX >= sx && mouseX < sx + pin && mouseY >= py && mouseY < py + pin && mouseX < open;
            if (sover) {
                Ui.surface(g, sx, py, pin, pin, pin / 2, Ui.OVERLAY_1);
                pendingTip = List.of(Component.translatable("screen.kuronami_quest_tree.search"));
            }
            Tiles.icon(g, "search", sx + (pin - TITLE_GLYPH) / 2f, py + (pin - TITLE_GLYPH) / 2f, TITLE_GLYPH,
                    sover ? Ui.TEXT : Ui.DIM);
            if (sx + pin <= open) {
                buttons.add(new Button(sx, py, pin, pin, this::focusSearch));
            }
            searchRect = new int[]{sx, py, pin, pin};
        }
        if (canEdit) {
            // a quiet gear beside the pin: dim until editing is on, then the one accent; no new texture
            boolean on = EditorSession.active();
            boolean eover = mouseX >= ex && mouseX < ex + pin && mouseY >= py && mouseY < py + pin && mouseX < open;
            if (on || eover) {
                Ui.surface(g, ex, py, pin, pin, pin / 2, on ? Ui.OVERLAY_2 : Ui.OVERLAY_1);
            }
            if (eover) {
                pendingTip = List.of(Component.translatable(on ? "screen.kuronami_quest_tree.editing.on"
                        : "screen.kuronami_quest_tree.editing.off"));
            }
            Tiles.icon(g, "gear", ex + (pin - TITLE_GLYPH) / 2f, py + (pin - TITLE_GLYPH) / 2f, TITLE_GLYPH,
                    on ? Ui.ACCENT : eover ? Ui.TEXT : Ui.DIM);
            if (ex + pin <= open) {
                buttons.add(new Button(ex, py, pin, pin, this::toggleEditor));
            }
        }
        if (ch.skills) {
            renderSkillHeader(g, ch, skillX, ty);
        }
        // the pin: gold while the chapter is pinned to the top of the list
        boolean pinned = ClientConfig.railPinned(ch.id);
        boolean over = mouseX >= px && mouseX < px + pin && mouseY >= py && mouseY < py + pin && mouseX < open;
        if (over) {
            Ui.surface(g, px, py, pin, pin, pin / 2, Ui.OVERLAY_1);
            pendingTip = List.of(Component.translatable(pinned ? "screen.kuronami_quest_tree.pinned" : "screen.kuronami_quest_tree.pin"));
        }
        Tiles.icon(g, "pin", px + (pin - TITLE_GLYPH) / 2f, py + (pin - TITLE_GLYPH) / 2f, TITLE_GLYPH,
                pinned ? Tiles.GOLD : over ? Ui.TEXT : Ui.DIM);
        if (px + pin <= open) {
            buttons.add(new Button(px, py, pin, pin, () -> {
                ClientConfig.toggleRailPin(ch.id);
                ProgressFeedback.click();
            }));
        }
        if (waiting > 0) {
            // this chapter's rewards only: the count and the claim are the chapter's
            String n = String.valueOf(waiting);
            int bx = width - Ui.S2 - bw;
            int by = Ui.S2;
            boolean bo = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + bh && mouseX < open;
            Ui.surface(g, bx, by, bw, bh, bh / 2, bo ? lerpColor(Tiles.GOLD, 0xFFFFFFFF, 0.25f) : Tiles.GOLD);
            Ui.text(g, font, claimLabel, bx + Ui.S2, by + (bh - 8) / 2, Tiles.INK);
            Ui.text(g, font, n, bx + bw - Ui.S2 - font.width(n), by + (bh - 8) / 2, Tiles.INK);
            Runnable claim = () -> {
                ClientProgress.send("claim_chapter", ch.id, -1);
                long start = Util.getMillis();
                int i = 0;
                for (ClientModel.Node node : ch.nodes.values()) {
                    var pn = ClientProgress.rules() == null ? null : ClientProgress.rules().model().nodes.get(node.key);
                    if (ClientProgress.state(node.key) == State.CLAIM && !node.needsChoice()
                            && (pn == null || !com.kuronami.questtree.data.ProgressModel.excludedFromClaimAll(pn))) {
                        // one tile after another, 140ms apart
                        flashes.put(node.key, start + 140L * i++);
                    }
                }
            };
            if (bx + bw <= open) {
                buttons.add(new Button(bx, by, bw, bh, claim));
            }
            claimRect = new int[]{bx, by, bw, bh};
        }
        g.pose().popPose();
    }

    /** Give the quest search the keyboard, and put the chapter list's field away. */
    private void focusSearch() {
        if (searchBox != null) {
            setFocused(searchBox);
            searchBox.setFocused(true);
            if (railBox != null) {
                railBox.setFocused(false);
            }
        }
    }

    /**
     * The quest search in the title row: a rounded field with a magnifier at its left, the EditBox inside it and its
     * match count right-aligned ("n", or "cursor/n" after Enter has walked the matches). The title is hidden while it
     * shows; it takes the title's place and depth, so nothing about the map or the panels moves.
     */
    private void renderQuestSearch(GuiGraphics g, int x, int y, int w, int h, int mouseX, int mouseY, float partialTick) {
        if (w <= 0) {
            return;
        }
        // on the map there is nothing under it but tiles: the same glass as the other controls, so the map does not
        // show through the text (the faint overlay used inside the rail let tile icons read through, 2026-10-20)
        if (GlassPanel.on()) {
            GlassPanel.render(g, GlassPanel.SEARCH, GlassPanel.Kind.REGULAR, x, y, w, h, h / 2, mouseX, mouseY);
        } else {
            Ui.surface(g, x, y, w, h, h / 2, Ui.PANEL_TRANSLUCENT);
        }
        if (searchBox.isFocused()) {
            Ui.outline(g, x, y, w, h, h / 2, Ui.FOCUS);
        }
        int ix = x + Ui.S1;
        Tiles.icon(g, "search", ix, y + (h - TITLE_GLYPH) / 2f, TITLE_GLYPH,
                searchBox.isFocused() ? Ui.TEXT : Ui.MUTED);
        String n = searching()
                ? (matches.isEmpty() ? "0" : (matchCursor >= 0 ? (matchCursor + 1) + "/" : "") + matches.size()) : null;
        int countW = n == null ? 0 : font.width(n) + Ui.S1;
        int ex = ix + TITLE_GLYPH + Ui.S1;
        searchBox.setX(ex);
        searchBox.setY(y + (h - EDIT_H) / 2);
        searchBox.setWidth(Math.max(10, x + w - Ui.S2 - countW - ex));
        searchBox.render(g, mouseX, mouseY, partialTick);
        if (n != null) {
            Ui.textRight(g, font, n, x + w - Ui.S2, y + (h - 8) / 2 + 1,
                    matches.isEmpty() ? Ui.TEXT_ERROR : Ui.MUTED);
        }
    }

    private int skillHeaderWidth(ClientModel.Chapter ch) {
        String lv = Component.translatable("screen.kuronami_quest_tree.level_short", ClientProgress.progress().level.getOrDefault(ProgressRules.key(ch.id, ch.pool), 0)).getString();
        int pts = ClientProgress.points(ch.id);
        return font.width(lv) + Ui.S1 + 60 + (pts > 0 ? 8 + font.width(pointsLabel(pts)) : 0);
    }

    /** Level, experience bar and points to spend, under a skill chapter's title. */
    private void renderSkillHeader(GuiGraphics g, ClientModel.Chapter ch, int x, int y) {
        PlayerProgress p = ClientProgress.progress();
        String key = ProgressRules.key(ch.id, ch.pool);
        int level = p.level.getOrDefault(key, 0);
        String lv = Component.translatable("screen.kuronami_quest_tree.level_short", level).getString();
        Ui.text(g, font, lv, x, y, Ui.TEXT);
        int bx = x + font.width(lv) + Ui.S1;
        var rules = ClientProgress.rules();
        var mc = rules == null ? null : rules.model().chapters.get(ch.id);
        double need = mc == null ? 0 : rules.experienceForLevel(mc, level);
        double frac = need <= 0 ? 0 : Mth.clamp(p.xp.getOrDefault(key, 0.0) / need, 0, 1);
        Ui.surface(g, bx, y + 2, 60, 4, 2, Ui.OVERLAY_2);
        Ui.surface(g, bx, y + 2, frac <= 0 ? 0 : Math.max(1, (int) (60 * frac)), 4, 2, Tiles.GREEN_TEXT);
        int pts = ClientProgress.points(ch.id);
        if (pts > 0) {
            Component t = pointsLabel(pts);
            Ui.text(g, font, t, bx + 68, y, Tiles.GOLD);
        }
    }

    private static Component pointsLabel(int n) {
        return Component.translatable(n == 1 ? "screen.kuronami_quest_tree.points_one" : "screen.kuronami_quest_tree.points", n);
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
        if (GlassPanel.on()) {
            GlassPanel.render(g, GlassPanel.SHEET, GlassPanel.Kind.REGULAR, x0, Ui.S1, pw, height - 2 * Ui.S1, Ui.R4, mouseX, mouseY);
        } else {
            Ui.floating(g, x0, Ui.S1, pw, height - 2 * Ui.S1, Ui.PANEL_TRANSLUCENT);
        }
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
        if (n.repeatable) {
            int done = ClientProgress.completions(n.key);
            if (done >= 1) {
                // compact repeat count, under the subtitle: a repeated quest must not look untouched (2026-10-20)
                y += 2;
                Ui.text(g, font, Component.translatable("screen.kuronami_quest_tree.times_done", done), x, y, Ui.DIM);
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
                ClientModel.Chapter reqChapter = m.chapterOf.get(r);
                if (!ClientProgress.visible(r) || reqChapter != null && ClientProgress.chapterHidden(reqChapter.id)) {
                    // a hidden requirement (or one in a hidden chapter): counted, never named
                    Tiles.tile(g, "square", x, y, 16, State.LOCKED, 0, ItemStack.EMPTY, false);
                    g.pose().pushPose();
                    g.pose().translate(0, 0, OVERLAY_Z);
                    Ui.text(g, font, "???", x + 22, y + 4, Ui.DIM);
                    g.pose().popPose();
                    y += 20;
                    continue;
                }
                Tiles.tile(g, p.shape, x, y, 16, ClientProgress.look(p.key), ClientProgress.fraction(p.key), ClientProgress.iconFor(p), false);
                g.pose().pushPose();
                g.pose().translate(0, 0, OVERLAY_Z);
                ClientModel.Chapter other = reqChapter;
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
        if (details) {
            // the quests this one opens in the same book; hidden ones are left out completely, so the section is not
            // a hint about quests the player may not see yet (2026-10-20)
            List<ClientModel.Node> leads = m.dependants.getOrDefault(n.key, List.of()).stream()
                    .filter(d -> {
                        ClientModel.Chapter dc = m.chapterOf.get(d.key);
                        return dc != null && dc.namespace.equals(ch.namespace)
                                && !ClientProgress.chapterHidden(dc.id) && ClientProgress.visible(d.key);
                    })
                    .toList();
            if (!leads.isEmpty()) {
                y += 8;
                Ui.text(g, font, Component.translatable("screen.kuronami_quest_tree.leads_to"), x, y, Ui.DIM);
                y += 12;
                for (ClientModel.Node d : leads) {
                    Tiles.tile(g, d.shape, x, y, 16, ClientProgress.look(d.key), ClientProgress.fraction(d.key), ClientProgress.iconFor(d), false);
                    g.pose().pushPose();
                    g.pose().translate(0, 0, OVERLAY_Z);
                    ClientModel.Chapter other = m.chapterOf.get(d.key);
                    if (other != ch) {
                        // the same row as a requirement in another chapter: its chapter name above the title
                        Ui.text(g, font, trim(d.title.getString(), w - 22), x + 22, y, Ui.TEXT);
                        Ui.text(g, font, trim(other.title.getString(), w - 22), x + 22, y + 9, Ui.DIM);
                    } else {
                        Ui.text(g, font, trim(d.title.getString(), w - 22), x + 22, y + 4, Ui.TEXT);
                    }
                    g.pose().popPose();
                    String key = d.key;
                    // only a row still inside the sheet may catch a click (a scrolled-away row must not)
                    if (y + 18 > contentTop && y < contentBottom) {
                        buttons.add(new Button(x, y, w, 18, () -> goTo(key)));
                    }
                    y += 20;
                }
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
                boolean open = met || ClientProgress.taskOpen(n.key, i);
                Ui.divider(g, x, y - 2, w);
                ItemStack shownItem = t.shown();
                if (!shownItem.isEmpty() && lastMouseX >= x && lastMouseX < x + 16 && lastMouseY >= y + 2 && lastMouseY < y + 18) {
                    hoverStack = shownItem;
                    RecipeViewer viewer = RecipeViewer.Holder.get();
                    if (viewer != null) {
                        ItemStack it = shownItem;
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
                if (!open) {
                    // a task waiting for the one before it (require_sequential_tasks): the lock mark says why it is dim
                    g.pose().pushPose();
                    g.pose().translate(0, 0, OVERLAY_Z);
                    Tiles.icon(g, "lock", x + 9, y + 9, 7, Ui.MUTED);
                    g.pose().popPose();
                }
                g.pose().pushPose();
                g.pose().translate(0, 0, OVERLAY_Z);
                String cnt = (met ? t.count : have) + "/" + t.count;
                int right = x + w;
                if (open && !met && working && (t.consume || t.type.equals("checkmark")) && y + 20 > contentTop && y < contentBottom) {
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
                        met || t.optional || !open ? Ui.DIM : Ui.TEXT);
                g.pose().popPose();
                y += 20;
            }
        }
        if ((!n.rewards.isEmpty() || !n.rewardLines.isEmpty()) && details) {
            y += 8;
            Ui.text(g, font, Component.translatable("screen.kuronami_quest_tree.rewards"), x, y, Ui.DIM);
            y += 12;
            int rx = x;
            for (int i = 0; i < n.rewards.size(); i++) {
                ItemStack r = n.rewards.get(i);
                int index = i < n.rewardIndices.size() ? n.rewardIndices.get(i) : i;
                boolean taken = ClientProgress.rewardClaimed(n.key, index);
                boolean canTake = state == State.CLAIM && !taken
                        && (!n.needsChoice() || choicePick >= 0);
                slot(g, r, rx, y, false, taken);
                if (canTake && y + 22 > contentTop && y < contentBottom) {
                    buttons.add(new Button(rx, y, 22, 22, () -> {
                        ClientProgress.send("claim_reward", n.key, index, n.needsChoice() ? choicePick : -1);
                        flashes.put(n.key, Util.getMillis());
                    }));
                }
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
            boolean ready = !n.needsChoice() || choicePick >= 0;
            Component claim = Component.translatable("screen.kuronami_quest_tree.claim");
            int bw = font.width(claim) + 2 * Ui.S2;
            button(g, claim, bx, y, bw, Tiles.GOLD, ready, mouseX, mouseY, () -> {
                ClientProgress.send("claim", n.key, n.needsChoice() ? choicePick : -1);
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
                pendingTip = List.of(Component.translatable(pinned ? "screen.kuronami_quest_tree.pinned" : "screen.kuronami_quest_tree.pin"));
            }
        }
    }

    private void slot(GuiGraphics g, ItemStack r, int x, int y, boolean picked) {
        slot(g, r, x, y, picked, false);
    }

    private void slot(GuiGraphics g, ItemStack r, int x, int y, boolean picked, boolean taken) {
        if (lastMouseX >= x && lastMouseX < x + 22 && lastMouseY >= y && lastMouseY < y + 22) {
            hoverStack = r;
        }
        Ui.surface(g, x, y, 22, 22, Ui.R2, picked ? Ui.OVERLAY_3 : Ui.OVERLAY_1);
        if (picked) {
            Ui.outline(g, x, y, 22, 22, Ui.R2, Ui.SELECTION);
        }
        g.renderItem(r, x + 3, y + 3);
        g.renderItemDecorations(font, r, x + 3, y + 3);
        if (taken) {
            // dimmed with a tick: this reward is already in the player's hands
            Ui.surface(g, x, y, 22, 22, Ui.R2, 0x99000000);
            Tiles.icon(g, "tick", x + 13, y + 13, 9, Tiles.GREEN_TEXT);
        }
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
        if (GlassPanel.on()) {
            GlassPanel.render(g, GlassPanel.ZOOM, GlassPanel.Kind.REGULAR, x, y, 3 * BTN, BTN, Ui.R2, mouseX, mouseY);
        } else {
            Ui.surface(g, x, y, 3 * BTN, BTN, Ui.R2, Ui.PANEL_TRANSLUCENT);
            Ui.outline(g, x, y, 3 * BTN, BTN, Ui.R2, Ui.HAIRLINE);
        }
        for (int i = 0; i < 3; i++) {
            int cx = x + i * BTN;
            if (i > 0) {
                g.fill(cx, y + 7, cx + 1, y + BTN - 7, Ui.DIVIDER);
            }
            boolean hover = mouseX >= cx && mouseX < cx + BTN && mouseY >= y && mouseY < y + BTN;
            if (hover) {
                // a round light inside the cell, clear of the dividers and the bar's rounded ends (until 2026-10-20 a
                // square strip that cut across the bar's corners, the player)
                Ui.surface(g, cx + 4, y + 4, BTN - 8, BTN - 8, (BTN - 8) / 2, Ui.OVERLAY_1);
            }
            Tiles.icon(g, glyphs[i], cx + (BTN - 12) / 2, y + (BTN - 12) / 2, 12, Ui.TEXT);
            int which = i;
            buttons.add(new Button(cx, y, BTN, BTN, () -> zoomButton(ch, v, which)));
        }
    }

    /** Minimap only when the chapter does not fit on screen at the current zoom. */
    private int[] minimapRect(ClientModel.Chapter ch, float[] v) {
        double[] b = bounds(ch);
        double cw = (b[2] - b[0]) * v[2];
        double chh = (b[3] - b[1]) * v[2];
        if (cw <= width - RAIL - 24 && chh <= height - 40) {
            return null;
        }
        // the chapter's own proportions inside 100 x 64 at most
        double bw = Math.max(1, b[2] - b[0]);
        double bh = Math.max(1, b[3] - b[1]);
        double k = Math.min((MINIMAP_W - 12) / bw, (MINIMAP_H - 12) / bh);
        int mw = (int) Math.ceil(bw * k) + 12;
        int mh = (int) Math.ceil(bh * k) + 12;
        int right = (sheetT > 0 ? sheetLeft() : width) - Ui.S2;
        return new int[]{right - mw, height - BTN - Ui.S2 - Ui.S1 - mh, mw, mh};
    }

    private static final int MINIMAP_W = 100;
    private static final int MINIMAP_H = 64;

    /**
     * A plan of the chapter: every quest in its own shape and state's colour, joined by thin lines, and the part on
     * screen as a frame (2026-10-20 rebuild, the player: zoomed in it read badly, squares overlapping oddly; make the
     * shapes all the same and add the lines). The shapes came back on 2026-10-20 too (the player: one round dot for
     * every quest hid the shape that tells them apart on the map). It sits on the regular glass: through the clear
     * glass the bent map behind showed between the tiles and read as more squares.
     */
    private void renderMinimap(GuiGraphics g, ClientModel.Chapter ch, float[] v, int mouseX, int mouseY) {
        int[] r = minimapRect(ch, v);
        // shown while the map moves (drag, zoom) and for a moment after, then gone: it covered large maps all the time
        if (r == null || !minimapShown()) {
            return;
        }
        if (GlassPanel.on()) {
            GlassPanel.render(g, GlassPanel.MINIMAP, GlassPanel.Kind.REGULAR, r[0], r[1], r[2], r[3], Ui.R3, mouseX, mouseY);
        } else {
            Ui.floating(g, r[0], r[1], r[2], r[3], Ui.PANEL_TRANSLUCENT);
        }
        double[] b = bounds(ch);
        float k = (float) Math.min((r[2] - 12) / Math.max(1, b[2] - b[0]), (r[3] - 12) / Math.max(1, b[3] - b[1]));
        float ox = r[0] + 6 - (float) b[0] * k;
        float oy = r[1] + 6 - (float) b[1] * k;
        ClientModel cm = model();
        g.pose().pushPose();
        g.pose().translate(ox, oy, 0);
        for (ClientModel.Node n : ch.nodes.values()) {
            if (ch.hideLines || n.hideLinesToRequired || !ClientProgress.visible(n.key)) {
                continue;
            }
            for (String req : n.requireKeys) {
                ClientModel.Node p = cm.nodesByKey.get(req);
                if (p == null || cm.chapterOf.get(req) != ch || p.hideLinesToDependents || !ClientProgress.visible(p.key)) {
                    continue;
                }
                float x1 = (float) (p.x * UNIT) * k;
                float y1 = (float) (p.y * UNIT) * k;
                float dx = (float) (n.x * UNIT) * k - x1;
                float dy = (float) (n.y * UNIT) * k - y1;
                g.pose().pushPose();
                g.pose().translate(x1, y1, 0);
                g.pose().mulPose(Axis.ZP.rotation((float) Math.atan2(dy, dx)));
                fillLine(g, 0, (float) Math.sqrt(dx * dx + dy * dy), 0.75f, Tiles.withAlpha(LINE_CLOSED, 0.8f));
                g.pose().popPose();
            }
        }
        for (ClientModel.Node n : ch.nodes.values()) {
            if (!ClientProgress.visible(n.key)) {
                continue;
            }
            String shape = Tiles.shapeOf(n.shape);
            // a little smaller than true scale, so tiles that sit close on the map stay apart here instead of merging
            float d = Math.max(2.5f, Tiles.drawnSize(shape, (int) Math.round(NODE * n.size)) * k * 0.75f);
            float cx = (float) (n.x * UNIT) * k;
            float cy = (float) (n.y * UNIT) * k;
            g.pose().pushPose();
            g.pose().translate(cx - d / 2, cy - d / 2, 0);
            g.pose().scale(d / 16f, d / 16f, 1);
            Tiles.shape(g, shape, 0, 0, 16, 16, minimapColor(ClientProgress.state(n.key)));
            g.pose().popPose();
        }
        g.pose().popPose();
        // the part of the chapter that is on screen
        double vx0 = (-v[0]) / v[2];
        double vy0 = (-v[1]) / v[2];
        double vx1 = vx0 + width / v[2];
        double vy1 = vy0 + height / v[2];
        int x0 = Mth.clamp((int) Math.floor(ox + vx0 * k), r[0] + 2, r[0] + r[2] - 2);
        int y0 = Mth.clamp((int) Math.floor(oy + vy0 * k), r[1] + 2, r[1] + r[3] - 2);
        int x1 = Mth.clamp((int) Math.ceil(ox + vx1 * k), r[0] + 2, r[0] + r[2] - 2);
        int y1 = Mth.clamp((int) Math.ceil(oy + vy1 * k), r[1] + 2, r[1] + r[3] - 2);
        Ui.outline(g, x0, y0, Math.max(3, x1 - x0), Math.max(3, y1 - y0), 2, Ui.MINIMAP_VIEW);
    }

    private static int minimapColor(State s) {
        return switch (s) {
            case DONE -> Tiles.GREEN;
            case CLAIM -> Tiles.GOLD;
            case OPEN, PROGRESS -> Ui.MINIMAP_OPEN;
            default -> Tiles.TILE_LOCKED;
        };
    }

    // ------------------------------------------------------------------ editing (phase 2)

    private boolean editActive() {
        return EditorSession.active() && chapter() != null;
    }

    /** A node's drawn position: the optimistic one while its move waits for the server, else the synced one. */
    private double editX(ClientModel.Node n) {
        double[] p = EditorSession.optimistic().get(n.key);
        return p == null ? n.x : p[0];
    }

    private double editY(ClientModel.Node n) {
        double[] p = EditorSession.optimistic().get(n.key);
        return p == null ? n.y : p[1];
    }

    /** The gear beside the pin: E or a click turns the map into the editor and back. */
    private void toggleEditor() {
        if (!EditorSession.allowed()) {
            return;
        }
        EditorSession.toggle();
        selected = null;
        editSelection.clear();
        cancelEditGestures();
        ProgressFeedback.click();
    }

    private ClientModel.Node nodeAt(ClientModel.Chapter ch, float[] v, double mx, double my) {
        ClientModel.Node hit = null;
        for (ClientModel.Node n : ch.nodes.values()) {
            if (!ClientProgress.visible(n.key)) {
                continue;
            }
            double sx = v[0] + editX(n) * UNIT * v[2];
            double sy = v[1] + editY(n) * UNIT * v[2];
            double half = NODE * n.size * v[2] / 2;
            if (Math.abs(mx - sx) <= half && Math.abs(my - sy) <= half) {
                hit = n;
            }
        }
        return hit;
    }

    /** A requirement line under the pointer: the dependent, the raw reference, and the required node's key. */
    private record Edge(String from, String ref, String toKey) {
    }

    private Edge edgeAt(ClientModel.Chapter ch, float[] v, double mx, double my) {
        ClientModel cm = model();
        if (cm == null) {
            return null;
        }
        Edge best = null;
        double bestD = 6;
        for (ClientModel.Node n : ch.nodes.values()) {
            if (!ClientProgress.visible(n.key)) {
                continue;
            }
            for (int i = 0; i < n.requireKeys.size(); i++) {
                String key = n.requireKeys.get(i);
                ClientModel.Node p = cm.nodesByKey.get(key);
                if (p == null || cm.chapterOf.get(key) != ch || !ClientProgress.visible(key)) {
                    continue;
                }
                double x1 = v[0] + editX(p) * UNIT * v[2];
                double y1 = v[1] + editY(p) * UNIT * v[2];
                double x2 = v[0] + editX(n) * UNIT * v[2];
                double y2 = v[1] + editY(n) * UNIT * v[2];
                double d = segmentDistance(mx, my, x1, y1, x2, y2);
                if (d < bestD) {
                    bestD = d;
                    best = new Edge(n.id, n.requires.get(i), key);
                }
            }
        }
        return best;
    }

    private static double segmentDistance(double px, double py, double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double len = dx * dx + dy * dy;
        if (len < 1e-6) {
            return Math.hypot(px - x1, py - y1);
        }
        double t = Math.max(0, Math.min(1, ((px - x1) * dx + (py - y1) * dy) / len));
        return Math.hypot(px - (x1 + t * dx), py - (y1 + t * dy));
    }

    private double mapX(float[] v, double mx) {
        return (mx - v[0]) / (UNIT * v[2]);
    }

    private double mapY(float[] v, double my) {
        return (my - v[1]) / (UNIT * v[2]);
    }

    private void cancelEditGestures() {
        if (editDrag == EditDrag.MOVE && editOriginal != null) {
            // a drag that never reached the server: the nodes go back to where they were
            EditorSession.optimistic().putAll(editOriginal);
        }
        editPressedKey = null;
        editDrag = EditDrag.NONE;
        editOriginal = null;
        editMoved = false;
        linkFrom = null;
        linkArmed = false;
        editorMenu = null;
    }

    private void openEditorMenu(double mx, double my, List<EditorMenuEntry> entries) {
        if (entries.isEmpty()) {
            editorMenu = null;
            return;
        }
        int w = 120;
        for (EditorMenuEntry e : entries) {
            w = Math.max(w, font.width(e.label()) + 2 * Ui.S2);
        }
        w = Math.min(w, width - 8);
        int h = entries.size() * MENU_ROW + 2 * Ui.S1;
        int x = Mth.clamp((int) mx, 4, Math.max(4, width - w - 4));
        int y = Mth.clamp((int) my, 4, Math.max(4, height - h - 4));
        editorMenu = new EditorMenu(x, y, w, entries);
    }

    private boolean editorMenuClick(double mx, double my) {
        EditorMenu menu = editorMenu;
        if (menu == null) {
            return false;
        }
        editorMenu = null;
        for (int i = 0; i < menu.entries.size(); i++) {
            int top = menu.y + Ui.S1 + i * MENU_ROW;
            if (mx >= menu.x && mx < menu.x + menu.w && my >= top && my < top + MENU_ROW) {
                EditorMenuEntry e = menu.entries.get(i);
                if (e.enabled()) {
                    e.run().run();
                }
                return true;
            }
        }
        return true;
    }

    private void renderEditorMenu(GuiGraphics g, int mouseX, int mouseY) {
        EditorMenu menu = editorMenu;
        if (menu == null) {
            return;
        }
        g.pose().pushPose();
        g.pose().translate(0, 0, OVERLAY_Z + 320);
        Ui.floating(g, menu.x, menu.y, menu.w, menu.h, Ui.FLOATING);
        Ui.outline(g, menu.x, menu.y, menu.w, menu.h, Ui.R2, Ui.HAIRLINE);
        for (int i = 0; i < menu.entries.size(); i++) {
            EditorMenuEntry e = menu.entries.get(i);
            int top = menu.y + Ui.S1 + i * MENU_ROW;
            boolean over = mouseX >= menu.x && mouseX < menu.x + menu.w && mouseY >= top && mouseY < top + MENU_ROW;
            if (over && e.enabled()) {
                Ui.surface(g, menu.x + 2, top, menu.w - 4, MENU_ROW, Ui.R1, Ui.OVERLAY_1);
            }
            Ui.text(g, font, e.label(), menu.x + Ui.S2, top + (MENU_ROW - 8) / 2, e.enabled() ? Ui.TEXT : Ui.DIM);
        }
        g.pose().popPose();
    }

    private void renderEditorNotices(GuiGraphics g) {
        List<EditorSession.Notice> notices = EditorSession.notices();
        if (notices.isEmpty()) {
            return;
        }
        long now = Util.getMillis();
        int shown = Math.min(4, notices.size());
        int y = height - 28 - shown * 16;
        g.pose().pushPose();
        g.pose().translate(0, 0, OVERLAY_Z + 280);
        for (int i = notices.size() - shown; i < notices.size(); i++) {
            EditorSession.Notice n = notices.get(i);
            long left = EDIT_NOTICE_MS - (now - n.at());
            float alpha = left < 700 ? Math.max(0, left / 700f) : 1f;
            int w = font.width(n.text()) + 2 * Ui.S2;
            int x = (width - w) / 2;
            Ui.surface(g, x, y, w, 14, 7, Tiles.withAlpha(Ui.FLOATING, alpha * 0.95f));
            Ui.text(g, font, n.text(), x + Ui.S2, y + 3, Tiles.withAlpha(n.error() ? Ui.TEXT_ERROR : Ui.TEXT, alpha));
            y += 16;
        }
        g.pose().popPose();
    }

    private void renderEditorMapOverlay(GuiGraphics g, ClientModel.Chapter ch, float[] v, int mouseX, int mouseY) {
        if (editDrag == EditDrag.BOX) {
            double x0 = v[0] + Math.min(editBoxX0, editBoxX1) * UNIT * v[2];
            double y0 = v[1] + Math.min(editBoxY0, editBoxY1) * UNIT * v[2];
            double x1 = v[0] + Math.max(editBoxX0, editBoxX1) * UNIT * v[2];
            double y1 = v[1] + Math.max(editBoxY0, editBoxY1) * UNIT * v[2];
            g.fill((int) x0, (int) y0, (int) Math.ceil(x1), (int) Math.ceil(y1), 0x335865F2);
            Ui.outline(g, (int) x0, (int) y0, Math.max(1, (int) Math.ceil(x1 - x0)),
                    Math.max(1, (int) Math.ceil(y1 - y0)), 0, Ui.ACCENT);
        }
        if (linkFrom != null) {
            ClientModel.Node source = model() == null ? null : model().nodesByKey.get(linkFrom);
            if (source != null) {
                float x1 = (float) (v[0] + editX(source) * UNIT * v[2]);
                float y1 = (float) (v[1] + editY(source) * UNIT * v[2]);
                g.pose().pushPose();
                g.pose().translate(0, 0, OVERLAY_Z + 30);
                drawLine(g, new LineDraw(x1, y1, mouseX, mouseY, 2.5f, 5f, Ui.ACCENT, 0, -1), true);
                drawLine(g, new LineDraw(x1, y1, mouseX, mouseY, 2.5f, 5f, Ui.ACCENT, 0, -1), false);
                g.pose().popPose();
            }
        }
    }

    private void newQuestAt(ClientModel.Chapter ch, double x, double y) {
        JsonObject node = new JsonObject();
        node.addProperty("x", GridSnap.snap(x));
        node.addProperty("y", GridSnap.snap(y));
        JsonObject args = new JsonObject();
        args.add("node", node);
        EditorSession.send(ch.id, "create_node", args);
    }

    private void editorEmptyMenu(ClientModel.Chapter ch, float[] v, double mx, double my) {
        List<EditorMenuEntry> entries = new ArrayList<>();
        double x = GridSnap.snap(mapX(v, mx));
        double y = GridSnap.snap(mapY(v, my));
        entries.add(new EditorMenuEntry(Component.translatable("screen.kuronami_quest_tree.editor.new"), true,
                () -> newQuestAt(ch, x, y)));
        EditorClipboard copy = EditorSession.clipboard();
        entries.add(new EditorMenuEntry(Component.translatable("screen.kuronami_quest_tree.editor.paste"),
                copy != null && !copy.isEmpty(), () -> EditorSession.paste(ch.id, copy, x, y, true)));
        entries.add(new EditorMenuEntry(Component.translatable("screen.kuronami_quest_tree.editor.chapter.title"), true,
                () -> openChapterEditor(ch)));
        entries.add(new EditorMenuEntry(Component.translatable("screen.kuronami_quest_tree.editor.chapter.new"), true,
                () -> openNewChapter(ch)));
        entries.add(new EditorMenuEntry(Component.translatable("screen.kuronami_quest_tree.editor.deleted.title"), true,
                this::openDeleted));
        entries.add(new EditorMenuEntry(Component.translatable("screen.kuronami_quest_tree.editor.export"), true,
                () -> exportChapter(ch)));
        entries.add(new EditorMenuEntry(Component.translatable("screen.kuronami_quest_tree.editor.test"), true,
                () -> confirmTest(ch)));
        openEditorMenu(mx, my, entries);
    }

    private void openNodeEditor(ClientModel.Chapter ch, ClientModel.Node n) {
        EditorSession.whenIdle(() -> {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.screen == this) {
                mc.setScreen(new NodeEditScreen(this, ch.id, n.id));
            }
        });
    }

    private void openChapterEditor(ClientModel.Chapter ch) {
        EditorSession.whenIdle(() -> {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.screen == this) {
                mc.setScreen(new ChapterEditScreen(this, ch.id));
            }
        });
    }

    private void openNewChapter(ClientModel.Chapter ch) {
        int colon = ch.id.indexOf(':');
        String namespace = colon < 0 ? ch.id : ch.id.substring(0, colon);
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        mc.setScreen(new NewChapterScreen(this, namespace));
    }

    private void openDeleted() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        mc.setScreen(new RestoreScreen(this));
    }

    private void exportChapter(ClientModel.Chapter ch) {
        exportChapter(ch, false);
    }

    private void exportChapter(ClientModel.Chapter ch, boolean force) {
        int colon = ch.id.indexOf(':');
        JsonObject args = new JsonObject();
        args.addProperty("namespace", colon < 0 ? ch.id : ch.id.substring(0, colon));
        if (force) {
            args.addProperty("force", true);
        }
        EditorSession.send(ch.id, "export", args, reply -> {
            if (!reply.ok() && reply.lines().stream()
                    .anyMatch(line -> line.startsWith(EditorReplies.EXPORT_FOLDER_EXISTS))) {
                confirmExportOverwrite(ch);
            }
        });
    }

    private void confirmExportOverwrite(ClientModel.Chapter ch) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.screen != this) {
            return;
        }
        mc.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(confirmed -> {
            mc.setScreen(this);
            if (confirmed) {
                exportChapter(ch, true);
            }
        }, Component.translatable("screen.kuronami_quest_tree.editor.export"),
                Component.translatable("screen.kuronami_quest_tree.editor.export.overwrite"),
                Component.translatable("screen.kuronami_quest_tree.editor.export.replace"),
                Component.translatable("screen.kuronami_quest_tree.editor.cancel")));
    }

    private void confirmTest(ClientModel.Chapter ch) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        mc.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(confirmed -> {
            mc.setScreen(this);
            if (confirmed) {
                EditorSession.send(ch.id, "test_chapter", new JsonObject());
            }
        }, Component.translatable("screen.kuronami_quest_tree.editor.test"),
                Component.literal(ch.id), Component.translatable("screen.kuronami_quest_tree.editor.test.yes"),
                Component.translatable("screen.kuronami_quest_tree.editor.cancel")));
    }

    private void editorNodeMenu(ClientModel.Node n, ClientModel.Chapter ch, double mx, double my) {
        if (!editSelection.contains(n.key)) {
            editSelection.click(n.key, false);
        }
        List<EditorMenuEntry> entries = new ArrayList<>();
        entries.add(new EditorMenuEntry(Component.translatable("screen.kuronami_quest_tree.editor.edit"), true,
                () -> openNodeEditor(ch, n)));
        entries.add(new EditorMenuEntry(Component.translatable("screen.kuronami_quest_tree.editor.duplicate"), true,
                () -> duplicateSelection(ch)));
        entries.add(new EditorMenuEntry(Component.translatable("screen.kuronami_quest_tree.editor.copy"), true,
                () -> copySelection(ch)));
        entries.add(new EditorMenuEntry(Component.translatable("screen.kuronami_quest_tree.editor.delete"), true,
                () -> deleteSelection(ch)));
        entries.add(new EditorMenuEntry(Component.translatable("screen.kuronami_quest_tree.editor.link"), true,
                () -> beginLink(n.key)));
        if (!n.requires.isEmpty()) {
            entries.add(new EditorMenuEntry(Component.translatable("screen.kuronami_quest_tree.editor.unlink"), true,
                    () -> editorUnlinkMenu(n, ch, mx, my)));
        }
        openEditorMenu(mx, my, entries);
    }

    private void editorUnlinkMenu(ClientModel.Node n, ClientModel.Chapter ch, double mx, double my) {
        List<EditorMenuEntry> entries = new ArrayList<>();
        ClientModel cm = model();
        for (int i = 0; i < n.requireKeys.size(); i++) {
            String key = n.requireKeys.get(i);
            ClientModel.Node p = cm == null ? null : cm.nodesByKey.get(key);
            Component name = p == null ? Component.literal(key) : p.title;
            String ref = n.requires.get(i);
            entries.add(new EditorMenuEntry(
                    Component.translatable("screen.kuronami_quest_tree.editor.unlink.edge", name), true,
                    () -> unlink(ch, n.id, ref)));
        }
        openEditorMenu(mx, my, entries);
    }

    private void editorEdgeMenu(Edge edge, ClientModel.Chapter ch, double mx, double my) {
        ClientModel.Node target = model() == null ? null : model().nodesByKey.get(edge.toKey());
        Component name = target == null ? Component.literal(edge.toKey()) : target.title;
        openEditorMenu(mx, my, List.of(new EditorMenuEntry(
                Component.translatable("screen.kuronami_quest_tree.editor.unlink.edge", name), true,
                () -> unlink(ch, edge.from(), edge.ref()))));
    }

    private void unlink(ClientModel.Chapter ch, String from, String to) {
        JsonObject args = new JsonObject();
        args.addProperty("from", from);
        args.addProperty("to", to);
        EditorSession.send(ch.id, "unlink", args);
    }

    /** Copy the picked nodes from the raw bundle JSON, keeping their relative positions. */
    private void copySelection(ClientModel.Chapter ch) {
        List<EditorClipboard.Entry> entries = new ArrayList<>();
        for (String key : editSelection.ids()) {
            ClientModel.Node n = model() == null ? null : model().nodesByKey.get(key);
            if (n == null) {
                continue;
            }
            JsonObject raw = ClientProgress.rawNode(ch.id, n.id);
            if (raw != null) {
                entries.add(new EditorClipboard.Entry(n.id, editX(n), editY(n), raw));
            }
        }
        if (!entries.isEmpty()) {
            EditorSession.setClipboard(new EditorClipboard(ch.id, entries));
        }
    }

    private void pasteSelection(ClientModel.Chapter ch) {
        EditorClipboard copy = EditorSession.clipboard();
        if (copy == null || copy.isEmpty()) {
            return;
        }
        float[] v = view(ch);
        if (overMap(lastMouseX, lastMouseY)) {
            EditorSession.paste(ch.id, copy, GridSnap.snap(mapX(v, lastMouseX)), GridSnap.snap(mapY(v, lastMouseY)), true);
        } else {
            EditorSession.paste(ch.id, copy, copy.minX() + 1, copy.minY() + 1, true);
        }
    }

    private void duplicateSelection(ClientModel.Chapter ch) {
        copySelection(ch);
        EditorClipboard copy = EditorSession.clipboard();
        if (copy != null && !copy.isEmpty()) {
            EditorSession.paste(ch.id, copy, copy.minX() + 1, copy.minY() + 1, true);
        }
    }

    private void deleteSelection(ClientModel.Chapter ch) {
        for (String key : editSelection.ids()) {
            String id = key.substring(ch.id.length() + 1);
            JsonObject args = new JsonObject();
            args.addProperty("id", id);
            EditorSession.send(ch.id, "delete_node", args);
        }
        editSelection.clear();
    }

    private Map<String, double[]> selectionPositions(ClientModel.Chapter ch) {
        Map<String, double[]> out = new LinkedHashMap<>();
        ClientModel cm = model();
        for (String key : editSelection.ids()) {
            ClientModel.Node n = cm == null ? null : cm.nodesByKey.get(key);
            if (n != null) {
                out.put(key, new double[]{editX(n), editY(n)});
            }
        }
        return out;
    }

    private void beginMove(ClientModel.Chapter ch) {
        editOriginal = selectionPositions(ch);
        editDrag = EditDrag.MOVE;
        editMoved = false;
        editStartMx = lastMouseX;
        editStartMy = lastMouseY;
    }

    /** The menu's "Link from here": the source is fixed, the next click on a node is the one that requires it. */
    private void beginLink(String key) {
        linkFrom = key;
        linkArmed = true;
        editDrag = EditDrag.NONE;
    }

    private void finishLink(ClientModel.Chapter ch, double mx, double my) {
        float[] v = view(ch);
        ClientModel.Node target = nodeAt(ch, v, mx, my);
        ClientModel.Node source = linkFrom == null || model() == null ? null : model().nodesByKey.get(linkFrom);
        if (target != null && source != null && !target.key.equals(source.key)) {
            // the node under the pointer requires the node the drag started on
            JsonObject args = new JsonObject();
            args.addProperty("from", target.id);
            args.addProperty("to", source.id);
            EditorSession.send(ch.id, "link", args);
        }
        linkFrom = null;
        linkArmed = false;
        editDrag = EditDrag.NONE;
    }

    private void commitMove(ClientModel.Chapter ch, Map<String, double[]> original, Map<String, double[]> target) {
        if (target.isEmpty()) {
            return;
        }
        MoveBatch batch = new MoveBatch(original, target);
        batch.apply(EditorSession.optimistic());
        for (Map.Entry<String, double[]> e : target.entrySet()) {
            String key = e.getKey();
            if (!key.startsWith(ch.id + "/")) {
                continue;
            }
            JsonObject args = new JsonObject();
            args.addProperty("id", key.substring(ch.id.length() + 1));
            args.addProperty("x", e.getValue()[0]);
            args.addProperty("y", e.getValue()[1]);
            EditorSession.send(ch.id, "move_node", args, reply -> {
                if (!reply.ok()) {
                    // only this node: the others in the batch are answered on their own
                    batch.rollbackKey(key, EditorSession.optimistic());
                }
            });
        }
    }

    private void finishMove(ClientModel.Chapter ch) {
        if (!editMoved && editPressedKey != null) {
            editSelection.click(editPressedKey, false);
        }
        editPressedKey = null;
        if (editMoved && editOriginal != null) {
            Map<String, double[]> target = new LinkedHashMap<>();
            for (String key : editOriginal.keySet()) {
                double[] p = EditorSession.optimistic().get(key);
                if (p != null) {
                    target.put(key, p.clone());
                }
            }
            commitMove(ch, editOriginal, target);
        }
        editDrag = EditDrag.NONE;
        editOriginal = null;
        editMoved = false;
    }

    private void nudgeSelection(ClientModel.Chapter ch, int dx, int dy, int step) {
        Map<String, double[]> original = selectionPositions(ch);
        Map<String, double[]> target = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> e : original.entrySet()) {
            target.put(e.getKey(), new double[]{e.getValue()[0] + dx * step, e.getValue()[1] + dy * step});
        }
        commitMove(ch, original, target);
    }

    private void finishBox(ClientModel.Chapter ch) {
        float[] v = view(ch);
        EditorSelection.Rect rect = EditorSelection.Rect.of(editBoxX0, editBoxY0, editBoxX1, editBoxY1);
        List<EditorSelection.Hit> hits = new ArrayList<>();
        for (ClientModel.Node n : ch.nodes.values()) {
            if (ClientProgress.visible(n.key)) {
                double half = NODE * n.size / (double) UNIT / 2;
                hits.add(new EditorSelection.Hit(n.key, editX(n), editY(n), half, half));
            }
        }
        editSelection.box(rect, hits, editAdditive);
        editDrag = EditDrag.NONE;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        ClientModel m = model();
        if (railBox != null && inRect(railRect, mx, my)) {
            setFocused(railBox);
            railBox.setFocused(true);
            if (searchBox != null) {
                searchBox.setFocused(false);
            }
            railBox.mouseClicked(mx, my, button);
            return true;
        }
        // the field may sit under the details sheet: what the sheet covers takes no clicks
        if (searchBox != null && inRect(searchRect, mx, my) && mx < (sheetT > 0 ? sheetLeft() : width)) {
            setFocused(searchBox);
            searchBox.setFocused(true);
            if (railBox != null) {
                railBox.setFocused(false);
            }
            searchBox.mouseClicked(mx, my, button);
            return true;
        }
        if (railBox != null && railBox.isFocused()) {
            railBox.setFocused(false);
            setFocused(null);
        }
        if (searchBox != null && searchBox.isFocused()) {
            searchBox.setFocused(false);
            setFocused(null);
        }
        if (editorMenu != null) {
            return editorMenuClick(mx, my);
        }
        if (m == null) {
            return super.mouseClicked(mx, my, button);
        }
        ClientModel.Chapter editChapter = chapter();
        float[] editView = editChapter == null ? null : view(editChapter);
        if (button == 1) {
            if (!editActive() || !overMap(mx, my) || editChapter == null || editView == null) {
                return super.mouseClicked(mx, my, button);
            }
            ClientModel.Node hit = nodeAt(editChapter, editView, mx, my);
            if (hit != null) {
                editorNodeMenu(hit, editChapter, mx, my);
            } else {
                Edge edge = edgeAt(editChapter, editView, mx, my);
                if (edge != null) {
                    editorEdgeMenu(edge, editChapter, mx, my);
                } else {
                    editorEmptyMenu(editChapter, editView, mx, my);
                }
            }
            return true;
        }
        if (button != 0) {
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
            if (my >= RAIL_LIST_TOP && my < railListBottom()) {
                double at = my - RAIL_LIST_TOP + railScrollShown();
                int rowY = 0;
                for (RailRow row : railRows) {
                    int hgt = row.isHeader() ? HEADER_H : RAIL_ROW;
                    if (at >= rowY && at < rowY + hgt) {
                        if (!row.isHeader()) {
                            select(m.chapters.indexOf(row.chapter()));
                        } else if (!searching() && !railFiltering()) {
                            // FTB Quests folds a group shut on its name; while searching every group stays open
                            ClientConfig.toggleRailFold(row.fold());
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
        if (overHeader(mx, my)) {
            return true;
        }
        ClientModel.Chapter ch = chapter();
        if (ch == null) {
            return true;
        }
        float[] v = view(ch);
        if (editActive()) {
            ClientModel.Node hit = nodeAt(ch, v, mx, my);
            if (linkArmed && linkFrom != null) {
                // "Link from here" picked the source; this click picks the node that will require it
                finishLink(ch, mx, my);
                return true;
            }
            if (linkKeyHeld) {
                if (hit != null) {
                    linkFrom = hit.key;
                    editDrag = EditDrag.LINK;
                }
                return true;
            }
            if (hit != null) {
                long now = Util.getMillis();
                if (!Screen.hasControlDown() && hit.key.equals(editClickKey) && now - editClickAt < 350) {
                    editClickKey = null;
                    openNodeEditor(ch, hit);
                    return true;
                }
                editClickKey = hit.key;
                editClickAt = now;
                boolean ctrl = Screen.hasControlDown();
                if (!ctrl && editSelection.contains(hit.key)) {
                    editPressedKey = hit.key;
                } else {
                    editSelection.click(hit.key, ctrl);
                    editPressedKey = null;
                }
                if (!editSelection.contains(hit.key)) {
                    return true;
                }
                beginMove(ch);
                editStartMx = mx;
                editStartMy = my;
                return true;
            }
            if (Screen.hasShiftDown()) {
                editDrag = EditDrag.BOX;
                editAdditive = Screen.hasControlDown();
                editBoxX0 = mapX(v, mx);
                editBoxY0 = mapY(v, my);
                editBoxX1 = editBoxX0;
                editBoxY1 = editBoxY0;
                return true;
            }
            editSelection.clear();
        }
        int[] r = minimapShown() ? minimapRect(ch, v) : null;
        if (r != null && mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
            double[] b = bounds(ch);
            double s = Math.min((r[2] - 12) / Math.max(1, b[2] - b[0]), (r[3] - 12) / Math.max(1, b[3] - b[1]));
            double wx = (mx - r[0] - 6) / s + b[0];
            double wy = (my - r[1] - 6) / s + b[1];
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

    /** Dev tour only ({@link DevTour}): open on a chapter. */
    void tourChapter(int index) {
        select(index);
    }

    /** Dev tour only: select the first node in the current view that has {@code state}. */
    void tourSelectFirst(State state) {
        ClientModel m = model();
        ClientModel.Chapter ch = chapter();
        if (ch == null) {
            return;
        }
        for (ClientModel.Node n : ch.nodes.values()) {
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
            // setValue does not run the responder, so set the search's own state the way typing would
            query = text;
            matchCursor = -1;
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

    /** Dev tour only: holds the chapter list open without the pointer over it. */
    private static boolean tourRailOpen;

    /** Dev tour only: centre a node at a zoom. */
    void tourZoomTo(ClientModel.Node n, float zoom) {
        ClientModel.Chapter ch = chapter();
        if (ch == null) {
            return;
        }
        float[] v = view(ch);
        v[2] = zoom;
        v[0] = (float) (width / 2.0 - n.x * UNIT * zoom);
        v[1] = (float) (height / 2.0 - n.y * UNIT * zoom);
    }

    /** Dev tour only: a node treated as under the pointer (null = the real pointer). */
    static void tourHover(String key) {
        tourHoverKey = key;
    }

    private static String tourHoverKey;

    /** Dev tour only: hover the node with the most lines in the current chapter. */
    void tourHoverBusiest() {
        ClientModel.Chapter ch = chapter();
        if (ch == null) {
            return;
        }
        Map<String, Integer> degree = new HashMap<>();
        for (ClientModel.Node n : ch.nodes.values()) {
            for (String r : n.requireKeys) {
                degree.merge(r, 1, Integer::sum);
                degree.merge(n.key, 1, Integer::sum);
            }
        }
        java.util.Set<String> here = new java.util.HashSet<>();
        ch.nodes.values().forEach(n -> here.add(n.key));
        degree.entrySet().stream().filter(e -> here.contains(e.getKey()) && ClientProgress.visible(e.getKey()))
                .max(Map.Entry.comparingByValue()).ifPresent(e -> tourHoverKey = e.getKey());
    }

    static void tourRailOpen(boolean open) {
        tourRailOpen = open;
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
        ClientModel m = model();
        ClientModel.Chapter c = m.chapterOf.get(key);
        if (c != null && ClientProgress.chapterHidden(c.id)) {
            // a chapter hidden as a whole is not opened through links, images or requirement rows either
            return;
        }
        if (!ClientProgress.visible(key)) {
            // hidden quests stay hidden, also through links, images and requirement rows
            ProgressFeedback.locked();
            return;
        }
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
        if (index >= 0 && index != chapterIndex && chapterIndex >= 0) {
            chapterHistory.addFirst(chapterIndex);
            // a new chapter starts its own trail; Backspace can still walk back to the chapters before it
            nodeHistory.clear();
        }
        applyChapter(index);
    }

    private void applyChapter(int index) {
        panView = null;
        chapterIndex = index;
        lastChapter = index;
        selected = null;
        choicePick = -1;
        sheetT = 0;
        editSelection.clear();
        cancelEditGestures();
    }

    /** Backspace: the previous quest, else the previous chapter (FTB Quests' {@code BACKSPACE_HISTORY}). */
    private void back() {
        if (!nodeHistory.isEmpty()) {
            selected = nodeHistory.removeFirst();
            choicePick = -1;
            sheetScrollFor = null;
            ProgressFeedback.click();
            return;
        }
        if (chapterHistory.isEmpty()) {
            return;
        }
        applyChapter(chapterHistory.removeFirst());
        ProgressFeedback.click();
    }

    /** Tab / Shift+Tab: the next or previous chapter in the list, folding groups aside. */
    private void cycleChapter(int direction) {
        ClientModel m = model();
        if (m == null || railChapters.isEmpty()) {
            return;
        }
        ClientModel.Chapter now = chapter();
        int at = railChapters.indexOf(now);
        int size = railChapters.size();
        int next = Math.floorMod(at < 0 ? (direction > 0 ? 0 : size - 1) : at + direction, size);
        ClientModel.Chapter target = railChapters.get(next);
        select(m.chapters.indexOf(target));
        railScrollTarget = 0;
        ProgressFeedback.click();
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
        if (editDrag != EditDrag.NONE && ch != null && button == 0) {
            float[] v = view(ch);
            if (editDrag == EditDrag.MOVE && editOriginal != null) {
                if (!editMoved && Math.hypot(mx - editStartMx, my - editStartMy) > 3) {
                    editMoved = true;
                }
                double gx = GridSnap.dragDelta(mx - editStartMx, v[2], UNIT, !Screen.hasAltDown());
                double gy = GridSnap.dragDelta(my - editStartMy, v[2], UNIT, !Screen.hasAltDown());
                if (editMoved) {
                    for (Map.Entry<String, double[]> e : editOriginal.entrySet()) {
                        EditorSession.optimistic().put(e.getKey(),
                                new double[]{e.getValue()[0] + gx, e.getValue()[1] + gy});
                    }
                }
            } else if (editDrag == EditDrag.BOX) {
                editBoxX1 = mapX(v, mx);
                editBoxY1 = mapY(v, my);
            }
            return true;
        }
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
        if (editDrag != EditDrag.NONE && ch != null) {
            EditDrag was = editDrag;
            if (was == EditDrag.MOVE) {
                finishMove(ch);
            } else if (was == EditDrag.BOX) {
                finishBox(ch);
            } else if (was == EditDrag.LINK) {
                finishLink(ch, mx, my);
            }
            dragging = false;
            moved = false;
            return true;
        }
        if (dragging && ch != null && !moved && !editActive()) {
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
                if (selected != null) {
                    nodeHistory.addFirst(selected);
                }
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
            railScrollTarget = Mth.clamp(railScrollTarget - (float) scrollY * RAIL_ROW * 3, 0, railMaxScroll());
            return true;
        }
        if (sheetT > 0 && selected != null && mx >= sheetLeft()) {
            sheetScrollTarget = Mth.clamp(sheetScrollTarget - (float) scrollY * 24, 0, sheetContentMax);
            return true;
        }
        ClientModel.Chapter ch = chapter();
        if (ch == null) {
            return true;
        }
        float[] v = view(ch);
        lastMapMove = Util.getMillis();
        if (scrollX != 0) {
            // trackpads send both axes: pan (FTB Quests issue #2059 asked for this)
            v[0] += (float) (scrollX * 12);
            v[1] += (float) (scrollY * 12);
        } else if (Screen.hasControlDown()) {
            // FTB Quests' default wheel: a plain wheel pans, Ctrl zooms (QuestPanel.scrollPanel)
            zoomAt(v, mx, my, (float) Math.pow(1.15, scrollY));
        } else if (Screen.hasShiftDown()) {
            v[0] += (float) (scrollY * 12);
        } else {
            v[1] += (float) (scrollY * 12);
        }
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_F && Screen.hasControlDown()) {
            // from anywhere, including the chapter list's filter: the quest search gets the title row
            focusSearch();
            return true;
        }
        if (railBox != null && railBox.isFocused()) {
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
                openFirstRailMatch();
                return true;
            }
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                railBox.setFocused(false);
                setFocused(null);
                return true;
            }
            // typing goes to the box (the open key must not close the screen mid-word)
            return railBox.keyPressed(key, scan, modifiers) || true;
        }
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
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && searching()) {
            // a second Escape (the first blurred the field) puts the title back: clearing the text does the same
            searchBox.setValue("");
            // setValue does not run the responder, so clear the search's own state too
            query = "";
            matchCursor = -1;
            return true;
        }
        if ((key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) && searching()) {
            nextMatch();
            return true;
        }
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_E && EditorSession.allowed()
                && !Screen.hasControlDown() && !Screen.hasAltDown() && !Screen.hasShiftDown()) {
            toggleEditor();
            return true;
        }
        if (editActive()) {
            ClientModel.Chapter ech = chapter();
            if (ech != null && (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                    || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) && editSelection.ids().size() == 1) {
                ClientModel.Node n = model() == null ? null : model().nodesByKey.get(editSelection.ids().get(0));
                if (n != null) {
                    openNodeEditor(ech, n);
                    return true;
                }
            }
            if (ech != null && Screen.hasControlDown()) {
                if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_A) {
                    List<String> all = ech.nodes.values().stream()
                            .filter(n -> ClientProgress.visible(n.key)).map(n -> n.key).toList();
                    editSelection.selectAll(all);
                    return true;
                }
                if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_C) {
                    copySelection(ech);
                    return true;
                }
                if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_V) {
                    pasteSelection(ech);
                    return true;
                }
                if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_D) {
                    duplicateSelection(ech);
                    return true;
                }
                if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_Z && !Screen.hasShiftDown()) {
                    EditorSession.send(ech.id, "undo", new JsonObject());
                    editSelection.clear();
                    return true;
                }
                if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_Z || key == org.lwjgl.glfw.GLFW.GLFW_KEY_Y) {
                    EditorSession.send(ech.id, "redo", new JsonObject());
                    editSelection.clear();
                    return true;
                }
            }
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE && ech != null) {
                deleteSelection(ech);
                return true;
            }
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_L) {
                linkKeyHeld = true;
                return true;
            }
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                if (linkFrom != null || editDrag != EditDrag.NONE) {
                    cancelEditGestures();
                    return true;
                }
                if (!editSelection.isEmpty()) {
                    editSelection.clear();
                    return true;
                }
            }
            if (ech != null && !editSelection.isEmpty() && switch (key) {
                case org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT,
                     org.lwjgl.glfw.GLFW.GLFW_KEY_UP, org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN -> true;
                default -> false;
            }) {
                int step = Screen.hasShiftDown() ? 5 : 1;
                int dx = key == org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT ? -1 : key == org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT ? 1 : 0;
                int dy = key == org.lwjgl.glfw.GLFW.GLFW_KEY_UP ? -1 : key == org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN ? 1 : 0;
                nudgeSelection(ech, dx, dy, step);
                return true;
            }
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
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_TAB) {
            cycleChapter(Screen.hasShiftDown() ? -1 : 1);
            return true;
        }
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE) {
            back();
            return true;
        }
        ClientModel.Chapter mapChapter = chapter();
        if (mapChapter != null && !mapChapter.skills) {
            float[] v = view(mapChapter);
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE) {
                // recentre on the selected quest, else the whole tree
                lastMapMove = Util.getMillis();
                if (selected != null && model() != null && model().nodesByKey.containsKey(selected)) {
                    ClientModel.Node n = model().nodesByKey.get(selected);
                    v[0] = (float) ((width - sheetWidth()) / 2.0 - n.x * UNIT * v[2]);
                    v[1] = (float) (height / 2.0 - n.y * UNIT * v[2]);
                } else {
                    VIEWS.put(mapChapter.id, fitView(mapChapter));
                }
                return true;
            }
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_EQUAL || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ADD) {
                zoomButton(mapChapter, v, 2);
                return true;
            }
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_MINUS || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_SUBTRACT) {
                zoomButton(mapChapter, v, 1);
                return true;
            }
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_0 || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_0) {
                zoomButton(mapChapter, v, 0);
                return true;
            }
            int pan = Screen.hasShiftDown() ? 64 : 24;
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT) {
                lastMapMove = Util.getMillis();
                v[0] += pan;
                return true;
            }
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT) {
                lastMapMove = Util.getMillis();
                v[0] -= pan;
                return true;
            }
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_UP) {
                lastMapMove = Util.getMillis();
                v[1] += pan;
                return true;
            }
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN) {
                lastMapMove = Util.getMillis();
                v[1] -= pan;
                return true;
            }
        }
        if (ClientProgress.OPEN_KEY.matches(key, scan)) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override
    public boolean keyReleased(int key, int scan, int modifiers) {
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_L) {
            linkKeyHeld = false;
        }
        return super.keyReleased(key, scan, modifiers);
    }

    @Override
    public void removed() {
        lastChapter = chapterIndex;
        GlassPanel.release();
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

    /**
     * One line from an earlier node to a later one, worked out once per frame: its two ends and its colour. {@code
     * role}: 0 = nothing in focus, 1 = into the focused node (what it needs), 2 = out of it (what it opens), 3 =
     * unrelated while something is in focus.
     */
    private record LineDraw(float x1, float y1, float x2, float y2, float w, float casing, int color, int role, float open) {
    }

    /**
     * Straight conveyor belts from the centre of the earlier tile to the centre of the later one, chevrons moving
     * towards the later quest (2026-10-20, the player, after FTB Quests: a line with one arrowhead at its end only told
     * its direction at that end, and once the other lines dimmed the head and the bar came apart). Straight because the
     * books come from FTB Quests, whose authors laid out their trees for straight lines: the elbows of 2026-10-18
     * invented routes that ran through other tiles and merged siblings into one trunk. Since 2026-10-20 the ends run to
     * the tiles' centres instead of their edges (the player: the ends looked bad): the lines are drawn before the
     * tiles, so each tile covers the end that runs in under it. The belt and its casing keep a minimum size on screen.
     */
    private LineDraw lineGeometry(ClientModel.Node p, ClientModel.Node n, int color, int role, float open, float zoom) {
        float x1 = (float) (editX(p) * UNIT);
        float y1 = (float) (editY(p) * UNIT);
        float x2 = (float) (editX(n) * UNIT);
        float y2 = (float) (editY(n) * UNIT);
        float dx = x2 - x1;
        float dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1) {
            return null;
        }
        float w = Math.max(LINE_W, LINE_MIN_PX / zoom);
        float casing = Math.max(LINE_CASING, CASING_MIN_PX / zoom);
        return new LineDraw(x1, y1, x2, y2, w, casing, color, role, open);
    }

    private static void drawLine(GuiGraphics g, LineDraw l, boolean casing) {
        float dx = l.x2 - l.x1;
        float dy = l.y2 - l.y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        int color = switch (l.role) {
            case 1 -> LINE_REQUIRES;
            case 2 -> LINE_UNLOCKS;
            default -> l.color;
        };
        float alpha = l.role == 3 ? 0.35f : 1f;
        g.pose().pushPose();
        g.pose().translate(l.x1, l.y1, 0);
        g.pose().mulPose(Axis.ZP.rotation((float) Math.atan2(dy, dx)));
        // one piece per line (casing, then the belt), so a dimmed line fades as a whole and nothing overlaps itself
        if (casing) {
            int c = Tiles.withAlpha(LINE_CASING_COLOR, alpha * ((LINE_CASING_COLOR >>> 24) / 255f));
            fillLine(g, 0, len, l.w + 2 * l.casing, c);
        } else {
            // the pattern is pinned to the map, not to the line's start: lines that run along the same track (a
            // column of quests all feeding one tile) show their chevrons on top of each other instead of a solid bar
            float along = (l.x1 * dx + l.y1 * dy) / len;
            // 2026-10-20 (the player): only the lines around the hovered or selected node flow, like FTB Quests, so a
            // resting map is still and the one node being read is the one that moves; the other lines keep the map
            // anchor alone, so their chevrons still line up with their neighbours'
            float phase = -along;
            if (l.role == 1 || l.role == 2) {
                phase += (Util.getMillis() % 1_000_000L) / 1000f * BELT_SPEED * l.w;
            }
            if (l.open >= 0) {
                // the way just opened: colour runs in from the earlier tile over the closed belt
                float e = ease(l.open);
                Tiles.belt(g, len * e, len, l.w, phase - len * e, Tiles.withAlpha(LINE_CLOSED, alpha));
                Tiles.belt(g, 0, len * e, l.w, phase, Tiles.withAlpha(color, alpha));
            } else {
                Tiles.belt(g, 0, len, l.w, phase, Tiles.withAlpha(color, alpha));
            }
        }
        g.pose().popPose();
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
