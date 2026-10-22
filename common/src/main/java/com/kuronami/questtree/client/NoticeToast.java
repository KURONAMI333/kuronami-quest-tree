package com.kuronami.questtree.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/** A short gameplay notice in the vanilla advancement frame: an icon and at most two lines, never chat. */
final class NoticeToast implements Toast, OwnSoundToast {

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
        Ui.toastFrame(g, width(), height());
        Tiles.icon(g, icon, 7, 8, 16, "gift".equals(icon) ? Tiles.GOLD : Ui.MUTED);
        var font = toasts.getMinecraft().font;
        int room = width() - 38;
        if (body != null) {
            wrapped(g, font, title, 32, 7, room, 1);
            int bodyLines = Math.max(1, (height() - 18) / 10);
            wrapped(g, font, body, 32, 18, room, bodyLines);
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

    /**
     * A component as at most {@code maxLines} lines, styles kept (2026-10-23, R6 L7). When more remains, the last line
     * gets an ellipsis: the body is a real sentence, so cutting it with no sign was reading as a broken toast.
     */
    private static void wrapped(GuiGraphics g, Font font, Component text, int x, int y, int room, int maxLines) {
        if (text == null || text.getString().isEmpty()) {
            return;
        }
        List<FormattedCharSequence> lines = font.split(text, room);
        boolean clipped = lines.size() > maxLines;
        if (clipped) {
            lines = font.split(text, Math.max(8, room - font.width("…")));
        }
        int shown = Math.min(maxLines, lines.size());
        for (int i = 0; i < shown; i++) {
            FormattedCharSequence line = lines.get(i);
            g.drawString(font, line, x, y + i * 10, Ui.TOAST_TITLE, false);
            if (clipped && i == shown - 1) {
                g.drawString(font, "…", x + font.width(line), y + i * 10, Ui.TOAST_TITLE, false);
            }
        }
    }
}
