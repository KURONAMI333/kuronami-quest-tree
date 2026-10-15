package com.kuronami.questtree.client;

import com.kuronami.questtree.progress.PlayerProgress;
import com.kuronami.questtree.progress.ProgressRules;
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
                ProgressHud.notice(t.icon().isEmpty() ? n.icon : t.icon(), taskName(t), e.getValue(), t.count, n.title);
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
        motions(m, old, now, events);
        // claim all sends one event per quest: play each sound once per update, not once per quest
        java.util.Set<String> played = new java.util.HashSet<>();
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
                                Component.translatable("toast.kuronami_quest_tree.complete"));
                        chapterCompleted(m, key);
                    }
                }
                case "toast" -> {
                    if (n != null) {
                        QuestToast.show(n, ProgressRules.State.DONE, n.title);
                    }
                }
                case "claim" -> {
                    if (played.add(kind)) {
                        play(SoundEvents.ITEM_PICKUP, 0.8f + (float) Math.random() * 0.6f, 0.5f);
                    }
                }
                case "submit" -> play(SoundEvents.BUNDLE_INSERT, 1, 0.8f);
                case "take" -> play(SoundEvents.ENCHANTMENT_TABLE_USE, 1, 0.8f);
                case "level" -> play(SoundEvents.PLAYER_LEVELUP, 1, 0.6f);
                default -> { }
            }
        }
    }

    /**
     * Starts the result animations of MOTION_SOUND.md: the count bump and the fill-up pling of a hand-in, the unlock
     * chain (lines grow, newly opened skills light up one by one with a rising pling) and the stamp on choices an
     * exclusive pick closed.
     */
    private static void motions(ClientModel m, PlayerProgress old, PlayerProgress now, List<String> events) {
        var rules = ClientProgress.rules();
        if (rules == null) {
            return;
        }
        long t = net.minecraft.Util.getMillis();
        java.util.Set<String> completed = new java.util.HashSet<>();
        for (String ev : events) {
            if (ev.startsWith("complete|")) {
                completed.add(ev.substring(9));
            }
        }
        boolean stamped = false;
        for (String ev : events) {
            int bar = ev.indexOf('|');
            if (bar < 0) {
                continue;
            }
            String kind = ev.substring(0, bar);
            String key = ev.substring(bar + 1);
            var node = rules.model().nodes.get(key);
            if (node == null) {
                continue;
            }
            if (kind.equals("submit")) {
                for (int i = 0; i < node.tasks.size(); i++) {
                    if (now.task(key, i) > old.task(key, i)) {
                        Motion.SUBMITTED.put(key + "#" + i, t);
                    }
                }
                if (completed.contains(key)) {
                    // after the fill has run up (360ms)
                    Motion.plingLater(360, 1.6f);
                }
            }
            if (kind.equals("take")) {
                Motion.UNLOCKED.put(key, t);
                int i = 0;
                for (var child : node.chapter.nodes.values()) {
                    if (child.requires.contains(key) && rules.state(old, child) == ProgressRules.State.LOCKED
                            && rules.state(now, child) != ProgressRules.State.LOCKED) {
                        long delay = Motion.LINE_GROW + Motion.CHILD_STEP * i;
                        Motion.OPENED.put(child.key, t + delay);
                        Motion.plingLater(delay, Math.min(2f, 1f + 0.1f * i));
                        i++;
                    }
                }
            }
            if (kind.equals("take") || kind.equals("complete")) {
                java.util.Set<com.kuronami.questtree.data.ProgressModel.Node> rivals = new java.util.LinkedHashSet<>();
                node.exclusiveWith.forEach(k -> rivals.add(rules.model().nodes.get(k)));
                if (node.exclusiveGroup != null) {
                    rivals.addAll(node.chapter.exclusiveGroups.getOrDefault(node.exclusiveGroup, List.of()));
                }
                for (var r : rivals) {
                    if (r != null && r != node && rules.state(old, r) != ProgressRules.State.EXCLUDED
                            && rules.state(now, r) == ProgressRules.State.EXCLUDED) {
                        Motion.STAMPED.put(r.key, t);
                        stamped = true;
                    }
                }
            }
        }
        if (stamped) {
            play(SoundEvents.IRON_DOOR_CLOSE, 1, 0.3f);
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
        return t.name();
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
