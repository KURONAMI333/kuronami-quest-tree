package com.kuronami.questtree.progress;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What a login must undo and redo so a player's Pack Stages match the team's. A stage removed while the player was
 * offline stays on their Pack Stages copy, so the login removes what this player was given and no longer holds, then
 * adds what the team holds now (2026-10-21). Game-free so the plan can be tested.
 */
public final class StageSync {

    public record Plan(Set<String> remove, Set<String> add) {
    }

    private StageSync() {
    }

    public static Plan plan(Set<String> applied, Set<String> held) {
        Set<String> remove = new LinkedHashSet<>(applied);
        remove.removeAll(held);
        Set<String> add = new LinkedHashSet<>(held);
        add.removeAll(applied);
        return new Plan(remove, add);
    }
}
