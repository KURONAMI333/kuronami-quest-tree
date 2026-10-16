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
    private static boolean noticeHeld;
    private static boolean tracker;
    /** Closest unfinished quests, recomputed only when new progress arrives (scanning every node per frame is too slow). */
    private static final List<ClientModel.Node> CLOSEST = new ArrayList<>();
    private static Object closestFor;

    private ProgressHud() {
    }

    static void notice(ItemStack icon, Component task, long have, long need, Component quest, boolean held) {
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
        noticeHeld = held;
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
        // scheduled sounds play even with the HUD hidden (F1), so they never pile up
        Motion.tick();
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
        if (noticeHeld) {
            count = Component.translatable("hud.kuronami_quest_tree.held", count).getString();
        }
        String task = font.plainSubstrByWidth(noticeTask.getString(), 120);
        String quest = font.plainSubstrByWidth(noticeQuest.getString(), 120);
        int w = 20 + font.width(task) + 6 + font.width(count) + 10 + font.width(quest) + 6;
        int x = (g.guiWidth() - w) / 2;
        // above the hotbar, hearts and the vanilla action bar: -82 ran into the action bar text at -72 (review 2026-10-16)
        int y = g.guiHeight() - 96 + (int) ((1 - Math.min(1, t / (float) FADE_IN)) * 3);
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
            if (n != null && ClientProgress.state(key) != State.DONE && ClientProgress.visible(key)) {
                rows.add(n);
            }
        }
        if (rows.isEmpty()) {
            if (closestFor != ClientProgress.progress()) {
                closestFor = ClientProgress.progress();
                CLOSEST.clear();
                m.nodesByKey.values().stream()
                        .filter(n -> !n.tasks.isEmpty() && ClientProgress.state(n.key) == State.PROGRESS && ClientProgress.visible(n.key))
                        .sorted(Comparator.comparingDouble((ClientModel.Node n) -> -ClientProgress.fraction(n.key)))
                        .limit(3).forEach(CLOSEST::add);
            }
            rows.addAll(CLOSEST);
        }
        int total = 0;
        for (ClientModel.Node n : rows) {
            total += 22 + 12 * materials(n).size();
        }
        int y = g.guiHeight() / 2 - total / 2;
        for (ClientModel.Node n : rows) {
            State s = ClientProgress.state(n.key);
            List<long[]> mats = materials(n);
            String title = font.plainSubstrByWidth(n.title.getString(), 110);
            String line = progressLine(n);
            // a band behind the entry: the tracker sits over terrain (snow, sky) where plain text is unreadable
            int bw = Math.max(26 + font.width(title), 26 + font.width(line));
            for (long[] mat : mats) {
                bw = Math.max(bw, 38 + font.width(Math.min(mat[1], mat[2]) + "/" + mat[2]));
            }
            g.fill(2, y - 2, bw + 2, y + 22 + 12 * mats.size() + 2, Tiles.withAlpha(0x66000000, 1));
            Tiles.tile(g, n.shape, 4, y, 18, ClientProgress.look(n.key), ClientProgress.fraction(n.key), ClientProgress.iconFor(n), false);
            g.pose().pushPose();
            g.pose().translate(0, 0, Tiles.OVERLAY_Z);
            g.drawString(font, title, 26, y + 1, 0xFFFFFF, true);
            g.drawString(font, line, 26, y + 10, s == State.CLAIM ? Tiles.GOLD : 0xFFE0E0E0, true);
            g.pose().popPose();
            y += 22;
            // the items still needed, with what is in the inventory now (Satisfactory's to-do list, kept per quest)
            for (long[] mat : mats) {
                ClientModel.Task t = n.tasks.get((int) mat[0]);
                g.pose().pushPose();
                g.pose().translate(26, y, 0);
                g.pose().scale(0.625f, 0.625f, 1);
                g.renderItem(t.icon(), 0, 0);
                g.pose().popPose();
                g.pose().pushPose();
                g.pose().translate(0, 0, Tiles.OVERLAY_Z);
                String c = Math.min(mat[1], mat[2]) + "/" + mat[2];
                g.drawString(font, c, 38, y + 1, mat[1] >= mat[2] ? Tiles.GREEN_TEXT : 0xFFB0B0B0, true);
                g.pose().popPose();
                y += 12;
            }
        }
    }

    /**
     * Unfinished item tasks of a pinned quest: {task index, in inventory now, still needed}. Up to four, only for
     * pinned quests (the closest-quests fallback stays one line each).
     */
    private static List<long[]> materials(ClientModel.Node n) {
        List<long[]> out = new ArrayList<>();
        var player = Minecraft.getInstance().player;
        if (player == null || !Pins.has(n.key)) {
            return out;
        }
        var p = ClientProgress.progress();
        for (int i = 0; i < n.tasks.size() && out.size() < 4; i++) {
            ClientModel.Task t = n.tasks.get(i);
            if (!t.type.equals("item") || t.item.isEmpty()) {
                continue;
            }
            long need = t.count - Math.min(p.task(n.key, i), t.count);
            if (need <= 0) {
                continue;
            }
            out.add(new long[]{i, player.getInventory().countItem(t.item.getItem()), need});
        }
        return out;
    }

    /** "n/N" of the first unfinished task, or the share done for nodes with several tasks. */
    static String progressLine(ClientModel.Node n) {
        var p = ClientProgress.progress();
        for (int i = 0; i < n.tasks.size(); i++) {
            long have = p.task(n.key, i);
            ClientModel.Task t = n.tasks.get(i);
            if (have < t.count) {
                return Math.min(have, t.count) + "/" + t.count + (n.tasks.size() > 1 ? "  " + ProgressFeedback.taskLine(t).getString() : "");
            }
        }
        return n.tasks.size() + "/" + n.tasks.size();
    }
}
