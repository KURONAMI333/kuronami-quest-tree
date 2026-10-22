package com.kuronami.questtree.client;

import com.kuronami.questtree.progress.ProgressRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** "Quest complete" in the vanilla advancement toast frame, with the quest's state tile and the mod's own toast sounds. */
final class QuestToast implements Toast, OwnSoundToast {

    private static final long SHOW = 3000;

    private final ClientModel.Node node;
    private final ProgressRules.State state;
    private final Component top;

    /** Quest toasts asked for while the progress screen was open, shown when it closes. */
    private static final List<Pending> PENDING = new ArrayList<>();

    private record Pending(ClientModel.Node node, ProgressRules.State state, Component top) {
    }

    private QuestToast(ClientModel.Node node, ProgressRules.State state, Component top) {
        this.node = node;
        this.state = state;
        this.top = top;
    }

    static void show(ClientModel.Node node, ProgressRules.State state, Component top) {
        if (PendingToasts.suppressed()) {
            PENDING.add(new Pending(node, state, top));
            return;
        }
        Minecraft.getInstance().getToasts().addToast(new QuestToast(node, state, top));
    }

    /** Shows the quest toasts queued while the progress screen was open (called as it closes). */
    static void flushPending() {
        if (PENDING.isEmpty()) {
            return;
        }
        List<Pending> queued = new ArrayList<>(PENDING);
        PENDING.clear();
        for (Pending p : queued) {
            show(p.node(), p.state(), p.top());
        }
    }

    @Override
    public Visibility render(GuiGraphics g, ToastComponent toasts, long time) {
        Ui.toastFrame(g, width(), height());
        Tiles.tile(g, node.shape, 6, 6, 20, state, 1, ClientProgress.iconFor(node), false, node.key);
        var font = toasts.getMinecraft().font;
        g.drawString(font, top, 32, 7, Ui.TOAST_LEVEL, false);
        g.drawString(font, font.plainSubstrByWidth(node.title.getString(), width() - 38), 32, 18, Ui.TOAST_TITLE, false);
        return time >= SHOW * toasts.getNotificationDisplayTimeMultiplier() ? Visibility.HIDE : Visibility.SHOW;
    }
}
