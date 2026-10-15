package com.kuronami.questtree.client;

import com.google.gson.JsonParser;
import com.kuronami.questtree.data.ProgressModel;
import com.kuronami.questtree.progress.PlayerProgress;
import com.kuronami.questtree.progress.ProgressRules;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The path to a distant skill: prerequisites first, cheapest "any" branch, batch stops before an exclusive choice. */
class PathFinderTest {

    private static final ProgressModel MODEL = ProgressModel.of(JsonParser.parseString("""
            {"chapters": {"t:s": {"points": {"start": 10}, "nodes": {
              "root": {"root": true, "cost": 1},
              "a": {"cost": 1, "requires": {"nodes": ["root"]}},
              "b": {"cost": 2, "requires": {"nodes": ["a"]}},
              "pick": {"cost": 1, "requires": {"nodes": ["b"]}, "exclusive": {"group": "g", "max": 1}},
              "other": {"cost": 1, "requires": {"nodes": ["b"]}, "exclusive": {"group": "g", "max": 1}},
              "end": {"cost": 1, "requires": {"nodes": ["pick"]}},
              "cheap": {"cost": 1, "requires": {"nodes": ["root"]}},
              "dear": {"cost": 9, "requires": {"nodes": ["root"]}},
              "either": {"cost": 1, "requires": {"nodes": ["dear", "cheap"], "mode": "any"}}
            }}}}""").getAsJsonObject());

    @Test
    void chainStopsBeforeExclusive() {
        ProgressRules r = new ProgressRules(MODEL);
        PathFinder.Path p = PathFinder.find(r, new PlayerProgress(), "t:s/end");
        assertEquals(List.of("t:s/root", "t:s/a", "t:s/b", "t:s/pick", "t:s/end"), p.nodes());
        assertEquals(6, p.cost());
        assertEquals(3, p.batchable(), "root, a, b; then the exclusive pick is the player's choice");
        assertEquals(4, p.batchCost());
    }

    @Test
    void anyTakesCheapestBranchAndSkipsDone() {
        ProgressRules r = new ProgressRules(MODEL);
        PlayerProgress pr = new PlayerProgress();
        pr.taken.put("t:s/root", 1);
        PathFinder.Path p = PathFinder.find(r, pr, "t:s/either");
        assertEquals(List.of("t:s/cheap", "t:s/either"), p.nodes());
        assertEquals(2, p.batchCost());
    }

    /** Pufferfish's Skills connections go both ways ("any"); a long ring must neither loop nor explode. */
    @Test
    void bidirectionalRingIsFastAndCheapest() {
        StringBuilder nodes = new StringBuilder("\"root\": {\"root\": true, \"cost\": 1}");
        int n = 40;
        for (int i = 0; i < n; i++) {
            String prev = i == 0 ? "root" : "r" + (i - 1);
            String next = i == n - 1 ? "root" : "r" + (i + 1);
            nodes.append(", \"r").append(i).append("\": {\"cost\": 1, \"requires\": {\"nodes\": [\"").append(prev)
                    .append("\", \"").append(next).append("\"], \"mode\": \"any\"}}");
        }
        ProgressModel m = ProgressModel.of(JsonParser.parseString("{\"chapters\": {\"t:ring\": {\"points\": {}, \"nodes\": {"
                + nodes + "}}}}").getAsJsonObject());
        ProgressRules r = new ProgressRules(m);
        PlayerProgress pr = new PlayerProgress();
        pr.taken.put("t:ring/root", 1);
        long t = System.nanoTime();
        PathFinder.Path p = PathFinder.find(r, pr, "t:ring/r37");
        assertTrue(System.nanoTime() - t < 200_000_000L, "path search too slow");
        // the short way round: root -> r39 -> r38 -> r37
        assertEquals(List.of("t:ring/r39", "t:ring/r38", "t:ring/r37"), p.nodes());
    }

    /** "At least 2 of" takes the two cheapest requirements. */
    @Test
    void atLeastNTakesCheapest() {
        ProgressModel m = ProgressModel.of(JsonParser.parseString("""
                {"chapters": {"t:m": {"points": {}, "nodes": {
                  "root": {"root": true},
                  "x": {"cost": 1, "requires": {"nodes": ["root"]}},
                  "y": {"cost": 2, "requires": {"nodes": ["root"]}},
                  "z": {"cost": 9, "requires": {"nodes": ["root"]}},
                  "goal": {"cost": 1, "requires": {"nodes": ["x", "y", "z"], "min": 2}}
                }}}}""").getAsJsonObject());
        PlayerProgress pr = new PlayerProgress();
        pr.taken.put("t:m/root", 1);
        PathFinder.Path p = PathFinder.find(new ProgressRules(m), pr, "t:m/goal");
        assertEquals(java.util.Set.of("t:m/x", "t:m/y", "t:m/goal"), new java.util.HashSet<>(p.nodes()));
        assertEquals("t:m/goal", p.nodes().get(p.nodes().size() - 1));
    }
}
