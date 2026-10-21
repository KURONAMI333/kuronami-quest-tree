package com.kuronami.questtree.progress;

import java.util.Set;

/**
 * Whether a quest barrier lets a player through. One function for the server and the client, so both read the same
 * rule from their own copy of the progress (2026-10-21). A node barrier asks whether the node was completed, not
 * whether its rewards were claimed; a stage barrier asks for a stage the player holds, team or personal.
 */
public final class BarrierRule {

    private BarrierRule() {
    }

    public static boolean allows(String mode, String key, Set<String> completed, Set<String> stages) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        return "stage".equals(mode) ? stages.contains(key) : completed.contains(key);
    }
}
