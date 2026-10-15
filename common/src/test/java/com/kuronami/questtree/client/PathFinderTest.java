package com.kuronami.questtree.client;

import com.google.gson.JsonParser;
import com.kuronami.questtree.data.ProgressModel;
import com.kuronami.questtree.progress.PlayerProgress;
import com.kuronami.questtree.progress.ProgressRules;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
