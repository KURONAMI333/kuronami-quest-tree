package com.kuronami.questtree.server;

import com.google.gson.JsonObject;
import com.kuronami.questtree.data.ProgressModel;
import com.kuronami.questtree.progress.PlayerProgress;
import com.kuronami.questtree.progress.ProgressRules;

/**
 * The write behind a task progress bump ({@code ProgressService.bump}). Conditions are deliberately not read here:
 * every caller has already judged them, and a hand-in has already taken the items by the time it writes, so judging
 * again would drop the progress of a condition that names the item just handed over (2026-10-22 review). Kept apart
 * from the service so the rule is testable without the game.
 */
final class TaskWrite {

    private TaskWrite() {
    }

    /**
     * Writes {@code value} into the task and reports whether this write is the first to reach the target, so its TASK
     * event is announced once.
     */
    static boolean records(PlayerProgress p, ProgressModel.Node n, int index, long value, long target) {
        long before = p.task(n.key, index);
        p.setTask(n.key, index, value);
        return before < target && value >= target;
    }

    /**
     * The value a command writes into a task (2026-10-22): an admin asking for more than the target gets the target,
     * because the counter past it means nothing, and a negative value is 0.
     */
    static long clamped(JsonObject task, long requested) {
        return Math.max(0, Math.min(requested, ProgressRules.target(task)));
    }
}
