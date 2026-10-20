package com.kuronami.questtree.progress;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kuronami.questtree.data.ProgressModel;
import com.kuronami.questtree.points.Expression;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Game-independent rules: the state of every node for one player, and the skill point arithmetic.
 * States match the screen's tiles: DONE (green), CLAIM (yellow: one click to claim or unlock), PROGRESS (filling),
 * OPEN (can be worked on), LOCKED (requirements missing), EXCLUDED (closed by an exclusive choice).
 */
public final class ProgressRules {

    public enum State { DONE, CLAIM, PROGRESS, OPEN, LOCKED, EXCLUDED }

    private final ProgressModel model;
    private final Map<String, Expression> curves = new HashMap<>();

    public ProgressRules(ProgressModel model) {
        this.model = model;
    }

    public ProgressModel model() {
        return model;
    }

    public State state(PlayerProgress p, ProgressModel.Node n) {
        if (!chapterUnlocked(p, n.chapter)) {
            return State.LOCKED;
        }
        if (excluded(p, n)) {
            return State.EXCLUDED;
        }
        if (n.chapter.isSkills() && n.tasks.isEmpty()) {
            int max = Math.max(1, n.ranks);
            int got = p.taken.getOrDefault(n.key, 0);
            if (got >= max) {
                return State.DONE;
            }
            if (!unlocked(p, n)) {
                return State.LOCKED;
            }
            if (availablePoints(p, n.chapter) >= Math.max(0, n.cost)) {
                return State.CLAIM;
            }
            // partly ranked nodes are not finished: they still need points (and nothingLeft must not stop experience)
            return State.OPEN;
        }
        if (p.cooldown.getOrDefault(n.key, 0L) > System.currentTimeMillis()) {
            return State.LOCKED;
        }
        if (p.completed.contains(n.key)) {
            return !n.rewards.isEmpty() && !p.claimed.contains(n.key) && !allRewardsClaimed(p, n) ? State.CLAIM : State.DONE;
        }
        if (!unlocked(p, n) && !flexible(n)) {
            return State.LOCKED;
        }
        return fraction(p, n) > 0 ? State.PROGRESS : State.OPEN;
    }

