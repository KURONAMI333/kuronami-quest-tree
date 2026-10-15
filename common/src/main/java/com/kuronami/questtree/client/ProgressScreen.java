package com.kuronami.questtree.client;

import com.kuronami.questtree.progress.PlayerProgress;
import com.kuronami.questtree.progress.ProgressRules.State;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
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

    private static final int GRAY = Tiles.GRAY;
    private static final int PANEL = 0xF20E0E10;
    /**
     * The scrim over the blurred world: dark and even, so the world's grass and sky do not show through in patches and
     * everything on the map (closed nodes, closed lines) can sit a step lighter than it (2026-10-15 review: a 188-quest
     * chapter zoomed out was almost black).
     */
    private static final int SCRIM = 0xE60C0C0E;
    /** Flat controls (zoom buttons, search field): an opaque surface one step above the scrim. */
    static final int CONTROL = 0xFF202024;
    static final int CONTROL_HOVER = 0xFF2E2E34;

    private static final int UNIT = 24;      // GUI px per grid unit (FTB Quests positions)
    private static final int NODE = 20;      // GUI px of a size-1 node
    private static final int RAIL = 34;
    private static final int RAIL_OPEN = 150;
    private static final int BTN = 20;
    private static final int NOW_TILE = 32;
    private static final int NOW_GAP = 6;
    /** Narrowest Now cell: the tile and room for about 15 characters of title beside it. */
    private static final int NOW_CELL_MIN = 130;
    private static final int DOCK_MIN_WIDTH = 560;
    private static final float FAR = 0.6f;   // below this zoom, nodes are drawn as state-coloured shapes without icons
    private static final float OVERLAY_Z = Tiles.OVERLAY_Z;
    private static final String[] NOW_ICON = {"##.#####", "##......", "........", "##.#####", "##......", "........", "##.####."};

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

    @Override
    protected void init() {
        searchBox = new net.minecraft.client.gui.components.EditBox(font, 0, 8, 110 - 12, 9, Component.translatable("screen.kuronami_quest_tree.search"));
        // drawn as one of the screen's flat controls (renderSearch), not vanilla's black box with a grey border
        searchBox.setBordered(false);
        searchBox.setHint(Component.translatable("screen.kuronami_quest_tree.search").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        searchBox.setMaxLength(64);
        searchBox.setValue(query);
        searchBox.setResponder(v -> {
            query = v;
            matchCursor = -1;
        });
        // input only: drawn by render() above the scrim
        addWidget(searchBox);
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

    /** A tile on the Now screen: a node, or a skill chapter with points to spend. */
    private record NowEntry(String key, ClientModel.Node node, ClientModel.Chapter pointsChapter) {
    }

    private int chapterIndex = lastChapter;
    /** Selected node, global key. */
    private String selected;
    private int choicePick = -1;
    private int railScroll;
    private int nowScroll;
    private float railT;
    private float sheetT;
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
    private final List<NowEntry> nowEntries = new ArrayList<>();
    private final Map<String, Integer> badges = new LinkedHashMap<>();
    /** done, visible, hidden node counts per chapter. */
    private final Map<String, int[]> chapterCounts = new LinkedHashMap<>();
    /** Chapters listed in the rail (chapters hidden as a whole are left out). */
    private final List<ClientModel.Chapter> railChapters = new ArrayList<>();
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
        if (p == cachedFor && m == cachedModel) {
            return;
        }
        cachedFor = p;
        cachedModel = m;
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
        railChapters.clear();
        for (ClientModel.Chapter c : m.chapters) {
            if (!ClientProgress.chapterHidden(c.id)) {
                railChapters.add(c);
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
                    points.add(new NowEntry(c.id, null, c));
                } else {
                    badge = 0;
                }
            }
            badges.put(c.id, badge);
            chapterCounts.put(c.id, new int[]{done, shown, hidden});
        }
        // the required path first, optional quests after it, each closest-to-done first
        moving.sort(Comparator.comparing((NowEntry e) -> e.node.optional).thenComparingDouble(e -> -ClientProgress.fraction(e.key)));
        nowEntries.addAll(pinned);
        nowEntries.addAll(claim);
        nowEntries.addAll(points);
        nowEntries.addAll(moving.subList(0, Math.min(moving.size(), 40)));
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
        super.render(g, mouseX, mouseY, partialTick);
        g.fill(0, 0, width, height, SCRIM);
        buttons.clear();
        Motion.tick();
        // claim animations of tiles that were never drawn (scrolled away) end here, so their tiles leave the list
        long nowMs = Util.getMillis();
        if (flashes.values().removeIf(at -> nowMs - at > 2500)) {
            cachedFor = null;
        }
        ClientModel m = model();
        if (m == null || m.chapters.isEmpty()) {
            g.drawCenteredString(font, Component.translatable("screen.kuronami_quest_tree.empty"), width / 2, height / 2 - 4, GRAY);
            return;
        }
        refresh(m);
        refreshMatches(m);
        ClientModel.Chapter ch = chapter();
        // the chapter list stays open on Now, except on small screens (GUI scale 4 on 1080p is 480 wide) where the tiles need the room
        boolean docked = ch == null && width >= DOCK_MIN_WIDTH;
        boolean overRail = mouseX < (railT > 0.5f ? RAIL_OPEN : RAIL) + 8;
        railT = docked ? 1 : Mth.clamp(railT + (overRail ? 0.25f : -0.25f), 0, 1);
        sheetT = Mth.clamp(sheetT + (selected != null ? 0.3f : -0.35f), 0, 1);

        if (ch == null) {
            renderNow(g, m, mouseX, mouseY);
        } else {
            renderChapter(g, ch, mouseX, mouseY);
        }
        g.pose().pushPose();
        g.pose().translate(0, 0, OVERLAY_Z + 50);
        if (sheetT > 0) {
            renderSheet(g, m, mouseX, mouseY);
        }
        g.pose().popPose();
        g.pose().pushPose();
        g.pose().translate(0, 0, OVERLAY_Z + 100);
        renderRail(g, m, mouseX, mouseY, docked);
        renderSearch(g, mouseX, mouseY, partialTick);
        g.pose().popPose();
    }

    /**
     * The search box sits in a corner nothing else uses, so it never covers a title, a button or the details sheet:
     * bottom left on chapter maps (zoom buttons and minimap are bottom right), bottom right on Now (its recent list is
     * bottom left). The match count is on the box's open side.
     */
    private void renderSearch(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        boolean map = chapter() != null;
        int w = searchBox.getWidth() + 12;
        int y = height - 8 - BTN;
        // on maps it moves aside with the chapter list, so it never sits on the list's rows
        int x = map ? railRight() + 8 : nowRight() - w;
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + BTN;
        g.fill(x, y, x + w, y + BTN, hover || searchBox.isFocused() ? CONTROL_HOVER : CONTROL);
        if (searchBox.isFocused()) {
            g.fill(x, y + BTN - 1, x + w, y + BTN, 0xFFFFFFFF);
        }
        searchBox.setX(x + 6);
        searchBox.setY(y + 6);
        searchBox.render(g, mouseX, mouseY, partialTick);
        if (searching()) {
            String n = matches.isEmpty() ? "0" : (matchCursor >= 0 ? (matchCursor + 1) + "/" : "") + matches.size();
            int nx = map ? x + w + 6 : x - font.width(n) - 6;
            g.drawString(font, n, nx, y + 6, matches.isEmpty() ? 0xFFC86464 : GRAY, true);
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // vanilla blur of the world behind, then our own scrim in render()
        this.renderBlurredBackground(partialTick);
    }

    private int railRows() {
        return Math.max(1, (height - 8 - 30 - 26) / 22);
    }

    private void renderRail(GuiGraphics g, ClientModel m, int mouseX, int mouseY, boolean docked) {
        int w = (int) Mth.lerp(ease(railT), RAIL, RAIL_OPEN);
        int bg = docked ? 0x40000000 : railT > 0.01f ? lerpColor(0x73000000, PANEL, railT) : 0x73000000;
        g.fill(4, 4, 4 + w, height - 4, bg);
        g.enableScissor(4, 4, 4 + w, height - 4);
        int y = 8;
        int nowBadge = 0;
        for (int b : badges.values()) {
            nowBadge += b;
        }
        rowRail(g, y, -1, null, Component.translatable("screen.kuronami_quest_tree.now"), w, mouseX, mouseY, nowBadge, null);
        y += 22;
        railScroll = Mth.clamp(railScroll, 0, Math.max(0, railChapters.size() - railRows()));
        for (int i = railScroll; i < railChapters.size() && i < railScroll + railRows(); i++) {
            ClientModel.Chapter c = railChapters.get(i);
            int[] cnt = chapterCounts.getOrDefault(c.id, new int[]{0, 0, 0});
            String right = c.skills ? Component.translatable("screen.kuronami_quest_tree.level_short",
                    ClientProgress.progress().level.getOrDefault(c.id, 0)).getString() : cnt[0] + "/" + cnt[1];
            if (searching()) {
                // while searching, the rail shows where the matches are instead
                int hits = matchCount.getOrDefault(c.id, 0);
                rowRail(g, y, m.chapters.indexOf(c), c.displayIcon(), c.title, w, mouseX, mouseY, 0, String.valueOf(hits));
                y += 22;
                continue;
            }
            rowRail(g, y, m.chapters.indexOf(c), c.displayIcon(), c.title, w, mouseX, mouseY, badges.getOrDefault(c.id, 0),
                    !c.skills && cnt[1] > 0 && cnt[0] == cnt[1] && cnt[2] == 0 ? "" : right);
            y += 22;
        }
        if (railScroll > 0) {
            g.fill(8, 30, w, 31, 0x55FFFFFF);
        }
        if (railScroll + railRows() < railChapters.size()) {
            g.fill(8, height - 9, w, height - 8, 0x55FFFFFF);
        }
        g.disableScissor();
    }

    /**
     * One rail row. {@code badge} > 0 shows a yellow count (claimable quests, points to spend); otherwise {@code right}
     * is a grey count, and an empty string means the chapter is complete (a green tick).
     */
    private void rowRail(GuiGraphics g, int y, int index, ItemStack icon, Component label, int w, int mouseX, int mouseY, int badge, String right) {
        boolean on = index == chapterIndex;
        boolean hover = mouseX >= 4 && mouseX < 4 + w && mouseY >= y && mouseY < y + 20;
        if (on) {
            g.fill(6, y, 2 + w, y + 20, 0x2EFFFFFF);
            g.fill(4, y, 6, y + 20, 0xFFFFFFFF);
        } else if (hover) {
            g.fill(6, y, 2 + w, y + 20, 0x12FFFFFF);
        }
        if (icon == null) {
            Tiles.pixels(g, NOW_ICON, 13, y + 6, 1, on ? 0xFFFFFFFF : 0xFFD8D8D8);
        } else {
            g.renderItem(icon, 13, y + 2);
        }
        g.pose().pushPose();
        g.pose().translate(0, 0, OVERLAY_Z);
        if (railT <= 0.6f && badge > 0) {
            // closed rail: a dot on the icon's corner
            g.fill(25, y + 1, 31, y + 7, Tiles.GOLD);
        }
        if (railT > 0.6f) {
            int alpha = (int) (255 * Mth.clamp((railT - 0.6f) / 0.4f, 0, 1)) << 24;
            int rightW = 0;
            if (badge > 0) {
                String b = String.valueOf(badge);
                rightW = font.width(b) + 6;
                int bx = w - rightW;
                g.fill(bx, y + 5, bx + rightW, y + 15, Tiles.GOLD);
                g.drawString(font, b, bx + 3, y + 6, Tiles.INK, false);
            } else if (right != null && right.isEmpty()) {
                rightW = 8;
                Tiles.pixels(g, Tiles.TICK, w - 7, y + 8, 1, Tiles.GREEN_TEXT);
            } else if (right != null) {
                rightW = font.width(right) + 2;
                g.drawString(font, right, w - rightW, y + 6, 0x808080 | alpha, true);
            }
            String s = trim(label.getString(), w - 38 - rightW);
            g.drawString(font, s, 34, y + 6, (on ? 0xFFFFFF : 0xD0D0D0) | alpha, true);
        }
        g.pose().popPose();
    }

    // ------------------------------------------------------------------ Now

    private int nowLeft() {
        return (width >= DOCK_MIN_WIDTH ? RAIL_OPEN : RAIL) + 20;
    }

    private int nowRight() {
        return (sheetT > 0 ? sheetLeft() : width) - 12;
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
        g.pose().pushPose();
        g.pose().translate(left, 12, 0);
        g.pose().scale(2, 2, 1);
        Component title = Component.translatable("screen.kuronami_quest_tree.now");
        g.drawString(font, title, 0, 0, 0xFFFFFFFF, true);
        g.pose().popPose();
        int tx = left + font.width(title) * 2 + 10;
        if (questTotal > 0) {
            g.drawString(font, doneTotal + "/" + questTotal, tx, 20, GRAY, true);
        }
        if (claimableNoChoice > 0) {
            Component ca = Component.translatable("screen.kuronami_quest_tree.claim_all");
            int bw = font.width(ca) + 16;
            button(g, ca, nowRight() - bw, 10, bw, Tiles.GOLD, true, mouseX, mouseY, () -> {
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
        int top = 40;
        int recentH = recentHeight();
        int bottom = Math.min(height - 12 - recentH, height - 34);
        int cols = nowCols();
        int cellW = nowCellWidth();
        int rows = (nowEntries.size() + cols - 1) / cols;
        int rowH = NOW_TILE + NOW_GAP;
        nowScroll = Mth.clamp(nowScroll, 0, Math.max(0, rows * rowH - (bottom - top)));
        g.enableScissor(left - 4, top - 4, nowRight() + 4, bottom);
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
                // the whole cell is the target; selected = a light band behind it, hover = a fainter one
                g.fill(x - 2, y - 2, x + cellW + 2, y + NOW_TILE + 2, e.key.equals(selected) ? 0x33FFFFFF : 0x14FFFFFF);
            }
            renderNowText(g, e, x + NOW_TILE + 8, y, cellW - NOW_TILE - 8);
            if (e.pointsChapter != null) {
                Tiles.tile(g, "square", x, y, NOW_TILE, Tiles.Look.AFFORDABLE, 1, e.pointsChapter.displayIcon(), false);
                g.pose().pushPose();
                g.pose().translate(0, 0, OVERLAY_Z);
                String pts = String.valueOf(ClientProgress.points(e.key));
                g.drawString(font, pts, x + NOW_TILE - 3 - font.width(pts), y + NOW_TILE - 10, Tiles.GOLD, true);
                g.pose().popPose();
            } else {
                Tiles.tile(g, e.node.shape, x, y, NOW_TILE, ClientProgress.look(e.key), Motion.fill(e.key, ClientProgress.fraction(e.key)), ClientProgress.iconFor(e.node), false);
                if (Pins.has(e.key)) {
                    g.pose().pushPose();
                    g.pose().translate(0, 0, OVERLAY_Z);
                    g.fill(x + 3, y + 3, x + 7, y + 7, 0xFFFFFFFF);
                    g.pose().popPose();
                }
            }
            flash(g, e.key, x, y, NOW_TILE, now);
        }
        g.disableScissor();
        if (rows * rowH > bottom - top) {
            int trackH = bottom - top;
            int barH = Math.max(10, trackH * trackH / (rows * rowH));
            int barY = top + (trackH - barH) * nowScroll / Math.max(1, rows * rowH - trackH);
            g.fill(nowRight() + 6, barY, nowRight() + 8, barY + barH, 0x55FFFFFF);
        }
        renderRecent(g, m, left, height - 8 - recentH);
    }

    /** Title (white) and chapter (grey) beside a Now tile; a skill chapter shows its points to spend in gold. */
    private void renderNowText(GuiGraphics g, NowEntry e, int x, int y, int w) {
        Component title;
        Component sub;
        int subColor = GRAY;
        if (e.pointsChapter != null) {
            title = e.pointsChapter.title;
            sub = Component.translatable("screen.kuronami_quest_tree.points", ClientProgress.points(e.key));
            subColor = Tiles.GOLD;
        } else {
            title = e.node.title;
            ClientModel.Chapter c = model().chapterOf.get(e.key);
            sub = c == null ? Component.empty() : c.title;
        }
        g.drawString(font, trim(title.getString(), w), x, y + 5, 0xFFFFFFFF, true);
        g.drawString(font, trim(sub.getString(), w), x, y + 18, subColor, true);
    }

    /** Recent entries shown under Now: fewer on short screens, so the Now cells keep the room. */
    private int recentRows() {
        return height < 320 ? 3 : 5;
    }

    private int recentHeight() {
        int n = Math.min(recentRows(), ClientProgress.progress().recent.size());
        return n == 0 ? 0 : 16 + n * 18;
    }

    private void renderRecent(GuiGraphics g, ClientModel m, int x, int y) {
        PlayerProgress p = ClientProgress.progress();
        if (p.recent.isEmpty()) {
            return;
        }
        g.drawString(font, Component.translatable("screen.kuronami_quest_tree.recent"), x, y, GRAY, true);
        y += 14;
        int shown = 0;
        long now = System.currentTimeMillis();
        for (String r : p.recent) {
            if (shown++ >= recentRows()) {
                break;
            }
            String[] parts = r.split("\\|");
            if (parts.length < 3) {
                continue;
            }
            ClientModel.Node n = m.nodesByKey.get(parts[1]);
            ClientModel.Chapter c = n != null ? m.chapterOf.get(parts[1]) : chapterById(m, parts[1].endsWith("/") ? parts[1].substring(0, parts[1].length() - 1) : "");
            ItemStack icon = n != null ? n.icon : c != null ? c.displayIcon() : ItemStack.EMPTY;
            Component name = n != null ? n.title : c != null ? c.title : Component.literal(parts[1]);
            g.renderItem(icon, x, y);
            g.pose().pushPose();
            g.pose().translate(0, 0, OVERLAY_Z);
            String kind = Component.translatable("event.kuronami_quest_tree." + parts[0]).getString();
            String ago = ago(now - parseLong(parts[2]));
            int nameW = Math.min(font.width(name.getString()), 200);
            g.drawString(font, trim(name.getString(), 200), x + 20, y + 4, 0xFFFFFFFF, true);
            g.drawString(font, kind, x + 26 + nameW, y + 4, GRAY, true);
            g.drawString(font, ago, x + 32 + nameW + font.width(kind), y + 4, 0xFF707070, true);
            g.pose().popPose();
            y += 18;
        }
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
        float availW = width - RAIL - 24;
        float availH = height - 40;
        float z = (float) Mth.clamp(Math.min(availW / (b[2] - b[0] + 2 * UNIT), availH / (b[3] - b[1] + 2 * UNIT)), 0.3, 1.5);
        float cx = (float) (b[0] + b[2]) / 2f;
        float cy = (float) (b[1] + b[3]) / 2f;
        return new float[]{RAIL + 12 + availW / 2f - cx * z, 30 + availH / 2f - cy * z, z};
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
    private static final int LINE_WALKED = 0xFF4F8A3E;
    private static final int LINE_OPEN = 0xFFC6C6C6;
    private static final int LINE_CLOSED = 0xFF3C3C42;

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

    private void renderChapter(GuiGraphics g, ClientModel.Chapter ch, int mouseX, int mouseY) {
        float[] v = view(ch);
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
        // lines
        // lines; authors hide some to keep busy maps readable (chapter default, or per node in either direction)
        ClientModel cm = model();
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
                int lc = lineColor(ClientProgress.state(p.key), cs);
                // open and walked ways are thick, ways into what is still closed are thin
                line(g, p.x * UNIT, p.y * UNIT, n.x * UNIT, n.y * UNIT, n.optional || lc == LINE_CLOSED ? 1 : 3, lc);
                float grow = Motion.progress(Motion.UNLOCKED, p.key, Motion.LINE_GROW + 400);
                if (grow >= 0) {
                    // unlock chain: a bright line runs from the unlocked node to each child (300ms), then fades
                    long ms = (long) (grow * (Motion.LINE_GROW + 400));
                    float reach = Math.min(1, ms / (float) Motion.LINE_GROW);
                    float fade = ms < Motion.LINE_GROW ? 1 : 1 - (ms - Motion.LINE_GROW) / 400f;
                    line(g, p.x * UNIT, p.y * UNIT, p.x * UNIT + (n.x - p.x) * UNIT * reach, p.y * UNIT + (n.y - p.y) * UNIT * reach,
                            3, Tiles.withAlpha(0xFFFFFFFF, fade));
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
            if (n.key.equals(selected)) {
                Tiles.shape(g, shape, x - 3, y - 3, s + 6, s + 6, 0xFFFFFFFF);
                Tiles.shape(g, shape, x - 1, y - 1, s + 2, s + 2, 0xFF141416);
            }
            if (n.optional && !far) {
                // optional quests: a broken outline, so they read as "extra" next to the required path
                dashedOutline(g, x - 2, y - 2, s + 4, s + 4, 0x66FFFFFF);
            }
            Tiles.tile(g, shape, x, y, s, ClientProgress.look(n.key), Motion.fill(n.key, ClientProgress.fraction(n.key)), ClientProgress.iconFor(n), far);
            flash(g, n.key, x, y, s, now);
            resultMotion(g, n.key, shape, x, y, s);
            if (path().nodes().size() > 1 && pathSet.contains(n.key) && !n.key.equals(selected)) {
                // the unfinished requirements on the way to the selected node
                g.pose().pushPose();
                g.pose().translate(0, 0, OVERLAY_Z + 15);
                dashedOutline(g, x - 3, y - 3, s + 6, s + 6, 0xCCFFFFFF);
                g.pose().popPose();
            }
            if (searching() && !matchSet.contains(n.key)) {
                g.pose().pushPose();
                g.pose().translate(0, 0, OVERLAY_Z + 20);
                Tiles.shape(g, shape, x - 1, y - 1, s + 2, s + 2, 0xC00A0A0C);
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
            Tiles.tile(g, shape, x, y, s, ClientProgress.look(target.key), Motion.fill(target.key, ClientProgress.fraction(target.key)), ClientProgress.iconFor(target), far);
            if (!far) {
                g.pose().pushPose();
                g.pose().translate(0, 0, OVERLAY_Z);
                Tiles.pixels(g, LINK_ARROW, x + s - 6, y + 1, 1, 0xFFFFFFFF);
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
        List<Component> decoTip = hovered == null && !dragging ? decorationAt(ch, v, mouseX, mouseY, true) : null;

        // everything below sits above the map's item icons
        g.pose().pushPose();
        g.pose().translate(0, 0, OVERLAY_Z);
        // the header moves aside with the chapter list when it opens, instead of showing through it
        int hx0 = railRight() + 10;
        g.pose().pushPose();
        g.pose().translate(hx0, 10, 0);
        g.pose().scale(2, 2, 1);
        g.drawString(font, ch.title, 0, 0, 0xFFFFFFFF, true);
        g.pose().popPose();
        if (ch.skills) {
            renderSkillHeader(g, ch, hx0, 30);
        } else {
            int[] cnt = chapterCounts.getOrDefault(ch.id, new int[]{0, ch.nodes.size(), 0});
            String count = cnt[0] + "/" + cnt[1];
            g.drawString(font, count, hx0, 30, GRAY, true);
            if (cnt[2] > 0) {
                // more is hidden (Steam's "n hidden achievements remaining"): a lock and the number, never what it is
                int hx = hx0 + font.width(count) + 6;
                Tiles.pixels(g, Tiles.LOCK, hx, 31, 1, 0xFF707070);
                g.drawString(font, "+" + cnt[2], hx + 7, 30, 0xFF707070, true);
            }
        }
        renderMinimap(g, ch, v);
        renderZoomButtons(g, ch, v, mouseX, mouseY);
        g.pose().popPose();
        if (hovered != null && !dragging && selected == null) {
            g.pose().pushPose();
            g.pose().translate(0, 0, OVERLAY_Z + 200);
            g.renderTooltip(font, model().nodesByKey.get(hovered).title, mouseX, mouseY);
            g.pose().popPose();
        } else if (decoTip != null && !decoTip.isEmpty()) {
            g.pose().pushPose();
            g.pose().translate(0, 0, OVERLAY_Z + 200);
            g.renderComponentTooltip(font, decoTip, mouseX, mouseY);
            g.pose().popPose();
        }
    }

    /** Level, experience bar and points to spend, under a skill chapter's title. */
    private void renderSkillHeader(GuiGraphics g, ClientModel.Chapter ch, int x, int y) {
        PlayerProgress p = ClientProgress.progress();
        int level = p.level.getOrDefault(ch.id, 0);
        String lv = Component.translatable("screen.kuronami_quest_tree.level_short", level).getString();
        g.drawString(font, lv, x, y, 0xFFFFFFFF, true);
        int bx = x + font.width(lv) + 6;
        var rules = ClientProgress.rules();
        var mc = rules == null ? null : rules.model().chapters.get(ch.id);
        double need = mc == null ? 0 : rules.experienceForLevel(mc, level);
        double frac = need <= 0 ? 0 : Mth.clamp(p.xp.getOrDefault(ch.id, 0.0) / need, 0, 1);
        g.fill(bx, y + 3, bx + 60, y + 6, 0xFF2A2A2A);
        g.fill(bx, y + 3, bx + (int) (60 * frac), y + 6, Tiles.GREEN_TEXT);
        int pts = ClientProgress.points(ch.id);
        if (pts > 0) {
            Component t = Component.translatable("screen.kuronami_quest_tree.points", pts);
            g.drawString(font, t, bx + 68, y, Tiles.GOLD, true);
        }
    }

    /** Right edge of the rail as currently drawn (it opens to RAIL_OPEN on hover). */
    private int railRight() {
        return (int) Mth.lerp(ease(railT), RAIL, RAIL_OPEN) + 4;
    }

    private int sheetWidth() {
        // on small screens (GUI scale 4 on 1080p is 480 wide) a third of the width, so the map keeps most of the room
        return Math.min(200, Math.max(150, (int) (width * 0.34f)));
    }

    private int sheetLeft() {
        return width - (int) (sheetWidth() * ease(sheetT));
    }

    // ------------------------------------------------------------------ details sheet

    private void renderSheet(GuiGraphics g, ClientModel m, int mouseX, int mouseY) {
        int sw = sheetWidth();
        int x0 = sheetLeft();
        g.fill(x0, 0, x0 + sw, height, PANEL);
        ClientModel.Node n = selected == null ? null : m.nodesByKey.get(selected);
        if (n == null) {
            return;
        }
        ClientModel.Chapter ch = m.chapterOf.get(n.key);
        State state = ClientProgress.state(n.key);
        boolean working = state == State.OPEN || state == State.PROGRESS;
        int x = x0 + 12;
        int w = sw - 24;
        int y = 14;
        Tiles.pixels(g, Tiles.CROSS, x0 + sw - 14, 10, 1, 0xFFC8C8C8);
        buttons.add(new Button(x0 + sw - 20, 4, 18, 18, () -> selected = null));
        g.drawString(font, trim(ch.title.getString(), w - 16), x, y, GRAY, true);
        y += 12;
        List<FormattedCharSequence> titleLines = font.split(n.title, w / 2);
        if (titleLines.size() <= 2) {
            for (FormattedCharSequence line : titleLines) {
                g.pose().pushPose();
                g.pose().translate(x, y, 0);
                g.pose().scale(2, 2, 1);
                g.drawString(font, line, 0, 0, 0xFFFFFFFF, true);
                g.pose().popPose();
                y += 20;
            }
        } else {
            for (FormattedCharSequence line : font.split(n.title, w)) {
                g.drawString(font, line, x, y, 0xFFFFFFFF, true);
                y += 10;
            }
        }
        int actionsTop = height - 34;
        y += 4;
        // pack authors can keep a quest's details secret until it can be started, and its text until it is done
        boolean details = !(n.hideDetailsUntilStartable && state == State.LOCKED);
        boolean text = details && !(n.hideTextUntilComplete && state != State.DONE && state != State.CLAIM);
        for (String d : text ? n.description : List.<String>of()) {
            for (FormattedCharSequence line : font.split(Component.literal(d), w)) {
                if (y > actionsTop - 70) {
                    break;
                }
                g.drawString(font, line, x, y, GRAY, true);
                y += 10;
            }
        }
        if (!n.requires.isEmpty() && state == State.LOCKED) {
            y += 8;
            g.drawString(font, Component.translatable("screen.kuronami_quest_tree.needs"), x, y, GRAY, true);
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
                    g.drawString(font, "???", x + 22, y + 4, GRAY, true);
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
                    g.drawString(font, trim(p.title.getString(), w - 22), x + 22, y, 0xFFFFFFFF, true);
                    g.drawString(font, trim(other.title.getString(), w - 22), x + 22, y + 9, GRAY, true);
                    String key = r;
                    buttons.add(new Button(x, y, w, 18, () -> goTo(key)));
                } else {
                    g.drawString(font, trim(p.title.getString(), w - 22), x + 22, y + 4, 0xFFFFFFFF, true);
                }
                g.pose().popPose();
                y += 20;
                if (y > actionsTop - 50) {
                    break;
                }
            }
        }
        if (!n.tasks.isEmpty() && details) {
            y += 8;
            g.drawString(font, Component.translatable("screen.kuronami_quest_tree.tasks"), x, y, GRAY, true);
            y += 12;
            PlayerProgress p = ClientProgress.progress();
            for (int i = 0; i < n.tasks.size(); i++) {
                ClientModel.Task t = n.tasks.get(i);
                long have = Math.min(p.task(n.key, i), t.count);
                boolean met = have >= t.count || state == State.DONE || state == State.CLAIM;
                g.fill(x, y - 2, x + w, y - 1, 0x1AFFFFFF);
                if (!t.icon().isEmpty()) {
                    g.renderItem(t.icon(), x, y + 2);
                } else if (t.type.equals("checkmark")) {
                    Tiles.pixels(g, Tiles.TICK, x + 4, y + 6, 2, met ? Tiles.GREEN_TEXT : 0xFFD0D0D0);
                }
                if (t.optional) {
                    // optional tasks: the same broken outline as optional quests on the map
                    dashedOutline(g, x - 1, y + 1, 18, 18, 0x66FFFFFF);
                }
                g.pose().pushPose();
                g.pose().translate(0, 0, OVERLAY_Z);
                String cnt = (met ? t.count : have) + "/" + t.count;
                int right = x + w;
                if (!met && working && (t.consume || t.type.equals("checkmark"))) {
                    Component label = Component.translatable(t.type.equals("checkmark") ? "screen.kuronami_quest_tree.check" : "screen.kuronami_quest_tree.submit");
                    int bw = font.width(label) + 10;
                    int index = i;
                    button(g, label, right - bw, y + 1, bw, 0, true, mouseX, mouseY, () -> {
                        ClientProgress.send(t.type.equals("checkmark") ? "check" : "submit", n.key, index);
                        ProgressFeedback.click();
                    });
                    right -= bw + 4;
                }
                float bump = Motion.progress(Motion.SUBMITTED, n.key + "#" + i, 200);
                if (bump >= 0) {
                    // hand-in: the count turns green and bumps (200ms)
                    float k = 1 + 0.35f * (1 - bump);
                    g.pose().pushPose();
                    g.pose().translate(right - font.width(cnt) / 2f, y + 10, 0);
                    g.pose().scale(k, k, 1);
                    g.drawString(font, cnt, -font.width(cnt) / 2, -4, Tiles.GREEN_TEXT, true);
                    g.pose().popPose();
                } else {
                    g.drawString(font, cnt, right - font.width(cnt), y + 6, met ? Tiles.GREEN_TEXT : GRAY, true);
                }
                g.drawString(font, trim(ProgressFeedback.taskName(t).getString(), right - font.width(cnt) - x - 26), x + 20, y + 6,
                        met || t.optional ? GRAY : 0xFFFFFFFF, true);
                g.pose().popPose();
                y += 20;
                if (y > actionsTop - 40) {
                    break;
                }
            }
        }
        if ((!n.rewards.isEmpty() || !n.rewardLines.isEmpty()) && details) {
            y += 8;
            g.drawString(font, Component.translatable("screen.kuronami_quest_tree.rewards"), x, y, GRAY, true);
            y += 12;
            int rx = x;
            for (ItemStack r : n.rewards) {
                slot(g, r, rx, y, false);
                rx += 26;
                if (rx + 22 > x + w) {
                    break;
                }
            }
            if (!n.rewards.isEmpty()) {
                y += 28;
            }
            for (String line : n.rewardLines) {
                g.drawString(font, line, x, y, 0xFFFFFFFF, true);
                y += 10;
            }
        }
        if (!n.choice.isEmpty() && details) {
            y += 8;
            g.drawString(font, Component.translatable("screen.kuronami_quest_tree.choose"), x, y, GRAY, true);
            y += 12;
            int rx = x;
            for (int i = 0; i < n.choice.size(); i++) {
                int index = i;
                boolean pickable = state == State.CLAIM;
                slot(g, n.choice.get(i), rx, y, index == choicePick);
                if (pickable) {
                    buttons.add(new Button(rx, y, 22, 22, () -> {
                        choicePick = index;
                        ProgressFeedback.click();
                    }));
                }
                rx += 26;
                if (rx + 22 > x + w) {
                    rx = x;
                    y += 26;
                }
            }
        }
        renderActions(g, n, ch, state, x, actionsTop, w, mouseX, mouseY);
    }

    private void renderActions(GuiGraphics g, ClientModel.Node n, ClientModel.Chapter ch, State state, int x, int y, int w, int mouseX, int mouseY) {
        int bx = x;
        if (ch.skills && n.tasks.isEmpty()) {
            if (state == State.CLAIM) {
                Component take = n.cost > 0 ? Component.translatable("screen.kuronami_quest_tree.unlock_cost", n.cost)
                        : Component.translatable("screen.kuronami_quest_tree.unlock");
                int bw = font.width(take) + 16;
                button(g, take, bx, y, bw, Tiles.WHITE, true, mouseX, mouseY, () -> {
                    ClientProgress.send("take", n.key, 0);
                    flashes.put(n.key, Util.getMillis());
                });
            } else {
                PathFinder.Path p = path();
                int points = ClientProgress.points(ch.id);
                if (p.nodes().size() > 1 && p.batchable() > 0) {
                    // the way there in one go (stops before an exclusive choice, which stays the player's pick)
                    Component all = Component.translatable("screen.kuronami_quest_tree.unlock_path", p.batchable(), p.batchCost());
                    int bw = font.width(all) + 16;
                    boolean afford = points >= p.batchCost();
                    List<String> batch = p.nodes().subList(0, p.batchable());
                    button(g, all, bx, y, bw, Tiles.WHITE, afford, mouseX, mouseY, () -> {
                        long t = Util.getMillis();
                        int i = 0;
                        for (String k : batch) {
                            ClientProgress.send("take", k, 0);
                            flashes.put(k, t + 110L * i++);
                        }
                    });
                    if (!afford) {
                        String need = p.batchCost() + "";
                        g.drawString(font, Component.translatable("screen.kuronami_quest_tree.cost", need), bx + bw + 6, y + 6, GRAY, true);
                    }
                } else if (state == State.OPEN && n.cost > 0) {
                    g.drawString(font, Component.translatable("screen.kuronami_quest_tree.cost", n.cost), bx, y + 6, GRAY, true);
                }
            }
            return;
        }
        if (state == State.CLAIM) {
            boolean ready = n.choice.isEmpty() || choicePick >= 0;
            Component claim = Component.translatable("screen.kuronami_quest_tree.claim");
            int bw = font.width(claim) + 16;
            button(g, claim, bx, y, bw, Tiles.GOLD, ready, mouseX, mouseY, () -> {
                ClientProgress.send("claim", n.key, n.choice.isEmpty() ? -1 : choicePick);
                flashes.put(n.key, Util.getMillis());
                choicePick = -1;
            });
            bx += bw + 6;
        }
        if (state != State.DONE && state != State.EXCLUDED) {
            Component pin = Component.translatable(Pins.has(n.key) ? "screen.kuronami_quest_tree.pinned" : "screen.kuronami_quest_tree.pin");
            int bw = font.width(pin) + 16;
            button(g, pin, bx, y, bw, 0, true, mouseX, mouseY, () -> {
                Pins.toggle(n.key);
                ProgressFeedback.click();
                cachedFor = null;
            });
        }
    }

    private void slot(GuiGraphics g, ItemStack r, int x, int y, boolean picked) {
        if (picked) {
            g.renderOutline(x - 2, y - 2, 26, 26, 0xFFFFFFFF);
        }
        g.fill(x, y, x + 22, y + 22, Tiles.TILE);
        g.renderItem(r, x + 3, y + 3);
        g.renderItemDecorations(font, r, x + 3, y + 3);
    }

    /** Flat button: yellow with dark text for the one action that matters, dark with white text otherwise. */
    /**
     * Flat button. {@code accent}: gold for taking a reward (claim), white for spending (unlock), 0 for the quiet ones.
     */
    private void button(GuiGraphics g, Component label, int x, int y, int w, int accent, boolean enabled, int mouseX, int mouseY, Runnable run) {
        int h = 20;
        boolean primary = accent != 0;
        boolean hover = enabled && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        int bg = !enabled ? 0x66000000 : primary ? (hover ? lerpColor(accent, 0xFFFFFFFF, 0.25f) : accent) : (hover ? 0xCC323236 : 0x99000000);
        g.fill(x, y, x + w, y + h, bg);
        if (primary && enabled) {
            g.fill(x, y + h - 2, x + w, y + h, lerpColor(accent, 0xFF000000, 0.3f));
        }
        int color = !enabled ? 0xFF6A6A6A : primary ? Tiles.INK : 0xFFFFFFFF;
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + 6, color, primary ? false : true);
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
            g.fill(x, y, x + s, y + s, Tiles.withAlpha(0xFFFFFFFF, a));
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
            Tiles.shape(g, shape, x, y, s, s, Tiles.withAlpha(0xFFFFFFFF, 0.6f * (1 - open)));
        }
        if (stamp >= 0) {
            // the cross lands: from twice its size down to its place
            int px = Math.max(1, Math.round(s / 20f * (2 - stamp)));
            Tiles.pixels(g, Tiles.CROSS, x + s / 2 - 5 * px / 2, y + s / 2 - 5 * px / 2, px, Tiles.withAlpha(0xFFFFFFFF, 0.4f + 0.6f * stamp));
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
        int right = (sheetT > 0 ? sheetLeft() : width) - 8;
        String[][] glyphs = {
                {"..#..", "#####", "#.#.#", "#####", "..#.."},
                {".....", ".....", "#####", ".....", "....."},
                {"..#..", "..#..", "#####", "..#..", "..#.."}};
        for (int i = 0; i < 3; i++) {
            int x = right - (3 - i) * (BTN + 3);
            int y = height - BTN - 8;
            boolean hover = mouseX >= x && mouseX < x + BTN && mouseY >= y && mouseY < y + BTN;
            g.fill(x, y, x + BTN, y + BTN, hover ? CONTROL_HOVER : CONTROL);
            Tiles.pixels(g, glyphs[i], x + 7, y + 7, 1, 0xFFFFFFFF);
            int which = i;
            buttons.add(new Button(x, y, BTN, BTN, () -> zoomButton(ch, v, which)));
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
        int mw = 110;
        int mh = 70;
        int right = (sheetT > 0 ? sheetLeft() : width) - 8;
        return new int[]{right - mw, height - BTN - 16 - mh, mw, mh};
    }

    private void renderMinimap(GuiGraphics g, ClientModel.Chapter ch, float[] v) {
        int[] r = minimapRect(ch, v);
        if (r == null) {
            return;
        }
        g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], PANEL);
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
        g.renderOutline(x0, y0, Math.max(2, x1 - x0), Math.max(2, y1 - y0), 0xFFFFFFFF);
    }

    private static int minimapColor(State s) {
        return switch (s) {
            case DONE -> Tiles.GREEN;
            case CLAIM -> Tiles.GOLD;
            case OPEN, PROGRESS -> 0xFF9A9A9A;
            default -> 0xFF4A4A4C;
        };
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        ClientModel m = model();
        if (searchBox != null && mx >= searchBox.getX() - 6 && mx < searchBox.getX() + searchBox.getWidth() + 6
                && my >= searchBox.getY() - 6 && my < searchBox.getY() - 6 + BTN) {
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
        int railW = railT > 0.5f ? RAIL_OPEN : RAIL;
        if (mx < railW + 4) {
            int row = (int) Math.floor((my - 8) / 22);
            if (row == 0) {
                select(-1);
            } else if (row >= 1 && row <= railRows()) {
                int index = railScroll + row - 1;
                if (index < railChapters.size()) {
                    select(m.chapters.indexOf(railChapters.get(index)));
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
        int[] r = minimapRect(ch, v);
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
        int bottom = Math.min(height - 12 - recentHeight(), height - 34);
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

    private static final String[] LINK_ARROW = {"..###", "...##", "..#.#", ".#...", "#...."};

    private boolean decorationVisible(ClientModel.Decoration d) {
        if (d.dev || d.image == null) {
            return false;
        }
        return d.visibleAfter == null || com.kuronami.questtree.progress.ProgressRules.doneOnce(ClientProgress.progress(), d.visibleAfter);
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
        chapterIndex = index;
        lastChapter = index;
        selected = null;
        choicePick = -1;
        sheetT = 0;
    }

    private void zoomButton(ClientModel.Chapter ch, float[] v, int which) {
        switch (which) {
            case 0 -> VIEWS.put(ch.id, fitView(ch));
            case 1 -> zoomAt(v, width / 2.0, height / 2.0, 1 / 1.25f);
            default -> zoomAt(v, width / 2.0, height / 2.0, 1.25f);
        }
    }

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

    /** Shift the map so the selected node is not hidden under the sheet. */
    private void keepVisible(float[] v, ClientModel.Node n) {
        double sx = v[0] + n.x * UNIT * v[2];
        int limit = width - sheetWidth() - 24;
        if (sx > limit) {
            v[0] -= (float) (sx - (RAIL + (limit - RAIL) / 2.0));
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        ClientModel m = model();
        if (m == null) {
            return super.mouseScrolled(mx, my, scrollX, scrollY);
        }
        if (mx < railRight()) {
            railScroll = Mth.clamp(railScroll - (int) Math.signum(scrollY), 0, Math.max(0, railChapters.size() - railRows()));
            return true;
        }
        if (sheetT > 0 && selected != null && mx >= sheetLeft()) {
            return true;
        }
        ClientModel.Chapter ch = chapter();
        if (ch == null) {
            nowScroll = Math.max(0, nowScroll - (int) (scrollY * 20));
            return true;
        }
        float[] v = view(ch);
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
        if (ClientProgress.OPEN_KEY.matches(key, scan)) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override
    public void removed() {
        lastChapter = chapterIndex;
        super.removed();
    }

    // ------------------------------------------------------------------ drawing helpers

    /** Draws a line of {@code thickness} GUI px between two points in the current pose. */
    private static void line(GuiGraphics g, double x1, double y1, double x2, double y2, int thickness, int argb) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1) {
            return;
        }
        g.pose().pushPose();
        g.pose().translate(x1, y1, 0);
        g.pose().mulPose(Axis.ZP.rotation((float) Math.atan2(dy, dx)));
        g.fill(0, -thickness / 2, (int) len, thickness - thickness / 2, argb);
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
