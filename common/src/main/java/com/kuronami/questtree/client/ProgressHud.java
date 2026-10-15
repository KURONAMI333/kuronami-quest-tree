package com.kuronami.questtree.client;

import com.kuronami.questtree.progress.ProgressRules.State;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * In-game display, variant B (decided 2026-10-15): nothing on screen while nothing happens. When a task moves, one
 * line appears above the hotbar for a moment. The tracker (pinned quests, or the closest ones when none are pinned)
 * is shown only after pressing its key.
 */
public final class ProgressHud {

    private static final long FADE_IN = 150;
    private static final long HOLD = 2200;
    private static final long FADE_OUT = 300;

    private static ItemStack noticeIcon = ItemStack.EMPTY;
    private static Component noticeTask;
    private static Component noticeQuest;
    private static long noticeHave;
    private static long noticeNeed;
    private static long noticeAt;
    private static boolean tracker;
    /** Closest unfinished quests, recomputed only when new progress arrives (scanning every node per frame is too slow). */
    private static final List<ClientModel.Node> CLOSEST = new ArrayList<>();
    private static Object closestFor;

    private ProgressHud() {
    }

    static void notice(ItemStack icon, Component task, long have, long need, Component quest) {
        long now = Util.getMillis();
        // a new notice while one is showing replaces it without fading in again
        if (noticeTask == null || now - noticeAt > FADE_IN + HOLD) {
            noticeAt = now;
        } else {
            noticeAt = now - FADE_IN;
        }
        noticeIcon = icon;
        noticeTask = task;
        noticeQuest = quest;
        noticeHave = Math.min(have, need);
        noticeNeed = need;
    }

    static void toggleTracker() {
        tracker = !tracker;
        ProgressFeedback.click();
    }

    static void clear() {
        noticeTask = null;
        CLOSEST.clear();
        closestFor = null;
    }

    /** Called by the loader's HUD layer after the vanilla HUD. */
    public static void render(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || ClientProgress.model() == null) {
            return;
        }
        renderNotice(g, mc.font);
        if (tracker) {
            renderTracker(g, mc.font);
        }
    }

    private static void renderNotice(GuiGraphics g, Font font) {
        if (noticeTask == null) {
            return;
        }
        long t = Util.getMillis() - noticeAt;
        float alpha;
        if (t < FADE_IN) {
            alpha = t / (float) FADE_IN;
        } else if (t < FADE_IN + HOLD) {
            alpha = 1;
        } else if (t < FADE_IN + HOLD + FADE_OUT) {
            alpha = 1 - (t - FADE_IN - HOLD) / (float) FADE_OUT;
        } else {
            noticeTask = null;
            return;
        }
        if (alpha < 0.05f) {
            return;
        }
        String count = noticeHave + "/" + noticeNeed;
        String task = font.plainSubstrByWidth(noticeTask.getString(), 120);
        String quest = font.plainSubstrByWidth(noticeQuest.getString(), 120);
        int w = 20 + font.width(task) + 6 + font.width(count) + 10 + font.width(quest) + 6;
        int x = (g.guiWidth() - w) / 2;
        // above the hotbar, hearts and the action bar line
        int y = g.guiHeight() - 82 + (int) ((1 - Math.min(1, t / (float) FADE_IN)) * 3);
        g.pose().pushPose();
        g.pose().translate(0, 0, -50);
        g.fill(x, y, x + w, y + 20, Tiles.withAlpha(0x66000000, alpha));
        if (alpha > 0.5f) {
            g.renderItem(noticeIcon, x + 2, y + 2);
        }
        int a = (int) (alpha * 255) << 24;
        int tx = x + 20;
        g.drawString(font, task, tx, y + 6, 0xFFFFFF | a, true);
        tx += font.width(task) + 6;
        g.drawString(font, count, tx, y + 6, (noticeHave >= noticeNeed ? Tiles.GREEN_TEXT & 0xFFFFFF : 0xE0E0E0) | a, true);
        tx += font.width(count) + 4;
        g.fill(tx + 1, y + 9, tx + 3, y + 11, 0x888888 | a);
        tx += 6;
        g.drawString(font, quest, tx, y + 6, 0xC8C8C8 | a, true);
        g.pose().popPose();
    }

    /** Pinned quests at the left edge, vertically centred; the closest unfinished quests when nothing is pinned. */
    private static void renderTracker(GuiGraphics g, Font font) {
        ClientModel m = ClientProgress.model();
        List<ClientModel.Node> rows = new ArrayList<>();
        for (String key : Pins.all()) {
            ClientModel.Node n = m.nodesByKey.get(key);
            if (n != null && ClientProgress.state(key) != State.DONE) {
                rows.add(n);
            }
        }
        if (rows.isEmpty()) {
            if (closestFor != ClientProgress.progress()) {
                closestFor = ClientProgress.progress();
                CLOSEST.clear();
                m.nodesByKey.values().stream()
                        .filter(n -> !n.tasks.isEmpty() && ClientProgress.state(n.key) == State.PROGRESS)
                        .sorted(Comparator.comparingDouble((ClientModel.Node n) -> -ClientProgress.fraction(n.key)))
                        .limit(3).forEach(CLOSEST::add);
            }
            rows.addAll(CLOSEST);
        }
        int y = g.guiHeight() / 2 - rows.size() * 11;
        for (ClientModel.Node n : rows) {
            State s = ClientProgress.state(n.key);
            Tiles.tile(g, n.shape, 4, y, 18, s, ClientProgress.fraction(n.key), n.icon, false);
            g.pose().pushPose();
            g.pose().translate(0, 0, Tiles.OVERLAY_Z);
            g.drawString(font, font.plainSubstrByWidth(n.title.getString(), 110), 26, y + 1, 0xFFFFFF, true);
            String line = progressLine(n);
            g.drawString(font, line, 26, y + 10, s == State.CLAIM ? Tiles.YELLOW : 0xFFE0E0E0, true);
            g.pose().popPose();
            y += 22;
        }
    }

    /** "n/N" of the first unfinished task, or the share done for nodes with several tasks. */
    static String progressLine(ClientModel.Node n) {
        var p = ClientProgress.progress();
        for (int i = 0; i < n.tasks.size(); i++) {
            long have = p.task(n.key, i);
            ClientModel.Task t = n.tasks.get(i);
            if (have < t.count) {
                return Math.min(have, t.count) + "/" + t.count + (n.tasks.size() > 1 ? "  " + ProgressFeedback.taskName(t).getString() : "");
            }
        }
        return n.tasks.size() + "/" + n.tasks.size();
    }
}
