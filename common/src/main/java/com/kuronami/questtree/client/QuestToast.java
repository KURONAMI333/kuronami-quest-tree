package com.kuronami.questtree.client;

import com.kuronami.questtree.progress.ProgressRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** "Quest complete" in the vanilla advancement toast frame, with the quest's state tile and the mod's own toast sounds. */
final class QuestToast implements Toast, OwnSoundToast {

    private static final ResourceLocation BACKGROUND = ResourceLocation.withDefaultNamespace("toast/advancement");
    private static final long SHOW = 3000;

    private final ClientModel.Node node;
    private final ProgressRules.State state;
    private final Component top;

    private QuestToast(ClientModel.Node node, ProgressRules.State state, Component top) {
        this.node = node;
        this.state = state;
        this.top = top;
    }

    static void show(ClientModel.Node node, ProgressRules.State state, Component top) {
        Minecraft.getInstance().getToasts().addToast(new QuestToast(node, state, top));
    }

    @Override
    public Visibility render(GuiGraphics g, ToastComponent toasts, long time) {
        g.blitSprite(BACKGROUND, 0, 0, width(), height());
        Tiles.tile(g, node.shape, 6, 6, 20, state, 1, node.icon, false);
        var font = toasts.getMinecraft().font;
        g.drawString(font, top, 32, 7, 0xFFFF55, false);
        g.drawString(font, font.plainSubstrByWidth(node.title.getString(), width() - 38), 32, 18, 0xFFFFFF, false);
        return time >= SHOW * toasts.getNotificationDisplayTimeMultiplier() ? Visibility.HIDE : Visibility.SHOW;
    }
}
