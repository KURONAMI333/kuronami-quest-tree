package com.kuronami.packprogress.progress;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kuronami.packprogress.data.ProgressModel;
import com.kuronami.packprogress.points.Expression;

import java.util.HashMap;
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
            return !n.rewards.isEmpty() && !p.claimed.contains(n.key) ? State.CLAIM : State.DONE;
        }
        if (!unlocked(p, n)) {
            return State.LOCKED;
        }
        return fraction(p, n) > 0 ? State.PROGRESS : State.OPEN;
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
        if (p.completed.contains(key) || p.taken.getOrDefault(key, 0) > 0) {
            return true;
        }
        if (!startedIsEnough) {
            return false;
        }
        ProgressModel.Node n = model.nodes.get(key);
        return n != null && fraction(p, n) > 0;
    }

    public boolean excluded(PlayerProgress p, ProgressModel.Node n) {
        if (p.completed.contains(n.key) || p.taken.getOrDefault(n.key, 0) > 0) {
            return false;
        }
        for (String other : n.exclusiveWith) {
            if (p.taken.getOrDefault(other, 0) > 0 || p.completed.contains(other)) {
                return true;
            }
        }
        if (n.exclusiveGroup != null) {
            List<ProgressModel.Node> group = n.chapter.exclusiveGroups.get(n.exclusiveGroup);
            long done = group == null ? 0 : group.stream().filter(g -> p.completed.contains(g.key) || p.taken.getOrDefault(g.key, 0) > 0).count();
            return done >= n.exclusiveMax;
        }
        return false;
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
        return 1;
    }

    // ------------------------------------------------------------------ skill points

    /** Points that can be spent now in a skill chapter. */
    public int availablePoints(PlayerProgress p, ProgressModel.Chapter c) {
        JsonObject pts = c.points == null ? new JsonObject() : c.points;
        int start = pts.has("start") ? pts.get("start").getAsInt() : 0;
        int earned = p.level.getOrDefault(c.id, 0);
        int total = start + earned + p.bonus.getOrDefault(c.id, 0);
        if (pts.has("spent_limit") && pts.get("spent_limit").isJsonPrimitive()) {
            total = Math.min(total, pts.get("spent_limit").getAsInt());
        }
        return Math.max(0, total - p.spent.getOrDefault(c.id, 0));
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
        int lvl = p.level.getOrDefault(c.id, 0);
        double x = p.xp.getOrDefault(c.id, 0.0) + amount;
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
        p.level.put(c.id, lvl);
        p.xp.put(c.id, x);
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
        JsonElement rep = n.json.get("repeat");
        if (rep == null || !(rep.isJsonObject() || (rep.isJsonPrimitive() && rep.getAsJsonPrimitive().isBoolean() && rep.getAsBoolean()))) {
            return false;
        }
        JsonObject r = rep.isJsonObject() ? rep.getAsJsonObject() : new JsonObject();
        long cooldown = r.has("cooldown") ? r.get("cooldown").getAsLong() : 0;
        int max = r.has("max") && r.get("max").isJsonPrimitive() ? r.get("max").getAsInt() : Integer.MAX_VALUE;
        int done = p.repeats.getOrDefault(n.key, 0);
        // max = how many times the quest can be done in total
        if (done + 1 >= max || (n.tasks.isEmpty() && cooldown <= 0)) {
            return false;
        }
        p.repeats.put(n.key, done + 1);
        p.clearNode(n.key);
        if (cooldown > 0) {
            p.cooldown.put(n.key, now + cooldown * 1000);
        }
        return true;
    }

    public boolean take(PlayerProgress p, ProgressModel.Node n) {
        // skill nodes with tasks are quests inside a skill chapter: they are claimed, not taken
        if (state(p, n) != State.CLAIM || !n.chapter.isSkills() || !n.tasks.isEmpty()) {
            return false;
        }
        p.spent.merge(n.chapter.id, Math.max(0, n.cost), Integer::sum);
        p.taken.merge(n.key, 1, Integer::sum);
        return true;
    }
}
