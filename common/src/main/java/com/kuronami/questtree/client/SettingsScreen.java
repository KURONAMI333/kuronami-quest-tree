package com.kuronami.questtree.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Mth;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Client preferences, opened from the gear in the chapter list (2026-10-20). Rows are labels and values only; a click
 * changes the value and saves it at once, the way FTB Quests' player settings do.
 */
public final class SettingsScreen extends Screen {

    private static final int ROW_H = 26;
    private static final int PANEL_W = 300;
    private static final float[] SCALES = {0.75f, 1f, 1.25f, 1.5f};
    private static final float[] TEXT_SCALES = {0.85f, 1f, 1.15f};

    private final Screen parent;
    private final List<Row> rows = new ArrayList<>();
    private float scroll;
    private float scrollTarget;

    private interface Row {
        Component label();

        Component value();

        void click();
    }

    public SettingsScreen(Screen parent) {
        super(Component.translatable("screen.kuronami_quest_tree.settings"));
        this.parent = parent;
        rows.add(new Row() {
            @Override
            public Component label() {
                return Component.translatable("screen.kuronami_quest_tree.settings.notifications");
            }

            @Override
            public Component value() {
                return Component.translatable("screen.kuronami_quest_tree.settings.notification."
                        + ClientConfig.notificationStyle().id);
            }

            @Override
            public void click() {
                ClientConfig.notificationStyle = ClientConfig.notificationStyle().next();
            }
        });
        rows.add(new Row() {
            @Override
            public Component label() {
                return Component.translatable("screen.kuronami_quest_tree.settings.sounds");
            }

            @Override
            public Component value() {
                return onOff(ClientConfig.completionSounds());
            }

            @Override
            public void click() {
                ClientConfig.completionSounds = !ClientConfig.completionSounds;
            }
        });
        rows.add(new Row() {
            @Override
            public Component label() {
                return Component.translatable("screen.kuronami_quest_tree.settings.tracker_side");
            }

            @Override
            public Component value() {
                return Component.translatable("screen.kuronami_quest_tree.settings."
                        + (ClientConfig.trackerRight() ? "right" : "left"));
            }

            @Override
            public void click() {
                ClientConfig.trackerRight = !ClientConfig.trackerRight;
            }
        });
        rows.add(new Row() {
            @Override
            public Component label() {
                return Component.translatable("screen.kuronami_quest_tree.settings.tracker_scale");
            }

            @Override
            public Component value() {
                return Component.literal(Math.round(ClientConfig.trackerScale() * 100) + "%");
            }

            @Override
            public void click() {
                float now = ClientConfig.trackerScale();
                for (int i = 0; i < SCALES.length; i++) {
                    if (Math.abs(SCALES[i] - now) < 0.01f) {
                        ClientConfig.trackerScale = SCALES[(i + 1) % SCALES.length];
                        return;
                    }
                }
                ClientConfig.trackerScale = SCALES[1];
            }
        });
        rows.add(new Row() {
            @Override
            public Component label() {
                return Component.translatable("screen.kuronami_quest_tree.settings.tracker_mode");
            }

            @Override
            public Component value() {
                return Component.translatable("screen.kuronami_quest_tree.settings.tracker."
                        + ClientConfig.trackerMode().id);
            }

            @Override
            public void click() {
                ClientConfig.trackerMode = ClientConfig.trackerMode().next();
            }
        });
        rows.add(new Row() {
            @Override
            public Component label() {
                return Component.translatable("screen.kuronami_quest_tree.settings.tracker_x");
            }

            @Override
            public Component value() {
                return offset(ClientConfig.trackerOffsetX());
            }

            @Override
            public void click() {
                ClientConfig.trackerOffsetX = nextOffset(ClientConfig.trackerOffsetX());
            }
        });
        rows.add(new Row() {
            @Override
            public Component label() {
                return Component.translatable("screen.kuronami_quest_tree.settings.tracker_y");
            }

            @Override
            public Component value() {
                return offset(ClientConfig.trackerOffsetY());
            }

            @Override
            public void click() {
                ClientConfig.trackerOffsetY = nextOffset(ClientConfig.trackerOffsetY());
            }
        });
        rows.add(new Row() {
            @Override
            public Component label() {
                return Component.translatable("screen.kuronami_quest_tree.settings.keep_rail");
            }

            @Override
            public Component value() {
                return onOff(ClientConfig.keepRailOpen());
            }

            @Override
            public void click() {
                ClientConfig.keepRailOpen = !ClientConfig.keepRailOpen;
            }
        });
        rows.add(new Row() {
            @Override
            public Component label() {
                return Component.translatable("screen.kuronami_quest_tree.settings.text_size");
            }

            @Override
            public Component value() {
                return Component.literal(Math.round(ClientConfig.textScale() * 100) + "%");
            }

            @Override
            public void click() {
                float now = ClientConfig.textScale();
                for (int i = 0; i < TEXT_SCALES.length; i++) {
                    if (Math.abs(TEXT_SCALES[i] - now) < 0.01f) {
                        ClientConfig.textScale = TEXT_SCALES[(i + 1) % TEXT_SCALES.length];
                        Ui.textScale = ClientConfig.textScale;
                        return;
                    }
                }
                ClientConfig.textScale = 1f;
                Ui.textScale = 1f;
            }
        });
        rows.add(new Row() {
            @Override
            public Component label() {
                return Component.translatable("screen.kuronami_quest_tree.settings.high_contrast");
            }

            @Override
            public Component value() {
                return onOff(ClientConfig.highContrast());
            }

            @Override
            public void click() {
                ClientConfig.highContrast = !ClientConfig.highContrast;
                Ui.loadPalette(net.minecraft.client.Minecraft.getInstance().getResourceManager());
            }
        });
        rows.add(new Row() {
            @Override
            public Component label() {
                return Component.translatable("screen.kuronami_quest_tree.settings.keys");
            }

            @Override
            public Component value() {
                return Component.literal(">");
            }

            @Override
            public void click() {
                if (minecraft != null) {
                    minecraft.setScreen(new KeyReferenceScreen(SettingsScreen.this));
                }
            }
        });
        ClientModel model = ClientProgress.model();
        if (model != null && model.wikiUrl != null) {
            rows.add(linkRow("wiki", model.wikiUrl));
        }
        if (model != null && model.guideUrl != null) {
            rows.add(linkRow("guide", model.guideUrl));
        }
        rows.add(new Row() {
            @Override
            public Component label() {
                return Component.translatable("screen.kuronami_quest_tree.settings.auto_pin");
            }

            @Override
            public Component value() {
                return onOff(ClientConfig.autoPin());
            }

            @Override
            public void click() {
                ClientConfig.autoPin = !ClientConfig.autoPin;
            }
        });
    }

