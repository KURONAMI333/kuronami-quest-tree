package com.kuronami.packprogress.client;

import com.kuronami.packprogress.progress.PlayerProgress;
import com.kuronami.packprogress.progress.ProgressRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * Turns progress updates into what the player sees and hears outside the screen (MOTION_SOUND.md):
 * a one-line notice when a task moves (HUD variant B), vanilla-style toasts when a quest completes or a skill chapter
 * gains experience, and the sounds of actions done on the screen. Gaining experience makes no sound (players found
 * Pufferfish's Skills' per-block sounds noisy); levelling up does.
 */
final class ProgressFeedback {

    private ProgressFeedback() {
    }

    static void onUpdate(PlayerProgress old, PlayerProgress now, List<String> events) {
        ClientModel m = ClientProgress.model();
        if (m == null) {
            return;
        }
        // task counters that went up: the HUD line (the newest one wins)
        for (Map.Entry<String, Long> e : now.tasks.entrySet()) {
            long before = old.tasks.getOrDefault(e.getKey(), 0L);
            if (e.getValue() <= before) {
                continue;
            }
            int hash = e.getKey().lastIndexOf('#');
            ClientModel.Node n = m.nodesByKey.get(e.getKey().substring(0, hash));
            if (n == null) {
                continue;
            }
            int i = Integer.parseInt(e.getKey().substring(hash + 1));
            if (i < n.tasks.size()) {
                ClientModel.Task t = n.tasks.get(i);
                ProgressHud.notice(t.item.isEmpty() ? n.icon : t.item, taskName(t), e.getValue(), t.count, n.title);
            }
        }
        // experience in skill chapters: one toast per chapter that updates while experience keeps coming
        for (ClientModel.Chapter c : m.chapters) {
            if (!c.skills) {
                continue;
            }
            double xpBefore = old.xp.getOrDefault(c.id, 0.0);
            int lvBefore = old.level.getOrDefault(c.id, 0);
            double xp = now.xp.getOrDefault(c.id, 0.0);
            int lv = now.level.getOrDefault(c.id, 0);
            if (lv > lvBefore || xp > xpBefore) {
                ExperienceToast.show(c, xp, lv, lv > lvBefore);
            }
        }
        for (String ev : events) {
            int bar = ev.indexOf('|');
            String kind = bar < 0 ? ev : ev.substring(0, bar);
            String key = bar < 0 ? "" : ev.substring(bar + 1);
            ClientModel.Node n = m.nodesByKey.get(key);
            switch (kind) {
                case "complete" -> {
                    if (n != null && !m.chapterOf.get(key).skills) {
                        boolean claim = ClientProgress.state(key) == ProgressRules.State.CLAIM;
                        QuestToast.show(n, claim ? ProgressRules.State.CLAIM : ProgressRules.State.DONE,
                                Component.translatable("toast.pack_progress.complete"));
                        chapterCompleted(m, key);
                    }
                }
                case "toast" -> {
                    if (n != null) {
                        QuestToast.show(n, ProgressRules.State.DONE, n.title);
                    }
                }
                case "claim" -> play(SoundEvents.ITEM_PICKUP, 0.8f + (float) Math.random() * 0.6f, 0.5f);
                case "submit" -> play(SoundEvents.BUNDLE_INSERT, 1, 0.8f);
                case "take" -> play(SoundEvents.ENCHANTMENT_TABLE_USE, 1, 0.8f);
                case "level" -> play(SoundEvents.PLAYER_LEVELUP, 1, 0.6f);
                default -> { }
            }
        }
    }

    /** All quests of the chapter done: the vanilla challenge sound, as FTB Quests does for chapters only. */
    private static void chapterCompleted(ClientModel m, String key) {
        ClientModel.Chapter c = m.chapterOf.get(key);
        for (ClientModel.Node n : c.nodes.values()) {
            ProgressRules.State s = ClientProgress.state(n.key);
            if (!n.optional && s != ProgressRules.State.DONE && s != ProgressRules.State.CLAIM && s != ProgressRules.State.EXCLUDED) {
                return;
            }
        }
        play(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1, 0.6f);
    }

    static Component taskName(ClientModel.Task t) {
        return t.item.isEmpty() ? Component.translatable("task.pack_progress." + t.type) : t.item.getHoverName();
    }

    static void play(SoundEvent sound, float pitch, float volume) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }

    static void play(net.minecraft.core.Holder<SoundEvent> sound, float pitch, float volume) {
        play(sound.value(), pitch, volume);
    }

    /** Short sound for clicks on locked nodes. */
    static void locked() {
        play(SoundEvents.CHEST_LOCKED, 1, 0.4f);
    }

    static void click() {
        play(SoundEvents.UI_BUTTON_CLICK, 1, 0.25f);
    }

    static ItemStack iconOr(ItemStack a, ItemStack b) {
        return a.isEmpty() ? b : a;
    }
}
