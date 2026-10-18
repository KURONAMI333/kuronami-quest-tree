package com.kuronami.questtree.progress;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kuronami.questtree.data.ProgressModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rules that the side-by-side run against FTB Quests on All the Mods 10 found missing (2026-10-18, FtbqDiff): flexible
 * progression, a hidden branch hiding what grows from it, and "deps_complete" following the requirement mode.
 */
class FtbqParityTest {

    private static ProgressModel model(String chapterJson) {
        JsonObject bundle = new JsonObject();
        JsonObject chapters = new JsonObject();
        chapters.add("t:c", JsonParser.parseString(chapterJson));
        bundle.add("chapters", chapters);
        return ProgressModel.of(bundle);
    }

    private static final String TASK = "\"tasks\": [{\"type\": \"checkmark\"}]";

    @Test
    void flexibleOpensBeforeRequirementsButStaysUnmet() {
        ProgressModel m = model("{\"defaults\": {\"progression_mode\": \"flexible\"}, \"nodes\": {"
                + "\"a\": {" + TASK + "}, \"b\": {" + TASK + ", \"requires\": {\"nodes\": [\"a\"]}},"
                + "\"c\": {" + TASK + ", \"progression_mode\": \"linear\", \"requires\": {\"nodes\": [\"a\"]}}}}");
        ProgressRules r = new ProgressRules(m);
        PlayerProgress p = new PlayerProgress();
        ProgressModel.Node b = m.nodes.get("t:c/b");
        assertEquals(ProgressRules.State.OPEN, r.state(p, b));
        assertFalse(r.unlocked(p, b));
        assertEquals(ProgressRules.State.LOCKED, r.state(p, m.nodes.get("t:c/c")));
    }

    @Test
    void nodeBehindOnlyHiddenRequirementsIsHidden() {
        ProgressModel m = model("{\"nodes\": {"
                + "\"a\": {" + TASK + "}, \"h\": {" + TASK + ", \"requires\": {\"nodes\": [\"a\"]}, \"visibility\": {\"hidden_until\": \"deps_complete\"}},"
                + "\"x\": {" + TASK + ", \"requires\": {\"nodes\": [\"h\"]}}}}");
        ProgressRules r = new ProgressRules(m);
        PlayerProgress p = new PlayerProgress();
        assertFalse(r.visible(p, m.nodes.get("t:c/x")));
        p.completed.add("t:c/a");
        assertTrue(r.visible(p, m.nodes.get("t:c/h")));
        assertTrue(r.visible(p, m.nodes.get("t:c/x")));
    }

    @Test
    void depsCompleteFollowsAnyMode() {
        ProgressModel m = model("{\"nodes\": {"
                + "\"a\": {" + TASK + "}, \"b\": {" + TASK + "},"
                + "\"x\": {" + TASK + ", \"requires\": {\"mode\": \"any\", \"nodes\": [\"a\", \"b\"]}, \"visibility\": {\"hidden_until\": \"deps_complete\"}}}}");
        ProgressRules r = new ProgressRules(m);
        PlayerProgress p = new PlayerProgress();
        assertFalse(r.visible(p, m.nodes.get("t:c/x")));
        p.completed.add("t:c/b");
        assertTrue(r.visible(p, m.nodes.get("t:c/x")));
    }
}