    /** A pack-defined Wiki / Guide address, opened through the vanilla "are you sure" card. */
    private Row linkRow(String id, String url) {
        return new Row() {
            @Override
            public Component label() {
                return Component.translatable("screen.kuronami_quest_tree.settings." + id);
            }

            @Override
            public Component value() {
                return Component.literal(">");
            }

            @Override
            public void click() {
                net.minecraft.client.gui.screens.ConfirmLinkScreen.confirmLinkNow(SettingsScreen.this, url);
            }
        };
    }

    private static final int[] OFFSETS = {-48, -40, -32, -24, -16, -8, 0, 8, 16, 24, 32, 40, 48};

    private static int nextOffset(int now) {
        for (int i = 0; i < OFFSETS.length; i++) {
            if (OFFSETS[i] == now) {
                return OFFSETS[(i + 1) % OFFSETS.length];
            }
        }
        return 0;
    }

    private static Component offset(int value) {
        return Component.translatable("screen.kuronami_quest_tree.settings.px", value);
    }

    private static Component onOff(boolean on) {
        return Component.translatable(on ? "screen.kuronami_quest_tree.settings.on" : "screen.kuronami_quest_tree.settings.off");
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int contentHeight() {
        return rows.size() * ROW_H + 8;
    }

    /** The title stays at its usual place while the list fits; a short screen moves it up to leave room to scroll. */
    private int titleY() {
        int natural = Math.max(24, height / 2 - (contentHeight() + 30) / 2);
        int room = height - (natural + 22) - 8;
        return contentHeight() <= room ? natural : 8;
    }

    private int panelTop() {
        return titleY() + 22;
    }

    private int panelHeight() {
        return Math.min(contentHeight(), Math.max(ROW_H + 8, height - panelTop() - 8));
    }

    private float maxScroll() {
        return Math.max(0, contentHeight() - panelHeight());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Ui.textScale = ClientConfig.textScale();
        renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(0, 0, width, height, Ui.GROUND);
        int x = (width - PANEL_W) / 2;
        int y = titleY();
        int top = panelTop();
        int h = panelHeight();
        scrollTarget = Mth.clamp(scrollTarget, 0, maxScroll());
        scroll += (scrollTarget - scroll) * 0.35f;
        scroll = Mth.clamp(scroll, 0, maxScroll());
        int off = Math.round(scroll);
        Ui.textCenter(g, font, title, width / 2, y, Ui.TEXT);
        Ui.surface(g, x, top, PANEL_W, h, Ui.R4, Ui.PANEL_TRANSLUCENT);
        Ui.outline(g, x, top, PANEL_W, h, Ui.R4, Ui.HAIRLINE);
        int listTop = top + 4;
        int listBottom = top + h - 4;
        g.enableScissor(x, listTop, x + PANEL_W, listBottom);
        boolean inPanel = mouseX >= x && mouseX < x + PANEL_W && mouseY >= listTop && mouseY < listBottom;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int ry = listTop + i * ROW_H - off;
            if (ry + ROW_H <= listTop || ry >= listBottom) {
                continue;
            }
            boolean hover = inPanel && mouseX >= x + 4 && mouseX < x + PANEL_W - 4
                    && mouseY >= ry && mouseY < ry + ROW_H;
            if (hover) {
                Ui.surface(g, x + 4, ry, PANEL_W - 8, ROW_H, Ui.R2, Ui.OVERLAY_1);
            }
            Ui.text(g, font, row.label(), x + 12, ry + (ROW_H - 8) / 2, hover ? Ui.TEXT : Ui.MUTED);
            Ui.textRight(g, font, row.value(), x + PANEL_W - 12, ry + (ROW_H - 8) / 2, Ui.TEXT);
        }
        g.disableScissor();
        if (maxScroll() > 0) {
            int track = h - 8;
            int knobH = Math.max(12, Math.round(track * h / (float) contentHeight()));
            int knobY = listTop + Math.round((track - knobH) * (scroll / maxScroll()));
            Ui.surface(g, x + PANEL_W - 4, listTop, 2, track, 1, Ui.OVERLAY_2);
            Ui.surface(g, x + PANEL_W - 4, knobY, 2, knobH, 1, Ui.MUTED);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) {
            return super.mouseClicked(mx, my, button);
        }
        int x = (width - PANEL_W) / 2;
        int top = panelTop();
        int listTop = top + 4;
        int listBottom = top + panelHeight() - 4;
        if (mx < x || mx >= x + PANEL_W || my < listTop || my >= listBottom) {
            return super.mouseClicked(mx, my, button);
        }
        int off = Math.round(scroll);
        for (int i = 0; i < rows.size(); i++) {
            int ry = listTop + i * ROW_H - off;
            if (mx >= x + 4 && mx < x + PANEL_W - 4 && my >= ry && my < ry + ROW_H) {
                rows.get(i).click();
                ClientConfig.save();
                ProgressFeedback.click();
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        int x = (width - PANEL_W) / 2;
        int top = panelTop();
        if (maxScroll() > 0 && mx >= x && mx < x + PANEL_W && my >= top && my < top + panelHeight()) {
            scrollTarget = Mth.clamp(scrollTarget - (float) scrollY * ROW_H * 2, 0, maxScroll());
            return true;
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    /** Dev tour only: show the list from its top. */
    void tourScrollTop() {
        scroll = 0;
        scrollTarget = 0;
    }

    /** Dev tour only: show the list from its bottom. */
    void tourScrollBottom() {
        scroll = maxScroll();
        scrollTarget = scroll;
    }
}
