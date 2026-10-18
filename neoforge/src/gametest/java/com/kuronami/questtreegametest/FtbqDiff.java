package com.kuronami.questtreegametest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kuronami.questtree.Constants;
import com.kuronami.questtree.data.ProgressModel;
import com.kuronami.questtree.importer.FtbQuestsImporter;
import com.kuronami.questtree.importer.ImportOutput;
import com.kuronami.questtree.progress.PlayerProgress;
import com.kuronami.questtree.progress.ProgressRules;
import net.minecraft.server.MinecraftServer;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Development only: plays the same questline through FTB Quests and through this mod's import of it, and records
 * every quest where the two disagree on whether it is visible, can be started, is complete or is shut out by an
 * exclusive branch. FTB Quests is the reference; each disagreement is a gap in the import or in the rules. Three walks
 * complete any quest FTB Quests lets the player start (flexible packs allow that early); three more only complete
 * quests whose dependencies are done, the way a questline is really played.
 * <p>
 * Runs only on a server started with {@code -Dquest_tree.ftbqDiff=<steps>} whose game directory holds FTB Quests
 * (and its libraries) in {@code mods/} and a pack's quests in {@code config/ftbquests/quests}. FTB Quests is reached
 * by reflection, so nothing here links against it and normal builds and tests never need it. The report is written to
 * {@code ftbq_diff_report.json} in the game directory and the server stops.
 */
final class FtbqDiff {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int EXAMPLES = 25;

    private FtbqDiff() {
    }

    static void run(MinecraftServer server, int steps) {
        try {
            JsonObject report = diff(server, steps);
            Path out = server.getServerDirectory().resolve("ftbq_diff_report.json");
            Files.writeString(out, GSON.toJson(report), StandardCharsets.UTF_8);
            Constants.LOG.info("FTBQ diff finished: {}", report.get("summary"));
        } catch (Exception ex) {
            Constants.LOG.error("FTBQ diff failed", ex);
        }
        server.halt(false);
    }

