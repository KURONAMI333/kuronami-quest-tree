package com.kuronami.questtree.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/** A short gameplay notice in the vanilla advancement frame: an icon and at most two lines, never chat. */
final class NoticeToast implements Toast, OwnSoundToast {

    private static final ResourceLocation BACKGROUND = ResourceLocation.withDefaultNamespace("toast/advancement");
    private static final long SHOW = 3200;

    private final String icon;
    private final Component title;
    private final Component body;

    private NoticeToast(String icon, Component title, Component body) {
        this.icon = icon == null || icon.isBlank() ? "book" : icon;
        this.title = title;
        this.body = body;
    }

    static void show(String icon, Component title) {
        show(icon, title, null);
    }

    static void show(String icon, Component title, Component body) {
        Minecraft.getInstance().getToasts().addToast(new NoticeToast(icon, title, body));
    }

    @Override
    public Visibility render(GuiGraphics g, ToastComponent toasts, long time) {
        g.blitSprite(BACKGROUND, 0, 0, width(), height());
        Tiles.icon(g, icon, 7, 8, 16, "gift".equals(icon) ? Tiles.GOLD : Ui.MUTED);
        var font = toasts.getMinecraft().font;
        int room = width() - 38;
        if (body != null) {
            g.drawString(font, font.plainSubstrByWidth(title.getString(), room), 32, 7, Ui.TOAST_TITLE, false);
            g.drawString(font, font.plainSubstrByWidth(body.getString(), room), 32, 18, Ui.TOAST_TITLE, false);
        } else {
            List<FormattedCharSequence> lines = font.split(title, room);
            int y = 8;
            for (int i = 0; i < Math.min(2, lines.size()); i++) {
                g.drawString(font, lines.get(i), 32, y, Ui.TOAST_TITLE, false);
                y += 10;
            }
        }
        return time >= SHOW * toasts.getNotificationDisplayTimeMultiplier() ? Visibility.HIDE : Visibility.SHOW;
    }
}
