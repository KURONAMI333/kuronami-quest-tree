package com.kuronami.questtree.client;

import com.kuronami.questtree.Constants;
import com.kuronami.questtree.progress.ProgressRules.State;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * Development only: a scripted walk through the screens that saves a screenshot of each, so the UI can be checked
 * without sending keyboard or mouse input to the machine. Runs only when the JVM property
 * {@code quest_tree.devTour} is set (the {@code clientTest} run in neoforge/build.gradle sets it); never in a normal game.
 */
final class DevTour {

    static final boolean ENABLED = System.getProperty("quest_tree.devTour") != null;

    private record Step(int at, Runnable run) {
    }

    private static final List<Step> STEPS = new ArrayList<>();
    private static int ticks = -1;
    private static int next;
    private static ProgressScreen screen;

    private DevTour() {
    }

    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (!ENABLED || mc.player == null || ClientProgress.model() == null) {
            return;
        }
        if (ticks < 0) {
            build();
        }
        ticks++;
        while (next < STEPS.size() && STEPS.get(next).at <= ticks) {
            try {
                STEPS.get(next).run.run();
            } catch (RuntimeException ex) {
                Constants.LOG.error("Dev tour step {} failed", next, ex);
            }
            next++;
        }
    }

    private static void build() {
        ticks = 0;
        ClientModel m = ClientProgress.model();
        ClientModel.Chapter quests = first(m, false);
        ClientModel.Chapter skills = first(m, true);
        List<ClientModel.Node> nodes = quests == null ? List.of() : new ArrayList<>(quests.nodes.values());
        int t = 100;
        // progress to look at: a few completed (claimable), one claimed, one half done, experience and points
        for (int i = 0; i < Math.min(4, nodes.size()); i++) {
            cmd(t++, "quest_tree complete @s " + nodes.get(i).key);
        }
        if (nodes.size() > 4) {
            give(t++, nodes.get(4), 0.5);
        }
        if (skills != null) {
            cmd(t++, "quest_tree experience @s " + skills.id + " 2000");
        }
        at(t + 20, () -> {
            if (!nodes.isEmpty()) {
                ClientProgress.send("claim", nodes.get(0).key, -1);
            }
        });
        t += 60;
        at(t, () -> open(-1));
        shot(t + 20, "01_now");
        at(t + 30, () -> selectFirst(State.PROGRESS));
        shot(t + 50, "02_now_detail");
        if (quests != null) {
            at(t + 60, () -> open(m.chapters.indexOf(quests)));
            shot(t + 80, "03_chapter");
            at(t + 90, () -> selectFirst(State.CLAIM));
            shot(t + 110, "04_chapter_claim");
        }
        ClientModel.Chapter big = m.chapters.stream().filter(c -> !c.skills).max((a, b) -> a.nodes.size() - b.nodes.size()).orElse(null);
        if (big != null && big != quests) {
            at(t + 120, () -> open(m.chapters.indexOf(big)));
            shot(t + 140, "05_big_chapter");
        }
        if (skills != null) {
            at(t + 150, () -> open(m.chapters.indexOf(skills)));
            shot(t + 170, "06_skills");
            at(t + 180, () -> selectFirst(State.CLAIM));
            shot(t + 200, "07_skills_take");
        }
        at(t + 210, () -> Minecraft.getInstance().setScreen(null));
        // a task that moves while playing: the HUD line, then the quest-complete toast
        if (nodes.size() > 5) {
            give(t + 220, nodes.get(5), 0.5);
            shot(t + 240, "08_hud_notice");
            give(t + 260, nodes.get(5), 1.0);
            shot(t + 290, "09_toast");
        }
        at(t + 380, ProgressHud::toggleTracker);
        shot(t + 400, "10_tracker");
        at(t + 410, () -> Constants.LOG.info("Dev tour finished"));
    }

    private static ClientModel.Chapter first(ClientModel m, boolean skills) {
        return m.chapters.stream().filter(c -> c.skills == skills).findFirst().orElse(null);
    }

    private static void at(int tick, Runnable r) {
        STEPS.add(new Step(tick, r));
    }

    private static void cmd(int tick, String command) {
        at(tick, () -> Minecraft.getInstance().player.connection.sendCommand(command));
    }

    /** Gives a share of the first item task of a node, so it shows progress. */
    private static void give(int tick, ClientModel.Node n, double share) {
        if (n.tasks.isEmpty() || n.tasks.get(0).item.isEmpty()) {
            return;
        }
        ClientModel.Task task = n.tasks.get(0);
        long count = Math.max(1, Math.round(task.count * share));
        String id = BuiltInRegistries.ITEM.getKey(task.item.getItem()).toString();
        cmd(tick, "give @s " + id + " " + count);
    }

    private static void open(int chapter) {
        screen = new ProgressScreen();
        screen.tourChapter(chapter);
        Minecraft.getInstance().setScreen(screen);
    }

    private static void selectFirst(State state) {
        if (screen != null) {
            screen.tourSelectFirst(state);
        }
    }

    private static void shot(int tick, String name) {
        at(tick, () -> {
            Minecraft mc = Minecraft.getInstance();
            Screenshot.grab(mc.gameDirectory, "questtree_" + name + ".png", mc.getMainRenderTarget(),
                    msg -> Constants.LOG.info("Dev tour screenshot {}", name));
        });
    }
}
