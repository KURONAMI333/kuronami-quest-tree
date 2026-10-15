package com.kuronami.questtree.client;

import com.kuronami.questtree.data.ProgressModel;
import com.kuronami.questtree.progress.PlayerProgress;
import com.kuronami.questtree.progress.ProgressRules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The unfinished requirements between the player and a node, prerequisites first (EXTERNAL_REFERENCES 1-2: Dead by
 * Daylight's Bloodweb chain, Diablo IV's path allocation). "Any of" requirements take the cheapest branch; players who
 * want another branch take nodes one by one. Exclusive choices are never decided for the player: the batch stops
 * before the first node that would close another.
 */
final class PathFinder {

    record Path(List<String> nodes, int cost, int batchable, int batchCost) {
        static final Path NONE = new Path(List.of(), 0, 0, 0);
    }

    private PathFinder() {
    }

    static Path find(ProgressRules rules, PlayerProgress p, String targetKey) {
        ProgressModel.Node target = rules.model().nodes.get(targetKey);
        if (target == null || ProgressRules.doneOnce(p, targetKey) || rules.state(p, target) == ProgressRules.State.EXCLUDED) {
            return Path.NONE;
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (!collect(rules, p, target, out, new HashSet<>())) {
            return Path.NONE;
        }
        List<String> nodes = new ArrayList<>(out);
        int cost = 0;
        int batchable = 0;
        int batchCost = 0;
        boolean stopped = false;
        for (String k : nodes) {
            ProgressModel.Node n = rules.model().nodes.get(k);
            int c = Math.max(0, n.cost);
            cost += c;
            // only skills can be bought in one go; an exclusive node (or anything after it) is the player's choice
            if (!stopped && n.chapter.isSkills() && n.tasks.isEmpty() && n.exclusiveGroup == null && n.exclusiveWith.isEmpty()) {
                batchable++;
                batchCost += c;
            } else {
                stopped = true;
            }
        }
        return new Path(nodes, cost, batchable, batchCost);
    }

    /** Adds the unfinished nodes needed for {@code n} (and {@code n}) in dependency order. False when unreachable. */
    private static boolean collect(ProgressRules rules, PlayerProgress p, ProgressModel.Node n, Set<String> out, Set<String> visiting) {
        if (ProgressRules.doneOnce(p, n.key) || out.contains(n.key)) {
            return true;
        }
        if (rules.state(p, n) == ProgressRules.State.EXCLUDED || !visiting.add(n.key)) {
            return false;
        }
        boolean ok = true;
        if (!n.root && !n.requires.isEmpty() && !rules.unlocked(p, n)) {
            boolean any = n.requireMode.startsWith("any") || n.requireMode.equals("one") || n.requireMin > 0;
            if (any) {
                // cheapest single branch that is reachable
                Set<String> best = null;
                int bestCost = Integer.MAX_VALUE;
                for (String r : n.requires) {
                    ProgressModel.Node d = rules.model().nodes.get(r);
                    if (d == null) {
                        continue;
                    }
                    Set<String> trial = new LinkedHashSet<>(out);
                    if (collect(rules, p, d, trial, new HashSet<>(visiting))) {
                        int c = 0;
                        for (String k : trial) {
                            if (!out.contains(k)) {
                                c += Math.max(0, rules.model().nodes.get(k).cost) + 1;
                            }
                        }
                        if (c < bestCost) {
                            bestCost = c;
                            best = trial;
                        }
                    }
                }
                if (best == null) {
                    ok = false;
                } else {
                    out.clear();
                    out.addAll(best);
                }
            } else {
                for (String r : n.requires) {
                    ProgressModel.Node d = rules.model().nodes.get(r);
                    if (d == null || !collect(rules, p, d, out, visiting)) {
                        ok = false;
                        break;
                    }
                }
            }
        }
        visiting.remove(n.key);
        if (ok) {
            out.add(n.key);
        }
        return ok;
    }
}
