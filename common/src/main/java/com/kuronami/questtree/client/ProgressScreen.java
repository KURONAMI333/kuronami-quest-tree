package com.kuronami.questtree.client;

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
    /** Whether the rows were built while searching: a search opens every folded group so its matches show. */
    private boolean railBuiltSearching;
    /** The item under the pointer in the detail panel (a task or a reward), for the recipe viewer's keys. */
    private ItemStack hoverStack = ItemStack.EMPTY;
    private int lastMouseX;
    private int lastMouseY;
    /** A quest to show once the screen is laid out (opened from an item, see {@link #openAt}). */
    private String pendingGoTo;
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
                    if (n.choice.isEmpty() && (pn == null || !com.kuronami.questtree.data.ProgressModel.excludedFromClaimAll(pn))) {
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
        railT = tourRailOpen ? 1 : Mth.clamp(railT + (overRail || (railBox != null && railBox.isFocused()) ? dt : -dt) / RAIL_MS, 0, 1);
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
        if (pendingTip != null) {
            g.pose().pushPose();
            g.pose().translate(0, 0, OVERLAY_Z + 300);
            Ui.tooltip(g, font, List.of(pendingTip), mouseX, mouseY);
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

    private int railListBottom() {
        return height - 8;
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
            double sx = v[0] + n.x * UNIT * v[2];
            double sy = v[1] + n.y * UNIT * v[2];
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
        if (tourHoverKey != null) {
            hovered = tourHoverKey;
        }
        hoveredNode = dragging ? null : hovered;
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
        int maxTitleW = Math.max(0, limitRight - left - icon - Ui.S1 - Ui.S1 - pin - searchBtn - skillW - Ui.S2);
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
        int gw = icon + Ui.S1 + contentW + Ui.S1 + pin + searchBtn + skillW;
        int x = left + (avail - gw) / 2;
        if (x + gw > limitRight) {
            x = limitRight - gw;
        }
        x = Math.max(left + Ui.S2, x);
        int tx = x + icon + Ui.S1;
        int px = tx + contentW + Ui.S1;
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
                pendingTip = Component.translatable("screen.kuronami_quest_tree.search");
            }
            Tiles.icon(g, "search", sx + (pin - TITLE_GLYPH) / 2f, py + (pin - TITLE_GLYPH) / 2f, TITLE_GLYPH,
                    sover ? Ui.TEXT : Ui.DIM);
            if (sx + pin <= open) {
                buttons.add(new Button(sx, py, pin, pin, this::focusSearch));
            }
            searchRect = new int[]{sx, py, pin, pin};
        }
        if (ch.skills) {
            renderSkillHeader(g, ch, skillX, ty);
        }
        // the pin: gold while the chapter is pinned to the top of the list
        boolean pinned = ClientConfig.railPinned(ch.id);
        boolean over = mouseX >= px && mouseX < px + pin && mouseY >= py && mouseY < py + pin && mouseX < open;
        if (over) {
            Ui.surface(g, px, py, pin, pin, pin / 2, Ui.OVERLAY_1);
            pendingTip = Component.translatable(pinned ? "screen.kuronami_quest_tree.pinned" : "screen.kuronami_quest_tree.pin");
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
                    if (ClientProgress.state(node.key) == State.CLAIM && node.choice.isEmpty()
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
        } else {
            zoomAt(v, mx, my, (float) Math.pow(1.15, scrollY));
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
        float x1 = (float) (p.x * UNIT);
        float y1 = (float) (p.y * UNIT);
        float x2 = (float) (n.x * UNIT);
        float y2 = (float) (n.y * UNIT);
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