    private static JsonObject diff(MinecraftServer server, int steps) throws Exception {
        Path questsDir = server.getServerDirectory().resolve("config/ftbquests/quests");
        ImportOutput imported = new ImportOutput("ftbquests");
        FtbQuestsImporter.convert(questsDir, imported);
        JsonObject bundle = new JsonObject();
        JsonObject chapters = new JsonObject();
        imported.chapters.forEach((k, v) -> chapters.add("ftbquests:" + k, v));
        JsonObject tables = new JsonObject();
        imported.tables.forEach((k, v) -> tables.add("ftbquests:" + k, v));
        bundle.add("chapters", chapters);
        bundle.add("reward_tables", tables);
        bundle.add("groups", new JsonArray());
        ProgressModel model = ProgressModel.of(bundle);
        ProgressRules rules = new ProgressRules(model);
        Map<String, ProgressModel.Node> byHex = new HashMap<>();
        for (ProgressModel.Node n : model.nodes.values()) {
            byHex.put(n.key.substring(n.key.lastIndexOf('/') + 1), n);
        }

        Class<?> fileClass = Class.forName("dev.ftb.mods.ftbquests.quest.ServerQuestFile");
        Object file = fileClass.getField("INSTANCE").get(null);
        Class<?> questClass = Class.forName("dev.ftb.mods.ftbquests.quest.Quest");
        Class<?> teamDataClass = Class.forName("dev.ftb.mods.ftbquests.quest.TeamData");
        Class<?> objClass = Class.forName("dev.ftb.mods.ftbquests.quest.QuestObject");
        Method getOrCreate = file.getClass().getMethod("getOrCreateTeamData", UUID.class);
        Method visible = questClass.getMethod("isVisible", teamDataClass);
        Method canStart = teamDataClass.getMethod("canStartTasks", questClass);
        Method completed = teamDataClass.getMethod("isCompleted", objClass);
        Method excluded = teamDataClass.getMethod("isExcludedByOtherQuestline", objClass);
        Method setCompleted = teamDataClass.getMethod("setCompleted", long.class, Date.class);
        Method depsComplete = teamDataClass.getMethod("areDependenciesComplete", questClass);
        java.lang.reflect.Field idField = Class.forName("dev.ftb.mods.ftbquests.quest.QuestObjectBase").getField("id");

        List<Object> quests = new ArrayList<>();
        for (Object o : (java.util.Collection<?>) file.getClass().getMethod("getAllObjects").invoke(file)) {
            if (questClass.isInstance(o)) {
                quests.add(o);
            }
        }
        Map<Object, String> hexOf = new HashMap<>();
        int unmapped = 0;
        for (Object q : quests) {
            String hex = String.format(Locale.ROOT, "%016x", idField.getLong(q));
            hexOf.put(q, hex);
            if (!byHex.containsKey(hex)) {
                unmapped++;
            }
        }

        Map<String, Integer> counts = new TreeMap<>();
        Map<String, List<JsonObject>> examples = new TreeMap<>();
        Map<String, java.util.Set<String>> distinct = new TreeMap<>();
        int totalSteps = 0;
        Map<String, Integer> walks = new LinkedHashMap<>();
        for (int seed = 1; seed <= 6; seed++) {
            boolean inOrder = seed > 3;
            Random random = new Random(seed);
            Object team = getOrCreate.invoke(file, UUID.randomUUID());
            PlayerProgress p = new PlayerProgress();
            for (int step = 0; step <= steps; step++) {
                List<Object> next = new ArrayList<>();
                for (Object q : quests) {
                    ProgressModel.Node n = byHex.get(hexOf.get(q));
                    if (n == null) {
                        continue;
                    }
                    boolean fv = (boolean) visible.invoke(q, team);
                    boolean fc = (boolean) completed.invoke(team, q);
                    boolean fs = !fc && (boolean) canStart.invoke(team, q);
                    boolean fe = (boolean) excluded.invoke(team, q);
                    ProgressRules.State ks = rules.state(p, n);
                    boolean kv = rules.visible(p, n);
                    boolean kc = ks == ProgressRules.State.DONE || ks == ProgressRules.State.CLAIM;
                    boolean kst = ks == ProgressRules.State.OPEN || ks == ProgressRules.State.PROGRESS;
                    boolean ke = ks == ProgressRules.State.EXCLUDED;
                    check(counts, examples, distinct, "visible", fv, kv, n, seed, step);
                    check(counts, examples, distinct, "startable", fs, kst, n, seed, step);
                    check(counts, examples, distinct, "completed", fc, kc, n, seed, step);
                    check(counts, examples, distinct, "excluded", fe, ke, n, seed, step);
                    if (fs && fv && (!inOrder || (boolean) depsComplete.invoke(team, q))) {
                        next.add(q);
                    }
                }
                if (next.isEmpty() || step == steps) {
                    break;
                }
                Object pick = next.get(random.nextInt(next.size()));
                setCompleted.invoke(team, idField.getLong(pick), new Date());
                String key = byHex.get(hexOf.get(pick)).key;
                p.completed.add(key);
                p.claimed.add(key);
                totalSteps++;
                walks.merge(inOrder ? "in_order" : "any_startable", 1, Integer::sum);
            }
        }

        JsonObject summary = new JsonObject();
        summary.addProperty("ftbq_quests", quests.size());
        summary.addProperty("kqt_nodes", model.nodes.size());
        summary.addProperty("ftbq_quests_without_node", unmapped);
        summary.addProperty("steps_played", totalSteps);
        JsonObject w = new JsonObject();
        walks.forEach(w::addProperty);
        summary.add("steps_by_walk", w);
        JsonObject mism = new JsonObject();
        counts.forEach((k, v) -> {
            JsonObject o = new JsonObject();
            o.addProperty("checks", v);
            o.addProperty("distinct_quests", distinct.getOrDefault(k, java.util.Set.of()).size());
            mism.add(k, o);
        });
        summary.add("mismatches", mism);
        JsonObject report = new JsonObject();
        report.add("summary", summary);
        JsonObject ex = new JsonObject();
        examples.forEach((k, list) -> {
            JsonArray a = new JsonArray();
            list.forEach(a::add);
            ex.add(k, a);
        });
        report.add("examples", ex);
        return report;
    }

    private static void check(Map<String, Integer> counts, Map<String, List<JsonObject>> examples,
                              Map<String, java.util.Set<String>> distinct, String what, boolean ftbq, boolean kqt,
                              ProgressModel.Node n, int seed, int step) {
        if (ftbq == kqt) {
            return;
        }
        String kind = what + (ftbq ? ".ftbq_yes_kqt_no" : ".ftbq_no_kqt_yes");
        counts.merge(kind, 1, Integer::sum);
        if (distinct.computeIfAbsent(kind, k -> new java.util.HashSet<>()).add(n.key)) {
            List<JsonObject> list = examples.computeIfAbsent(kind, k -> new ArrayList<>());
            if (list.size() < EXAMPLES) {
                JsonObject o = new JsonObject();
                o.addProperty("node", n.key);
                o.addProperty("seed", seed);
                o.addProperty("step", step);
                JsonObject data = new JsonObject();
                for (Map.Entry<String, JsonElement> e : n.json.entrySet()) {
                    if (!e.getKey().equals("tasks") && !e.getKey().equals("rewards") && !e.getKey().equals("description")) {
                        data.add(e.getKey(), e.getValue());
                    }
                }
                o.add("data", data);
                list.add(o);
            }
        }
    }
}
