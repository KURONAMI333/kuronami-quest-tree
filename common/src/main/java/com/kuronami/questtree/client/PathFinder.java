package com.kuronami.questtree.client;

import com.kuronami.questtree.data.ProgressModel;
import com.kuronami.questtree.progress.PlayerProgress;
import com.kuronami.questtree.progress.ProgressRules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * The unfinished requirements between the player and a node, prerequisites first (EXTERNAL_REFERENCES 1-2: Dead by
 * Daylight's Bloodweb chain, Diablo IV's path allocation). Exclusive choices are never decided for the player: the batch
 * stops before the first node that would close another.
 * <p>
 * Requirements form an and/or graph ("all of", "any of", "at least n of"), often with cycles (Pufferfish's Skills
 * connections go both ways). A cost-ordered search (Knuth's generalisation of Dijkstra to and/or graphs) settles each
 * node once at its cheapest cost: an "any" node from its cheapest settled requirement, an "all" node once all its
 * requirements are settled, an "at least n" node from its n cheapest. Polynomial, and cycles cannot loop. Costs of shared
 * ancestors are counted per branch while choosing (an estimate); the reported cost counts each node once.
 */
final class PathFinder {

    record Path(List<String> nodes, int cost, int batchable, int batchCost) {
        static final Path NONE = new Path(List.of(), 0, 0, 0);
    }

    private PathFinder() {
    }

    static Path find(ProgressRules rules, PlayerProgress p, String targetKey) {
        ProgressModel model = rules.model();
        ProgressModel.Node target = model.nodes.get(targetKey);
        if (target == null || ProgressRules.doneOnce(p, targetKey) || rules.state(p, target) == ProgressRules.State.EXCLUDED) {
            return Path.NONE;
        }
        // nodes of the target's namespace only (requirements never leave it)
        Map<String, List<ProgressModel.Node>> dependents = new HashMap<>();
        Map<String, Long> dist = new HashMap<>();
        Map<String, List<String>> chosen = new HashMap<>();
        Map<String, Integer> settledReqs = new HashMap<>();
        Map<String, List<String>> settledList = new HashMap<>();
        PriorityQueue<Object[]> queue = new PriorityQueue<>((a, b) -> Long.compare((long) a[1], (long) b[1]));
        for (ProgressModel.Node n : model.nodes.values()) {
            if (!n.chapter.namespace.equals(target.chapter.namespace)) {
                continue;
            }
            for (String r : n.requires) {
                dependents.computeIfAbsent(r, k -> new ArrayList<>()).add(n);
            }
            if (ProgressRules.doneOnce(p, n.key)) {
                queue.add(new Object[]{n.key, 0L, List.of()});
            } else if (rules.state(p, n) != ProgressRules.State.EXCLUDED && (n.root || n.requires.isEmpty() || rules.unlocked(p, n))) {
                queue.add(new Object[]{n.key, (long) Math.max(0, n.cost) + 1, List.of()});
            }
        }
        while (!queue.isEmpty()) {
            Object[] e = queue.poll();
            String key = (String) e[0];
            if (dist.containsKey(key)) {
                continue;
            }
            long d = (long) e[1];
            @SuppressWarnings("unchecked")
            List<String> via = (List<String>) e[2];
            dist.put(key, d);
            chosen.put(key, via);
            if (key.equals(targetKey)) {
                break;
            }
            for (ProgressModel.Node v : dependents.getOrDefault(key, List.of())) {
                if (dist.containsKey(v.key) || rules.state(p, v) == ProgressRules.State.EXCLUDED) {
                    continue;
                }
                int settled = settledReqs.merge(v.key, 1, Integer::sum);
                List<String> list = settledList.computeIfAbsent(v.key, k -> new ArrayList<>());
                list.add(key);
                long own = (long) Math.max(0, v.cost) + 1;
                boolean any = v.requireMode.startsWith("any") || v.requireMode.equals("one");
                int need = v.requireMin > 0 ? Math.min(v.requireMin, v.requires.size()) : any ? 1 : v.requires.size();
                if (settled == need) {
                    // settled in cost order, so the first "need" requirements are the cheapest ones
                    long sum = own;
                    for (String r : list) {
                        sum += dist.get(r);
                    }
                    queue.add(new Object[]{v.key, sum, List.copyOf(list)});
                }
            }
        }
        if (!dist.containsKey(targetKey)) {
            return Path.NONE;
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        collect(targetKey, p, chosen, out, new LinkedHashSet<>());
        List<String> nodes = new ArrayList<>(out);
        int cost = 0;
        int batchable = 0;
        int batchCost = 0;
        boolean stopped = false;
        for (String k : nodes) {
            ProgressModel.Node n = model.nodes.get(k);
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

    /** Unfinished nodes of the chosen requirements, dependencies first. */
    private static void collect(String key, PlayerProgress p, Map<String, List<String>> chosen, Set<String> out, Set<String> stack) {
        if (ProgressRules.doneOnce(p, key) || out.contains(key) || !stack.add(key)) {
            return;
        }
        for (String r : chosen.getOrDefault(key, List.of())) {
            collect(r, p, chosen, out, stack);
        }
        stack.remove(key);
        out.add(key);
    }
}
