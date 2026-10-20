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

    private static int idle;
    private static String lastScreen = "";

    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (!ENABLED) {
            return;
        }
        if (mc.player == null || ClientProgress.model() == null) {
            // not in the world yet: say which screen holds it up, and grab it once
            String screen = mc.screen == null ? "none" : mc.screen.getClass().getName();
            if (!screen.equals(lastScreen) || ++idle % 200 == 0) {
                lastScreen = screen;
                Constants.LOG.info("Dev tour waiting: player={} model={} screen={}", mc.player != null, ClientProgress.model() != null, screen);
                if (idle > 0 && idle % 200 == 0) {
                    Screenshot.grab(mc.gameDirectory, "questtree_00_waiting.png", mc.getMainRenderTarget(), msg -> { });
                }
            }
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
        // one open quest part done, so a blue (in progress) tile is in the photos
        at(t + 30, () -> {
            ClientModel.Node open = openItemQuest();
            if (open != null) {
                giveNow(open, 0.4);
            }
        });
        t += 60;
        at(t - 4, DevTour::parkMouse);
        at(t, () -> open(-1));
        shot(t + 20, "01_now");
        at(t + 30, () -> selectFirst(State.PROGRESS));
        shot(t + 50, "02_now_detail");
        if (quests != null) {
            at(t + 60, () -> open(m.chapters.indexOf(quests)));
            shot(t + 80, "03_chapter");
            at(t + 90, () -> selectFirst(State.CLAIM));
            shot(t + 110, "04_chapter_claim");
            // a selected open node: its lines in and out are coloured, with arrows running along them
            at(t + 112, () -> selectFirst(State.OPEN));
            shot(t + 118, "12_lines");
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
            // a skill tree's lines (two-way in the data) with a node selected: arrows run outward only
            at(t + 202, () -> selectFirst(State.OPEN));
            shot(t + 208, "13_skill_lines");
        }
        at(t + 210, () -> Minecraft.getInstance().setScreen(null));
        // a task that moves while playing: the HUD line, then the quest-complete toast
        // an open quest with an item task (picked when the step runs; the dev world keeps progress between runs):
        // its task moves, so the HUD line shows, then the toast when it completes
        at(t + 220, () -> {
            ClientModel.Node open = openItemQuest();
            if (open != null) {
                giveNow(open, 0.3);
                at(ticks + 20, () -> shot("08_hud_notice"));
                at(ticks + 40, () -> giveNow(open, 1.0));
                at(ticks + 70, () -> shot("09_toast"));
            }
        });
        at(t + 380, ProgressHud::toggleTracker);
        shot(t + 400, "10_tracker");
        int pt = t + 420;
        // every shape in every look, in the game's renderer
        at(pt, () -> Minecraft.getInstance().setScreen(new DevShapes()));
        shot(pt + 10, "11_shapes");
        pt += 20;
        at(pt, () -> Minecraft.getInstance().setScreen(null));
        pt += 5;
        if (big != null) {
            // the zoom buttons and the minimap (it shows while the map moves), then the search over the whole book,
            // with everything it does not match sunk into the ground
            at(pt + 10, () -> open(m.chapters.indexOf(big)));
            at(pt + 14, () -> screen.tourZoom());
            shot(pt + 18, "14_minimap_zoom");
            at(pt + 24, () -> screen.tourSearch("qqqzzz"));
            shot(pt + 30, "15_search_none");
        }
        // the recipe viewer both ways: an item a quest wants opens that quest; the quest's item opens its recipes
        at(pt + 34, () -> Minecraft.getInstance().setScreen(null));
        at(pt + 36, () -> {
            net.minecraft.world.item.ItemStack item = net.minecraft.world.item.ItemStack.EMPTY;
            String quest = null;
            for (ClientModel.Node n : ClientProgress.model().nodesByKey.values()) {
                for (ClientModel.Task task : n.tasks) {
                    if (!task.item.isEmpty() && (quest = ItemTooltips.questFor(task.item)) != null) {
                        item = task.item;
                        break;
                    }
                }
                if (quest != null) {
                    break;
                }
            }
            Constants.LOG.info("Dev tour: recipe viewer {}, item {} wanted by {}", RecipeViewer.Holder.get() != null, item, quest);
            if (quest != null) {
                ProgressScreen.openAt(quest);
            }
        });
        shot(pt + 46, "16_item_to_quest");
        at(pt + 50, () -> {
            ClientModel.Node want = openItemQuest();
            RecipeViewer viewer = RecipeViewer.Holder.get();
            if (viewer != null && want != null) {
                viewer.showRecipes(want.tasks.get(0).item);
            }
        });
        shot(pt + 60, "17_quest_to_recipes");
        // the pinned block at the top of the chapter list: Now keeps the list docked open
        at(pt + 62, () -> open(-1));
        // the developer's own pins and glass setting are put back at the end, so the tour leaves the config as it found it
        List<String> pinsBefore = new ArrayList<>(ClientConfig.railPinned());
        boolean glassBefore = ClientConfig.glass();
        at(pt + 64, () -> screen.tourPin(2));
        shot(pt + 68, "18_rail_pins");
        at(pt + 70, () -> {
            // the glass minimap, over a dense part of the map. The same view is shot without and with the glass so the
            // two can be put side by side
            if (big != null) {
                open(m.chapters.indexOf(big));
                screen.tourZoom();
                screen.tourZoom();
            }
        });
        at(pt + 76, () -> ClientConfig.glass = false);
        shot(pt + 78, "19_glass_off");
        at(pt + 80, () -> ClientConfig.glass = true);
        shot(pt + 82, "20_glass_on");
        // the open chapter list over the light tiles of the same map: the text on the glass has to stay legible there
        at(pt + 84, () -> ProgressScreen.tourRailOpen(true));
        shot(pt + 88, "21_rail_over_tiles");
        at(pt + 90, () -> ProgressScreen.tourRailOpen(false));
        at(pt + 92, () -> {
            ClientConfig.glass = glassBefore;
            for (String id : new ArrayList<>(ClientConfig.railPinned())) {
                ClientConfig.setRailPin(id, pinsBefore.contains(id));
            }
            pinsBefore.forEach(id -> ClientConfig.setRailPin(id, true));
            ClientConfig.write();
        });
        at(pt + 94, () -> {
            ProgressScreen.tourResting(false);
            Constants.LOG.info("Dev tour finished");
        });
    }
    private static ClientModel.Node openItemQuest() {
        for (ClientModel.Node n : ClientProgress.model().nodesByKey.values()) {
            State s = ClientProgress.state(n.key);
            if ((s == State.OPEN || s == State.PROGRESS) && !n.tasks.isEmpty() && !n.tasks.get(0).item.isEmpty()
                    && ClientProgress.visible(n.key)) {
                return n;
            }
        }
        return null;
    }

    private static void giveNow(ClientModel.Node n, double share) {
        ClientModel.Task task = n.tasks.get(0);
        long count = Math.max(1, Math.round(task.count * share));
        Minecraft.getInstance().player.connection.sendCommand("give @s " + BuiltInRegistries.ITEM.getKey(task.item.getItem()) + " " + count);
    }

    private static void shot(String name) {
        Minecraft mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, "questtree_" + name + ".png", mc.getMainRenderTarget(),
                msg -> Constants.LOG.info("Dev tour screenshot {}", name));
    }

    /** Parks the pointer's idea of hovering: see {@link ProgressScreen#tourResting(boolean)}. */
    private static void parkMouse() {
        ProgressScreen.tourResting(true);
    }

    private static ClientModel.Chapter first(ClientModel m, boolean skills) {
        return m.chapters.stream().filter(c -> c.skills == skills).findFirst().orElse(null);
    }

    private static void at(int tick, Runnable r) {
        int i = STEPS.size();
        while (i > 0 && STEPS.get(i - 1).at > tick) {
            i--;
        }
        STEPS.add(Math.max(i, next), new Step(tick, r));
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
