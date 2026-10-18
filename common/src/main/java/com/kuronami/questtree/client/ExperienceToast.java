package com.kuronami.questtree.client;

import com.kuronami.questtree.progress.ProgressRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Experience gained in a skill chapter. One toast per chapter that is updated while experience keeps coming in (mining
 * gives experience per block), with a bar that eases to the new value. No sound for experience; level-ups sound once.
 */
final class ExperienceToast implements Toast, OwnSoundToast {

    private static final ResourceLocation BACKGROUND = ResourceLocation.withDefaultNamespace("toast/advancement");
    private static final long SHOW = 3000;

    private final ClientModel.Chapter chapter;
    private double shown;
    private double target;
    private int level;
    private boolean levelled;
    private boolean changed = true;
    private long lastChange;

    /** The latest experience per chapter while the progress screen was open, shown when it closes. */
    private static final Map<String, Pending> PENDING = new LinkedHashMap<>();

    private record Pending(ClientModel.Chapter chapter, double xp, int level, boolean levelled) {
    }

    private ExperienceToast(ClientModel.Chapter chapter) {
        this.chapter = chapter;
    }

    static void show(ClientModel.Chapter chapter, double xp, int level, boolean levelled) {
        if (PendingToasts.suppressed()) {
            // one toast per chapter: a newer value replaces the queued one, as the live toast would be updated
            PENDING.put(chapter.id, new Pending(chapter, xp, level, levelled));
            return;
        }
        var toasts = Minecraft.getInstance().getToasts();
        ExperienceToast t = toasts.getToast(ExperienceToast.class, chapter.id);
        boolean fresh = t == null;
        if (fresh) {
            t = new ExperienceToast(chapter);
        }
        double need = need(chapter, level);
        t.target = need <= 0 ? 0 : Mth.clamp(xp / need, 0, 1);
        if (levelled || t.level != level) {
            // a level-up runs the bar from empty
            t.shown = 0;
        }
        t.level = level;
        t.levelled |= levelled;
        t.changed = true;
        if (fresh) {
            toasts.addToast(t);
        }
    }

    /** Shows the experience toasts queued while the progress screen was open (called as it closes). */
    static void flushPending() {
        if (PENDING.isEmpty()) {
            return;
        }
        List<Pending> queued = new ArrayList<>(PENDING.values());
        PENDING.clear();
        for (Pending p : queued) {
            show(p.chapter(), p.xp(), p.level(), p.levelled());
        }
    }

    private static double need(ClientModel.Chapter c, int level) {
        ProgressRules r = ClientProgress.rules();
        var mc = r == null ? null : r.model().chapters.get(c.id);
        return mc == null ? 0 : r.experienceForLevel(mc, level);
    }

    @Override
    public Object getToken() {
        return chapter.id;
    }

    @Override
    public Visibility render(GuiGraphics g, ToastComponent toasts, long time) {
        if (changed) {
            lastChange = time;
            changed = false;
        }
        shown += (target - shown) * 0.25;
        g.blitSprite(BACKGROUND, 0, 0, width(), height());
        Tiles.tile(g, "square", 6, 6, 20, levelled ? Tiles.Look.AFFORDABLE : Tiles.Look.PROGRESS, shown,
                chapter.displayIcon(), false);
        var font = toasts.getMinecraft().font;
        Component top = levelled ? Component.translatable("toast.kuronami_quest_tree.level", level) : chapter.title;
        g.drawString(font, font.plainSubstrByWidth(top.getString(), width() - 38), 32, 7, levelled ? 0xFFFF55 : 0xFFFFFF, false);
        int x0 = 32;
        int x1 = width() - 10;
        g.fill(x0, 20, x1, 23, Ui.TOAST_TRACK);
        g.fill(x0, 20, x0 + (int) ((x1 - x0) * shown), 23, Tiles.GREEN_TEXT);
        return time - lastChange >= SHOW * toasts.getNotificationDisplayTimeMultiplier() ? Visibility.HIDE : Visibility.SHOW;
    }
}