    /** Whether every reward of a node was claimed one by one (2026-10-20). */
    public static boolean allRewardsClaimed(PlayerProgress p, ProgressModel.Node n) {
        for (int i = 0; i < n.rewards.size(); i++) {
            if (!p.rewardClaimed(n.key + "#" + i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a node's tasks count before its requirements are met (FTB Quests' "flexible" progression): set on the
     * node ({@code progression_mode}, or the older {@code tasks_ignore_requires}) or as the chapter default. Such a
     * node can be worked on at any time but only completes once its requirements are met.
     */
    public static boolean flexible(ProgressModel.Node n) {
        if (ProgressModel.boolOf(n.json, "tasks_ignore_requires")) {
            return true;
        }
        String mode = n.json.has("progression_mode") && n.json.get("progression_mode").isJsonPrimitive()
                ? n.json.get("progression_mode").getAsString() : "default";
        if (mode.equals("default") && n.chapter.defaults.has("progression_mode") && n.chapter.defaults.get("progression_mode").isJsonPrimitive()) {
            mode = n.chapter.defaults.get("progression_mode").getAsString();
        }
        return mode.equals("flexible");
    }

    /**
     * Whether the chapter itself is open (Pufferfish's Skills category lock). A pack locks a chapter with
     * {@code points.unlocked_by_default: false}; commands store an override per player, so unlocking one player does not
     * open it for the whole server. Locked chapters are left out of the rail, and their XP sources do not count.
     */
    public static boolean chapterUnlocked(PlayerProgress p, ProgressModel.Chapter c) {
        Boolean override = p.chapterOpen.get(c.id);
        if (override != null) {
            return override;
        }
        return c.points == null || !c.points.has("unlocked_by_default")
                || !c.points.get("unlocked_by_default").isJsonPrimitive()
                || c.points.get("unlocked_by_default").getAsJsonPrimitive().isBoolean()
                && c.points.get("unlocked_by_default").getAsBoolean();
    }

    /** Whether the requirements are met (not considering exclusivity). */
    public boolean unlocked(PlayerProgress p, ProgressModel.Node n) {
        // Pufferfish's Skills roots are available from the start even though connections also point back at them
        if (n.requires.isEmpty() || n.root) {
            return true;
        }
        boolean started = n.requireMode.endsWith("started");
        int met = 0;
        for (String r : n.requires) {
            if (satisfied(p, r, started)) {
                met++;
            }
        }
        if (n.requireMin > 0) {
            return met >= n.requireMin;
        }
        return n.requireMode.startsWith("any") || n.requireMode.equals("one") ? met > 0 : met == n.requires.size();
    }

    private boolean satisfied(PlayerProgress p, String key, boolean startedIsEnough) {
        if (doneOnce(p, key)) {
            return true;
        }
        if (!startedIsEnough) {
            return false;
        }
        ProgressModel.Node n = model.nodes.get(key);
        return n != null && fraction(p, n) > 0;
    }

    public boolean excluded(PlayerProgress p, ProgressModel.Node n) {
        if (doneOnce(p, n.key)) {
            return false;
        }
        for (String other : n.exclusiveWith) {
            if (doneOnce(p, other)) {
                return true;
            }
        }
        if (n.exclusiveGroup != null) {
            List<ProgressModel.Node> group = n.chapter.exclusiveGroups.get(n.exclusiveGroup);
            long done = group == null ? 0 : group.stream().filter(g -> doneOnce(p, g.key)).count();
            return done >= n.exclusiveMax;
        }
        return false;
    }

    /**
     * Completed at least once: completed now, taken (skills), or a repeatable quest that already started over. A
     * repeat must not lock the quests after it again or reopen the other side of an exclusive choice.
     */
    public static boolean doneOnce(PlayerProgress p, String key) {
        return p.completed.contains(key) || p.taken.getOrDefault(key, 0) > 0 || p.repeats.getOrDefault(key, 0) > 0;
    }

    /** Whether a node repeats: {@code "repeat": true} or an object. */
    public static boolean repeatable(ProgressModel.Node n) {
        return repeatable(n.json);
    }

    /** The same from raw node JSON, which the client reads before it builds its own node type (2026-10-20). */
    public static boolean repeatable(JsonObject node) {
        JsonElement rep = node.get("repeat");
        return rep != null && (rep.isJsonObject() || (rep.isJsonPrimitive() && rep.getAsJsonPrimitive().isBoolean() && rep.getAsBoolean()));
    }

    /**
     * Whether the player may see a node yet (FTB Quests' hidden quests). Done nodes are always visible. Otherwise
     * {@code visibility.hidden_until} (or the chapter default) decides: "complete" (secret until done),
     * "deps_complete", "deps_visible"; {@code visibility.invisible_until_tasks} shows it after that many tasks;
     * {@code visibility.requires} names nodes that must be done first. Hidden nodes still track progress.
     */
    public boolean visible(PlayerProgress p, ProgressModel.Node n) {
        return visible(p, n, new java.util.HashMap<>());
    }

    /**
     * The same, sharing {@code memo} (node key to answer) with other calls for the same progress. The answer for one
     * node can depend on a whole web of requirements, so a caller that asks about many nodes (the map, item tooltips)
     * passes one memo and each node is worked out once; without it, dense hidden chapters cost exponential time
     * (2026-10-19 review).
     */
    public boolean visible(PlayerProgress p, ProgressModel.Node n, java.util.Map<String, Boolean> memo) {
        return visible(p, n, memo, new java.util.HashSet<>());
    }

    private boolean visible(PlayerProgress p, ProgressModel.Node n, java.util.Map<String, Boolean> memo, java.util.Set<String> path) {
        Boolean known = memo.get(n.key);
        if (known != null) {
            return known;
        }
        if (!path.add(n.key)) {
            // a loop of requirements shows nothing by itself
            return false;
        }
        boolean v = visibleNow(p, n, memo, path);
        path.remove(n.key);
        memo.put(n.key, v);
        return v;
    }

    private boolean visibleNow(PlayerProgress p, ProgressModel.Node n, java.util.Map<String, Boolean> memo, java.util.Set<String> path) {
        if (doneOnce(p, n.key)) {
            return true;
        }
        JsonObject vis = n.json.has("visibility") && n.json.get("visibility").isJsonObject() ? n.json.getAsJsonObject("visibility") : new JsonObject();
        if (vis.has("invisible_until_tasks")) {
            int need = vis.get("invisible_until_tasks").getAsInt();
            int done = 0;
            for (int i = 0; i < n.tasks.size(); i++) {
                if (p.task(n.key, i) >= target(n.tasks.get(i))) {
                    done++;
                }
            }
            if (done < need) {
                return false;
            }
        }
        if (vis.has("requires") && vis.get("requires").isJsonObject()) {
            for (JsonElement e : vis.getAsJsonObject("requires").has("nodes") ? vis.getAsJsonObject("requires").getAsJsonArray("nodes") : new com.google.gson.JsonArray()) {
                String r = e.getAsString();
                String key = r.contains(":") ? r : r.contains("/") ? n.chapter.namespace + ":" + r : n.chapter.id + "/" + r;
                if (!doneOnce(p, key)) {
                    return false;
                }
            }
        }
        String until = vis.has("hidden_until") ? vis.get("hidden_until").getAsString()
                : n.chapter.defaults.has("hidden_until") && n.chapter.defaults.get("hidden_until").isJsonPrimitive()
                ? n.chapter.defaults.get("hidden_until").getAsString() : "";
        switch (until) {
            case "complete":
                return false;
            case "deps_complete":
                return unlocked(p, n);
            case "deps_visible":
                for (String r : n.requires) {
                    ProgressModel.Node d = model.nodes.get(r);
                    if (d != null && !visible(p, d, memo, path)) {
                        return false;
                    }
                }
                return true;
            default:
                // like FTB Quests: a node behind requirements shows while at least one of them shows, so a hidden
                // branch keeps what grows from it hidden too (skill trees are exempt: their links run both ways)
                if (n.requires.isEmpty() || n.root || n.chapter.isSkills()) {
                    return true;
                }
                for (String r : n.requires) {
                    ProgressModel.Node d = model.nodes.get(r);
                    if (d == null || visible(p, d, memo, path)) {
                        return true;
                    }
                }
                return false;
        }
    }

    /**
     * Whether a task may move now (FTB Quests' {@code require_sequential_tasks}, 2026-10-20): tasks run in the order
     * they are written, and each waits until the one before it reached its target. The last task still has to be done;
     * the rule only stops a later task from being counted or submitted early.
     */
    public boolean taskOpen(PlayerProgress p, ProgressModel.Node n, int index) {
        if (!n.sequentialTasks || index <= 0) {
            return true;
        }
        if (index >= n.tasks.size()) {
            return false;
        }
        return p.task(n.key, index - 1) >= target(n.tasks.get(index - 1));
    }

    /** Share of the node's tasks done, 0..1 (counts weighted by their targets). */
    public double fraction(PlayerProgress p, ProgressModel.Node n) {
        if (n.tasks.isEmpty()) {
            return p.completed.contains(n.key) ? 1 : 0;
        }
        double got = 0;
        double need = 0;
        for (int i = 0; i < n.tasks.size(); i++) {
            long target = target(n.tasks.get(i));
            got += Math.min(p.task(n.key, i), target);
            need += target;
        }
        return need == 0 ? 0 : got / need;
    }

    /** Whether all (or {@code tasks_required}) non-optional tasks reached their targets. */
    public boolean tasksDone(PlayerProgress p, ProgressModel.Node n) {
        int done = 0;
        int required = 0;
        for (int i = 0; i < n.tasks.size(); i++) {
            JsonObject t = n.tasks.get(i);
            boolean optional = t.has("optional") && t.get("optional").getAsBoolean();
            boolean ok = p.task(n.key, i) >= target(t);
            if (!optional) {
                required++;
                if (ok) {
                    done++;
                }
            }
        }
        int need = n.tasksRequired > 0 ? Math.min(n.tasksRequired, required) : required;
        return done >= need;
    }

    public static long target(JsonObject task) {
        if (task.has("count") && task.get("count").isJsonPrimitive()) {
            return Math.max(1, task.get("count").getAsLong());
        }
        if (task.has("item") && task.get("item").isJsonObject() && task.getAsJsonObject("item").has("count")) {
            return Math.max(1, task.getAsJsonObject("item").get("count").getAsLong());
        }
        if (task.has("amount")) {
            return Math.max(1, task.get("amount").getAsLong());
        }
        if (task.has("ticks") && task.get("ticks").isJsonPrimitive()) {
            return Math.max(1, task.get("ticks").getAsLong());
        }
        return 1;
    }

    // ------------------------------------------------------------------ skill points

    /**
     * Where a chapter's experience, level and points live (2026-10-20). Chapters that name the same
     * {@code points.pool} share one progression; the stored key is prefixed so it cannot collide with a chapter id.
     */
    public static String key(ProgressModel.Chapter c) {
        JsonElement pool = c.points == null ? null : c.points.get("pool");
        String name = pool != null && pool.isJsonPrimitive() && pool.getAsJsonPrimitive().isString() ? pool.getAsString() : "";
        return name.isBlank() ? c.id : key(c.id, name);
    }

    /** The client has the chapter id and {@code points.pool} but not the whole chapter. */
    public static String key(String chapterId, String pool) {
        return pool == null || pool.isBlank() ? chapterId : "@pool:" + pool;
    }

    /** Whether several chapters share this chapter's experience, level, points and exchange counter. */
    public static boolean isPooled(ProgressModel.Chapter c) {
        return !key(c).equals(c.id);
    }

    /**
     * Saved progress written before a chapter joined a pool is keyed by the chapter id. On load, fold those values
     * into the pool key so adding {@code points.pool} to an existing chapter does not strand its level, experience,
     * spent points, extra points or exchange count (2026-10-20). The fold is idempotent and also picks up chapters
     * added to a pool later. Two chapters that already had their own values are summed: each was earned separately.
     */
    public static void migratePools(PlayerProgress p, ProgressModel model) {
        Map<String, List<ProgressModel.Chapter>> pools = new LinkedHashMap<>();
        for (ProgressModel.Chapter c : model.chapters.values()) {
            if (isPooled(c)) {
                pools.computeIfAbsent(key(c), k -> new ArrayList<>()).add(c);
            }
        }
        for (Map.Entry<String, List<ProgressModel.Chapter>> e : pools.entrySet()) {
            String pool = e.getKey();
            boolean xp = false;
            double xpSum = p.xp.getOrDefault(pool, 0.0);
            int levelSum = p.level.getOrDefault(pool, 0);
            int spentSum = p.spent.getOrDefault(pool, 0);
            int bonusSum = p.bonus.getOrDefault(pool, 0);
            int exchangeSum = p.exchange.getOrDefault(pool, 0);
            boolean level = p.level.containsKey(pool);
            boolean spent = p.spent.containsKey(pool);
            boolean bonus = p.bonus.containsKey(pool);
            boolean exchange = p.exchange.containsKey(pool);
            for (ProgressModel.Chapter c : e.getValue()) {
                Double x = p.xp.remove(c.id);
                if (x != null) {
                    xpSum += x;
                    xp = true;
                }
                Integer l = p.level.remove(c.id);
                if (l != null) {
                    levelSum += l;
                    level = true;
                }
                Integer s = p.spent.remove(c.id);
                if (s != null) {
                    spentSum += s;
                    spent = true;
                }
                Integer b = p.bonus.remove(c.id);
                if (b != null) {
                    bonusSum += b;
                    bonus = true;
                }
                Integer ex = p.exchange.remove(c.id);
                if (ex != null) {
                    exchangeSum += ex;
                    exchange = true;
                }
            }
            if (xp) {
                p.xp.put(pool, xpSum);
            }
            if (level) {
                p.level.put(pool, levelSum);
            }
            if (spent) {
                p.spent.put(pool, spentSum);
            }
            if (bonus) {
                p.bonus.put(pool, bonusSum);
            }
            if (exchange) {
                p.exchange.put(pool, exchangeSum);
            }
        }
    }

    /**
     * Respec and a one-chapter reset give back only this chapter's nodes, also when they share a pool with other
     * chapters: the pool's spent counter is the sum of every member's taken nodes, so subtracting this chapter's
     * share leaves the others' points spent (2026-10-20 review).
     */
    public static int refundChapterSpent(PlayerProgress p, ProgressModel.Chapter c) {
        int refund = 0;
        for (ProgressModel.Node n : c.nodes.values()) {
            int taken = p.taken.getOrDefault(n.key, 0);
            if (taken > 0) {
                refund += Math.max(0, n.cost) * taken;
            }
        }
        if (refund > 0) {
            int refunded = refund;
            p.spent.computeIfPresent(key(c), (k, v) -> Math.max(0, v - refunded));
        }
        return refund;
    }

    /**
     * A one-chapter reset's stored values: a chapter without a pool also loses its experience, level, spent counter
     * and extra points; a pooled chapter keeps them because they belong to every chapter in the pool. The exchange
     * count always goes (2026-10-20 review).
     */
    public static void resetChapterData(PlayerProgress p, ProgressModel.Chapter c) {
        String key = key(c);
        if (!isPooled(c)) {
            p.xp.remove(key);
            p.level.remove(key);
            p.spent.remove(key);
            p.bonus.remove(key);
        }
        p.exchange.remove(key);
    }

    /**
     * Forgets every stored skill value and per-player chapter override, for a full reset. Taken nodes are reset by
     * the caller first (they also give their attributes back), so they are not touched here (2026-10-20 review).
     */
    public static void clearAll(PlayerProgress p) {
        p.xp.clear();
        p.level.clear();
        p.spent.clear();
        p.bonus.clear();
        p.exchange.clear();
        p.chapterOpen.clear();
        p.recent.clear();
    }

    /** Forgets a pool's experience, level, points and exchange count (Pufferfish's Skills' erase for a shared pool). */
    public static void erasePoolData(PlayerProgress p, String pool) {
        p.xp.remove(pool);
        p.level.remove(pool);
        p.spent.remove(pool);
        p.bonus.remove(pool);
        p.exchange.remove(pool);
    }

    /**
     * Pufferfish's Skills' {@code erase_on_death} for one chapter. A chapter without a pool erases its own values and
     * its per-player lock override, as {@code removeCategoryData} does. A pooled chapter has no values of its own:
     * its whole pool is erased (experience, level, points, bonus and exchange) and every chapter in it loses its
     * lock override (2026-10-20 decision; Pufferfish's Skills has no pool equivalent).
     */
    public static void eraseChapterData(PlayerProgress p, ProgressModel model, ProgressModel.Chapter c) {
        String pool = key(c);
        if (!isPooled(c)) {
            p.xp.remove(c.id);
            p.level.remove(c.id);
            p.spent.remove(c.id);
            p.bonus.remove(c.id);
            p.exchange.remove(c.id);
            p.chapterOpen.remove(c.id);
            return;
        }
        for (ProgressModel.Chapter other : model.chapters.values()) {
            if (pool.equals(key(other))) {
                p.chapterOpen.remove(other.id);
            }
        }
        erasePoolData(p, pool);
    }

    /**
     * Pufferfish's Skills' scoreboard reward context: how many skills of this node's definition are unlocked. Skills
     * sharing a {@code definition} share its reward, so the score is the count of taken nodes of that definition.
     * Data without a definition counts the node itself as one.
     */
    public static int definitionCount(PlayerProgress p, ProgressModel.Node n) {
        if (n.definition == null || n.definition.isEmpty()) {
            return p.taken.getOrDefault(n.key, 0) > 0 ? 1 : 0;
        }
        int count = 0;
        for (ProgressModel.Node other : n.chapter.nodes.values()) {
            if (n.definition.equals(other.definition) && p.taken.getOrDefault(other.key, 0) > 0) {
                count++;
            }
        }
        return count;
    }

    /** A skill chapter's exchange settings ({@code points.exchange}). */
    public record Exchange(int cost, int limit) {
    }

    public static Exchange exchangeOf(ProgressModel.Chapter c) {
        if (c == null || c.points == null || !c.points.has("exchange") || !c.points.get("exchange").isJsonObject()) {
            return new Exchange(0, Integer.MAX_VALUE);
        }
        JsonObject exchange = c.points.getAsJsonObject("exchange");
        int cost = exchange.has("xp_levels") && exchange.get("xp_levels").isJsonPrimitive()
                ? Math.max(1, exchange.get("xp_levels").getAsInt()) : 0;
        int limit = exchange.has("level_limit") && exchange.get("level_limit").isJsonPrimitive()
                ? exchange.get("level_limit").getAsInt() : Integer.MAX_VALUE;
        return new Exchange(cost, limit);
    }

    /** Whether one exchange purchase is allowed now: open chapter, levels to pay, and the limit counted per pool. */
    public static boolean canExchange(PlayerProgress p, ProgressModel.Chapter c, int playerLevel) {
        if (c == null || !c.isSkills() || !chapterUnlocked(p, c)) {
            return false;
        }
        Exchange e = exchangeOf(c);
        if (e.cost() <= 0 || playerLevel < e.cost()) {
            return false;
        }
        return p.exchange.getOrDefault(key(c), 0) < e.limit();
    }

    /** Records one exchange purchase: the counter and the extra point both live on the pool key. */
    public static void applyExchange(PlayerProgress p, ProgressModel.Chapter c) {
        String key = key(c);
        p.exchange.merge(key, 1, Integer::sum);
        p.bonus.merge(key, 1, Integer::sum);
    }

    /** Points that can be spent now in a skill chapter. */
    public int availablePoints(PlayerProgress p, ProgressModel.Chapter c) {
        JsonObject pts = c.points == null ? new JsonObject() : c.points;
        int start = pts.has("start") ? pts.get("start").getAsInt() : 0;
        String key = key(c);
        int earned = p.level.getOrDefault(key, 0);
        int total = start + earned + p.bonus.getOrDefault(key, 0);
        if (pts.has("spent_limit") && pts.get("spent_limit").isJsonPrimitive()) {
            total = Math.min(total, pts.get("spent_limit").getAsInt());
        }
        return Math.max(0, total - p.spent.getOrDefault(key, 0));
    }

    /** Experience needed to go from {@code level} to the next one (Pufferfish: expression of "level"). */
    public double experienceForLevel(ProgressModel.Chapter c, int level) {
        JsonElement per = c.points == null ? null : c.points.get("per_level");
        if (per == null) {
            return 100;
        }
        if (per.isJsonPrimitive() && per.getAsJsonPrimitive().isNumber()) {
            return per.getAsDouble();
        }
        String expr = per.isJsonPrimitive() ? per.getAsString()
                : per.isJsonObject() && per.getAsJsonObject().has("data") ? per.getAsJsonObject().getAsJsonObject("data").get("expression").getAsString()
                : "100";
        Expression e = curves.computeIfAbsent(c.id + "|" + expr, k -> Expression.parse(expr));
        return Math.max(1, e.eval(Map.of("level", (double) level)));
    }

    /**
     * Adds experience to a skill chapter, levelling up as needed. Returns the number of levels gained.
     * Stops adding once nothing in the chapter can be taken any more (players asked for this, see REQUESTS.md).
     */
    public int addExperience(PlayerProgress p, ProgressModel.Chapter c, double amount) {
        if (amount <= 0 || nothingLeft(p, c)) {
            return 0;
        }
        int limit = c.points != null && c.points.has("level_limit") ? c.points.get("level_limit").getAsInt() : Integer.MAX_VALUE;
        String key = key(c);
        int lvl = p.level.getOrDefault(key, 0);
        double x = p.xp.getOrDefault(key, 0.0) + amount;
        int gained = 0;
        while (lvl < limit) {
            double need = experienceForLevel(c, lvl);
            // a curve that is not a finite number (pack data error) stops levelling instead of looping forever
            if (!Double.isFinite(need) || x < need) {
                break;
            }
            x -= need;
            lvl++;
            gained++;
        }
        if (lvl >= limit) {
            x = 0;
        }
        p.level.put(key, lvl);
        p.xp.put(key, x);
        return gained;
    }

    /** True when every node of the chapter is taken or excluded, so more points would be wasted. */
    public boolean nothingLeft(PlayerProgress p, ProgressModel.Chapter c) {
        for (ProgressModel.Node n : c.nodes.values()) {
            State s = state(p, n);
            if (s != State.DONE && s != State.EXCLUDED) {
                return false;
            }
        }
        return true;
    }

    /** Spends points on a skill node if allowed. Returns true if it was taken. */
    /**
     * After a claim: whether the quest starts over ({@code "repeat": {"cooldown": seconds, "max": n}}). Quests without
     * tasks only repeat with a cooldown, or they would complete again in the same tick forever.
     */
    public boolean repeatAfterClaim(PlayerProgress p, ProgressModel.Node n, long now) {
        if (!repeatable(n)) {
            return false;
        }
        JsonElement rep = n.json.get("repeat");
        JsonObject r = rep.isJsonObject() ? rep.getAsJsonObject() : new JsonObject();
        long cooldown = r.has("cooldown") ? r.get("cooldown").getAsLong() : 0;
        int max = r.has("max") && r.get("max").isJsonPrimitive() ? r.get("max").getAsInt() : Integer.MAX_VALUE;
        int done = p.repeats.getOrDefault(n.key, 0);
        // max = how many times the quest can be done in total
        // without a required task that must be done again (an action, not a state), only a cooldown keeps it from
        // completing again at once: quests with no required tasks, or with only "being somewhere / having something" tasks
        if (done + 1 >= max || (cooldown <= 0 && !hasRepeatableWork(n))) {
            return false;
        }
        p.repeats.put(n.key, done + 1);
        p.clearNode(n.key);
        if (cooldown > 0) {
            p.cooldown.put(n.key, now + cooldown * 1000);
        }
        return true;
    }

    /**
     * A repeatable quest with no rewards has no claim click that would start it over, so its completion does
     * (2026-10-20). Quests with rewards keep the old order: the claim after the last reward resets them.
     */
    public boolean repeatAfterCompletion(PlayerProgress p, ProgressModel.Node n, long now) {
        if (!n.rewards.isEmpty()) {
            return false;
        }
        return repeatAfterClaim(p, n, now);
    }

    /**
     * Tasks a player must do again after a repeat: kills, checkmarks and hand-ins. Item tasks of repeatable quests are
     * hand-ins (see {@link #consumes}); position, advancement, stat and stage tasks would be met again immediately.
     */
    static boolean hasRepeatableWork(ProgressModel.Node n) {
        for (JsonObject t : n.tasks) {
            boolean optional = t.has("optional") && t.get("optional").getAsBoolean();
            String type = t.has("type") ? t.get("type").getAsString() : "";
            if (!optional && (type.equals("kill") || type.equals("checkmark") || type.equals("item") || type.equals("xp"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether an item task takes the items (a Submit button) instead of checking the inventory. Repeatable quests
     * always consume: otherwise the items still held would complete the quest again right after each repeat.
     */
    public static boolean consumes(ProgressModel.Node n, JsonObject task) {
        String type = task.has("type") ? task.get("type").getAsString() : "";
        if (type.equals("xp")) {
            return true;
        }
        return type.equals("item") && (repeatable(n) || (task.has("consume") && task.get("consume").getAsBoolean()));
    }

    public boolean take(PlayerProgress p, ProgressModel.Node n) {
        // skill nodes with tasks are quests inside a skill chapter: they are claimed, not taken
        if (state(p, n) != State.CLAIM || !n.chapter.isSkills() || !n.tasks.isEmpty()) {
            return false;
        }
        p.spent.merge(key(n.chapter), Math.max(0, n.cost), Integer::sum);
        p.taken.merge(n.key, 1, Integer::sum);
        return true;
    }
}
