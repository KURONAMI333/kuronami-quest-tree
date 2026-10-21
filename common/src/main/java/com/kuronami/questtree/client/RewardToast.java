package com.kuronami.questtree.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** "Reward" in the vanilla toast frame, with the names of what was just paid out (2026-10-22). */
final class RewardToast implements Toast, OwnSoundToast {

    private static final ResourceLocation BACKGROUND = ResourceLocation.withDefaultNamespace("toast/advancement");
    private static final long SHOW = 3000;
    private record Pending(ItemStack icon, Component names) {
    }

    private static final List<Pending> PENDING = new ArrayList<>();

    private final ItemStack icon;
    private final Component names;

    private RewardToast(ItemStack icon, Component names) {
        this.icon = icon;
        this.names = names;
    }

    /** One toast for everything claimed in one update, so "claim all" does not stack a toast per quest. */
    static void show(List<ItemStack> icons, List<Component> rewardNames) {
        if (rewardNames.isEmpty()) {
            return;
        }
        Component names = joined(rewardNames);
        ItemStack icon = icons.stream().filter(s -> !s.isEmpty()).findFirst().orElse(new ItemStack(net.minecraft.world.item.Items.CHEST));
        if (PendingToasts.suppressed()) {
            PENDING.add(new Pending(icon, names));
            return;
        }
        Minecraft.getInstance().getToasts().addToast(new RewardToast(icon, names));
    }

    private static Component joined(List<Component> names) {
        Component out = Component.empty();
        int shown = Math.min(3, names.size());
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                out = out.copy().append(", ");
            }
            out = out.copy().append(names.get(i));
        }
        if (names.size() > shown) {
            out = out.copy().append(Component.literal(" +" + (names.size() - shown)));
        }
        return out;
    }

    static void flushPending() {
        if (PENDING.isEmpty()) {
            return;
        }
        List<Pending> queued = new ArrayList<>(PENDING);
        PENDING.clear();
        for (Pending q : queued) {
            Minecraft.getInstance().getToasts().addToast(new RewardToast(q.icon(), q.names()));
        }
    }

    @Override
    public Visibility render(GuiGraphics g, ToastComponent toasts, long time) {
        g.blitSprite(BACKGROUND, 0, 0, width(), height());
        Tiles.tile(g, "square", 6, 6, 20, Tiles.Look.DONE, 1, icon, false);
        var font = toasts.getMinecraft().font;
        g.drawString(font, Component.translatable("toast.kuronami_quest_tree.reward"), 32, 7, 0xFFAA00, false);
        g.drawString(font, font.plainSubstrByWidth(names.getString(), width() - 38), 32, 18, 0xFFFFFF, false);
        return time >= SHOW * toasts.getNotificationDisplayTimeMultiplier() ? Visibility.HIDE : Visibility.SHOW;
    }
}
