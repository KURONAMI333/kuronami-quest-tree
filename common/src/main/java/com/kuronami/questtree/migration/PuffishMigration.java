package com.kuronami.questtree.migration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kuronami.questtree.data.ProgressModel;
import com.kuronami.questtree.importer.FtbQuestsImporter;
import com.kuronami.questtree.progress.PlayerProgress;
import com.kuronami.questtree.progress.ProgressRules;
import com.kuronami.questtree.progress.TeamProgress;
import com.kuronami.questtree.progress.TeamStoreData;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Folds Pufferfish's Skills player data into KQT skill chapters (2026-10-20). Merge-only like {@link FtbqMigration}.
 * <ul>
 *   <li>Unlocked skills become taken nodes (rank 1). The chapter is found from the category id the way
 *       {@code PuffishImporter} named it ({@code skill_<slug of namespace_category>}), the node from the same slug of
 *       the skill key, so no id is guessed.</li>
 *   <li>Pufferfish keeps the experience total; KQT keeps the level and the experience inside it. The total is walked
 *       down the chapter's own {@code per_level} curve and honours {@code level_limit}. Copying the total into the
 *       stored experience would read as thousands of points inside level 0.</li>
 *   <li>{@code spent} grows by the cost of every skill taken by this run, so it equals the sum of the costs of the
 *       taken skills when KQT had none.</li>
 *   <li>{@code bonus} makes the points KQT shows equal Pufferfish's total: {@code total - start - level}. When that is
 *       negative (the source gave fewer points per level than KQT does) it is written as 0 and counted in
 *       {@code points_over_source}: taking points a player already earned is worse than giving one extra.</li>
 *   <li>A category that was unlocked in Pufferfish but is locked by default in the pack gets an "open" override.</li>
 * </ul>
 * Pufferfish's {@code level} counts the exchanges of experience for a point; it becomes KQT's exchange count (so the
 * exchange limit still applies), and the points those exchanges gave are already in the points total, hence in the bonus.
 */
public final class PuffishMigration {

    private static final String DEFAULT_NAMESPACE = "puffish_skills";

    /**
     * @param namespace KQT namespace the trees were imported under; null looks for a unique one
     * @param players   player uuid to that player's {@code PlayerData} object (the one that holds {@code categories})
     */
    public record Input(String namespace, ProgressModel model, Map<UUID, JsonObject> players,
                        Function<UUID, UUID> teamOf) {
    }

    private final Input in;
    private final TeamStoreData store;
    private final MigrationReport report;
    private final ProgressRules rules;

    private PuffishMigration(Input in, TeamStoreData store, MigrationReport report) {
        this.in = in;
        this.store = store;
        this.report = report;
        this.rules = new ProgressRules(in.model());
    }

    public static void run(Input in, TeamStoreData store, MigrationReport report) {
        new PuffishMigration(in, store, report).run();
    }

    private void run() {
        for (Map.Entry<UUID, JsonObject> player : in.players().entrySet()) {
            report.add("players", 1);
            JsonObject categories = obj(player.getValue(), "categories");
            UUID team = in.teamOf().apply(player.getKey());
            for (Map.Entry<String, JsonElement> cat : categories.entrySet()) {
                if (cat.getValue().isJsonObject()) {
                    category(player.getKey(), team, cat.getKey(), cat.getValue().getAsJsonObject());
                }
            }
        }
    }

