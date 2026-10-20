package com.kuronami.questtree.client;

import com.kuronami.questtree.progress.PlayerProgress;
import com.kuronami.questtree.progress.ProgressRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
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
                // item tasks count what is held right now; say so, or a count that follows the inventory reads as a tally
                boolean held = t.type.equals("item") && !t.consume;
                ProgressHud.notice(t.icon().isEmpty() ? n.icon : t.icon(), taskName(t), e.getValue(), t.count, n.title, held);
            }
        }
        // experience in skill chapters: one toast per chapter that updates while experience keeps coming
        for (ClientModel.Chapter c : m.chapters) {
            if (!c.skills) {
                continue;
            }
            String key = ProgressRules.key(c.id, c.pool);
            double xpBefore = old.xp.getOrDefault(key, 0.0);
            int lvBefore = old.level.getOrDefault(key, 0);
            double xp = now.xp.getOrDefault(key, 0.0);
            int lv = now.level.getOrDefault(key, 0);
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
                        notifyCompletion(n, claim ? ProgressRules.State.CLAIM : ProgressRules.State.DONE);
                        chapterCompleted(m, key);
                    }
                }
                case "toast" -> {
                    if (n != null) {
                        notifyCompletion(n, ProgressRules.State.DONE);
                    }
                }
                case "claim" -> {
                    if (played.add(kind)) {
                        feedback(QtSounds.CLAIM, 0.97f + (float) Math.random() * 0.06f, 0.8f);
                    }
                }
                case "submit" -> feedback(QtSounds.SUBMIT, 1, 0.8f);
                case "take" -> feedback(QtSounds.UNLOCK, 1, 0.8f);
                case "level" -> feedback(QtSounds.LEVEL, 1, 0.8f);
                default -> { }
            }
        }
    }

    /**
     * Starts the result animations of MOTION_SOUND.md: the count bump and the fill-up sound of a hand-in, the unlock
     * chain (lines grow, newly opened skills light up one by one with a rising note) and the stamp on choices an
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
                    feedbackLater(QtSounds.FILL, 360, 1f);
                }
            }
            if (kind.equals("complete")) {
                // a quest done: its ways out take the new colour the same way a skill's do
                Motion.UNLOCKED.put(key, t);
            }
            if (kind.equals("take")) {
                Motion.UNLOCKED.put(key, t);
                int i = 0;
                for (var child : node.chapter.nodes.values()) {
                    if (child.requires.contains(key) && rules.state(old, child) == ProgressRules.State.LOCKED
                            && rules.state(now, child) != ProgressRules.State.LOCKED) {
                        long delay = Motion.LINE_GROW + Motion.CHILD_STEP * i;
                        Motion.OPENED.put(child.key, t + delay);
                        feedbackLater(QtSounds.OPEN, delay, Math.min(1.5f, 1f + 0.06f * i));
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
            feedback(QtSounds.EXCLUDE, 1, 0.7f);
        }
    }

    /** All quests of the chapter done: the chapter fanfare, kept for chapters only as FTB Quests does. */
    private static void chapterCompleted(ClientModel m, String key) {
        ClientModel.Chapter c = m.chapterOf.get(key);
        for (ClientModel.Node n : c.nodes.values()) {
            ProgressRules.State s = ClientProgress.state(n.key);
            if (!n.optional && s != ProgressRules.State.DONE && s != ProgressRules.State.CLAIM && s != ProgressRules.State.EXCLUDED) {
                return;
            }
        }
        feedback(QtSounds.CHAPTER, 1, 0.8f);
    }

    /**
     * Announces a completion in the style the player picked (2026-10-20): a vanilla toast, a chat line, the action bar,
     * or nothing. The toast frame keeps its own sound; chat and the action bar stay silent, as FTB Quests' do.
     */
    private static void notifyCompletion(ClientModel.Node n, ProgressRules.State state) {
        Component top = Component.translatable("toast.kuronami_quest_tree.complete");
        switch (ClientConfig.notificationStyle()) {
            case TOAST -> QuestToast.show(n, state, top);
            case CHAT -> chat(top, n.title, false);
            case ACTION_BAR -> chat(top, n.title, true);
            case NONE -> { }
        }
    }

    private static void chat(Component top, Component title, boolean actionBar) {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(top.copy().append(" ").append(title), actionBar);
        }
    }

    static Component taskName(ClientModel.Task t) {
        return t.name();
    }

    /**
     * The label of a task in a task line (tracker, details sheet). A checkmark task shows what to do, not its type
     * name: "Iron Ingot / 0/1 Checkmark" told the player nothing (review 2026-10-16).
     */
    static Component taskLine(ClientModel.Task t) {
        return t.type.equals("checkmark") ? Component.translatable("task.kuronami_quest_tree.checkmark_action") : t.name();
    }

    /** A completion, claim, unlock or level sound, only while the player keeps them on (2026-10-20). */
    static void feedback(SoundEvent sound, float pitch, float volume) {
        if (ClientConfig.completionSounds()) {
            play(sound, pitch, volume);
        }
    }

    static void feedback(net.minecraft.core.Holder<SoundEvent> sound, float pitch, float volume) {
        feedback(sound.value(), pitch, volume);
    }

    private static void feedbackLater(SoundEvent sound, long delay, float pitch) {
        if (ClientConfig.completionSounds()) {
            Motion.soundLater(sound, delay, pitch);
        }
    }

    static void play(SoundEvent sound, float pitch, float volume) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }

    static void play(net.minecraft.core.Holder<SoundEvent> sound, float pitch, float volume) {
        play(sound.value(), pitch, volume);
    }

    /** Short sound for clicks on locked nodes. */
    static void locked() {
        play(QtSounds.LOCKED, 1, 0.6f);
    }

    static void click() {
        play(QtSounds.CLICK, 1, 0.5f);
    }

    static ItemStack iconOr(ItemStack a, ItemStack b) {
        return a.isEmpty() ? b : a;
    }
}
