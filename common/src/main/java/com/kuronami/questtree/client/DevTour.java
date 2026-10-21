package com.kuronami.questtree.client;

import com.kuronami.questtree.Constants;
import com.kuronami.questtree.progress.ProgressRules.State;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

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
        // no chapter asked for: the screen opens on the first one in the list, fitted
        at(t, () -> open(-1));
        shot(t + 20, "01_first_open");
        at(t + 30, () -> selectFirst(State.PROGRESS));
        shot(t + 50, "02_detail");
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
            // a node under the pointer in the big chapter: its lines in cyan and yellow, the rest stepped back
            at(t + 142, () -> screen.tourHoverBusiest());
            shot(t + 146, "22_big_hover");
            at(t + 148, () -> ProgressScreen.tourHover(null));
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
        // the pinned block at the top of the chapter list, held open for the photo
        at(pt + 62, () -> open(-1));
        // the developer's own pins and glass setting are put back at the end, so the tour leaves the config as it found it
        List<String> pinsBefore = new ArrayList<>(ClientConfig.railPinned());
        boolean glassBefore = ClientConfig.glass();
        List<String> foldsBefore = new ArrayList<>(ClientConfig.railFolded);
        at(pt + 64, () -> {
            screen.tourPin(2);
            // and the first titled group folded shut, so the fold mark is in the same photo both ways
            for (ClientModel.Chapter c : m.chapters) {
                if (c.group != null && m.groupTitle(c) != null) {
                    if (!ClientConfig.railFolded(c.group)) {
                        ClientConfig.toggleRailFold(c.group);
                    }
                    break;
                }
            }
            ProgressScreen.tourRailOpen(true);
        });
        shot(pt + 68, "18_rail_pins");
        at(pt + 69, () -> ProgressScreen.tourRailOpen(false));
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
            ClientConfig.setRailFolded(foldsBefore);
            ClientConfig.write();
        });
        // ATM10's own chapters, to put next to the player's screenshots: Chapter 1 and the ATM Star, then the star's
        // pentagon under the pointer (it drew lines along its top until 2026-10-20)
        for (String[] c : new String[][]{{"mainquestline_part_1", "23_atm_chapter1"}, {"chapter_2_the_star", "24_atm_star"}}) {
            ClientModel.Chapter atm = m.chapters.stream().filter(x -> x.id.endsWith(":" + c[0])).findFirst().orElse(null);
            if (atm != null) {
                int base = c[1].startsWith("23") ? pt + 96 : pt + 104;
                at(base, () -> open(m.chapters.indexOf(atm)));
                shot(base + 4, c[1]);
            }
        }
        at(pt + 110, () -> {
            ClientModel.Chapter ch = screen == null ? null : m.chapters.stream().filter(x -> x.id.endsWith(":chapter_2_the_star")).findFirst().orElse(null);
            if (ch != null) {
                ch.nodes.values().stream().filter(n -> "pentagon".equals(n.shape)).findFirst().ifPresent(n -> {
                    ProgressScreen.tourHover(n.key);
                    screen.tourZoomTo(n, 2.5f);
                });
            }
        });
        shot(pt + 116, "25_star_pentagon_hover");
        at(pt + 118, () -> ProgressScreen.tourHover(null));
        float textScaleBefore = ClientConfig.textScale();
        boolean contrastBefore = ClientConfig.highContrast();
        boolean editorAllowedBefore = EditorSession.allowed();
        int et = pt + 130;
        // the client preferences: the list from its top, then from its bottom, then the keys
        at(et, () -> {
            screen = null;
            Minecraft.getInstance().setScreen(new SettingsScreen(null));
        });
        shot(et + 3, "26_settings_top");
        at(et + 5, () -> {
            if (Minecraft.getInstance().screen instanceof SettingsScreen s) {
                s.tourScrollBottom();
            }
        });
        shot(et + 9, "27_settings_bottom");
        at(et + 11, () -> Minecraft.getInstance().setScreen(new KeyReferenceScreen(null)));
        shot(et + 15, "28_key_reference");
        // editing: the mode on, a node selected, then the screens a player reaches from it
        at(et + 17, () -> {
            EditorSession.setAllowed(true);
            openId("aa_devtour:showcase");
        });
        at(et + 19, () -> EditorSession.setActive(true));
        at(et + 21, () -> screen.tourEditSelectFirst());
        shot(et + 25, "29_editor_map");
        at(et + 27, () -> screen.tourOpenNodeEditor("aa_devtour:showcase", "rich"));
        shot(et + 33, "30_node_edit");
        at(et + 35, () -> screen.tourOpenChapterEditor("aa_devtour:showcase"));
        shot(et + 41, "31_chapter_settings");
        at(et + 43, () -> screen.tourOpenTableEditor());
        shot(et + 49, "32_reward_table");
        at(et + 51, () -> screen.tourOpenRestore());
        shot(et + 57, "33_restore");
        at(et + 59, () -> screen.tourOpenParty());
        shot(et + 65, "34_party");
        // a skill chapter: the respec card, then the exchange chip the same header shows
        at(et + 67, () -> openId("aa_devtour:skills"));
        at(et + 71, () -> screen.tourAskRespec("aa_devtour:skills"));
        shot(et + 75, "35_respec_confirm");
        at(et + 77, () -> screen.tourClearConfirm());
        shot(et + 81, "36_exchange");
        // a locked chapter in the rail, with the pack's own hint under the pointer
        at(et + 83, () -> {
            ProgressScreen.tourResting(false);
            openId("aa_devtour:showcase");
        });
        at(et + 85, () -> ProgressScreen.tourRailOpen(true));
        at(et + 87, () -> {
            int[] row = screen.tourRailRow("aa_devtour:locked");
            if (row == null) {
                Constants.LOG.warn("Dev tour: the locked chapter has no rail row");
            } else {
                ProgressScreen.tourPointer(row[0], row[1]);
            }
        });
        shot(et + 93, "37_locked_chapter_hint");
        // the reward toast is a HUD toast, so the screen is closed for it
        at(et + 95, () -> {
            ProgressScreen.tourRailOpen(false);
            ProgressScreen.tourPointerClear();
            Minecraft.getInstance().setScreen(null);
        });
        at(et + 97, () -> RewardToast.show(
                List.of(new ItemStack(Items.DIAMOND, 4), new ItemStack(Items.EMERALD, 4)),
                List.of(Component.literal("Diamond x4"), Component.literal("Emerald x4"))));
        shot(et + 100, "38_reward_toast");
        // a description with a picture and both link kinds (the dev pack)
        at(et + 102, () -> openId("aa_devtour:showcase"));
        at(et + 104, () -> screen.tourSelect("aa_devtour:showcase/rich"));
        shot(et + 112, "39_description_image_links");
        // the emergency row, the chapter list open with the pointer on it, its items in the tooltip
        at(et + 114, () -> ProgressScreen.tourRailOpen(true));
        at(et + 116, () -> {
            int[] row = screen.tourEmergencyRow();
            if (row == null) {
                Constants.LOG.warn("Dev tour: the pack has no emergency items");
            } else {
                ProgressScreen.tourPointer(row[0], row[1]);
            }
        });
        shot(et + 122, "40_emergency_items");
        // the same sheet at 120% text, then the high-contrast palette
        at(et + 124, () -> {
            ProgressScreen.tourRailOpen(false);
            ProgressScreen.tourPointerClear();
            ClientConfig.textScale = 1.2f;
            openId("aa_devtour:showcase");
        });
        at(et + 127, () -> screen.tourSelect("aa_devtour:showcase/rich"));
        shot(et + 136, "41_text_120");
        at(et + 138, () -> {
            ClientConfig.highContrast = true;
            Ui.loadPalette(Minecraft.getInstance().getResourceManager());
        });
        shot(et + 143, "42_high_contrast");
        at(et + 145, () -> {
            // leave the developer's own settings as they were found
            ClientConfig.textScale = textScaleBefore;
            ClientConfig.highContrast = contrastBefore;
            Ui.textScale = textScaleBefore;
            Ui.loadPalette(Minecraft.getInstance().getResourceManager());
            ClientConfig.write();
            EditorSession.setActive(false);
            EditorSession.setAllowed(editorAllowedBefore);
            ProgressScreen.tourPointerClear();
            ProgressScreen.tourRailOpen(false);
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

    /** Opens a chapter by id; logs (and leaves the current screen) when the pack this world runs does not have it. */
    private static void openId(String id) {
        ClientModel m = ClientProgress.model();
        if (m == null) {
            return;
        }
        for (int i = 0; i < m.chapters.size(); i++) {
            if (m.chapters.get(i).id.equals(id)) {
                open(i);
                return;
            }
        }
        Constants.LOG.warn("Dev tour: no chapter {}", id);
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
