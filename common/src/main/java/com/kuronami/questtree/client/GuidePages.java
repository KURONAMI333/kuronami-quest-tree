package com.kuronami.questtree.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.Method;

/**
 * Opens a quest's {@code guide_page} (FTB Quests). Patchouli is asked directly when it is installed; a page written as
 * an address or a command opens as those. No guide mod is required: every lookup is reflective and a missing class or
 * a changed method simply leaves the button doing nothing (2026-10-22).
 */
final class GuidePages {

    private GuidePages() {
    }

    static void open(Screen parent, String page) {
        String value = page == null ? "" : page.trim();
        if (value.isEmpty()) {
            return;
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            // the description's links ask first too: a pack's data is not trusted to open a browser on one click
            ConfirmLinkScreen.confirmLinkNow(parent, value);
            return;
        }
        if (value.startsWith("/") && Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.connection.sendCommand(value.substring(1));
            return;
        }
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id != null && patchouli(id)) {
            return;
        }
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.displayClientMessage(
                    Component.translatable("screen.kuronami_quest_tree.guide_missing"), true);
        }
    }

    /** {@code PatchouliAPI.get().openBookGUI(id)}; false when Patchouli is absent or refused the id. */
    private static boolean patchouli(ResourceLocation book) {
        try {
            Class<?> api = Class.forName("vazkii.patchouli.api.PatchouliAPI");
            Object instance = api.getMethod("get").invoke(null);
            Method open = instance.getClass().getMethod("openBookGUI", ResourceLocation.class);
            Object answer = open.invoke(instance, book);
            return open.getReturnType() == void.class || Boolean.TRUE.equals(answer);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