    private void category(UUID player, UUID teamId, String rawId, JsonObject data) {
        ProgressModel.Chapter chapter = chapterFor(rawId);
        if (chapter == null) {
            return;
        }
        report.add("categories", 1);
        TeamProgress team = store.team(teamId);
        PlayerProgress shared = team.shared;
        String pool = ProgressRules.key(chapter);

        if (bool(data.get("unlocked")) && !ProgressRules.chapterUnlocked(new PlayerProgress(), chapter)
                && !shared.chapterOpen.containsKey(chapter.id)) {
            shared.chapterOpen.put(chapter.id, true);
            report.add("chapters_opened", 1);
        }

        int spentAdded = 0;
        if (data.has("unlocked_skills") && data.get("unlocked_skills").isJsonArray()) {
            for (JsonElement s : data.getAsJsonArray("unlocked_skills")) {
                String skill = s.isJsonPrimitive() ? s.getAsString() : "";
                String key = chapter.id + "/" + FtbQuestsImporter.slug(skill);
                ProgressModel.Node node = in.model().nodes.get(key);
                if (node == null) {
                    report.unresolved("skill", rawId + " " + skill + " (no node " + key + ")");
                    continue;
                }
                report.add("skills_found", 1);
                if (shared.taken.getOrDefault(key, 0) < 1) {
                    shared.taken.put(key, 1);
                    spentAdded += Math.max(0, node.cost);
                    report.add("skills_added", 1);
                }
            }
        }
        if (spentAdded > 0) {
            shared.spent.merge(pool, spentAdded, Integer::sum);
            report.add("points_spent_added", spentAdded);
        }

        // experience total -> level and experience inside it, then keep whichever side is further along
        int level = 0;
        double rest = number(data.get("experience"));
        int limit = chapter.points != null && chapter.points.has("level_limit")
                && chapter.points.get("level_limit").isJsonPrimitive()
                ? chapter.points.get("level_limit").getAsInt() : Integer.MAX_VALUE;
        while (level < limit) {
            double need = rules.experienceForLevel(chapter, level);
            if (!Double.isFinite(need) || need <= 0 || rest < need) {
                break;
            }
            rest -= need;
            level++;
        }
        if (level >= limit) {
            rest = 0;
        }
        int have = shared.level.getOrDefault(pool, 0);
        double haveXp = shared.xp.getOrDefault(pool, 0.0);
        if (level > have || level == have && rest > haveXp) {
            if (level > have) {
                report.add("levels_added", level - have);
            }
            shared.level.put(pool, level);
            shared.xp.put(pool, rest);
        }
        int exchanged = (int) number(data.get("level"));
        if (exchanged > shared.exchange.getOrDefault(pool, 0)) {
            shared.exchange.put(pool, exchanged);
            report.add("exchange_added", 1);
        }
        int finalLevel = shared.level.getOrDefault(pool, 0);

        int start = chapter.points != null && chapter.points.has("start") && chapter.points.get("start").isJsonPrimitive()
                ? chapter.points.get("start").getAsInt() : 0;
        int derived = pointsTotal(data.get("points")) - start - finalLevel;
        if (derived < 0) {
            report.add("points_over_source", -derived);
            derived = 0;
        }
        if (derived > shared.bonus.getOrDefault(pool, 0)) {
            shared.bonus.put(pool, derived);
            report.add("bonus_added", 1);
        }
    }

    /** The chapter a Pufferfish category id became, or null with an entry in the report. */
    private ProgressModel.Chapter chapterFor(String rawId) {
        String id = rawId.contains(":") ? rawId : DEFAULT_NAMESPACE + ":" + rawId;
        int colon = id.indexOf(':');
        String ns = id.substring(0, colon);
        String path = id.substring(colon + 1);
        // categories from data packs are "namespace/category"; those read from the config folder are "config/category"
        List<String> sources = new ArrayList<>();
        sources.add(ns + "/" + path);
        if (ns.equals(DEFAULT_NAMESPACE)) {
            sources.add("config/" + path);
        }
        Set<ProgressModel.Chapter> found = new LinkedHashSet<>();
        for (String source : sources) {
            String cid = "skill_" + FtbQuestsImporter.slug(source.replace('/', '_'));
            for (ProgressModel.Chapter c : in.model().chapters.values()) {
                if (c.isSkills() && c.path.equals(cid) && (in.namespace() == null || c.namespace.equals(in.namespace()))) {
                    found.add(c);
                }
            }
        }
        if (found.size() == 1) {
            return found.iterator().next();
        }
        report.unresolved(found.isEmpty() ? "category" : "category_ambiguous", id);
        return null;
    }

    /** Pufferfish writes points as one number (old files) or a map of source to count; the total is their sum. */
    static int pointsTotal(JsonElement points) {
        if (points == null) {
            return 0;
        }
        if (points.isJsonPrimitive()) {
            return (int) number(points);
        }
        int total = 0;
        if (points.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : points.getAsJsonObject().entrySet()) {
                total += (int) number(e.getValue());
            }
        }
        return total;
    }

    private static double number(JsonElement e) {
        if (e == null || !e.isJsonPrimitive()) {
            return 0;
        }
        try {
            return e.getAsDouble();
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    /** NBT booleans arrive as 0/1 bytes once converted to JSON. */
    private static boolean bool(JsonElement e) {
        if (e == null || !e.isJsonPrimitive()) {
            return false;
        }
        return e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : number(e) != 0;
    }

    private static JsonObject obj(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : new JsonObject();
    }
}
